package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-product helpdesk's half of the contract gate, read from the GENERATED
 * `district-support-*.json` fixtures.
 *
 * ⛔ THE REAL-OUTPUT COUNTERPART OF [SupportContractShapeTest], which keeps the branches no fixture
 * carries (the deduplicated and pending creates, a localised status name, the draft's field set).
 * A separate class from [DeskContractFixtureTest] because the two surfaces are mirror images, and
 * a file name that says which queue it is about is the cheapest guard against reading one
 * surface's fixture into the other's DTO.
 *
 * ⛔ WHAT THIS SURFACE IS WORTH PINNING FOR. `issueKey` is NULL until the request has been filed at
 * the desk. The local claim row is written first on purpose, so an unfiled row is the ordinary state
 * of anything raised in the last few seconds, and a client keying a list on `issueKey` collides
 * every such row onto one key.
 */
class SupportContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    @Test
    fun `the list carries a filed row, an unfiled row and a resolved row`() {
        val response = json.decodeFromString<SupportRequestListResponse>(
            fixture("district-support-requests.json"),
        )

        assertTrue(response.success)
        assertEquals(3, response.requests.size)

        val filed = response.requests[0]
        assertEquals("DA-42", filed.issueKey)
        assertTrue(filed.filed)
        assertFalse("INDETERMINATE is an open bucket", filed.isResolved)

        // ⛔ THE UNFILED ROW: null key, `filed: false`, and the synthetic `PENDING` category, which a
        // client must never read as resolved.
        val pending = response.requests[1]
        assertNull(pending.issueKey)
        assertFalse(pending.filed)
        assertEquals("PENDING", pending.statusCategory)
        assertFalse(pending.isResolved)
        // ⚠️ Raised from a phone call rather than from a client, in another region.
        assertEquals("voice-call", pending.source)
        assertEquals("eu", pending.region)

        assertTrue("DONE is the only resolved bucket", response.requests[2].isResolved)
    }

    @Test
    fun `the filed create answers its key and carries neither other branch's flag`() {
        val response = json.decodeFromString<SupportRequestCreateResponse>(
            fixture("district-support-request-create.json"),
        )

        assertTrue(response.success)
        assertEquals(SupportRequestFiling.Filed("DA-43"), response.filing)
        // ⚠️ ABSENT, NOT FALSE, on the filed branch. The deduplicated and pending branches are both
        // successes and are covered by [SupportContractShapeTest].
        assertNull(response.deduplicated)
        assertNull(response.pending)
    }

    @Test
    fun `the request read substitutes the author and carries the workflow's own closeable`() {
        val response = json.decodeFromString<SupportRequestDetailResponse>(
            fixture("district-support-request.json"),
        )
        val request = requireNotNull(response.request)

        // ⛔ THE SERVER'S ANSWER, AND THE ONLY THING THAT MAY DRAW THE CLOSE CONTROL.
        assertTrue(request.closeable)
        assertEquals(2, request.messages.size)
        assertEquals(SupportMessageRole.CUSTOMER, request.messages[0].knownRole)
        assertEquals(SupportMessageRole.AGENT, request.messages[1].knownRole)

        // ⛔ `author` IS A LABEL THE ROUTE SYNTHESISES. The desk's own display name is dropped
        // server-side and never reaches this client.
        assertEquals("You", request.messages[0].author)
        assertEquals("Distronode Support", request.messages[1].author)
        assertFalse(
            "the vendor's display name must not reach this client",
            "authorName" in fixture("district-support-request.json"),
        )
    }

    @Test
    fun `the detail repeats the summary's fields and agrees about what resolved means`() {
        val detail = requireNotNull(
            json.decodeFromString<SupportRequestDetailResponse>(fixture("district-support-request.json")).request,
        )
        val summary = json.decodeFromString<SupportRequestListResponse>(
            fixture("district-support-requests.json"),
        ).requests[0]

        // ⛔ THE ROUTE SPREADS THE SUMMARY AND APPENDS `closeable` AND `messages`, so the detail is
        // flat rather than nested, and both types resolve through the one helper.
        assertEquals(summary.issueKey, detail.issueKey)
        assertEquals(summary.statusCategory, detail.statusCategory)
        assertEquals(summary.isResolved, detail.isResolved)
    }

    @Test
    fun `the reply answers one message whose text is under body, as the desk's does`() {
        val response = json.decodeFromString<SupportReplyResponse>(fixture("district-support-reply.json"))
        val message = requireNotNull(response.message)

        assertEquals("Still failing as of this morning.", message.body)
        assertEquals("You", message.author)
        assertEquals(SupportMessageRole.CUSTOMER, message.knownRole)

        // ⛔ THE TWO REPLY RESPONSES DO NOT DISTINGUISH THE SURFACES. Only the REQUESTS differ
        // (support sends `{body}`, the desk sends `{message}`); both responses nest the text under
        // `body` inside a message object.
        val deskMessage = (json.parseToJsonElement(fixture("district-desk-ticket-reply.json")) as JsonObject)["message"]
            as JsonObject
        val supportMessage = (json.parseToJsonElement(fixture("district-support-reply.json")) as JsonObject)["message"]
            as JsonObject
        assertTrue("body" in deskMessage.keys && "body" in supportMessage.keys)
    }

    @Test
    fun `the close answers the workflow's own name for where the request landed`() {
        val response = json.decodeFromString<SupportCloseResponse>(fixture("district-support-close.json"))

        assertTrue(response.success)
        // ⚠️ ADOPTED, NEVER SUBSTITUTED: the desk may spell it in another language.
        assertEquals("Done", response.statusName)
    }
}
