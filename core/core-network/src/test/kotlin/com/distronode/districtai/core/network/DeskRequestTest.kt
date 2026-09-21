package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DeskBrandName
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.DeskTicketDraft
import com.distronode.districtai.core.model.DeskTicketStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * What the desk client puts on the wire.
 *
 * ⛔ THE THREE THINGS ONLY A TEST AT THIS LEVEL CAN SEE, each of which is a silent failure rather
 * than a loud one:
 * 1. **`workspaceId` is a QUERY parameter on all nine routes, the multipart upload included.** Put
 *    it in the body and `requireWorkspaceRole` gets null — the request is refused before the
 *    handler runs, while the body looks entirely correct.
 * 2. **The reply field is `message`, not `body`.** The adjacent SUPPORT desk's reply takes exactly
 *    `body`, and transposing them is a 400 on a screen whose whole job is to deliver a sentence.
 *    Worse, the server's reply route contains the word `body` twice and NEITHER is the request field, so a
 *    reader grepping it concludes the opposite of the truth.
 * 3. **`publicBrandName: null` must survive to the wire.** It is the one explicit null this client
 *    sends and it means "clear the column"; the shared body encoder drops nulls, so an ordinary DTO
 *    could not express it and the field would silently mean "leave it alone".
 */
class DeskRequestTest {

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

    private fun api(): DeskApi = HttpDeskApi(
        DistrictApiClient(
            baseUrl = server.url("/"),
            httpClient = OkHttpClient(),
            tokens = signedInCoordinator(refreshApi),
        ),
    )

    private fun ok(body: String) = MockResponse(code = 200, body = body)

    private fun okSettings() = ok(
        """{"success":true,"settings":{"enabled":true,"notifyCustomersByEmail":true,
           "publicBrandName":null,"publicLogoUrl":null}}""",
    )

    private fun okTicket() = ok(
        """{"success":true,"ticket":{"id":"t","reference":1,"displayReference":"T-1",
           "subject":"s","status":"open","source":"manual","contactId":null,"requesterName":null,
           "requesterEmail":null,"requesterPhone":null,"createdAt":"a","updatedAt":"b",
           "resolvedAt":null,"messageCount":1}}""",
    )

    // ── Paths and the query ──────────────────────────────────────────────────

    @Test
    fun `every read puts the workspace in the QUERY and never in a body`() = runTest {
        server.enqueue(okSettings())
        api().deskSettings("ws-1")

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/district/desk/settings", recorded.url.encodedPath)
        assertEquals(setOf("workspaceId"), recorded.url.queryParameterNames)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
    }

    @Test
    fun `the ticket queue drops a null status rather than sending it empty`() = runTest {
        server.enqueue(ok("""{"success":true,"tickets":[]}"""))
        api().deskTickets("ws-1", status = null)

        val url = server.takeRequest().url
        assertEquals("/api/district/desk/tickets", url.encodedPath)
        // ⛔ `status=` PRESENT-AND-EMPTY IS NOT IN THE ROUTE'S VOCABULARY. It would fall through to
        // "no filter" — the same answer by accident rather than by contract, which is the kind of
        // agreement that stops holding the moment the route adds validation.
        assertEquals(setOf("workspaceId"), url.queryParameterNames)
    }

    @Test
    fun `a status filter is sent as the enum's wire value`() = runTest {
        server.enqueue(ok("""{"success":true,"tickets":[]}"""))
        api().deskTickets("ws-1", status = DeskTicketStatus.WAITING)

        val url = server.takeRequest().url
        assertEquals(setOf("workspaceId", "status"), url.queryParameterNames)
        assertEquals("waiting", url.queryParameter("status"))
    }

