package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.model.SetupTestCallResponse
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the setup wizard's routes receive, and what [HttpSetupWizardApi] makes of the answers.
 *
 * ⛔ THE FACTS PATCH IS THE ONE BODY ON THIS SURFACE WHERE A NULL MUST REACH THE WIRE: a section
 * sent as `null` clears it, and a section left out is left alone. Both halves are pinned here.
 */
class SetupWizardRequestTest {

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

    private fun api(): SetupWizardApi = HttpSetupWizardApi(testApiClient(server))

    private fun fixture(name: String): String {
        val configured = System.getProperty("district.contracts.dir")
        assertTrue("System property district.contracts.dir is not set.", !configured.isNullOrBlank())
        return File(configured!!, name).readText()
    }

    private fun answer(name: String, code: Int = 200) = server.enqueue(MockResponse(code = code, body = fixture(name)))

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body?.utf8().orEmpty()) as JsonObject

    private fun <T> decoded(name: String, serializer: kotlinx.serialization.KSerializer<T>): T =
        DistrictApiClient.DEFAULT_JSON.decodeFromString(serializer, fixture(name))

    @Test
    fun `the wizard reads the same setup state the overview's card does`() = runTest {
        answer("district-setup.json")

        val result = api().districtSetup("ws-1")

        assertEquals(ApiResult.Success(decoded("district-setup.json", DistrictSetupResponse.serializer())), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/setup", recorded.url.encodedPath)
    }

    @Test
    fun `a step save is a PATCH carrying only its step state`() = runTest {
        answer("district-setup-patch.json")

        val result = api().districtSetupPatch("ws-1", steps = mapOf("business" to "skipped"), businessFacts = null)

        val expected = decoded("district-setup-patch.json", DistrictSetupResponse.serializer())
        assertEquals(ApiResult.Success(expected), result)
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/setup", recorded.url.encodedPath)
        val body = recorded.json()
        assertEquals(setOf("workspaceId", "setupProgress"), body.keys)
        assertEquals("ws-1", body["workspaceId"]!!.jsonPrimitive.content)
        val steps = body["setupProgress"]!!.jsonObject["steps"]!!.jsonObject
        assertEquals(mapOf("business" to "skipped"), steps.mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `a facts save sends no progress, and keeps an explicit null as a clear`() = runTest {
        answer("district-setup-patch.json")
        val facts = buildJsonObject {
            put("timezone", "America/Toronto")
            put("services", JsonNull)
        }

        api().districtSetupPatch("ws-1", steps = emptyMap(), businessFacts = facts)

        val body = server.takeRequest().json()
        assertEquals(setOf("workspaceId", "businessFacts"), body.keys)
        val sent = body["businessFacts"]!!.jsonObject
        assertEquals(JsonNull, sent["services"])
        assertFalse("an absent section stays absent", "faqs" in sent)
    }

    @Test
    fun `the lookup posts the page and a locale the route knows`() = runTest {
        answer("district-setup-lookup.json")
        answer("district-setup-lookup.json")

        val result = api().setupLookup("ws-1", websiteUrl = "example.com", locale = "fr")
        api().setupLookup("ws-1", websiteUrl = "example.com", locale = "de")

        assertEquals(ApiResult.Success(decoded("district-setup-lookup.json", SetupLookupResponse.serializer())), result)
        val french = server.takeRequest()
        assertEquals("POST", french.method)
        assertEquals("/api/district/setup/lookup", french.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "locale" to "fr", "websiteUrl" to "example.com"),
            french.json().mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals("en", server.takeRequest().json()["locale"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unreadable website keeps its code`() = runTest {
        answer("district-setup-lookup-unreadable.json", code = 422)

        val result = api().setupLookup("ws-1", websiteUrl = "nowhere.invalid", locale = "en")

        assertEquals("website_unreadable", (result as ApiResult.HttpFailure).code)
        assertEquals(422, result.status)
    }

    @Test
    fun `the test call posts only the phone`() = runTest {
        answer("district-setup-test-call.json")

        val result = api().setupTestCall("ws-1", phone = "+14165550123")

        val expected = decoded("district-setup-test-call.json", SetupTestCallResponse.serializer())
        assertEquals(ApiResult.Success(expected), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/setup/test-call", recorded.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to JsonPrimitive("ws-1"), "phone" to JsonPrimitive("+14165550123")),
            recorded.json(),
        )
    }

    @Test
    fun `the forwarding check is read by query and started by body, on one path`() = runTest {
        answer("district-forwarding-check.json")
        answer("district-forwarding-check-start.json", code = 202)

        val read = api().forwardingCheck("ws-1")
        val started = api().forwardingCheckStart("ws-1", businessLine = "+14165550123", mode = "unanswered")

        val expected = decoded("district-forwarding-check.json", ForwardingCheckResponse.serializer())
        assertEquals(ApiResult.Success(expected), read)
        assertEquals(
            ApiResult.Success(decoded("district-forwarding-check-start.json", ForwardingCheckResponse.serializer())),
            started,
        )
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/api/district/setup/forwarding-check", get.url.encodedPath)
        assertEquals("ws-1", get.url.queryParameter("workspaceId"))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/district/setup/forwarding-check", post.url.encodedPath)
        assertEquals(
            mapOf("workspaceId" to "ws-1", "businessLine" to "+14165550123", "mode" to "unanswered"),
            post.json().mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun `the integrations read is a GET by query`() = runTest {
        answer("district-connections.json")

        val result = api().workspaceConnections("ws-1")

        assertEquals(ApiResult.Success(decoded("district-connections.json", ConnectionsResponse.serializer())), result)
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/connections", recorded.url.encodedPath)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
    }
}
