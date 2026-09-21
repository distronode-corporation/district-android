package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The VERB, the PATH and the BODY KEYS of the dashboard hand-off.
 *
 * ⛔ WHY THIS EXISTS AND WHY IT IS NOT A CONTRACT FIXTURE. Nothing under
 * `contracts/` pins this route: adding a fixture is a two-repo change (the
 * website's `android-contracts.test.ts` and the iOS suite both count them) and was deliberately
 * deferred, so this file is the only thing standing between a client-side spelling mistake and a
 * scheduling button that cannot work. Every failure below is one this client can commit entirely
 * on its own:
 *
 *   - the route is a JSON **POST** and reads `req.json()`. There is no query-string fallback the
 *     way `scheduling/enable` has one, so a `workspaceId` that travelled as a parameter is a 400
 *     naming a field rather than a cause.
 *   - the path is `handoff`, no hyphen, matching the dashboard route it mints a link to. A
 *     `hand-off` would 404 and read as an unreleased server.
 *   - ⛔ a `deviceId` key MUST NOT appear. The server takes the installation off the verified
 *     bearer and ignores anything the body claims, so sending one is a client asserting an
 *     identity it does not get to assert — and its presence would read to the next person as
 *     evidence the server honours it.
 */
class SchedulingHandOffRequestTest {

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

    private fun schedulingApi(): SchedulingApi = HttpSchedulingApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    @Test
    fun `the hand-off is a POST to scheduling handoff carrying the workspace and the next path`() =
        runTest {
            server.enqueue(
                MockResponse(
                    code = 200,
                    body = """{"url":"https://x.test/dashboard/handoff?code=abc","expiresIn":60}""",
                ),
            )

            val result = schedulingApi().schedulingHandOff(
                SchedulingHandOffRequest(
                    workspaceId = "ws-1",
                    next = "/dashboard/district/scheduling",
                ),
            )

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/api/district/scheduling/handoff", recorded.url.encodedPath)
            // ⚠️ NOTHING IN THE QUERY STRING. A code minted for this workspace is a credential for
            // it, and a query parameter is the half of a request that reaches access logs.
            assertEquals("", recorded.url.encodedQuery.orEmpty())

            val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
            // ⛔ EXACTLY TWO KEYS, AND `deviceId` IS NOT ONE OF THEM. See the class doc.
            assertEquals(setOf("workspaceId", "next"), body.keys)
            assertEquals("ws-1", (body["workspaceId"] as JsonPrimitive).content)
            assertEquals(
                "/dashboard/district/scheduling",
                (body["next"] as JsonPrimitive).content,
            )

            val value = (result as ApiResult.Success).value
            assertEquals("https://x.test/dashboard/handoff?code=abc", value.url)
            assertEquals(60, value.expiresIn)
        }

    @Test
    fun `an absent next is OMITTED rather than sent as an explicit null`() = runTest {
        // ⚠️ `BODY_JSON` SETS `explicitNulls = false`, AND THIS IS WHAT PROVES IT REACHES THIS
        // ROUTE. An explicit `"next": null` is a value the server would have to validate rather
        // than a field it never received, and the route's own default only applies to the second.
        server.enqueue(MockResponse(code = 200, body = """{"url":"https://x.test/d","expiresIn":60}"""))

        schedulingApi().schedulingHandOff(SchedulingHandOffRequest(workspaceId = "ws-1"))

        val body = Json.parseToJsonElement(
            server.takeRequest().body?.utf8().orEmpty(),
        ) as JsonObject
        assertEquals(setOf("workspaceId"), body.keys)
    }

    @Test
    fun `a 200 missing expiresIn still decodes, because no fixture pins this route`() = runTest {
        // ⛔ THE LENIENCY IS THE POINT AND IT IS NOT LAZINESS. Every other scheduling DTO makes its
        // fields required because a committed fixture and a strict gate would catch drift first.
        // This route has neither, so a required `expiresIn` would turn a server that stopped
        // sending it into "scheduling is broken" on every already-installed phone — for a number
        // nothing in this app reads.
        server.enqueue(MockResponse(code = 200, body = """{"url":"https://x.test/d"}"""))

        val result = schedulingApi().schedulingHandOff(SchedulingHandOffRequest("ws-1"))

        assertEquals("https://x.test/d", (result as ApiResult.Success).value.url)
        assertEquals(0, result.value.expiresIn)
    }

    @Test
    fun `a 200 with no url at all is contract drift rather than an empty hand-off`() = runTest {
        // ⛔ THE ASYMMETRY WITH THE TEST ABOVE. `url` IS the response; a default would decode `{}`
        // into an empty string that the repository is then asked to verify and a browser to open.
        server.enqueue(MockResponse(code = 200, body = """{"expiresIn":60}"""))

        val result = schedulingApi().schedulingHandOff(SchedulingHandOffRequest("ws-1"))

        assertTrue("a body with no url must not decode", result is ApiResult.DecodeFailure)
    }

    @Test
    fun `the 429 body puts a MACHINE TOKEN in error, which is why nothing may show it`() = runTest {
        // ⛔ THE TRAP THIS TEST EXISTS TO RECORD. Every other rate limit in this API writes a
        // sentence into `error`; this route writes `rate_limited` there and puts the sentence in
        // `message`, which `ApiErrorEnvelope` does not model. So the shared mapping — which renders
        // `RateLimited.message` verbatim — would print snake_case on a customer's phone. The
        // screen therefore words this one itself; see `SchedulingViewModel.handOffNotice`.
        server.enqueue(
            MockResponse(
                code = 429,
                body = """{"error":"rate_limited","message":"Too many hand-offs. Try again."}""",
            ),
        )

        val result = schedulingApi().schedulingHandOff(SchedulingHandOffRequest("ws-1"))

        assertEquals("rate_limited", (result as ApiResult.RateLimited).message)
    }

    @Test
    fun `a 403 arrives as Forbidden, which on THIS route means the bearer and not the role`() =
        runTest {
            // ⚠️ A bare `{"error":"Forbidden"}`. On every other district route a 403
            // is a role refusal; here it is "no bearer, or it does not verify", so the screen's
            // sentence is "sign in again" rather than "ask an admin".
            server.enqueue(MockResponse(code = 403, body = """{"error":"Forbidden"}"""))

            val result = schedulingApi().schedulingHandOff(SchedulingHandOffRequest("ws-1"))

            assertEquals("Forbidden", (result as ApiResult.Forbidden).message)
        }
}
