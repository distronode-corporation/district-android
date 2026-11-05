package com.distronode.districtai.core.network

import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What live supervise puts on the wire, answered with the server's recorded fixtures, and the proof
 * that none of its three actions is ever sent twice.
 *
 * ⛔ THE NO-RETRY TESTS ENQUEUE A SECOND, SUCCESSFUL ANSWER BEHIND THE FAILURE. A client that
 * retried would consume it and report success; one that does not reports the failure, once, and
 * the server counts one request.
 */
class SuperviseRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun api() = HttpSuperviseApi(testApiClient(server, FakeRefreshApi().apply { rotating() }))

    private fun fixture(name: String): MockResponse {
        val dir = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir must be set by core-network's build", !dir.isNullOrBlank())
        return MockResponse(code = 200, body = File(dir, name).readText())
    }

    @Test
    fun `listening posts the call id as the route's roomName and decodes the grant`() = runTest {
        server.enqueue(fixture("district-call-monitor-token.json"))

        val result = api().monitorCall("cm1call")

        assertEquals("wss://livekit-wss.distronode.com", (result as ApiResult.Success).value.url)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/calls/token", recorded.url.encodedPath)
        assertEquals("""{"roomName":"cm1call","identity":"supervisor-android"}""", recorded.body?.utf8())
    }

    @Test
    fun `a takeover posts the workspace, with the call id as one path segment`() = runTest {
        server.enqueue(fixture("district-call-takeover.json"))

        val result = api().takeOverCall("ws-1", "cm1call")

        assertEquals("contract-livekit-takeover-jwt", (result as ApiResult.Success).value.token)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/calls/cm1call/takeover", recorded.url.encodedPath)
        assertEquals("""{"workspaceId":"ws-1"}""", recorded.body?.utf8())
    }

    @Test
    fun `a takeover id that tries to climb out of its segment stays one segment`() = runTest {
        server.enqueue(fixture("district-call-takeover.json"))

        api().takeOverCall("ws-1", "../../admin")

        assertEquals("/api/district/calls/..%2F..%2Fadmin/takeover", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `a whisper posts the workspace, the call and the line`() = runTest {
        server.enqueue(fixture("district-call-whisper.json"))

        val result = api().whisper("ws-1", "cm1call", "Offer Thursday at 3.")

        assertEquals(true, (result as ApiResult.Success).value.success)
        val recorded = server.takeRequest()
        assertEquals("/api/district/calls/whisper", recorded.url.encodedPath)
        assertEquals(
            """{"workspaceId":"ws-1","callId":"cm1call","whisper":"Offer Thursday at 3."}""",
            recorded.body?.utf8(),
        )
    }

    @Test
    fun `the live calls are a GET scoped by query, read leniently`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"calls":[{"id":"cm1call","from":"+15550100","status":"in-progress",""" +
                    """"roomName":"direct_ws-1-CA01","createdAt":"2026-11-05T14:00:00.000Z","duration":12}]}""",
            ),
        )

        val result = api().activeCalls("ws-1")

        val row = (result as ApiResult.Success).value.calls.single()
        assertTrue(row.isTeamMemberCall)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/calls/active", recorded.url.encodedPath)
        assertEquals("workspaceId=ws-1", recorded.url.encodedQuery)
    }

    @Test
    fun `a server error on any of the three is returned once, from one request`() = runTest {
        val actions: List<suspend () -> ApiResult<*>> = listOf(
            { api().monitorCall("cm1call") },
            { api().takeOverCall("ws-1", "cm1call") },
            { api().whisper("ws-1", "cm1call", "Offer Thursday.") },
        )
        actions.forEachIndexed { index, action ->
            server.enqueue(MockResponse(code = 500, body = """{"success":false,"code":"server_error"}"""))
            server.enqueue(MockResponse(code = 200, body = """{"success":true,"token":"t","url":"wss://x"}"""))

            val result = action()

            assertTrue("action $index must fail, not retry into the 200: $result", result is ApiResult.HttpFailure)
            assertEquals("action $index must send exactly one request", 2 * index + 1, server.requestCount)
            // Drain the unused 200 so the next action starts from a clean queue.
            server.takeRequest()
            api().activeCalls("ws-1")
            server.takeRequest()
        }
    }

    @Test
    fun `a dropped connection on any of the three is returned once, from one request`() = runTest {
        val actions: List<suspend () -> ApiResult<*>> = listOf(
            { api().monitorCall("cm1call") },
            { api().takeOverCall("ws-1", "cm1call") },
            { api().whisper("ws-1", "cm1call", "Offer Thursday.") },
        )
        actions.forEachIndexed { index, action ->
            val before = server.requestCount
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
            server.enqueue(MockResponse(code = 200, body = """{"success":true,"token":"t","url":"wss://x"}"""))

            val result = action()

            assertTrue("action $index must fail, not retry into the 200: $result", result is ApiResult.NetworkFailure)
            assertEquals("action $index must send exactly one request", before + 1, server.requestCount)
            // Spend the queued 200 on a read so the next action meets its own failure first.
            api().activeCalls("ws-1")
        }
    }

    @Test
    fun `a refusal keeps the server's code for the catalog`() = runTest {
        server.enqueue(
            MockResponse(code = 409, body = """{"success":false,"error":"This call has ended.","code":"call_ended"}"""),
        )

        val result = api().monitorCall("cm1call")

        assertEquals(ApiResult.HttpFailure(409, "This call has ended.", "call_ended"), result)
        assertEquals(1, server.requestCount)
    }
}
