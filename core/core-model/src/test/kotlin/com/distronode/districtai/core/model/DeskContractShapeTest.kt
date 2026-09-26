package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk's wire shapes, for the branches no generated fixture carries.
 *
 * ⛔ THIS IS NOT A CONTRACT-FIXTURE TEST, AND IT IS NOT THE ONLY THING PINNING THE DESK EITHER.
 * The server's generator records nine `district-desk-*.json` fixtures from the real handlers, and
 * [DeskContractFixtureTest] decodes every one of them. What remains here is JSON transcribed by
 * hand from the server's desk route handlers and serializers, which is weaker evidence than a
 * generated fixture: it proves the DTOs match what a reader believed the server sends, not what it
 * sends.
 *
 * ⚠️ WHY IT IS KEPT: the generated fixtures are one body per route, and several branches the UI
 * depends on have no fixture at all (the deduplicated create with no `ticket` key, the degraded
 * reply replay with no `notified`, an unrecognised status). These cases are decoded with the SAME
 * strict decoder ([ContractFixtures.json], `ignoreUnknownKeys = false`), so they still pin field
 * NAMES and optionality for those branches. If a branch gains a generated fixture, move its case to
 * [DeskContractFixtureTest] rather than keeping two copies.
 */
class DeskContractShapeTest {

    private val json: Json = ContractFixtures.json

    // ── Settings ─────────────────────────────────────────────────────────────

    @Test
    fun `settings decode with both nullable columns explicitly null`() {
        // ⚠️ `publicBrandName` and `publicLogoUrl` are EXPLICIT null rather than absent, because the
        // route's Prisma `select` always returns all four columns.
        val response = json.decodeFromString<DeskSettingsResponse>(
            """{"success":true,"settings":{"enabled":true,"notifyCustomersByEmail":false,
               "publicBrandName":null,"publicLogoUrl":null}}""",
        )

        assertEquals(true, response.success)
        val settings = response.settings!!
        assertTrue(settings.enabled)
        assertFalse(settings.notifyCustomersByEmail)
        // ⛔ NULL MEANS "FALL BACK TO THE WORKSPACE NAME", not "unknown". A screen rendering it as a
        // blank brand line would show the tenant an empty space where their own name goes.
        assertNull(settings.publicBrandName)
        assertNull(settings.publicLogoUrl)
    }

    @Test
    fun `the logo DELETE carries objectRemoved and the POST does not`() {
        // ⛔ THE TAKEDOWN'S TWO HALVES. A 200 says the column was cleared (the image is off the
        // customers' page); `objectRemoved` says whether the stored file went too.
        val removal = json.decodeFromString<DeskLogoRemovalResponse>(
            """{"success":true,"settings":{"enabled":true,"notifyCustomersByEmail":true,
               "publicBrandName":"Ada Plumbing","publicLogoUrl":null},"objectRemoved":false}""",
        )
        assertFalse(removal.objectRemoved)
        assertNull(removal.settings!!.publicLogoUrl)

        // ⚠️ THE UPLOAD ANSWERS THE SETTINGS ENVELOPE, WHICH HAS NO `objectRemoved` KEY AT ALL —
        // two keys, not three. Decoding the upload as the removal type would be a strict-decoder
        // pass only because the field defaults, and would then report "the object was not removed"
        // about an upload.
        val upload = json.decodeFromString<DeskSettingsResponse>(
            """{"success":true,"settings":{"enabled":true,"notifyCustomersByEmail":true,
               "publicBrandName":null,"publicLogoUrl":"https://cdn.example/logo.png"}}""",
        )
        assertEquals("https://cdn.example/logo.png", upload.settings!!.publicLogoUrl)
    }

    // ── The queue ────────────────────────────────────────────────────────────

    @Test
    fun `a ticket summary decodes all fourteen keys, resolvedAt included as null`() {
        val response = json.decodeFromString<DeskTicketsResponse>(
            """{"success":true,"tickets":[{"id":"tkt_1","reference":42,
               "displayReference":"T-42","subject":"Leaking tap","status":"open",
               "source":"voice-call","contactId":null,"requesterName":"Ada Lovelace",
               "requesterEmail":null,"requesterPhone":"+14165550142",
               "createdAt":"2026-09-01T10:00:00.000Z","updatedAt":"2026-09-02T11:00:00.000Z",
               "resolvedAt":null,"messageCount":3}]}""",
        )

        val ticket = response.tickets.single()
        assertEquals("T-42", ticket.displayReference)
        assertEquals(DeskTicketStatus.OPEN, ticket.knownStatus)
        // ⚠️ The one derived fact this type offers about `source`.
        assertTrue(ticket.fromCall)
        assertNull(ticket.resolvedAt)
        assertEquals(3, ticket.messageCount)
    }

