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
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The wire body of `PATCH /api/district/contacts/update`.
 *
 * ⛔ THE ROUTE IS A WHOLESALE REPLACE, SO THE BODY'S KEY SET IS THE CONTRACT. Every column it writes
 * is cleared when its key is absent, and the two whose wire name differs from the column
 * (`linkedin` for `socialHandles.linkedin`, `contextSummary` for `latestContextSummary`) are silently
 * ignored under any other spelling. Nothing server-side pins a REQUEST body, so this does.
 *
 * ⚠️ Driven over real HTTP through MockWebServer for the reason `HqRequestBodyTest` states: the
 * `explicitNulls = false` encoder that decides these shapes is private to HttpDistrictApi.kt.
 */
class ContactUpdateRequestTest {

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

    private fun api(): DistrictApi = HttpDistrictApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    private fun recordedBody(): JsonObject {
        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        assertEquals("/api/district/contacts/update", recorded.url.encodedPath)
        return Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
    }

    @Test
    fun `every populated column reaches the wire under the route's own key names`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true}"""))

        api().updateContact(
            UpdateContactRequest(
                workspaceId = "ws-1",
                contactId = "c1",
                name = "Ada L",
                phoneNumber = "+14165550142",
                email = "ada@contract.test",
                linkedin = "in/ada",
                contextSummary = "Asked about Thursday.",
                budget = "5000",
                timeline = "Q4",
                website = "https://ada.test",
            ),
        )

        val body = recordedBody()
        assertEquals(
            mapOf(
                "workspaceId" to "ws-1",
                "contactId" to "c1",
                "name" to "Ada L",
                "phoneNumber" to "+14165550142",
                "email" to "ada@contract.test",
                "linkedin" to "in/ada",
                "contextSummary" to "Asked about Thursday.",
                "budget" to "5000",
                "timeline" to "Q4",
                "website" to "https://ada.test",
            ),
            body.mapValues { (it.value as JsonPrimitive).content },
        )
    }

    @Test
    fun `a null column is omitted, which the route treats exactly like a null`() = runTest {
        // ⚠️ `explicitNulls = false` drops the key. Harmless on this route, where an absent key and
        // a null are written the same way, and pinned so the difference is a decision rather than
        // an accident if either side ever changes.
        server.enqueue(MockResponse(code = 200, body = """{"success":true}"""))

        api().updateContact(
            UpdateContactRequest(workspaceId = "ws-1", contactId = "c1", name = "Ada", phoneNumber = "+14165550142"),
        )

        val body = recordedBody()
        assertFalse(body.containsKey("email"))
        assertFalse(body.containsKey("linkedin"))
        assertEquals("+14165550142", (body["phoneNumber"] as JsonPrimitive).content)
    }
}
