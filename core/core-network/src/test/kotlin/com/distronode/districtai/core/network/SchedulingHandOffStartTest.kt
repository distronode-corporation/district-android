package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The probe of the nonce-bound hand-off's leg 1, `GET /dashboard/handoff/start?state=`.
 *
 * ⛔ THE THREE THINGS IT MUST NEVER DO. Send the access token (the route is a browser route and
 * takes none, so a bearer there is a credential sent for nothing), follow the redirect (it points at
 * this app's own custom scheme), or spend a token refresh on a request that needs no token. And
 * every answer that is not a redirect must reach the caller as a failure, because the caller's
 * answer to "no" is the unbound flow.
 */
class SchedulingHandOffStartTest {

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

    private fun schedulingApi(): SchedulingApi = HttpSchedulingApi(testApiClient(server, refreshApi))

    @Test
    fun `a redirect onto the callback is returned verbatim, unfollowed and without a bearer`() = runTest {
        val location = "districtai://handoff?state=probe-state-0123456789&nonce=$NONCE"
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", location)
                .addHeader("Set-Cookie", "__Host-dn-handoff-nonce=$NONCE; Path=/; Secure; HttpOnly")
                .build(),
        )

        val result = schedulingApi().schedulingHandOffStart("probe-state-0123456789")

        assertEquals(location, (result as ApiResult.Success).value)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/dashboard/handoff/start", recorded.url.encodedPath)
        assertEquals("probe-state-0123456789", recorded.url.queryParameter("state"))
        // ⛔ NO CREDENTIAL ON A BROWSER ROUTE, and no refresh spent finding one.
        assertNull(recorded.headers["Authorization"])
        assertEquals(0, refreshApi.callCount)
        // ⚠️ AND NOTHING FOLLOWED THE REDIRECT: one request reached the server.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `today's production 404 is a failure carrying the status, with the HTML body unread`() = runTest {
        server.enqueue(MockResponse(code = 404, body = "<html>Not found</html>"))

        val result = schedulingApi().schedulingHandOffStart("probe-state-0123456789")

        assertEquals(404, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `the route's 400 for a bad state is a failure too`() = runTest {
        server.enqueue(MockResponse(code = 400, body = "<html>Bad state</html>"))

        val result = schedulingApi().schedulingHandOffStart("x")

        assertEquals(400, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a 200 is not a redirect, whatever it carries`() = runTest {
        // ⚠️ A captive portal answers 200 with its own page for every request.
        server.enqueue(MockResponse(code = 200, body = "<html>Sign in to the wifi</html>"))

        val result = schedulingApi().schedulingHandOffStart("probe-state-0123456789")

        assertEquals(200, (result as ApiResult.HttpFailure).status)
    }

    @Test
    fun `a redirect with no Location, or a blank one, is a failure rather than an empty success`() =
        runTest {
            server.enqueue(MockResponse(code = 302))
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", " ").build())

            val missing = schedulingApi().schedulingHandOffStart("probe-state-0123456789")
            val blank = schedulingApi().schedulingHandOffStart("probe-state-0123456789")

            assertEquals(302, (missing as ApiResult.HttpFailure).status)
            assertEquals(302, (blank as ApiResult.HttpFailure).status)
        }

    @Test
    fun `an unreachable server is a network failure, not a crash`() = runTest {
        val api = schedulingApi()
        server.close()

        val result = api.schedulingHandOffStart("probe-state-0123456789")

        assertTrue(result is ApiResult.NetworkFailure)
    }

    private companion object {
        /** 43 base64url characters, the shape the route mints. */
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"
    }
}
