package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DistrictSetupResponse
import java.io.File
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What [HttpSetupApi] puts on the wire, and what it makes of the answer.
 *
 * The setup read is owner-scoped and addressed by the workspace in the QUERY, not the path, and
 * a read carries no body. The answer is the committed fixture the server's own suite generated
 * for the route, compared with an independent decode of the same file.
 */
class HttpSetupApiTest {

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

    private fun fixture(name: String): String {
        val configured = System.getProperty("district.contracts.dir")
        assertTrue(
            "System property district.contracts.dir is not set; without it this test cannot find " +
                "the fixture and would otherwise pass by verifying nothing.",
            !configured.isNullOrBlank(),
        )
        val file = File(configured!!, name)
        assertTrue("Missing contract fixture ${file.absolutePath}.", file.isFile)
        return file.readText()
    }

    @Test
    fun `the setup read is a bodiless GET addressed by the workspace in the query`() = runTest {
        val body = fixture("district-setup.json")
        server.enqueue(MockResponse(code = 200, body = body))
        val api = HttpSetupApi(testApiClient(server))

        val result = api.districtSetup("ws-1")

        val expected = DistrictApiClient.DEFAULT_JSON.decodeFromString(DistrictSetupResponse.serializer(), body)
        assertEquals(ApiResult.Success(expected), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/setup", recorded.url.encodedPath)
        assertEquals(listOf("workspaceId"), recorded.url.queryParameterNames.toList())
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        assertEquals("a read carries no body", 0, recorded.body?.size ?: 0)
    }

    @Test
    fun `a member's forbidden answer comes back as Forbidden rather than as data`() = runTest {
        // The route is owner-only. The interface promises callers a Forbidden they can treat as
        // "nothing to show", so the status must survive the client rather than decode as a body.
        server.enqueue(MockResponse(code = 403, body = """{"success":false,"error":"Owner only"}"""))
        val api = HttpSetupApi(testApiClient(server))

        assertEquals(ApiResult.Forbidden("Owner only"), api.districtSetup("ws-1"))
    }
}
