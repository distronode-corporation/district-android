package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AccountSettingsSaveResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** `Accept-Language` on every request, and the account-language write. */
class LanguageSignalRequestTest {

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

    private fun fetch(
        client: okhttp3.OkHttpClient,
        request: Request = Request.Builder().url(server.url("/x")).build(),
    ) {
        server.enqueue(MockResponse(code = 200, body = "{}"))
        client.newCall(request).execute().close()
    }

    @Test
    fun `every request carries the language the app reads in at that moment`() {
        var tag = ACCEPT_LANGUAGE_FR_CA
        val client = DistrictHttp.client { tag }
        fetch(client)
        assertEquals("fr-CA", server.takeRequest().headers["Accept-Language"])
        // A per-app language change reaches the very next request.
        tag = ACCEPT_LANGUAGE_EN
        fetch(client)
        assertEquals("en", server.takeRequest().headers["Accept-Language"])
    }

    @Test
    fun `a client built without a language says English, and a request naming one keeps it`() {
        fetch(DistrictHttp.client())
        assertEquals("en", server.takeRequest().headers["Accept-Language"])
        fetch(
            DistrictHttp.client { ACCEPT_LANGUAGE_FR_CA },
            Request.Builder().url(server.url("/x")).header("Accept-Language", "de").build(),
        )
        assertEquals("de", server.takeRequest().headers["Accept-Language"])
    }

    @Test
    fun `the account language is posted as profile locale, fr or en, and the answer decodes`() = runTest {
        // The body of contracts/district-settings-save.json (monorepo 49019be).
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"message":"Settings saved successfully"}"""))
        val result = HttpAccountApi(testApiClient(server)).saveLocale(SETTINGS_LOCALE_FR)

        assertEquals(ApiResult.Success(AccountSettingsSaveResponse(true, "Settings saved successfully")), result)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/settings", recorded.url.encodedPath)
        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals(setOf("profile"), body.keys)
        val profile = body.getValue("profile").jsonObject
        assertEquals(setOf("locale"), profile.keys)
        assertEquals("fr", profile.getValue("locale").jsonPrimitive.content)
        assertEquals("en", SETTINGS_LOCALE_EN)
    }
}