    @Test
    fun `the ticket detail, reply and status paths are built from the ticket id`() = runTest {
        server.enqueue(okTicket())
        api().deskTicket("ws-1", "tkt_9")
        assertEquals("/api/district/desk/tickets/tkt_9", server.takeRequest().url.encodedPath)

        server.enqueue(ok("""{"success":true}"""))
        api().replyToDeskTicket("ws-1", "tkt_9", "Tuesday.")
        assertEquals("/api/district/desk/tickets/tkt_9/reply", server.takeRequest().url.encodedPath)

        server.enqueue(okTicket())
        api().setDeskTicketStatus("ws-1", "tkt_9", DeskTicketStatus.RESOLVED)
        assertEquals("/api/district/desk/tickets/tkt_9/status", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `the status route is a POST, not a PATCH, and sends only status`() = runTest {
        server.enqueue(okTicket())
        api().setDeskTicketStatus("ws-1", "tkt_9", DeskTicketStatus.RESOLVED)

        val recorded = server.takeRequest()
        // ⚠️ THE SETTINGS ROUTE IS A PATCH AND THIS ONE IS A POST. The server's asymmetry, mirrored
        // rather than smoothed over; the other verb is a 405.
        assertEquals("POST", recorded.method)
        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        assertEquals(setOf("status"), body.keys)
        assertEquals(JsonPrimitive("resolved"), body["status"])
    }

    @Test
    fun `the logo DELETE carries a query and no body`() = runTest {
        server.enqueue(
            ok(
                """{"success":true,"settings":{"enabled":true,"notifyCustomersByEmail":true,
                   "publicBrandName":null,"publicLogoUrl":null},"objectRemoved":true}""",
            ),
        )
        api().deleteDeskLogo("ws-1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/district/desk/logo", recorded.url.encodedPath)
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))
        assertTrue("a DELETE here must carry no body", recorded.body?.utf8().isNullOrEmpty())
    }

    // ── The multipart upload, which is where the workspace is easiest to lose ─

    @Test
    fun `the logo upload puts the workspace in the QUERY and sends no form fields`() = runTest {
        server.enqueue(okSettings())
        api().uploadDeskLogo("ws-1", "logo.png", "image/png", byteArrayOf(1, 2, 3))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/district/desk/logo", recorded.url.encodedPath)
        // ⛔ THE ONE WAY THIS DIFFERS FROM `uploadMedia`, AND THE ONLY WAY TO GET IT WRONG. That
        // route reads `workspaceId` off `req.formData()`; this one reads it off the URL. A part
        // list copied from there leaves `requireWorkspaceRole` with null.
        assertEquals("ws-1", recorded.url.queryParameter("workspaceId"))

        val raw = recorded.body!!.utf8()
        assertTrue("the file part must be named `file`", raw.contains("name=\"file\""))
        assertTrue("the part must carry a filename", raw.contains("filename=\"logo.png\""))
        assertFalse(
            "the multipart body must carry NO workspaceId field",
            raw.contains("name=\"workspaceId\""),
        )
    }

    // ── The bodies ───────────────────────────────────────────────────────────

    @Test
    fun `a reply sends message, NOT body`() = runTest {
        server.enqueue(ok("""{"success":true}"""))
        api().replyToDeskTicket("ws-1", "tkt_9", "We will come Tuesday.", "key-1")

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // ⛔ THE ASSERTION THIS FILE EXISTS FOR. `body` here is a 400 with "A message is required",
        // and the route file itself reads like evidence for the wrong answer — see the class ⛔.
        assertEquals(setOf("message", "idempotencyKey"), body.keys)
        assertEquals(JsonPrimitive("We will come Tuesday."), body["message"])
        assertFalse("the desk's reply field is never `body`", "body" in body.keys)
    }

    @Test
    fun `a reply with no idempotency key omits it rather than sending null`() = runTest {
        server.enqueue(ok("""{"success":true}"""))
        api().replyToDeskTicket("ws-1", "tkt_9", "Tuesday.", idempotencyKey = null)

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // ⚠️ The schema is `z.string().uuid().optional()`, so an explicit null fails it — absent is
        // the only legal way to forgo the protection.
        assertEquals(setOf("message"), body.keys)
    }

    @Test
    fun `a create drops every blank optional rather than sending an empty string`() = runTest {
        server.enqueue(ok("""{"success":true,"deduplicated":true}"""))
        api().createDeskTicket(
            workspaceId = "ws-1",
            draft = DeskTicketDraft(
                subject = "Leaking tap",
                message = "It drips.",
                requesterPhone = "+14165550142",
            ),
            idempotencyKey = "key-1",
        )

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        // ⛔ `requesterEmail: ""` FAILS THE ROUTE'S `.email()` AND TAKES THE WHOLE OBJECT DOWN, and
        // the 400 it answers names the subject and the description — two fields that were both
        // filled in. So a phone-only ticket must send neither name nor email at all.
        assertEquals(
            setOf("subject", "message", "requesterPhone", "idempotencyKey"),
            body.keys,
        )
        assertFalse("requesterName" in body.keys)
        assertFalse("requesterEmail" in body.keys)
        assertFalse("contactId" in body.keys)
    }

    @Test
    fun `a settings patch sends only the fields it was given`() = runTest {
        server.enqueue(okSettings())
        api().saveDeskSettings("ws-1", DeskSettingsPatch(enabled = true))

        val recorded = server.takeRequest()
        assertEquals("PATCH", recorded.method)
        val body = Json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        // ⛔ AN OMITTED KEY IS PRESERVED SERVER-SIDE. A client that posted its whole form would
        // become the writer of values it may have read before another tab changed them.
        assertEquals(setOf("enabled"), body.keys)
    }

