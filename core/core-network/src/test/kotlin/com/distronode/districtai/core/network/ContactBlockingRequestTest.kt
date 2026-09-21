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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The contact-block write and the blocked-set read, over real HTTP.
 *
 * ⛔ THE TWO PATHS ARE ONE LETTER APART (`block` / `blocked`) AND TAKE DIFFERENT VERBS, so each test
 * pins the verb and the path together. ⛔ AND AN UNBLOCK SENDS `blocked: false`, the value a
 * null-dropping encoder is most likely to lose, so its presence is asserted rather than assumed.
 */
class ContactBlockingRequestTest {

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

    private fun api(): ContactBlockingApi = HttpContactBlockingApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    @Test
    fun `a block by contact id posts the state and drops the absent number`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"contactId":"c1","name":"Ada","phoneNumber":"+14165550142",
                           "blockedAt":"2026-09-16T12:00:00.000Z"}""",
            ),
        )

        val result = api().setContactBlocked("ws-1", contactId = "c1", phoneNumber = null, blocked = true)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/contacts/block", recorded.url.encodedPath)
        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals(setOf("workspaceId", "contactId", "blocked"), body.keys)
        assertEquals("c1", body.getValue("contactId").jsonPrimitive.content)
        assertEquals("true", body.getValue("blocked").jsonPrimitive.content)

        assertTrue(result is ApiResult.Success)
        assertEquals("2026-09-16T12:00:00.000Z", (result as ApiResult.Success).value.blockedAt)
    }

    @Test
    fun `an unblock by number sends blocked false and reads a null blockedAt`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"contactId":"c2","name":"+14165550159",
                           "phoneNumber":"+14165550159","blockedAt":null}""",
            ),
        )

        val result = api().setContactBlocked("ws-1", contactId = null, phoneNumber = "+14165550159", blocked = false)

        val body = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject
        assertEquals(setOf("workspaceId", "phoneNumber", "blocked"), body.keys)
        assertEquals("false", body.getValue("blocked").jsonPrimitive.content)
        assertNull((result as ApiResult.Success).value.blockedAt)
    }

    @Test
    fun `the blocked set is a GET on its own path, and an empty list decodes`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"blocked":[]}"""))

        val result = api().blockedContacts("ws-3")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/contacts/blocked", recorded.url.encodedPath)
        assertEquals("ws-3", recorded.url.queryParameter("workspaceId"))
        assertTrue((result as ApiResult.Success).value.blocked.isEmpty())
    }

    @Test
    fun `a blocked row with no number decodes`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"blocked":[{"contactId":"c1","name":"Ada",
                           "phoneNumber":null,"blockedAt":"2026-09-16T12:00:00.000Z"}]}""",
            ),
        )

        val row = (api().blockedContacts("ws-3") as ApiResult.Success).value.blocked.single()

        assertEquals("c1", row.contactId)
        assertNull(row.phoneNumber)
        assertFalse(row.blockedAt.isNullOrEmpty())
    }
}
