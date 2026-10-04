package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [DistrictApiClient.redirectTarget]: a 302 is read, never followed.
 *
 * ⛔ WHY THIS IS ITS OWN TEST CLASS. `GET /api/district/scheduling/sso` answers with a REDIRECT
 * whose `Location` is a one-time sign-in URL. OkHttp follows redirects by default, so the obvious
 * implementation spends the single-use token on a transport nobody sees and hands the browser a
 * credential that has already been claimed. These tests hold the helper's behaviour in place
 * through its one caller, and the first one fails loudly if the redirect is ever followed again.
 */
class RedirectTargetTest {

    private lateinit var server: MockWebServer
    private lateinit var refreshApi: FakeRefreshApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        refreshApi = FakeRefreshApi().apply { rotating() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = HttpDistrictApi(testApiClient(server, refreshApi))

    @Test
    fun `returns the redirect target without following it`() = runTest {
        val target = "https://book.example.test/v1/auth/sso?token=one-use"
        server.enqueue(
            MockResponse(code = 302, headers = Headers.headersOf("Location", target)),
        )

        val result = api().schedulingSsoTarget("ws-1", "/event-types")

        assertEquals(ApiResult.Success(target), result)
        // ⛔ EXACTLY ONE REQUEST. A second would mean the redirect was followed and the single-use
        // token was spent through this process.
        assertEquals("the redirect must not be followed", 1, server.requestCount)
    }

    @Test
    fun `never fetches the redirect target`() = runTest {
        // Belt and braces on the assertion above: if the redirect were followed, this second
        // response would be consumed, and with it the token.
        val target = "https://book.example.test/v1/auth/sso?token=spent-if-followed"
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", target)))
        server.enqueue(MockResponse(code = 200, body = "THE-SCHEDULER-SIGN-IN-PAGE"))

        val result = api().schedulingSsoTarget("ws-1", "/event-types")

        assertTrue(result is ApiResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a refusal is the ordinary failure, not a missing-location error`() = runTest {
        // ⚠️ A refusal arrives with a JSON body rather than a redirect. It must fall through to the
        // normal error mapping rather than being reported as a malformed redirect.
        server.enqueue(MockResponse(code = 404, body = """{"success":false,"error":"Not found"}"""))

        val result = api().schedulingSsoTarget("ws-1", "/event-types")

        assertEquals(ApiResult.NotFound("Not found"), result)
    }

    @Test
    fun `a redirect with no Location is reported rather than returning a blank url`() = runTest {
        // Handing an empty string to a browser produces an unattributable failure.
        server.enqueue(MockResponse(code = 302))

        val result = api().schedulingSsoTarget("ws-1", "/event-types")

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        assertEquals(302, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a redirect with an empty Location is reported the same way`() = runTest {
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "")))

        val result = api().schedulingSsoTarget("ws-1", "/event-types")

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        assertEquals(302, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a Location that is not an https address is refused, and never quoted`() = runTest {
        // ⛔ EVERY CALLER HANDS THIS VALUE STRAIGHT TO ANOTHER APP, a browser for a scheduler
        // sign-in, and the URL carries its own credential (a single-use token). A plaintext scheme
        // puts it on the wire in clear and an `intent:` or `content:` scheme is not a web page at
        // all. One check, here, covers every route that answers with a redirect.
        val refused = listOf(
            "http://book.example.test/v1/auth/sso?token=deadbeef",
            "intent://book.example.test/sso?token=deadbeef#Intent;end",
            "content://book.example.test/sso?token=deadbeef",
            "javascript:deadbeef",
            "/v1/auth/sso?token=deadbeef",
            "//book.example.test/sso?token=deadbeef",
            "https://",
        )

        for (location in refused) {
            server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", location)))

            val result = api().schedulingSsoTarget("ws-1", "/event-types")

            assertTrue("'$location' must be refused, got $result", result is ApiResult.DecodeFailure)
            // ⛔ THE DIAGNOSTIC NAMES THE SHAPE, NEVER THE VALUE, which carries the credential.
            val preview = (result as ApiResult.DecodeFailure).bodyPreview
            assertEquals("RedirectTarget{scheme!=https}", preview)
        }
    }

    @Test
    fun `an upper-case HTTPS scheme is still https`() = runTest {
        val target = "HTTPS://book.example.test/v1/auth/sso?token=one-use"
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", target)))

        assertEquals(ApiResult.Success(target), api().schedulingSsoTarget("ws-1", "/event-types"))
    }

    @Test
    fun `still refreshes and retries once on a rejected token`() = runTest {
        // The redirect path shares the auth/retry loop, so it inherits the single retry. Asserted
        // because it is easy to reimplement a bespoke request and lose it.
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "https://x.test/sso")))

        val result = api().schedulingSsoTarget("ws-1", "/event-types")

        assertEquals(ApiResult.Success("https://x.test/sso"), result)
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `overriding redirects does not leak into other requests`() = runTest {
        // ⛔ The override is applied to a CLONE via newBuilder(). Mutating the shared client would
        // silently stop the whole app following redirects, which would break anything relying on
        // one. Proven by making an ordinary call that DOES redirect and observing it followed.
        val client = DistrictHttp.client()
        val apiClient = DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = client,
            tokens = signedInCoordinator(refreshApi),
        )

        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "https://x.test/sso")))
        apiClient.redirectTarget(listOf("api", "district", "scheduling", "sso"))

        // Now an ordinary get() against a redirecting endpoint on the SAME client instance.
        server.enqueue(
            MockResponse(
                code = 302,
                headers = Headers.headersOf("Location", server.url("/final").toString()),
            ),
        )
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"transcript":"followed"}"""))

        val followed = apiClient.get(
            listOf("api", "district", "calls", "c1", "transcript"),
            com.distronode.districtai.core.model.CallTranscriptResponse.serializer(),
        )

        assertTrue("the ordinary client must still follow redirects, got $followed", followed is ApiResult.Success)
        assertEquals("followed", (followed as ApiResult.Success).value.transcript)
    }
}
