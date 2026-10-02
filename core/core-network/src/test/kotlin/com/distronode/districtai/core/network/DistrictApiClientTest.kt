package com.distronode.districtai.core.network

import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.auth.RefreshResult
import com.distronode.districtai.core.auth.TokenRefreshCoordinator
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The status-code and envelope mapping, against a real socket.
 *
 * ⛔ MOCKWEBSERVER RATHER THAN A STUBBED CLIENT, for the same reason core-auth does it: what
 * is under test here decides whether a user is signed out, told to wait, or shown an empty
 * account. A fake that returned canned [ApiResult]s would only prove the fake agrees with
 * itself, and the interesting failures live in the real HTTP details — a one-shot body being
 * read twice, a 401 retry re-sending the wrong header, a non-JSON error page.
 */
class DistrictApiClientTest {

    @Serializable
    data class Payload(val value: String, val count: Int = 0)

    /** ⚠️ Segments, one per element — see the note on DistrictApiClient.get. */
    private val thingPath = listOf("api", "district", "thing")

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

    private fun client(signedIn: Boolean = true) = testApiClient(
        server,
        tokens = if (signedIn) signedInCoordinator(refreshApi) else signedOutCoordinator(),
    )

    private fun get() = client().let { c ->
        suspend { c.get(thingPath, Payload.serializer()) }
    }

    // ── Success ──────────────────────────────────────────────────────────────

