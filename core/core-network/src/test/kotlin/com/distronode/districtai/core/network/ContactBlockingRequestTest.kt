package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.ContactBlockResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * What blocking puts on the wire, and that the route's documented answers decode.
 *
 * ⚠️ THE BODIES BELOW ARE THE ROUTES' DOCUMENTED SHAPES (`contacts/block/route.ts` and
 * `contacts/blocked/route.ts`), not server-generated fixtures: the monorepo has none for these two
 * routes yet, which the hand-back reports.
 */
class ContactBlockingRequestTest {

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

    private fun api() = HttpContactBlockingApi(testApiClient(server, FakeRefreshApi().apply { rotating() }))

    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body?.utf8().orEmpty()) as JsonObject

    @Test
    fun `a block by contact posts the id and the state, and no number`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"contactId":"c1","name":"Ada","phoneNumber":"+14165550142",""" +
                    """"blockedAt":"2026-10-13T12:00:00.000Z"}""",
            ),
        )
        val result = api().setContactBlocked("ws_1", contactId = "c1", phoneNumber = null, blocked = true)

        assertEquals(
            ApiResult.Success(ContactBlockResponse(true, "c1", "Ada", "+14165550142", "2026-10-13T12:00:00.000Z")),
            result,
        )
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/contacts/block", recorded.url.encodedPath)
        val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
        assertEquals("ws_1", body["workspaceId"]?.jsonPrimitive?.content)
        assertEquals("c1", body["contactId"]?.jsonPrimitive?.content)
        assertEquals("true", body["blocked"]?.jsonPrimitive?.content)
        assertFalse("an absent identity is left out, not sent as null", body.containsKey("phoneNumber"))
    }

    @Test
    fun `an unblock by number still sends blocked false`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"contactId":"c9","name":"","phoneNumber":"+1","blockedAt":null}"""
            )
        )
        api().setContactBlocked("ws_1", contactId = null, phoneNumber = "+14165550142", blocked = false)

        val body = body()
        assertEquals("+14165550142", body["phoneNumber"]?.jsonPrimitive?.content)
        assertEquals("false", body["blocked"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("contactId"))
    }

    @Test
    fun `the blocked list is a GET with the workspace in the query`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"blocked":[{"contactId":"c1","name":"Ada","phoneNumber":null,""" +
                    """"blockedAt":"2026-10-13T12:00:00.000Z"}]}""",
            ),
        )
        val result = api().blockedContacts("ws_1")

        assertEquals(
            ApiResult.Success(
                BlockedContactsResponse(true, listOf(BlockedContact("c1", "Ada", null, "2026-10-13T12:00:00.000Z"))),
            ),
            result,
        )
        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/contacts/blocked", recorded.url.encodedPath)
        assertEquals("ws_1", recorded.url.queryParameter("workspaceId"))
    }
}
