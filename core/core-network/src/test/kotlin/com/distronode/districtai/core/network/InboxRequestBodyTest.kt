package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.MarkReadRequest
import com.distronode.districtai.core.model.SendMessageRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the Inbox's two WRITE endpoints actually put on the wire, and what the thread READ puts in
 * its query string.
 *
 * ⛔ WHY THIS EXISTS SEPARATELY FROM THE CONTRACT FIXTURES. Those pin what the server SENDS. Nothing
 * pinned what this client sends, and the request bodies are where a client can be wrong on its own:
 * both of these routes read their fields off a plain `await req.json()` with no zod schema, so a
 * renamed or explicitly-null field is not rejected — it is silently absent, and the server answers
 * 400 "Missing required parameters" or, worse, does the wrong thing quietly.
 *
 * ⛔ AND BOTH REQUEST TYPES HAD ZERO COVERAGE. `SendMessageRequest` and `MarkReadRequest` were
 * written, wired up and never once serialised by a test.
 *
 * ⚠️ Driven over real HTTP through MockWebServer, like [CallsFeedTest], rather than by calling the
 * serializer directly. The setting that decides the shape (`explicitNulls = false`) lives on a
 * PRIVATE Json instance inside HttpDistrictApi.kt, so encoding a DTO here with a locally-built Json
 * would test a different configuration than the one that ships — which is exactly the class of
 * mistake that comment warns about.
 */
class InboxRequestBodyTest {

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

    private fun api(): InboxApi = HttpInboxApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    /** The body of the one request the server received, parsed. */
    private fun recordedBody(): JsonObject {
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        val raw = recorded.body?.utf8().orEmpty()
        assertTrue("the request must carry a body", raw.isNotBlank())
        return Json.parseToJsonElement(raw) as JsonObject
    }

    private fun okSend() = MockResponse(
        code = 200,
        body = """{"success":true,"message":{"id":"m1","from":"+1","to":"+2","body":"b",
                   "status":"queued","direction":"outbound","type":"sms","provider":"twilio",
                   "workspaceId":"ws-1","createdAt":"2026-08-15T14:30:00.000Z"}}""",
    )

