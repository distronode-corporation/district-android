package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.TelemetryTokenResponse
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** What the telemetry credential request puts on the wire, and that its fixture decodes. */
class TelemetryRequestTest {

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

    @Test
    fun `the token is a POST with the workspace in the body`() = runTest {
        val fixture = File(System.getProperty("district.contracts.dir"), "district-telemetry-token.json").readText()
        server.enqueue(MockResponse(code = 200, body = fixture))

        val api = HttpTelemetryApi(testApiClient(server, FakeRefreshApi().apply { rotating() }))
        val result = api.telemetryToken("ws_1")

        assertEquals(
            ApiResult.Success(
                TelemetryTokenResponse(
                    success = true,
                    token = "contract-telemetry-token",
                    expiresAt = 1_790_433_900_000,
                    wsUrl = "wss://telemetry.example.com/ws/telemetry",
                ),
            ),
            result,
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/telemetry/token", recorded.url.encodedPath)
        // ⚠️ The route reads `body.workspaceId`, never the query.
        assertEquals(null, recorded.url.queryParameter("workspaceId"))
        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("ws_1", body["workspaceId"]?.jsonPrimitive?.content)
    }
}
