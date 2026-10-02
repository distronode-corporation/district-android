package com.distronode.districtai.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The three endpoints that hang off their own interfaces: message search, the push resolver, and
 * the server-side hang-up.
 *
 * ⛔ THE HANG-UP IS THE ONE WORTH A TEST OF ITS OWN. `calls/{id}/hangup` is one path segment from
 * `calls/{id}/answer` and two from `calls/dial`, and the three do opposite things — end a call,
 * take one, and place one. A path built wrongly would not fail loudly: `calls/dial` answers 200
 * and PLACES A SECOND CALL.
 *
 * ⛔ AND THE MESSAGE ID IS A PATH SEGMENT ARRIVING FROM A PUSH, i.e. from the least trustworthy
 * input in the app. The percent-encoding assertion is what stops a hostile value climbing out of
 * the `messages/` family.
 */
class InboxExtrasRequestTest {

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

    private fun client() = testApiClient(server, refreshApi)

    private fun inbox(): InboxExtrasApi = HttpInboxExtrasApi(client())

    private fun calls(): CallControlApi = HttpCallControlApi(client())

    @Test
    fun `search sends the query as q, alongside the workspace`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"results":[],"limit":30}"""))

        inbox().searchMessages("ws-2", "refund policy")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/messages/search", recorded.url.encodedPath)
        assertEquals(setOf("workspaceId", "q"), recorded.url.queryParameterNames)
        assertEquals("ws-2", recorded.url.queryParameter("workspaceId"))
        // ⚠️ SENT AS TYPED. The server trims and then measures its own floor; trimming here as well
        // would be a second opinion about the same rule.
        assertEquals("refund policy", recorded.url.queryParameter("q"))
    }

    @Test
    fun `a short-query answer with no limit key still decodes`() = runTest {
        // ⛔ THE BRANCH A STRICT DECODER FAILS ON. A `q` shorter than two characters answers
        // `{success, results: []}` with NO `limit` key at all, so a non-nullable Int there would be
        // a decode failure waiting for the first person who types one letter.
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"results":[]}"""))

        val result = inbox().searchMessages("ws-2", "a")

        assertTrue(result is ApiResult.Success)
        assertEquals(null, (result as ApiResult.Success).value.limit)
    }

    @Test
    fun `the thread resolver puts the id in the path and the workspace in the query`() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"message":{"id":"m1","direction":"inbound","type":"sms",
                           "readAt":null,"createdAt":"2026-08-15T14:30:00.000Z"},
                           "thread":{"threadKey":"contact:c1","contactId":"c1",
                           "counterpart":"+14165550142","channel":"sms"}}""",
            ),
        )

        inbox().messageThread("ws-2", "msg_abc")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/messages/msg_abc", recorded.url.encodedPath)
        // ⚠️ WORTH SENDING EVEN THOUGH THE ROUTE WOULD FALL BACK: omitted, the guard picks the
        // caller's active workspace, which on a multi-tenant account is a different tenant from
        // the one the push named — and the answer would then 404 for a message that exists.
        assertEquals("ws-2", recorded.url.queryParameter("workspaceId"))
    }

    @Test
    fun `a hostile message id is percent-encoded into one segment`() = runTest {
        // ⛔ THE ID ARRIVES FROM A PUSH. Without encoding, `../dial` would address a route that
        // PLACES A CALL.
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"success":true,"message":{"id":"m1","direction":"inbound","type":null,
                           "readAt":null,"createdAt":"2026-08-15T14:30:00.000Z"},
                           "thread":{"threadKey":"addr:x","contactId":null,"counterpart":"x",
                           "channel":"sms"}}""",
            ),
        )

        inbox().messageThread("ws-2", "../dial")

        val path = server.takeRequest().url.encodedPath
        assertEquals("/api/district/messages/..%2Fdial", path)
    }

    @Test
    fun `the hang-up is a POST on the call's own hangup path, with the workspace in the body`() =
        runTest {
            server.enqueue(MockResponse(code = 200, body = """{"success":true,"ended":true}"""))

            calls().hangUpCall("ws-2", "CA123")

            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            // ⛔ NOT `calls/CA123/answer` AND NOT `calls/dial`. See the class doc.
            assertEquals("/api/district/calls/CA123/hangup", recorded.url.encodedPath)
            // ⛔ THE ROUTE PARSES `req.json()` WITH A ZOD SCHEMA. A query parameter would leave
            // workspaceId undefined and answer 400 while the URL looked perfectly correct.
            assertTrue(recorded.url.queryParameterNames.isEmpty())
            val body = Json.parseToJsonElement(recorded.body?.utf8().orEmpty()) as JsonObject
            assertEquals(setOf("workspaceId"), body.keys)
            assertEquals("ws-2", body["workspaceId"]?.jsonPrimitive?.content)
        }
}
