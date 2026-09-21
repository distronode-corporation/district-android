package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the push and inbound-answer requests actually put on the wire.
 *
 * ⛔ WHY THIS EXISTS SEPARATELY FROM THE CONTRACT FIXTURES, same as [ComposerRequestTest] and
 * [InboxRequestBodyTest]: those pin what the SERVER sends. Nothing pins what this client sends, and
 * three of these four properties are invisible to every other gate —
 *
 *   * the PATHS. `/api/district/devices/register` and `/api/auth/native/devices/revoke` are
 *     different prefixes for adjacent-sounding features, and each 404s at the other's address.
 *   * the ABSENCE of a `deviceId` in the register body. The server recovers the installation from
 *     the bearer precisely because that field is the UPSERT KEY: a caller able to name it could
 *     point somebody else's row at their own token. A client that "helpfully" started sending one
 *     would be refused by nothing.
 *   * the CALL ID AS A PATH SEGMENT. OkHttp's `addPathSegments` splits on `/` and then resolves
 *     `..`, which is how a call id once turned `calls/{id}/transcript` into a different route
 *     entirely.
 */
class PushRequestTest {

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

    private fun client() = DistrictApiClient(
        baseUrl = server.url("/"),
        httpClient = OkHttpClient(),
        tokens = signedInCoordinator(refreshApi),
    )

    private fun push(): PushApi = HttpPushApi(client())

    private fun inbound(): InboundCallApi = HttpInboundCallApi(client())

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    @Test
    fun `register posts the district path with the token and platform, and no device id`() = runTest {
        server.enqueue(ok("""{"success":true}"""))

        push().registerPushToken(PushTokenRegisterRequest(token = "fcm-abc", platform = PLATFORM_ANDROID))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        // ⛔ THE DISTRICT PREFIX, NOT THE NATIVE-AUTH ONE. Session management lives under
        // `/api/auth/native/devices/...`; PUSH registration is a district resource and sits behind
        // proxy.ts's default-deny middleware as well as the route's own guard.
        assertEquals("/api/district/devices/register", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("fcm-abc", body["token"]?.jsonPrimitive?.content)
        assertEquals("android", body["platform"]?.jsonPrimitive?.content)
        // ⛔ THE ABSENCE IS THE SECURITY PROPERTY. `deviceId` is the upsert key, so a caller who
        // could name it could point somebody else's row at their own token and receive that
        // person's notifications. The server takes it from the bearer instead.
        assertFalse("the body must not name a device", body.containsKey("deviceId"))
    }

    @Test
    fun `unregister posts an empty object, which is an OkHttp constraint rather than a server one`() =
        runTest {
            server.enqueue(ok("""{"success":true}"""))

            push().unregisterPushToken()

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/district/devices/unregister", recorded.url.encodedPath)
            // ⚠️ The route parses NOTHING and would accept a body-less POST happily. OkHttp would
            // not: `Request.method("POST", null)` is rejected outright from the request builder,
            // outside the client's IOException handling, so it would surface as a crash rather than
            // as an ApiResult. `{}` is the smallest thing that is legal here and ignored there.
            assertEquals("{}", recorded.body?.utf8().orEmpty())
        }

    @Test
    fun `answer puts the call id in its own path segment and the workspace in the body`() = runTest {
        server.enqueue(
            ok("""{"success":true,"url":"wss://x","token":"t","roomName":"call_ws-1_CA1"}"""),
        )

        inbound().answerCall("CA1", CallAnswerRequest(workspaceId = "ws-1"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/calls/CA1/answer", recorded.url.encodedPath)

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("ws-1", body["workspaceId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a call id containing slashes cannot escape its segment`() = runTest {
        // ⛔ THE `a/../../admin` CASE, WHICH ALREADY HAPPENED ONCE ON THE TRANSCRIPT ROUTE. OkHttp's
        // `addPathSegments` SPLITS on "/" instead of encoding it, and OkHttp then resolves "..", so
        // an interpolated id could point the request at an entirely different route. The client
        // takes a segment LIST and uses `addPathSegment` (singular) per element; this is what pins
        // that the answer route is built the same way.
        server.enqueue(ok("""{"success":true,"url":"wss://x","token":"t","roomName":"r"}"""))

        inbound().answerCall("a/../../admin", CallAnswerRequest(workspaceId = "ws-1"))

        val path = server.takeRequest().url.encodedPath
        assertTrue(
            "the id must stay inside one segment, got $path",
            path.startsWith("/api/district/calls/") && path.endsWith("/answer"),
        )
        assertFalse("nothing may resolve out of the calls path", path.contains("/admin/"))
    }

    @Test
    fun `all three carry the bearer, which is what identifies the installation`() = runTest {
        // ⛔ THE BEARER IS THE **ONLY** THING THAT NAMES THIS DEVICE ON THE REGISTRATION ROUTES, so
        // a request that somehow lost it would not merely 401 — it would be the shape of request the
        // server refuses precisely because it cannot attribute it. Worth asserting once for all
        // three, because the alternative is discovering it on a phone.
        server.enqueue(ok("""{"success":true}"""))
        server.enqueue(ok("""{"success":true}"""))
        server.enqueue(ok("""{"success":true,"url":"wss://x","token":"t","roomName":"r"}"""))

        push().registerPushToken(PushTokenRegisterRequest(token = "fcm-abc", platform = PLATFORM_ANDROID))
        push().unregisterPushToken()
        inbound().answerCall("CA1", CallAnswerRequest(workspaceId = "ws-1"))

        repeat(3) {
            val header = server.takeRequest().headers["Authorization"]
            assertTrue("every push/answer request must be authenticated", header?.startsWith("Bearer ") == true)
        }
    }
}
