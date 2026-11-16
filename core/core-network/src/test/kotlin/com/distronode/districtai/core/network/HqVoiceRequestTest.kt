package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.HqConfirmAction
import com.distronode.districtai.core.model.HqConfirmRequest
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoice
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.model.HqVoiceTokenResponse
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What District HQ by voice puts on the wire, key for key the bodies iOS sends, answered by the
 * contract fixtures the server generated.
 *
 * ⛔ THE SESSION'S TWO ENDINGS ARE SENT WITH THE CREDENTIAL'S OWN `sessionId`, read from the token
 * fixture here, so the end and the discard are proven against the same session the server minted.
 */
class HqVoiceRequestTest {

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

    private fun api(): HqVoiceApi = HttpHqVoiceApi(testApiClient(server))

    private fun fixture(name: String): String {
        val dir = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir is not set (core/core-network/build.gradle.kts)", !dir.isNullOrBlank())
        val file = File(dir!!, name)
        assertTrue("missing fixture ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun answer(name: String, code: Int = 200) {
        server.enqueue(
            MockResponse(
                code = code,
                headers = Headers.headersOf("Content-Type", "application/json"),
                body = fixture(name)
            ),
        )
    }

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

    private fun token(): HqVoiceTokenResponse =
        Json.decodeFromString(HqVoiceTokenResponse.serializer(), fixture("district-hq-voice-token.json"))

    @Test
    fun `the token request carries the workspace, the language and the bounded history`() = runTest {
        answer("district-hq-voice-token.json")
        val history = (1..8).map { HqTurn(if (it % 2 == 0) "model" else "user", "turn $it") } + HqTurn("user", " ")

        val result = api().hqVoiceToken("ws-1", "fr", history)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/hq/voice-token", recorded.url.encodedPath)
        val body = recorded.json()
        assertEquals(setOf("workspaceId", "locale", "history"), body.keys)
        assertEquals(JsonPrimitive("ws-1"), body["workspaceId"])
        assertEquals(JsonPrimitive("fr"), body["locale"])
        val turns = body["history"] as JsonArray
        assertEquals(HqVoice.HISTORY_MAX_TURNS, turns.size)
        assertEquals(
            JsonObject(mapOf("role" to JsonPrimitive("model"), "text" to JsonPrimitive("turn 8"))),
            turns.last(),
        )
        assertEquals(token(), (result as ApiResult.Success).value)
    }

    @Test
    fun `an empty history is left out, and any language but French is English`() = runTest {
        answer("district-hq-voice-token.json")

        api().hqVoiceToken("ws-1", "de", listOf(HqTurn("user", "  ")))

        val body = server.takeRequest().json()
        assertEquals(setOf("workspaceId", "locale"), body.keys)
        assertEquals(JsonPrimitive("en"), body["locale"])
    }

    @Test
    fun `the allowance refusal is a coded failure carrying the service's sentence`() = runTest {
        answer("district-hq-voice-token-allowance.json", code = 409)

        val result = api().hqVoiceToken("ws-1", "en", emptyList())

        val failure = result as ApiResult.HttpFailure
        assertEquals(409, failure.status)
        assertEquals(HqVoice.ALLOWANCE_REACHED_CODE, failure.code)
        val sentence = (Json.parseToJsonElement(fixture("district-hq-voice-token-allowance.json")) as JsonObject)
            .getValue("error").let { (it as JsonPrimitive).content }
        assertEquals(sentence, failure.message)
    }

    @Test
    fun `the member's Stop ends the session by its own id`() = runTest {
        answer("district-hq-voice-end.json")
        val sessionId = token().sessionId

        val result = api().hqVoiceEnd("ws-1", sessionId)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/hq/voice-end", recorded.url.encodedPath)
        assertEquals(
            JsonObject(mapOf("workspaceId" to JsonPrimitive("ws-1"), "sessionId" to JsonPrimitive(sessionId))),
            recorded.json(),
        )
        assertTrue((result as ApiResult.Success).value.ended)
    }

    @Test
    fun `a session that never went live is discarded by its own id`() = runTest {
        answer("district-hq-voice-discard.json")
        val sessionId = token().sessionId

        val result = api().hqVoiceDiscard("ws-1", sessionId)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/hq/voice-discard", recorded.url.encodedPath)
        assertEquals(
            JsonObject(mapOf("workspaceId" to JsonPrimitive("ws-1"), "sessionId" to JsonPrimitive(sessionId))),
            recorded.json(),
        )
        assertTrue((result as ApiResult.Success).value.discarded)
    }

    @Test
    fun `the screen report names the room and a page, and nothing else`() = runTest {
        answer("district-hq-voice-context.json")

        val result = api().hqVoiceContext("ws-1", token().roomName, 1, "hq")

        val recorded = server.takeRequest()
        assertEquals("/api/district/hq/voice-context", recorded.url.encodedPath)
        assertEquals(
            JsonObject(
                mapOf(
                    "workspaceId" to JsonPrimitive("ws-1"),
                    "roomName" to JsonPrimitive(token().roomName),
                    "snapshot" to JsonObject(mapOf("version" to JsonPrimitive(1), "page" to JsonPrimitive("hq"))),
                ),
            ),
            recorded.json(),
        )
        assertTrue((result as ApiResult.Success).value.success)
    }

    @Test
    fun `the settings are read with the workspace in the query and written whole`() = runTest {
        answer("district-hq-voice-settings.json")
        answer("district-hq-voice-settings-put.json")
        val wanted = HqVoiceSettings("Charon", "detailed", "normal", "fr", "push")

        val read = api().hqVoiceSettings("ws-1")
        val written = api().saveHqVoiceSettings("ws-1", wanted)

        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/district/hq/voice-settings", get.url.encodedPath)
        assertEquals("ws-1", get.url.queryParameter("workspaceId"))
        assertEquals("Puck", (read as ApiResult.Success).value.settings.voice)

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/api/district/hq/voice-settings", put.url.encodedPath)
        assertEquals(
            JsonObject(
                mapOf(
                    "workspaceId" to JsonPrimitive("ws-1"),
                    "settings" to JsonObject(
                        mapOf(
                            "voice" to JsonPrimitive("Charon"),
                            "length" to JsonPrimitive("detailed"),
                            "pace" to JsonPrimitive("normal"),
                            "language" to JsonPrimitive("fr"),
                            "micMode" to JsonPrimitive("push"),
                        ),
                    ),
                ),
            ),
            put.json(),
        )
        assertEquals(wanted, (written as ApiResult.Success).value.settings)
    }

    @Test
    fun `a voice proposal is cancelled by its id`() = runTest {
        answer("district-hq-proposal-cancel.json")

        val result = api().hqProposalCancel("ws-1", "p-1")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/hq/proposals/cancel", recorded.url.encodedPath)
        assertEquals(
            JsonObject(mapOf("workspaceId" to JsonPrimitive("ws-1"), "proposalId" to JsonPrimitive("p-1"))),
            recorded.json(),
        )
        assertTrue((result as ApiResult.Success).value.success)
    }

    @Test
    fun `a voice proposal confirmed by a tap carries its id, and a console confirm carries none`() = runTest {
        val confirmed = """{"success":true,"executed":true,"tool":"update_persona","answer":"Done."}"""
        server.enqueue(MockResponse(code = 200, body = confirmed))
        server.enqueue(MockResponse(code = 200, body = confirmed))
        val hq = HttpHqApi(testApiClient(server))

        val args = JsonObject(mapOf("greeting" to JsonPrimitive("Hi")))
        hq.hqConfirm(
            HqConfirmRequest("ws-1", HqConfirmAction(tool = "update_persona", args = args, proposalId = "p-1"))
        )
        hq.hqConfirm(HqConfirmRequest("ws-1", HqConfirmAction(tool = "update_persona", args = args)))

        val voice = server.takeRequest().json()["confirm"] as JsonObject
        assertEquals(setOf("tool", "args", "proposalId"), voice.keys)
        assertEquals(JsonPrimitive("p-1"), voice["proposalId"])
        val console = server.takeRequest().json()["confirm"] as JsonObject
        assertEquals("a console confirm is unchanged", setOf("tool", "args"), console.keys)
    }
}
