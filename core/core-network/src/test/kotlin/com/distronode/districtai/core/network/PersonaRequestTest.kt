package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.PersonaPreviewForm
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the two persona routes actually put on the wire.
 *
 * ⛔ THE PREVIEW BODY NESTS UNDER `formData`, unlike every other write on this surface, and the
 * route answers **400 "Missing workspaceId or formData"** for a flattened one — which reads as a
 * broken client rather than as a shape mismatch. Nothing else pinned that shape.
 *
 * ⚠️ DRIVEN OVER REAL HTTP THROUGH MockWebServer, like [InboxRequestBodyTest], rather than by
 * calling the serializer directly: the setting that decides the shape (`explicitNulls = false`)
 * lives on a private Json instance inside the production file, so encoding a DTO here with a
 * locally built Json would test a different configuration than the one that ships.
 */
class PersonaRequestTest {

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

    private fun api(): PersonaApi = HttpPersonaApi(testApiClient(server, refreshApi))

    private fun okOptions() = MockResponse(
        code = 200,
        body = """{"success":true,"region":"us","engines":[],"languages":{"deepgram":[],
                   "general":[]},"voices":[],"voiceStyles":[],"defaults":{"voiceByEngine":{},
                   "voiceByDeepgramLanguage":{},"responseLength":"concise","temperature":0.7}}""",
    )

    private fun okToken() = MockResponse(
        code = 200,
        body = """{"success":true,"token":"jwt","url":"wss://x","roomName":"preview_ws-1_abc",
                   "e2ee":{"key":"k"}}""",
    )

    @Test
    fun `the options read is a GET on the persona route's child path`() = runTest {
        server.enqueue(okOptions())

        api().personaOptions("ws-7")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        // ⛔ A CHILD OF `workspace/persona`, NOT A SIBLING. `workspace/persona-options` does not
        // exist and would 404.
        assertEquals("/api/district/workspace/persona/options", recorded.url.encodedPath)
        assertEquals(setOf("workspaceId"), recorded.url.queryParameterNames)
        assertEquals("ws-7", recorded.url.queryParameter("workspaceId"))
    }

    @Test
    fun `the preview mint is a POST and its form nests under formData`() = runTest {
        server.enqueue(okToken())

        api().personaPreviewToken(
            workspaceId = "ws-7",
            form = PersonaPreviewForm(name = "Ada", modelId = "deepgram-pipeline"),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/workspace/persona/preview-token", recorded.url.encodedPath)
        // ⚠️ THE WORKSPACE TRAVELS IN THE BODY, NOT THE QUERY. The route reads `req.json()`.
        assertTrue(recorded.url.queryParameterNames.isEmpty())

        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("ws-7", body["workspaceId"]?.jsonPrimitive?.content)
        val form = body["formData"] as JsonObject
        assertEquals("Ada", form["name"]?.jsonPrimitive?.content)
        assertEquals("deepgram-pipeline", form["modelId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an untouched preview field is ABSENT rather than an explicit null`() = runTest {
        // ⛔ THE SANITISER READS EACH KEY IT KNOWS AND LEAVES THE AGENT ON ITS OWN FALLBACK FOR THE
        // REST. A null would be dropped server-side anyway, so sending one would be a larger body
        // saying the same thing — and the house rule on this surface is that nil means "not on the
        // wire". Asserting the ABSENCE is the only way that rule is checkable.
        server.enqueue(okToken())

        api().personaPreviewToken(workspaceId = "ws-7", form = PersonaPreviewForm(name = "Ada"))

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        val form = body["formData"] as JsonObject
        assertEquals(setOf("name"), form.keys)
        assertFalse("greeting must not be sent as null", "greeting" in form)
        assertFalse("temperature must not be sent as null", "temperature" in form)
        assertFalse("preemptiveTts must not be sent as null", "preemptiveTts" in form)
    }

    @Test
    fun `an explicit false for preemptive TTS survives the null-dropping encoder`() = runTest {
        // ⛔ `false` IS A VALUE AND `null` IS AN ABSENCE, and an encoder that lost the difference
        // would silently leave paid speculative synthesis on for a session somebody turned it off
        // for. The agent reads it as `bool(persona_data.get("preemptiveTts"))`.
        server.enqueue(okToken())

        api().personaPreviewToken(
            workspaceId = "ws-7",
            form = PersonaPreviewForm(preemptiveTts = false, temperature = 0.0),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        val form = body["formData"] as JsonObject
        assertEquals("false", form["preemptiveTts"]?.jsonPrimitive?.content)
        assertEquals("0.0", form["temperature"]?.jsonPrimitive?.content)
    }

    private fun fixture(name: String): String {
        val configured = System.getProperty("district.contracts.dir")
        assertTrue("district.contracts.dir is not set", !configured.isNullOrBlank())
        val file = File(configured!!, name)
        assertTrue("Missing contract fixture ${file.absolutePath}.", file.isFile)
        return file.readText()
    }

    @Test
    fun `the Voice Studio read is a GET on the persona route's child path, and the fixture decodes`() =
        runTest {
            server.enqueue(MockResponse(code = 200, body = fixture("district-voice-studio.json")))

            val result = api().personaVoiceStudio("ws-7")

            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            assertEquals("/api/district/workspace/persona/voice-studio", recorded.url.encodedPath)
            assertEquals(setOf("workspaceId"), recorded.url.queryParameterNames)
            assertEquals("ws-7", recorded.url.queryParameter("workspaceId"))
            assertTrue(result is ApiResult.Success)
            assertEquals("us", (result as ApiResult.Success).value.region)
        }

    @Test
    fun `a refused chain is a 400 whose code names it, and the mix travels inside the PATCH`() = runTest {
        // ⛔ `invalid_engine_mix` IS IN THE BODY, NOT A HEADER, and NOTHING was written: the screen
        // must keep the operator's edits and say the chain was refused.
        server.enqueue(
            MockResponse(
                code = 400,
                body = """{"success":false,"error":"That voice chain cannot be saved.",""" +
                    """"code":"invalid_engine_mix"}""",
            ),
        )
        val studio = Json { ignoreUnknownKeys = true }
            .parseToJsonElement(fixture("district-voice-studio.json")) as JsonObject
        val mix = Json.decodeFromJsonElement(
            EngineMix.serializer(),
            ((studio["current"] as JsonObject)["chain"] as JsonObject)["engineMix"]!!,
        )

        val result = HttpDistrictApi(testApiClient(server, refreshApi)).savePersona(
            PersonaPatchRequest(workspaceId = "ws-7", modelId = "custom-pipeline", engineMix = mix),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        assertEquals("custom-pipeline", body["modelId"]?.jsonPrimitive?.content)
        val sent = body["engineMix"] as JsonObject
        assertEquals("1", sent["v"]?.jsonPrimitive?.content)
        assertFalse("bilingual was not set and must not be sent", "bilingual" in body)
        assertTrue(result is ApiResult.HttpFailure)
        assertEquals(400, (result as ApiResult.HttpFailure).status)
        assertEquals("invalid_engine_mix", result.code)
    }
}