    @Test
    fun `an SMS send omits subject entirely rather than sending null`() = runTest {
        server.enqueue(okSend())

        api().sendMessage(
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "+14165550142",
                body = "Confirmed for Thursday at 2pm.",
                channel = "sms",
            ),
        )

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "to", "body", "channel"), body.keys)
        // ⛔ ABSENT, NOT NULL. `explicitNulls = false` is what makes this true, and it is load-bearing
        // for more than tidiness: the send route treats subject as
        // `(typeof subject === "string" && subject.trim()) || "Message from District"`, so a null is
        // survivable there — but the same setting governs every other request body this client
        // builds, and a route that validates presence rather than type would reject one.
        assertFalse("an SMS must not carry a subject key at all", "subject" in body.keys)
    }

    @Test
    fun `an email send carries its subject and its channel`() = runTest {
        server.enqueue(okSend())

        api().sendMessage(
            SendMessageRequest(
                workspaceId = "ws-1",
                to = "ada@contract.test",
                body = "Friday works.",
                channel = "email",
                subject = "Re: Thursday appointment",
            ),
        )

        val body = recordedBody()
        assertEquals("email", body["channel"]?.asString())
        assertEquals("Re: Thursday appointment", body["subject"]?.asString())
        // ⚠️ The channel is ALWAYS sent explicitly even though the server defaults it to "sms". A
        // thread carrying both channels has no obvious default, so the operator's choice must not be
        // decided by a server default they cannot see.
        assertTrue("channel must be explicit", "channel" in body.keys)
    }

    @Test
    fun `mark-read by contact sends only the contact id`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"marked":3}"""))

        api().markRead(MarkReadRequest(workspaceId = "ws-1", contactId = "contact_1"))

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "contactId"), body.keys)
        assertEquals("contact_1", body["contactId"]?.asString())
    }

    @Test
    fun `mark-read by address sends only the counterpart`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"marked":1}"""))

        api().markRead(MarkReadRequest(workspaceId = "ws-1", counterpart = "+14165550168"))

        val body = recordedBody()
        assertEquals(setOf("workspaceId", "counterpart"), body.keys)
        // ⛔ AND CRITICALLY NO `all` KEY. The route accepts `all: true` to clear the ENTIRE
        // workspace, and its selector logic falls through to an unscoped update when nothing else
        // resolves. This client has no reason to ever send it, so the DTO does not model it — this
        // assertion is what keeps that true if someone adds a field.
        assertFalse("a client must never be able to clear the whole workspace", "all" in body.keys)
    }

    // ── The thread read's QUERY, which is the other half of "what this client sends" ──────────

    /**
     * ⛔ THE ASSERTION THAT NOTHING ELSE CAN MAKE. Paging added two optional query parameters to a
     * route every thread open already used, and the client's query builder drops a null entry
     * rather than sending it empty. If that ever changed — a `?: ""`, an `addQueryParameter` on the
     * raw map — an opening read would send `before=`, `new Date("")` would be an Invalid Date, and
     * the route would answer 400 for EVERY thread rather than only for paging. Nothing in the DTOs
     * or the repository can see the URL; only this can.
     */
    @Test
    fun `a thread read with no cursor sends exactly the parameters it always did`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"timeline":[]}"""))

        api().timeline(workspaceId = "ws-1", contactId = "c1", address = null, before = null, beforeId = null)

        val url = server.takeRequest().url
        assertEquals("/api/district/timeline", url.encodedPath)
        assertEquals(setOf("workspaceId", "contactId"), url.queryParameterNames)
        assertEquals("ws-1", url.queryParameter("workspaceId"))
        assertEquals("c1", url.queryParameter("contactId"))
    }

    @Test
    fun `a thread read with a cursor sends before and beforeId`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"timeline":[]}"""))

        api().timeline(
            workspaceId = "ws-1",
            contactId = null,
            address = "+14165550142",
            before = "2026-08-15T12:11:00.000Z",
            beforeId = "msg_page_049",
        )

        val url = server.takeRequest().url
        // ⚠️ `phoneNumber`, not `address`: the server's historical name for the selector, and the
        // only one guaranteed present in every deployed version.
        assertEquals(
            setOf("workspaceId", "phoneNumber", "before", "beforeId"),
            url.queryParameterNames,
        )
        assertEquals("2026-08-15T12:11:00.000Z", url.queryParameter("before"))
        assertEquals("msg_page_049", url.queryParameter("beforeId"))
    }

    @Test
    fun `a cursor with no tiebreak sends before alone, not an empty beforeId`() = runTest {
        // ⛔ AN EMPTY `beforeId` IS NOT AN ABSENT ONE. The route's guard is `if (beforeIdParam &&
        // !beforeParam)`, so an empty string is falsy and would survive here — but it then reaches
        // the query as `id: { lt: "" }`, which matches nothing and silently returns an empty page
        // that reads as the end of the thread.
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"timeline":[]}"""))

        api().timeline(
            workspaceId = "ws-1",
            contactId = "c1",
            address = null,
            before = "2026-08-15T12:11:00.000Z",
            beforeId = null,
        )

        val url = server.takeRequest().url
        assertEquals(setOf("workspaceId", "contactId", "before"), url.queryParameterNames)
        assertFalse("beforeId must be absent, not empty", "beforeId" in url.queryParameterNames)
    }

    @Test
    fun `both requests post to the routes the server actually exports`() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"success":true,"marked":0}"""))
        api().markRead(MarkReadRequest(workspaceId = "ws-1", contactId = "c1"))
        assertEquals("/api/district/messages/mark-read", server.takeRequest().url.encodedPath)

        server.enqueue(okSend())
        api().sendMessage(
            SendMessageRequest(workspaceId = "ws-1", to = "+1", body = "b", channel = "sms"),
        )
        assertEquals("/api/district/messages/send", server.takeRequest().url.encodedPath)
    }

    private fun kotlinx.serialization.json.JsonElement.asString(): String =
        (this as kotlinx.serialization.json.JsonPrimitive).content
}
