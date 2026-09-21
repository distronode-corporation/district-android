package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * District Desk's half of the contract gate, read from the GENERATED `district-desk-*.json`
 * fixtures.
 *
 * ⛔ THE REAL-OUTPUT COUNTERPART OF [DeskContractShapeTest]. That class decodes JSON transcribed by
 * hand from the route source and still earns its place for the branches no fixture carries (the
 * deduplicated create, the degraded reply replay, an unknown status). This class decodes what the
 * website's contract generator recorded from today's handlers, so a DTO that drifts from the
 * server fails here rather than on a phone.
 *
 * ⛔ A SEPARATE CLASS FROM [ContractFixtureTest] FOR THE REASON [ContractFixtures] STATES: that one
 * reached detekt's LargeClass ceiling, and the healthy answer is more classes. [SupportContractFixtureTest]
 * is a third class rather than more tests here because the two surfaces are mirror images with
 * near-identical shapes, and the one mistake the pair invites is a fixture read into the wrong DTO.
 *
 * ⛔ WHAT THIS SURFACE IS WORTH PINNING FOR. The reply and status echoes are SUMMARIES with no
 * `messages` key, and only the ticket read carries a thread. Decoding an echo into
 * [DeskTicketDetail] succeeds with an empty thread, so pressing Resolve would appear to delete the
 * customer's conversation. Nothing errors and nothing logs.
 */
class DeskContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    private fun rawKeys(name: String, key: String): Set<String> {
        val envelope = json.parseToJsonElement(fixture(name)) as JsonObject
        return (envelope[key] as JsonObject).keys
    }

    @Test
    fun `the settings read carries all four fields with both nullable ones populated`() {
        val response = json.decodeFromString<DeskSettingsResponse>(fixture("district-desk-settings.json"))

        assertTrue(response.success)
        val settings = requireNotNull(response.settings) { "a settings read must carry a row" }
        assertTrue(settings.enabled)
        assertTrue(settings.notifyCustomersByEmail)
        // ⛔ POPULATED HERE AND NULL IN THE PATCH FIXTURE, so neither can be typed as always-present
        // or always-absent and pass.
        assertEquals("Contract Test Desk", settings.publicBrandName)
        assertNotNull(settings.publicLogoUrl)
    }

    @Test
    fun `the settings patch answers the stored row, with both nullable fields null`() {
        val response = json.decodeFromString<DeskSettingsResponse>(
            fixture("district-desk-settings-patch.json"),
        )
        val settings = requireNotNull(response.settings)

        assertFalse(settings.enabled)
        // ⛔ THE SERVER'S ANSWER, NOT THE REQUEST ECHOED BACK. A blank brand name is stored as null,
        // which means "fall back to the workspace name", never "show nothing".
        assertNull(settings.publicBrandName)
        assertNull(settings.publicLogoUrl)
    }

    @Test
    fun `the logo upload reuses the settings envelope and the delete adds objectRemoved`() {
        val upload = json.decodeFromString<DeskSettingsResponse>(fixture("district-desk-logo.json"))
        assertNotNull(requireNotNull(upload.settings).publicLogoUrl)
        assertEquals(
            "the upload is the two-key settings envelope",
            setOf("success", "settings"),
            (json.parseToJsonElement(fixture("district-desk-logo.json")) as JsonObject).keys,
        )

        // ⛔ THE DELETE NEEDS ITS OWN TYPE. `objectRemoved` says whether the stored BYTES went, which
        // is a different fact from whether the customers' page still shows the image: the column is
        // cleared first and the object second, and the second half can fail alone. The strict
        // decoder refuses this fixture as a [DeskSettingsResponse], which is the point of the split.
        val removal = json.decodeFromString<DeskLogoRemovalResponse>(
            fixture("district-desk-logo-delete.json"),
        )
        assertTrue(removal.objectRemoved)
        assertNull(requireNotNull(removal.settings).publicLogoUrl)
        val misread = runCatching {
            json.decodeFromString<DeskSettingsResponse>(fixture("district-desk-logo-delete.json"))
        }
        assertTrue("the settings envelope must not absorb objectRemoved", misread.isFailure)
    }

    @Test
    fun `the queue carries a voice-call row with every requester field null`() {
        val response = json.decodeFromString<DeskTicketsResponse>(fixture("district-desk-tickets.json"))

        assertEquals(3, response.tickets.size)
        // ⛔ THE SERVER'S OWN `displayReference`, pinned by value. Rebuilding "T-$reference" agrees
        // today and drifts the day the prefix changes.
        assertEquals("T-41", response.tickets[0].displayReference)
        assertEquals(DeskTicketStatus.OPEN, response.tickets[0].knownStatus)

        // ⛔ ALL FOUR NULL AT ONCE, WHICH IS THE COMMONEST SHAPE ON A VOICE WORKSPACE: a ticket the
        // voice agent raised from a withheld number has no contact, name, email or phone.
        val voiceRow = response.tickets[1]
        assertNull(voiceRow.contactId)
        assertNull(voiceRow.requesterName)
        assertNull(voiceRow.requesterEmail)
        assertNull(voiceRow.requesterPhone)
        assertTrue(voiceRow.fromCall)
        assertEquals(DeskTicketStatus.WAITING, voiceRow.knownStatus)

        // ⚠️ The only populated `resolvedAt` in the set.
        assertNull(response.tickets[0].resolvedAt)
        assertNotNull(response.tickets[2].resolvedAt)
        assertEquals(DeskTicketStatus.RESOLVED, response.tickets[2].knownStatus)
    }

    @Test
    fun `the create echo is a summary with no thread and no deduplicated key`() {
        val response = json.decodeFromString<DeskTicketCreateResponse>(
            fixture("district-desk-ticket-create.json"),
        )

        assertTrue(response.success)
        assertEquals("T-41", requireNotNull(response.ticket).displayReference)
        // ⚠️ ABSENT ON THIS BRANCH, so the DTO must not require it. The deduplicated branch, which
        // carries no ticket at all, is covered by [DeskContractShapeTest].
        assertNull(response.deduplicated)
        assertFalse(
            "the create echo carries no thread",
            "messages" in rawKeys("district-desk-ticket-create.json", "ticket"),
        )
    }

    @Test
    fun `the ticket read is the only desk body with a thread, and it has all three authors`() {
        val response = json.decodeFromString<DeskTicketResponse>(fixture("district-desk-ticket.json"))
        val ticket = requireNotNull(response.ticket)

        // ⛔ `assistant` IS THE VOICE AGENT AND IS THE AUTHOR A CLIENT WOULD OMIT. It writes the
        // opening message of every ticket raised from an unresolved call.
        assertEquals(
            listOf(DeskMessageAuthor.CUSTOMER, DeskMessageAuthor.ASSISTANT, DeskMessageAuthor.TEAM),
            ticket.messages.map { it.knownAuthor },
        )
        // ⚠️ NO AUTHOR NAME ON THE WIRE. A team reply is published under the workspace's public
        // brand, never the individual operator's.
        assertFalse("a desk message carries no author name", "authorName" in fixture("district-desk-ticket.json"))
    }

    @Test
    fun `the reply and status echoes are summaries, which is what stops a blanked thread`() {
        val reply = json.decodeFromString<DeskReplyResponse>(fixture("district-desk-ticket-reply.json"))
        // ⚠️ The team reply moved the ticket to `waiting` server-side, which is what makes the echo
        // worth adopting rather than discarding.
        assertEquals(DeskTicketStatus.WAITING, requireNotNull(reply.ticket).knownStatus)
        assertEquals(DeskMessageAuthor.TEAM, requireNotNull(reply.message).knownAuthor)
        assertEquals(true, reply.notified)
        assertNull(reply.deduplicated)

        val status = json.decodeFromString<DeskTicketStatusResponse>(
            fixture("district-desk-ticket-status.json"),
        )
        val resolved = requireNotNull(status.ticket)
        assertEquals(DeskTicketStatus.RESOLVED, resolved.knownStatus)
        assertNotNull("resolving stamps resolvedAt", resolved.resolvedAt)

        // ⛔ THE ASSERTION THIS CLASS EXISTS FOR. Neither echo carries `messages`.
        assertFalse("messages" in rawKeys("district-desk-ticket-reply.json", "ticket"))
        assertFalse("messages" in rawKeys("district-desk-ticket-status.json", "ticket"))
    }

    @Test
    fun `adopting a real status echo keeps the thread already on screen`() {
        val detail = requireNotNull(
            json.decodeFromString<DeskTicketResponse>(fixture("district-desk-ticket.json")).ticket,
        )
        val echo = requireNotNull(
            json.decodeFromString<DeskTicketStatusResponse>(fixture("district-desk-ticket-status.json")).ticket,
        )

        val merged = detail.adopting(echo)

        // ⛔ THE SCALARS MOVE AND THE CONVERSATION DOES NOT.
        assertEquals(DeskTicketStatus.RESOLVED, merged.knownStatus)
        assertEquals(echo.resolvedAt, merged.resolvedAt)
        assertEquals(detail.messages, merged.messages)
        assertEquals(detail.messages.size, merged.messageCount)
    }
}