    @Test
    fun `decodes a 2xx body`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok","count":3}"""))

        val result = get()()

        assertEquals(ApiResult.Success(Payload("ok", 3)), result)
    }

    @Test
    fun `sends the bearer token`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok"}"""))

        get()()

        // access-1 is the first token the fake refresh mints; the coordinator holds no
        // access token after construction, so the first call necessarily refreshes.
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `ignores an unmodelled server field rather than failing`() = runTest {
        // ⛔ THE OPPOSITE POLICY FROM ContractFixtureTest, ON PURPOSE. The gate is strict so
        // a new field reds CI; the SHIPPED parser is lenient so that same field, deployed
        // before a user updates the app, degrades to "ignored" instead of "every response
        // fails to parse on installed builds".
        server.enqueue(
            MockResponse(code = 200, body = """{"value":"ok","count":1,"brandNewField":"boom"}"""),
        )

        assertEquals(ApiResult.Success(Payload("ok", 1)), get()())
    }

    @Test
    fun `reports a shape mismatch as a decode failure, not a network failure`() = runTest {
        // "You are offline" would be the wrong message and would hide contract drift behind
        // a retry button.
        server.enqueue(MockResponse(code = 200, body = """{"value":42}"""))

        val result = get()()

        assertTrue("expected DecodeFailure, got $result", result is ApiResult.DecodeFailure)
        assertTrue((result as ApiResult.DecodeFailure).bodyPreview.contains("42"))
    }

    // ── Content type: contract drift versus a captive portal ─────────────────

    @Test
    fun `reports an HTML 200 as connectivity rather than telling the user to update`() = runTest {
        // ⛔ THE CAPTIVE PORTAL. Hotel, airport and conference wifi answers 200 with its own login
        // page for every request the app makes. Handed to the parser this produced DecodeFailure,
        // which FailureText renders as "District AI sent a response this version of the app does
        // not understand. Updating the app should fix it." and offers no retry — a working app
        // telling the user to reinstall it because they had not accepted the wifi terms yet.
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "text/html; charset=utf-8"),
                body = "<html><body><h1>Sign in to WiFi</h1></body></html>",
            ),
        )

        val result = get()()

        assertTrue("expected NetworkFailure, got $result", result is ApiResult.NetworkFailure)
        val cause = (result as ApiResult.NetworkFailure).cause
        assertTrue("expected NonJsonResponseException, got $cause", cause is NonJsonResponseException)
        cause as NonJsonResponseException
        assertTrue(cause.contentType.startsWith("text/html"))
        assertEquals("the status is retained for diagnostics", 200, cause.status)
    }

    @Test
    fun `keeps DecodeFailure meaning real contract drift`() = runTest {
        // ⛔ THE OTHER HALF OF THE PREVIOUS TEST, AND THE REASON IT IS A SEPARATE CASE. A JSON body
        // whose SHAPE is wrong is a shipped incompatibility: retrying can never fix it and the
        // "update the app" wording is correct. Folding the portal case in here is what made that
        // message unreachable-but-shown; folding this case into connectivity would hide genuine
        // drift behind a retry button.
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = """{"value":42}""",
            ),
        )

        assertTrue(get()() is ApiResult.DecodeFailure)
    }

    @Test
    fun `decodes a body whose content type the server did not send`() = runTest {
        // ⚠️ ABSENT IS NOT EVIDENCE OF INTERCEPTION, and failing closed on it would break a working
        // app behind a header-stripping proxy in exchange for nothing. Every District route answers
        // through NextResponse.json so a real response always carries one; a portal always says
        // text/html. Only a PRESENT, non-JSON type is treated as interception.
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok","count":2}"""))

        assertEquals(ApiResult.Success(Payload("ok", 2)), get()())
    }

    @Test
    fun `accepts a structured JSON suffix`() = runTest {
        // ⚠️ `application/problem+json` and friends are still JSON (RFC 6839), and an edge or a
        // future route answering one must not read as a captive portal. Matched on the SUBTYPE.
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "application/problem+json"),
                body = """{"value":"ok"}""",
            ),
        )

        assertEquals(ApiResult.Success(Payload("ok")), get()())
    }

    @Test
    fun `accepts a json content type regardless of case and parameters`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                headers = Headers.headersOf("Content-Type", "Application/JSON; charset=UTF-8"),
                body = """{"value":"ok"}""",
            ),
        )

        assertEquals(ApiResult.Success(Payload("ok")), get()())
    }

    @Test
    fun `an HTML error page still maps on its status rather than its body`() = runTest {
        // ⚠️ The ERROR path already tolerated non-JSON (see parseError) and must keep doing so —
        // the content-type gate is on the SUCCESS path only. A 502 of HTML is still a 502.
        server.enqueue(
            MockResponse(
                code = 502,
                headers = Headers.headersOf("Content-Type", "text/html"),
                body = "<html>Bad Gateway</html>",
            ),
        )

        val result = get()()

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        assertEquals(502, (result as ApiResult.HttpFailure).status)
    }

    // ── Query parameters ─────────────────────────────────────────────────────

    @Test
    fun `omits a null query parameter instead of sending it empty`() = runTest {
        // The server distinguishes absent (re-derive the workspace) from present-but-empty,
        // which fails its id pattern with a 400.
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok"}"""))

        client().get(thingPath, Payload.serializer(), mapOf("workspaceId" to null))

        assertEquals("/api/district/thing", server.takeRequest().url.encodedPath)
        assertNull(server.url("/").newBuilder().build().queryParameter("workspaceId"))
    }

    @Test
    fun `sends a present query parameter`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"value":"ok"}"""))

        client().get(thingPath, Payload.serializer(), mapOf("workspaceId" to "ws-1"))

        assertEquals("ws-1", server.takeRequest().url.queryParameter("workspaceId"))
    }

    // ── 401 and the single retry ──────────────────────────────────────────────

    @Test
    fun `refreshes and retries once when a token is rejected`() = runTest {
        // What a server-side revocation looks like: the coordinator believed the token was
        // fresh (it only consults the clock) but requireAuth's isSessionRevoked check
        // rejected it.
        server.enqueue(MockResponse(code = 401, body = """{"error":"Session expired. Please sign in again."}"""))
        server.enqueue(MockResponse(code = 200, body = """{"value":"recovered"}"""))

        val result = get()()

        assertEquals(ApiResult.Success(Payload("recovered")), result)
        assertEquals("Bearer access-1", server.takeRequest().headers["Authorization"])
        // A DIFFERENT token on the retry. Re-sending the rejected one would loop forever,
        // and this is the assertion that proves invalidateAccessToken actually took effect.
        assertEquals("Bearer access-2", server.takeRequest().headers["Authorization"])
        assertEquals(2, refreshApi.callCount)
    }

    @Test
    fun `gives up after one retry rather than looping`() = runTest {
        // ⛔ Bounded on purpose. Looping would hammer the refresh endpoint, trip its
        // per-user rate limit, and turn a clean re-login into a 429 storm that degrades the
        // same user's other devices.
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))
        server.enqueue(MockResponse(code = 401, body = """{"error":"Unauthorized"}"""))

        val result = get()()

        assertEquals(ApiResult.Unauthorized(reason = null), result)
        assertEquals(2, server.requestCount)
    }

    // ── Coordinator outcomes that never reach the network ────────────────────

    @Test
    fun `reports a throttled refresh as rate limited and keeps the session`() = runTest {
        // ⛔ NOT A SIGN-OUT. The server rate-limits before rotating, so the credential was
        // provably never consumed. Its limits are sized to tolerate several devices behind
        // one NAT — an office sharing an egress IP — and signing them out would be the wrong
        // answer to the product working as designed.
        refreshApi.always(RefreshResult.RateLimited)

        val result = get()()

        assertEquals(ApiResult.RateLimited::class, result::class)
        assertTrue((result as ApiResult.RateLimited).refreshThrottled)
        assertEquals("no request should be sent", 0, server.requestCount)
    }

    @Test
    fun `reports no session without sending a request`() = runTest {
        val result = client(signedIn = false).get(thingPath, Payload.serializer())

        assertEquals(ApiResult.Unauthorized(ReauthReason.NoSession), result)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `surfaces an interrupted refresh as its own reason`() = runTest {
        // Process died mid-refresh. ⚠️ Signs the user out but is NOT a security event, and the
        // reason has to survive to the UI so the prompt can be worded as a routine re-login.
        refreshApi.always(RefreshResult.TransportFailure)

        val first = get()()
        assertEquals(ApiResult.Unauthorized(ReauthReason.RefreshUnreachable), first)
        assertEquals(0, server.requestCount)
    }

    // ── Error envelopes ──────────────────────────────────────────────────────

    @Test
    fun `maps a bare error envelope on 403`() = runTest {
        // The shared auth guard's shape: no `success` key at all. A strict {success,error}
        // DTO would throw here, on every 403 in the API.
        server.enqueue(
            MockResponse(code = 403, body = """{"error":"Forbidden: Insufficient workspace privileges"}"""),
        )

        assertEquals(
            ApiResult.Forbidden("Forbidden: Insufficient workspace privileges"),
            get()(),
        )
    }

    @Test
    fun `maps 404 including the no-workspace case`() = runTest {
        // ⚠️ 404, not 403, when the account has no workspace at all — a distinct legitimate
        // state that needs its own screen rather than a "not found" dead end.
        server.enqueue(MockResponse(code = 404, body = """{"error":"User has no workspace"}"""))

        assertEquals(ApiResult.NotFound("User has no workspace"), get()())
    }

    @Test
    fun `maps 429 without marking it as a throttled refresh`() = runTest {
        server.enqueue(MockResponse(code = 429, body = """{"error":"Too many requests."}"""))

        val result = get()()

        assertEquals(ApiResult.RateLimited("Too many requests.", refreshThrottled = false), result)
    }

    @Test
    fun `maps a success-false envelope`() = runTest {
        // A route's OWN failure shape, as opposed to its auth guard's.
        server.enqueue(MockResponse(code = 500, body = """{"success":false,"error":"Boom"}"""))

        assertEquals(ApiResult.HttpFailure(500, "Boom", code = null), get()())
    }

    @Test
    fun `carries a machine-readable code when the server sends one`() = runTest {
        server.enqueue(
            MockResponse(
                code = 500,
                body = """{"error":"An internal error occurred. Please try again.","code":"INTERNAL_ERROR"}""",
            ),
        )

        assertEquals(
            ApiResult.HttpFailure(500, "An internal error occurred. Please try again.", "INTERNAL_ERROR"),
            get()(),
        )
    }

    @Test
    fun `distinguishes degraded regions from an ordinary outage`() = runTest {
        // ⛔ THE CASE THAT MUST NEVER BE RENDERED AS EMPTY. "We could not look" and "there is
        // nothing" are different answers, and showing the second reads to the user as
        // account loss. Keyed on the CODE, not the status.
        server.enqueue(
            MockResponse(
                code = 503,
                body = """{"error":"regions unreachable","code":"REGIONS_DEGRADED","degradedRegions":["eu","apac"]}""",
            ),
        )

        val result = get()()

        assertEquals(
            ApiResult.RegionsDegraded("regions unreachable", listOf("eu", "apac")),
            result,
        )
    }

    @Test
    fun `treats a plain 503 as an ordinary failure`() = runTest {
        // An edge proxy outage carries no code and says nothing about regions.
        server.enqueue(MockResponse(code = 503, body = """{"error":"upstream unavailable"}"""))

        assertEquals(ApiResult.HttpFailure(503, "upstream unavailable", null), get()())
    }

    @Test
    fun `falls back to a readable message when the error body is not JSON`() = runTest {
        // ⚠️ Expected, not exceptional: an edge 502 or a captive portal answers HTML. The
        // status code is still meaningful and must not be lost to a parse failure.
        server.enqueue(MockResponse(code = 502, body = "<html><body>Bad Gateway</body></html>"))

        val result = get()()

        assertTrue("expected HttpFailure, got $result", result is ApiResult.HttpFailure)
        result as ApiResult.HttpFailure
        assertEquals(502, result.status)
        assertTrue(
            "message should be showable, was '${result.message}'",
            result.message.startsWith("Something went wrong"),
        )
    }

    @Test
    fun `falls back to a readable message when the error body is empty`() = runTest {
        server.enqueue(MockResponse(code = 500, body = ""))

        val result = get()()

        assertEquals(
            ApiResult.HttpFailure(500, ApiErrorEnvelope.FALLBACK_MESSAGE, null),
            result,
        )
    }

    @Test
    fun `falls back to a readable message when the error text is blank`() = runTest {
        // A blank sentence is not a message; showing it would render an empty error banner.
        server.enqueue(MockResponse(code = 500, body = """{"error":"   "}"""))

        assertEquals(ApiResult.HttpFailure(500, ApiErrorEnvelope.FALLBACK_MESSAGE, null), get()())
    }

    // ── Transport ────────────────────────────────────────────────────────────

    @Test
    fun `a request cancelled in flight never reaches the server and its late failure is swallowed`() =
        runTest {
            // OkHttp runs calls on its dispatcher's executor; holding the work here makes the order
            // of "coroutine cancelled" and "OkHttp reports the cancelled call" deterministic.
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val held = ArrayDeque<Runnable>()
            val executor = object : AbstractExecutorService() {
                override fun execute(command: Runnable) {
                    held.addLast(command)
                }
                override fun shutdown() = Unit
                override fun shutdownNow(): List<Runnable> = emptyList()
                override fun isShutdown(): Boolean = false
                override fun isTerminated(): Boolean = false
                override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
            }
            val client = DistrictApiClient(
                baseUrl = server.url("/"),
                httpClient = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).build(),
                tokens = TokenRefreshCoordinator(
                    store = FakeTokenStore(
                        PersistedSession(
                            refreshToken = "refresh-0",
                            refreshTokenExpiresAt = System.currentTimeMillis() + 24L * 60 * 60 * 1000,
                            deviceId = "device-under-test",
                        ),
                    ),
                    refreshApi = refreshApi,
                    io = testDispatcher,
                ),
                io = testDispatcher,
            )
            var outcome: ApiResult<Payload>? = null

            val request = launch { outcome = client.get(thingPath, Payload.serializer()) }
            runCurrent()
            assertEquals("the call must be handed to OkHttp and be waiting", 1, held.size)

            request.cancel()
            runCurrent()
            // OkHttp now runs the call it was told to cancel. It reports a failure on its own
            // thread, which must neither throw there nor resume a coroutine that is gone.
            held.removeFirst().run()
            runCurrent()

            assertTrue(request.isCancelled)
            assertNull("a cancelled request produces no result", outcome)
            assertEquals("a cancelled call must not reach the server", 0, server.requestCount)
        }

    @Test
    fun `reports an unreachable server as a network failure`() = runTest {
        val url = server.url("/")
        server.close()

        val result = DistrictApiClient(
            baseUrl = url,
            httpClient = DistrictHttp.client(),
            tokens = signedInCoordinator(refreshApi),
        ).get(thingPath, Payload.serializer())

        assertTrue("expected NetworkFailure, got $result", result is ApiResult.NetworkFailure)
    }
}
