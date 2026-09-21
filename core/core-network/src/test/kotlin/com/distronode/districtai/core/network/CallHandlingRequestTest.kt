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
 * What the call-handling and availability routes receive.
 *
 * ⛔ THE TWO PATCHES ARE THE ONLY WRITES ON THIS SURFACE WHERE A DROPPED NULL IS THE FEATURE. The
 * call-handling route refuses a body carrying only the workspace ("Nothing to update", 400) and
 * accepts either field alone, so the encoder's `explicitNulls = false` is what makes "send only
 * what changed" reach the wire. Nothing pinned that until this file.
 *
 * ⛔ AND THE AVAILABILITY PATCH IS THE MIRROR CASE: `availableForCalls` is NON-NULLABLE precisely
 * so the same encoder cannot drop a `false`, which is the value a careless optional would lose.
 */
class CallHandlingRequestTest {

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

    private fun api(): CallHandlingApi = HttpCallHandlingApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    private fun okHandling() = MockResponse(
        code = 200,
        body = """{"success":true,"callHandling":"ai_then_app","appRingSeconds":20}""",
    )

    private fun okAvailability() = MockResponse(
        code = 200,
        body = """{"success":true,"availableForCalls":false,"reason":null}""",
    )

    private fun recordedBody(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject

    @Test
    fun `the call-handling read is a GET carrying only the workspace`() = runTest {
        server.enqueue(okHandling())

        api().callHandling("ws-3")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/workspace/call-handling", recorded.url.encodedPath)
        assertEquals(setOf("workspaceId"), recorded.url.queryParameterNames)
        assertEquals("ws-3", recorded.url.queryParameter("workspaceId"))
    }

    @Test
    fun `saving only the mode omits the ring window entirely`() = runTest {
        // ⛔ THE POINT OF THE NULL DROP. Sending both every time would be harmless today and still
        // wrong: it would write a ring window the operator never touched.
        server.enqueue(okHandling())

        api().saveCallHandling("ws-3", callHandling = "app_first", appRingSeconds = null)

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "callHandling"), body.keys)
        assertEquals("app_first", body["callHandling"]?.jsonPrimitive?.content)
        assertFalse("an untouched ring window must not be sent", "appRingSeconds" in body)
    }

    @Test
    fun `saving only the ring window omits the mode, and sends a whole number`() = runTest {
        // ⛔ THE ROUTE'S SCHEMA IS `.int()`. A value that encoded as `20.0` would be refused as
        // fractional, which is why the parameter is an Int rather than a Double.
        server.enqueue(okHandling())

        api().saveCallHandling("ws-3", callHandling = null, appRingSeconds = 25)

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "appRingSeconds"), body.keys)
        assertEquals("25", body["appRingSeconds"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the call-handling write is a PATCH, not a POST`() = runTest {
        // ⛔ The route exports PATCH only; a POST would 405.
        server.enqueue(okHandling())

        api().saveCallHandling("ws-3", callHandling = "ai_first", appRingSeconds = null)

        assertEquals("PATCH", server.takeRequest().method)
    }

    @Test
    fun `the availability read is a GET on its own path, not a field of call-handling`() = runTest {
        server.enqueue(okAvailability())

        api().availability("ws-3")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        // ⛔ A DIFFERENT SCOPE FROM ITS NEIGHBOUR. `call-handling` is the workspace's; this one is
        // the caller's own membership row.
        assertEquals("/api/district/workspace/availability", recorded.url.encodedPath)
    }

    @Test
    fun `the availability write carries a false and takes no identity`() = runTest {
        // ⛔ TWO RULES IN ONE ASSERTION. `false` must survive the null-dropping encoder, and there
        // must be no `email` or `userId` on the wire: an identity that arrives as an argument is
        // an identity the caller chose, and this route writes the row the SESSION resolves.
        server.enqueue(okAvailability())

        api().saveAvailability("ws-3", availableForCalls = false)

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals(setOf("workspaceId", "availableForCalls"), body.keys)
        assertEquals("false", body["availableForCalls"]?.jsonPrimitive?.content)
        assertFalse("no identity may be sent", "email" in body)
        assertFalse("no identity may be sent", "userId" in body)
    }

    @Test
    fun `a reason of null decodes rather than being treated as a missing key`() = runTest {
        // ⚠️ `reason` IS ALWAYS PRESENT ON THE WIRE AND IS null WHEN THERE IS NOTHING TO EXPLAIN.
        // A key that appeared only in the interesting cases is exactly the shape a strict decoder
        // fails on for the answer worth reading.
        server.enqueue(okAvailability())

        val result = api().availability("ws-3")

        assertTrue(result is ApiResult.Success)
        assertEquals(null, (result as ApiResult.Success).value.reason)
    }
}