    @Test
    fun `an unrecognised status decodes and renders as itself rather than throwing`() {
        // ⛔ THE COLUMN IS PLAIN `TEXT`, chosen so adding a state never needs a migration on four
        // databases. A DTO that typed this as a sealed vocabulary would fail to read the queue on
        // an installed build the day a fourth state ships.
        val response = json.decodeFromString<DeskTicketsResponse>(
            """{"success":true,"tickets":[{"id":"t","reference":1,"displayReference":"T-1",
               "subject":"s","status":"escalated","source":"manual","contactId":null,
               "requesterName":null,"requesterEmail":null,"requesterPhone":null,
               "createdAt":"x","updatedAt":"y","resolvedAt":null,"messageCount":0}]}""",
        )

        val ticket = response.tickets.single()
        assertEquals("escalated", ticket.status)
        assertNull("an unknown status must not resolve to a known one", ticket.knownStatus)
        assertFalse(ticket.fromCall)
    }

    @Test
    fun `a ticket detail is the summary plus its thread`() {
        val response = json.decodeFromString<DeskTicketResponse>(
            """{"success":true,"ticket":{"id":"tkt_1","reference":7,"displayReference":"T-7",
               "subject":"Leaking tap","status":"waiting","source":"manual","contactId":"c_1",
               "requesterName":null,"requesterEmail":"ada@example.test","requesterPhone":null,
               "createdAt":"a","updatedAt":"b","resolvedAt":null,"messageCount":2,
               "messages":[{"id":"m1","authorType":"customer","body":"It drips.","createdAt":"a"},
               {"id":"m2","authorType":"team","body":"We will come Tuesday.","createdAt":"b"}]}}""",
        )

        val ticket = response.ticket!!
        assertEquals(2, ticket.messages.size)
        assertEquals(DeskMessageAuthor.CUSTOMER, ticket.messages[0].knownAuthor)
        assertEquals(DeskMessageAuthor.TEAM, ticket.messages[1].knownAuthor)
    }

    // ── The write shapes that omit keys rather than nulling them ─────────────

    @Test
    fun `a deduplicated create carries NO ticket key at all`() {
        // ⛔ ABSENT, NOT NULL. A non-nullable `ticket` would throw on the response to a submit the
        // operator will read as having worked.
        val raw = """{"success":true,"deduplicated":true}"""
        val response = json.decodeFromString<DeskTicketCreateResponse>(raw)

        assertTrue(response.success)
        assertNull(response.ticket)
        assertEquals(true, response.deduplicated)
        assertFalse("the fixture must keep omitting the key, or this proves nothing", "ticket" in raw)
    }

    @Test
    fun `a normal create carries a ticket and no deduplicated key`() {
        val response = json.decodeFromString<DeskTicketCreateResponse>(
            """{"success":true,"ticket":{"id":"t","reference":9,"displayReference":"T-9",
               "subject":"s","status":"open","source":"manual","contactId":null,
               "requesterName":null,"requesterEmail":null,"requesterPhone":null,
               "createdAt":"a","updatedAt":"b","resolvedAt":null,"messageCount":1}}""",
        )

        assertEquals("T-9", response.ticket!!.displayReference)
        // ⚠️ Absent rather than false, so the DTO cannot require it.
        assertNull(response.deduplicated)
    }

    @Test
    fun `a degraded reply replay carries neither ticket nor message nor notified`() {
        // ⛔ THE THIRD REPLY SHAPE. A replayed key whose cached payload has left Redis answers with
        // `deduplicated` alone — so `notified` is ABSENT, and a DTO defaulting it to false would
        // tell an operator the customer was not emailed when the truth is that we cannot say.
        val response = json.decodeFromString<DeskReplyResponse>(
            """{"success":true,"deduplicated":true}""",
        )

        assertTrue(response.success)
        assertNull(response.ticket)
        assertNull(response.message)
        assertNull("null is 'we do not know', never 'no'", response.notified)
    }