    @Test
    fun `clearing the brand name sends an EXPLICIT null, which is the one this client ever sends`() =
        runTest {
            server.enqueue(okSettings())
            api().saveDeskSettings(
                "ws-1",
                DeskSettingsPatch(publicBrandName = DeskBrandName.Clear),
            )

            val raw = server.takeRequest().body!!.utf8()
            val body = Json.parseToJsonElement(raw) as JsonObject
            // ⛔ PRESENT AND NULL, NOT ABSENT. The route reads absent as "leave it alone" and null
            // as "clear it, and fall back to the workspace name" — two different outcomes for the
            // one call site whose whole purpose is the second.
            assertEquals(setOf("publicBrandName"), body.keys)
            assertEquals(JsonNull, body["publicBrandName"])
            assertTrue("the null must reach the wire literally", raw.contains("null"))
        }

    @Test
    fun `setting a brand name sends the string`() = runTest {
        server.enqueue(okSettings())
        api().saveDeskSettings(
            "ws-1",
            DeskSettingsPatch(publicBrandName = DeskBrandName.Set("Ada Plumbing")),
        )

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
        assertEquals(JsonPrimitive("Ada Plumbing"), body["publicBrandName"])
    }

    @Test
    fun `an empty patch still reaches the wire as an empty object, which the route refuses`() =
        runTest {
            // ⚠️ THE CLIENT DOES NOT GUARD THIS; `DeskRepository` does, and it declines to call at
            // all. Pinned here so the division of labour is visible: if the repository's guard were
            // ever removed, this is what would be sent and the route would answer 400 rather than
            // a silent 200.
            server.enqueue(MockResponse(code = 400, body = """{"error":"At least one setting"}"""))
            val result = api().saveDeskSettings("ws-1", DeskSettingsPatch())

            val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()) as JsonObject
            assertTrue(body.keys.isEmpty())
            assertTrue(result is ApiResult.HttpFailure)
        }

    @Test
    fun `a 404 on a foreign ticket id stays a NotFound and is not reinterpreted`() = runTest {
        // ⚠️ THE ROUTE ANSWERS 404 RATHER THAN 403 ON PURPOSE: a 403 would confirm the id exists
        // somewhere on the platform. The client must carry that through unchanged.
        server.enqueue(MockResponse(code = 404, body = """{"error":"Ticket not found."}"""))
        val result = api().deskTicket("ws-1", "someone-elses")

        assertTrue(result is ApiResult.NotFound)
        assertEquals("Ticket not found.", (result as ApiResult.NotFound).message)
    }

    @Test
    fun `a 429 carries the server's own sentence through`() = runTest {
        server.enqueue(
            MockResponse(
                code = 429,
                body = """{"success":false,"error":"Too many replies in the last hour."}""",
            ),
        )
        val result = api().replyToDeskTicket("ws-1", "tkt_9", "hi")

        assertTrue(result is ApiResult.RateLimited)
        assertEquals("Too many replies in the last hour.", (result as ApiResult.RateLimited).message)
    }

    @Test
    fun `a 415 from the logo route keeps its status, because this client cannot precompute it`() =
        runTest {
            // ⚠️ FIVE DIFFERENT REFUSALS WITH FIVE DIFFERENT MEANINGS (413, 415, 400, 502, 503) and
            // the allowlist is not published to this client. An SVG lands on 415 because the server
            // sniffs the BYTES rather than trusting the header, which no local check could do.
            server.enqueue(
                MockResponse(code = 415, body = """{"success":false,"error":"We cannot host SVG."}"""),
            )
            val result = api().uploadDeskLogo("ws-1", "x.svg", "image/svg+xml", byteArrayOf(1))

            assertTrue(result is ApiResult.HttpFailure)
            assertEquals(415, (result as ApiResult.HttpFailure).status)
            assertEquals("We cannot host SVG.", result.message)
        }

    @Test
    fun `the desk carries the session bearer`() = runTest {
        // ⚠️ The desk is workspace-scoped: every call rides the session bearer the coordinator
        // hands out, and with it the single refresh-and-retry that every call here should keep.
        server.enqueue(okSettings())
        api().deskSettings("ws-1")

        val auth = server.takeRequest().headers["Authorization"]
        assertTrue("a bearer must be attached", auth?.startsWith("Bearer ") == true)
    }
}
