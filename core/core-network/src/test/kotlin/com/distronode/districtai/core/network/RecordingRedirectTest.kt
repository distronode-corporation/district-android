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
 * The recording endpoint answers 302, not JSON.
 *
 * ⛔ WHY THIS IS ITS OWN TEST CLASS. `GET /api/district/calls/{id}/recording` resolves a short-lived
 * presigned object URL and REDIRECTS, specifically so recording bytes never proxy through the app
 * server. OkHttp follows redirects by default, so the obvious implementation streams the entire
 * audio file into this process just to learn its address — on a phone, on a metered connection, for
 * a file the media player is about to fetch again itself. These tests are what hold that behaviour
 * in place, and the first one fails loudly if the redirect is ever followed again.
 */
class RecordingRedirectTest {

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
        val presigned = "https://storage.example.test/recordings/abc.mp3?X-Amz-Signature=deadbeef"
        server.enqueue(
            MockResponse(code = 302, headers = Headers.headersOf("Location", presigned)),
        )

        val result = api().callRecordingUrl("ws-1", "call-1")

        assertEquals(ApiResult.Success(presigned), result)
        // ⛔ EXACTLY ONE REQUEST. A second would mean the redirect was followed and the audio was
        // downloaded through this process.
        assertEquals("the redirect must not be followed", 1, server.requestCount)
    }

    @Test
    fun `never downloads the audio body`() = runTest {
        // Belt and braces on the assertion above, stated as the thing that actually costs the user
        // money: if the redirect were followed, this second response would be consumed.
        val presigned = "https://storage.example.test/recordings/large.mp3"
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", presigned)))
        server.enqueue(MockResponse(code = 200, body = "PRETEND-THIS-IS-TWENTY-MEGABYTES-OF-AUDIO"))

        val result = api().callRecordingUrl("ws-1", "call-1")

        assertTrue(result is ApiResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `sends the workspace id and the call id on the right path`() = runTest {
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "https://x.test/a.mp3")))

        api().callRecordingUrl("ws-42", "call-99")

        val request = server.takeRequest()
        assertEquals("/api/district/calls/call-99/recording", request.url.encodedPath)
        assertEquals("ws-42", request.url.queryParameter("workspaceId"))
    }

    @Test
    fun `a call with no recording is a NotFound, not a missing-location error`() = runTest {
        // ⚠️ The route answers 404 with a JSON body for a call that has no recording — an ordinary
        // state for a missed call. It must fall through to the normal error mapping rather than
        // being reported as a malformed redirect.
        server.enqueue(MockResponse(code = 404, body = """{"success":false,"error":"Recording not found"}"""))

        val result = api().callRecordingUrl("ws-1", "call-1")

        assertEquals(ApiResult.NotFound("Recording not found"), result)
    }

    @Test
    fun `a redirect with no Location is reported rather than returning a blank url`() = runTest {
        // Handing an empty string to a media player produces an unattributable playback failure.
        server.enqueue(MockResponse(code = 302))

        val result = api().callRecordingUrl("ws-1", "call-1")

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        assertEquals(302, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a redirect with an empty Location is reported the same way`() = runTest {
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "")))

        val result = api().callRecordingUrl("ws-1", "call-1")

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        assertEquals(302, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a Location that is not an https address is refused, and never quoted`() = runTest {
        // ⛔ EVERY CALLER HANDS THIS VALUE STRAIGHT TO ANOTHER APP: a media player for a recording,
        // a browser for a scheduler sign-in. Each URL carries its own credential (a presigned
        // signature, a single-use token), so a plaintext scheme puts it on the wire in clear and an
        // `intent:` or `content:` scheme is not a download at all. One check, here, covers all three
        // routes that answer with a redirect.
        val refused = listOf(
            "http://storage.example.test/recordings/a.mp3?X-Amz-Signature=deadbeef",
            "intent://storage.example.test/a.mp3?sig=deadbeef#Intent;end",
            "content://storage.example.test/a.mp3?sig=deadbeef",
            "javascript:deadbeef",
            "/recordings/a.mp3?sig=deadbeef",
            "//storage.example.test/a.mp3?sig=deadbeef",
            "https://",
        )

        for (location in refused) {
            server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", location)))

            val result = api().callRecordingUrl("ws-1", "call-1")

            assertTrue("'$location' must be refused, got $result", result is ApiResult.DecodeFailure)
            // ⛔ THE DIAGNOSTIC NAMES THE SHAPE, NEVER THE VALUE, which carries the credential.
            val preview = (result as ApiResult.DecodeFailure).bodyPreview
            assertEquals("RedirectTarget{scheme!=https}", preview)
        }
    }

    @Test
    fun `an upper-case HTTPS scheme is still https`() = runTest {
        val presigned = "HTTPS://storage.example.test/recordings/a.mp3"
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", presigned)))

        assertEquals(ApiResult.Success(presigned), api().callRecordingUrl("ws-1", "call-1"))
    }

    @Test
    fun `still refreshes and retries once on a rejected token`() = runTest {
        // The redirect path shares the auth/retry loop, so it inherits the single retry. Asserted
        // because it is easy to reimplement a bespoke request and lose it.
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "https://x.test/a.mp3")))

        val result = api().callRecordingUrl("ws-1", "call-1")

        assertEquals(ApiResult.Success("https://x.test/a.mp3"), result)
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

        server.enqueue(MockResponse(code = 302, headers = Headers.headersOf("Location", "https://x.test/a.mp3")))
        apiClient.redirectTarget(listOf("api", "district", "calls", "c1", "recording"))

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