    @Test
    fun `a normal reply carries the echoed ticket, the message and notified`() {
        val response = json.decodeFromString<DeskReplyResponse>(
            """{"success":true,"ticket":{"id":"t","reference":9,"displayReference":"T-9",
               "subject":"s","status":"waiting","source":"manual","contactId":null,
               "requesterName":null,"requesterEmail":null,"requesterPhone":null,
               "createdAt":"a","updatedAt":"b","resolvedAt":null,"messageCount":2},
               "message":{"id":"m2","authorType":"team","body":"Tuesday.","createdAt":"b"},
               "notified":true}""",
        )

        // ⛔ THE STATUS COMES FROM THE ECHO. A reply auto-sets `waiting` UNLESS the ticket is
        // resolved, so a client that assumed `waiting` would be wrong for a resolved ticket.
        assertEquals(DeskTicketStatus.WAITING, response.ticket!!.knownStatus)
        assertEquals(true, response.notified)
        assertNull(response.deduplicated)
    }

    // ── The two derived behaviours the UI depends on ─────────────────────────

    @Test
    fun `adopting takes every field from the echo and recomputes the message count`() {
        val detail = DeskTicketDetail(
            id = "t",
            status = DESK_STATUS_OPEN,
            resolvedAt = null,
            messageCount = 1,
            messages = listOf(DeskMessage(id = "m1", authorType = "customer", body = "hi")),
        )
        val echoed = DeskTicketSummary(
            id = "t",
            status = DESK_STATUS_RESOLVED,
            // ⛔ THE SERVER STAMPS THIS WHEN A TICKET IS RESOLVED AND CLEARS IT OTHERWISE. Adopting
            // it is the only way a screen can be right about a ticket that was reopened.
            resolvedAt = "2026-09-03T09:00:00.000Z",
            messageCount = 99,
        )

        val appended = detail.adopting(
            echoed,
            appending = DeskMessage(id = "m2", authorType = "team", body = "ok"),
        )

        assertEquals(DeskTicketStatus.RESOLVED, appended.knownStatus)
        assertEquals("2026-09-03T09:00:00.000Z", appended.resolvedAt)
        assertEquals(listOf("m1", "m2"), appended.messages.map { it.id })
        // ⚠️ RECOMPUTED FROM THE THREAD IN HAND, not adopted: the server's 99 would disagree with
        // the two messages actually on screen.
        assertEquals(2, appended.messageCount)
    }

    @Test
    fun `an empty settings patch knows it is empty, because the route answers 400 for one`() {
        assertTrue(DeskSettingsPatch().isEmpty)
        assertFalse(DeskSettingsPatch(enabled = false).isEmpty)
        assertFalse(DeskSettingsPatch(notifyCustomersByEmail = false).isEmpty)
        // ⛔ `Clear` IS A CHANGE. It is the explicit-null escape hatch and means "clear the column",
        // which is emphatically not the same as sending nothing.
        assertFalse(DeskSettingsPatch(publicBrandName = DeskBrandName.Clear).isEmpty)
    }

    @Test
    fun `the status vocabulary is exactly the route's z-enum and is case folded on the way in`() {
        assertEquals(
            listOf(DESK_STATUS_OPEN, DESK_STATUS_WAITING, DESK_STATUS_RESOLVED),
            DeskTicketStatus.entries.map { it.wire },
        )
        assertEquals(DeskTicketStatus.RESOLVED, DeskTicketStatus.fromWire(" Resolved "))
        assertNull(DeskTicketStatus.fromWire("closed"))
        assertNull(DeskTicketStatus.fromWire(null))
    }

    @Test
    fun `the author vocabulary is the three the server writes`() {
        assertEquals(
            listOf("customer", "team", "assistant"),
            DeskMessageAuthor.entries.map { it.wire },
        )
        assertEquals(DeskMessageAuthor.TEAM, DeskMessageAuthor.fromWire("TEAM"))
        assertNull(DeskMessageAuthor.fromWire("operator"))
        assertNull(DeskMessageAuthor.fromWire(null))
    }

    @Test
    fun `an unknown key fails the strict decoder, which is what makes the rest of this file mean anything`() {
        // ⛔ THE GUARD ON THE GUARD. If `ignoreUnknownKeys` were ever relaxed, every assertion above
        // would keep passing while the app silently dropped a field the server had added.
        val thrown = runCatching {
            json.decodeFromString<DeskSettingsResponse>(
                """{"success":true,"settings":{"enabled":true,"notifyCustomersByEmail":true,
                   "publicBrandName":null,"publicLogoUrl":null,"publicThemeColour":"#fff"}}""",
            )
        }.exceptionOrNull()

        assertTrue("an unknown key must throw", thrown != null)
    }
}
