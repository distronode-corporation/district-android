package com.distronode.districtai.core.auth

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The status-code mapping is the whole point of these tests.
 *
 * ⛔ EACH MAPPING HAS A DIFFERENT, SEVERE FAILURE MODE IF WRONG:
 *   - 429 mapped to Rejected  -> users signed out by a transient rate limit (and the server
 *     sizes that limit to allow "several devices behind one NAT", so it fires in real offices).
 *   - 5xx mapped to Rejected  -> the pending marker is cleared even though the server may have
 *     rotated the token, so a later attempt replays it and revokes the entire token family.
 *   - 401 mapped to retryable -> the app spins forever on a credential that is definitively dead.
 *
 * None of those are visible in a unit test that mocks the HTTP client, because the mock simply
 * returns whatever the test asserts. MockWebServer serves real responses over a real socket.
 */
class NativeAuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: NativeAuthApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = NativeAuthApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun tokenBody(suffix: String) = """
        {
          "tokenType": "Bearer",
          "accessToken": "access-$suffix",
          "accessTokenExpiresAt": 1800000600000,
          "refreshToken": "refresh-$suffix",
          "refreshTokenExpiresAt": 1805184000000
        }
    """.trimIndent()

    private fun enqueue(code: Int, body: String = "{}") {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    /**
     * ⚠️ `RecordedRequest.body` is a NULLABLE ByteString in mockwebserver3 (okhttp 5.x), unlike
     * the non-null Buffer of the 4.x `mockwebserver` artifact. Empty rather than null-asserted so
     * a body-less request produces a readable assertion failure instead of an NPE.
     */
    private fun mockwebserver3.RecordedRequest.bodyText(): String = body?.utf8().orEmpty()

    // ── refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `200 yields the rotated pair with millisecond expiries intact`() = runTest {
        enqueue(200, tokenBody("r1"))

        val result = api.refresh("refresh-r0")

        assertTrue(result is RefreshResult.Success)
        val tokens = (result as RefreshResult.Success).tokens
        assertEquals("access-r1", tokens.accessToken)
        assertEquals("refresh-r1", tokens.refreshToken)
        // ⚠️ Milliseconds, not seconds. Dividing here would make the client think every token
        // expired in 1970 and refresh in a tight loop.
        assertEquals(1_800_000_600_000L, tokens.accessTokenExpiresAt)
        assertEquals(1_805_184_000_000L, tokens.refreshTokenExpiresAt)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/native/refresh", request.target)
        assertTrue(
            "the refresh token must be in the body",
            request.bodyText().contains("refresh-r0"),
        )
    }

    @Test
    fun `401 invalid_grant is a dead credential`() = runTest {
        enqueue(401, """{"error":"invalid_grant","message":"Session expired."}""")
        assertEquals(RefreshResult.Rejected, api.refresh("refresh-r0"))
    }

    @Test
    fun `429 is retryable, NOT a dead credential`() = runTest {
        enqueue(429, """{"error":"rate_limited","message":"Too many attempts."}""")
        assertEquals(
            "the server rate-limits BEFORE rotating, so the token is provably unspent",
            RefreshResult.RateLimited,
            api.refresh("refresh-r0"),
        )
    }

    @Test
    fun `5xx is ambiguous, not fatal`() = runTest {
        enqueue(500, """{"error":"server_error"}""")
        assertEquals(
            "a 500 may have rotated the token before failing — the marker must stay set",
            RefreshResult.TransportFailure,
            api.refresh("refresh-r0"),
        )
    }

    @Test
    fun `an unreachable server is NOT SENT, not ambiguous`() = runTest {
        // ⛔ THIS TEST USED TO ASSERT TransportFailure, i.e. IT AGREED WITH THE BUG. Nothing is
        // listening, so the connection is refused and not one byte of the request left the
        // device — the refresh token is provably unspent. Calling that "ambiguous" left the
        // pending marker set, and the next attempt read marker == stored token and cleared the
        // session. The practical cost was that opening the app offline signed the user out,
        // since the access token is memory-only and every cold start more than ten minutes
        // after last use has to refresh.
        server.close() // nothing listening
        assertEquals(
            "a request that never left the device must not cost the session",
            RefreshResult.NotSent,
            api.refresh("refresh-r0"),
        )
    }

    @Test
    fun `a 200 with an unparseable body is treated as ambiguous`() = runTest {
        // ⛔ The worst case: the server HAS rotated the token and we cannot read the successor.
        // Anything other than TransportFailure here either loses the session silently or lets a
        // spent token be replayed.
        enqueue(200, """{"tokenType":"Bearer"}""")
        assertEquals(RefreshResult.TransportFailure, api.refresh("refresh-r0"))
    }

    // ── code exchange ────────────────────────────────────────────────────────

    @Test
    fun `code exchange posts every field the server's schema requires`() = runTest {
        enqueue(200, tokenBody("first"))

        val result = api.exchangeCode(
            CodeExchangeRequest(
                code = "auth-code",
                codeVerifier = "v".repeat(43),
                redirectUri = "districtai://auth",
                deviceId = "device-abcdefgh",
                deviceName = "Pixel \"Test\" 9",
            ),
        )

        assertTrue(result is CodeExchangeResult.Success)

        val request = server.takeRequest()
        assertEquals("/api/auth/native/token", request.target)
        val body = request.bodyText()
        listOf("code", "codeVerifier", "redirectUri", "deviceId", "deviceName", "platform")
            .forEach { field ->
                assertTrue("body must contain $field, was: $body", body.contains("\"$field\""))
            }
        assertTrue(
            "platform must be exactly \"android\" — the server's zod enum accepts only ios|android",
            body.contains("\"platform\":\"android\""),
        )
        // The device name contains quotes on purpose: an unescaped one produces a malformed
        // body and a 400 on every login.
        assertTrue(
            "quotes in a device name must be escaped, not emitted raw",
            body.contains("""Pixel \"Test\" 9"""),
        )
    }

    @Test
    fun `code exchange maps 400 to rejected`() = runTest {
        // Expired (codes live 120s), replayed, PKCE mismatch, redirect mismatch — the server
        // collapses all of them into one opaque invalid_grant.
        enqueue(400, """{"error":"invalid_grant"}""")
        assertEquals(
            CodeExchangeResult.Rejected,
            api.exchangeCode(
                CodeExchangeRequest("c", "v".repeat(43), "districtai://auth", "device-abcdefgh"),
            ),
        )
    }

    @Test
    fun `code exchange maps 429 to rate limited`() = runTest {
        enqueue(429, """{"error":"rate_limited"}""")
        assertEquals(
            CodeExchangeResult.RateLimited,
            api.exchangeCode(
                CodeExchangeRequest("c", "v".repeat(43), "districtai://auth", "device-abcdefgh"),
            ),
        )
    }

    // ── revoke ───────────────────────────────────────────────────────────────
    //
    // ⛔ THE MAPPING IS THE MIRROR IMAGE OF `refresh`'S, AND GETTING IT BACKWARDS IS EXPENSIVE IN
    // BOTH DIRECTIONS. Treating the 503 as Done throws away a refresh token the server is still
    // honouring for up to 60 days, with nothing tracking it — the exact fail-open the route was
    // changed to stop reporting as success. Treating a 200 or a 4xx as RetryLater leaves an
    // outbox entry that can never drain, retried on every launch forever.

    @Test
    fun `200 is done`() = runTest {
        enqueue(200, """{"success":true}""")

        assertEquals(RevokeResult.Done, api.revoke("refresh-r0"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/auth/native/revoke", request.target)
        assertTrue(
            "the refresh token IS the credential here, so it must be in the body",
            request.bodyText().contains("refresh-r0"),
        )
    }

    @Test
    fun `an unknown token is done, because the server answers 200 for one`() = runTest {
        // ⛔ THE ROUTE DELIBERATELY DOES NOT CONFIRM WHETHER A PRESENTED TOKEN EXISTED — it is
        // unauthenticated by necessity, so a 4xx for "already gone" would both push clients into
        // retry loops and turn the endpoint into an existence oracle. So this looks identical to
        // the case above, and the client must not try to tell them apart.
        enqueue(200, """{"success":true}""")
        assertEquals(RevokeResult.Done, api.revoke("a-token-the-server-never-issued"))
    }

    @Test
    fun `404 is done, not a retry`() = runTest {
        // ⚠️ THE ASYMMETRY WITH `refresh`, STATED AS A TEST. There a 400 is retryable because the
        // token is provably unspent and the session is worth saving; here there is no session to
        // save, and a status the server chose is an answer. Chasing it would mean an outbox entry
        // that never drains.
        enqueue(404, """{"error":"not_found"}""")
        assertEquals(RevokeResult.Done, api.revoke("refresh-r0"))
    }

    @Test
    fun `400 is done, not a retry`() = runTest {
        // A body this client sent that the route refuses will be refused identically next launch.
        enqueue(400, """{"error":"invalid_request","message":"refreshToken is required"}""")
        assertEquals(RevokeResult.Done, api.revoke("refresh-r0"))
    }

    @Test
    fun `429 is done, because retrying the same token will not clear a limit`() = runTest {
        // ⚠️ Deliberately NOT the `refresh` treatment. There a 429 protects a live session worth
        // keeping; here the session is already gone locally, and the only thing a retry could
        // achieve is a second refusal.
        enqueue(429, """{"error":"rate_limited"}""")
        assertEquals(RevokeResult.Done, api.revoke("refresh-r0"))
    }

    @Test
    fun `503 must be retried, because the write did not happen`() = runTest {
        // ⛔ THE CASE THIS WHOLE MECHANISM EXISTS FOR. The route returns 503 specifically when its
        // database write THREW: the server may honour that refresh token for the rest of its
        // 60-day life, so the client must keep it and try again. Mapping this to Done is the
        // fail-open the route's own header describes — "a client that threw its refresh token
        // away while the server kept honouring it".
        enqueue(503, """{"error":"temporarily_unavailable"}""")
        assertEquals(RevokeResult.RetryLater, api.revoke("refresh-r0"))
    }

    @Test
    fun `500 must be retried too`() = runTest {
        // Same class of unknown as the 503. The route documents 503 as its deliberate answer, but
        // an edge proxy or an unhandled path can still produce a bare 5xx, and the client cannot
        // tell whether the write landed.
        enqueue(500, """{"error":"server_error"}""")
        assertEquals(RevokeResult.RetryLater, api.revoke("refresh-r0"))
    }

    @Test
    fun `an unreachable server must be retried`() = runTest {
        // ⚠️ NOT the `NotSent` distinction `refresh` makes, and it would be meaningless here:
        // that split exists to tell a spent refresh token from an unspent one. A revoke has no
        // such state — every failure to get an answer means the same thing.
        server.close() // nothing listening
        assertEquals(RevokeResult.RetryLater, api.revoke("refresh-r0"))
    }

    @Test
    fun `an unparseable 200 is still done`() = runTest {
        // ⚠️ Nothing in the response body is read. The route answers `{success:true}` and the
        // status is the entire contract, so a body this client cannot parse changes nothing —
        // unlike `refresh`, where an unreadable 200 means the successor token is lost.
        enqueue(200, "<html>hello</html>")
        assertEquals(RevokeResult.Done, api.revoke("refresh-r0"))
    }

    @Test
    fun `deviceName is omitted entirely when null rather than sent as null`() = runTest {
        enqueue(200, tokenBody("first"))

        api.exchangeCode(
            CodeExchangeRequest("c", "v".repeat(43), "districtai://auth", "device-abcdefgh", deviceName = null),
        )

        val body = server.takeRequest().bodyText()
        // The server's schema has deviceName as `.optional()`, which accepts an absent key but
        // NOT an explicit null — that would fail validation with a 400.
        assertTrue("deviceName should be absent, was: $body", !body.contains("deviceName"))
    }
}
