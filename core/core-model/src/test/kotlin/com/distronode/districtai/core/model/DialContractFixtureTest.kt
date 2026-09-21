package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The softphone half of the contract gate.
 *
 * ⛔ WHY THIS ONE MATTERS MORE THAN THE OTHER FIXTURE TESTS IN THIS MODULE. Everywhere else a
 * renamed field means a screen renders wrong and somebody retries. Here the server has ALREADY
 * written the `Call` row and ALREADY told the carrier to dial by the time it mints this body — so
 * a field this DTO cannot read is a telephone that rings with nobody on the line, billed, while
 * the operator looks at an error. And there is nothing to retry onto: retrying places a second
 * call.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` for the reason the meetings, device and billing
 * halves are: that class reached detekt's LargeClass ceiling as endpoints accumulated, and the
 * healthy answer is another class rather than a raised threshold. The strict decoder and the
 * fixture loader come from [ContractFixtures] so the split cannot leave one of them lenient.
 *
 * ⚠️ THE ERROR ENVELOPES FOR THIS ROUTE ARE PINNED ELSEWHERE — in core-network's
 * `ApiErrorEnvelopeContractTest`, next to the type that decodes them and the mapping that turns a
 * `code` into an outcome. Pinning a test-only copy of an error shape here would prove a duplicate
 * correct while the shipping type drifted.
 */
class DialContractFixtureTest {

    private val json: Json = ContractFixtures.json

    @Test
    fun `the dial response decodes strictly and carries a usable join credential`() {
        val response = json.decodeFromString<DialResponse>(ContractFixtures.read("district-dial.json"))

        assertEquals(true, response.success)
        // ⛔ BOTH OR NEITHER. Every field of this DTO defaults, so a `{}` body would decode into a
        // well-formed response holding an empty token and an empty URL — and the failure would
        // surface at `engine.connect` as a media-plane error for what was really a server refusal.
        // `DialRepository` rejects that at runtime; this asserts the real response is not that.
        assertTrue("the token must not be blank", response.token.isNotBlank())
        assertTrue("the url must not be blank", response.url.isNotBlank())
        // ⛔ A `wss://` URL, USED VERBATIM. It names the TRUNK's deployment rather than the
        // workspace's, because the room is created by the SIP dial on whichever bus the server's
        // SipClient points at — so a client that derived this would join a bus that has never
        // heard of the room, and the call would be live, billed and inaudible.
        assertTrue("the url must be a websocket url", response.url.startsWith("wss://"))
    }

    @Test
    fun `the room is a direct_ room, which is what keeps the AI off a human's call`() {
        val response = json.decodeFromString<DialResponse>(ContractFixtures.read("district-dial.json"))

        // ⛔ THE PREFIX IS A ROUTING DECISION, NOT A LABEL. The voice agent auto-dispatches into
        // every room it does not refuse and `request_fnc` refuses this one BY NAME; a `call_` room
        // would put the agent on the line talking over the operator. One character apart, and
        // nothing else in the response distinguishes them.
        assertTrue(
            "a direct dial must produce a $DIRECT_ROOM_PREFIX room, got ${response.roomName}",
            response.roomName.startsWith(DIRECT_ROOM_PREFIX),
        )
        // ⚠️ THE CALL ID IS INSIDE THE ROOM NAME. The server builds
        // `direct_<workspaceId>-<callId>`, so the two are not independent — a client that
        // reconstructed either from the other would be reimplementing a server template, which is
        // why nothing here does and why both are read from the response.
        assertTrue(
            "the room name must embed the call id",
            response.roomName.endsWith(response.callId),
        )
    }

    @Test
    fun `the call id is the call log's own key`() {
        val response = json.decodeFromString<DialResponse>(ContractFixtures.read("district-dial.json"))

        // ⚠️ `CA` + 32 hex characters, which is the `Call.callSid` shape the campaign dialer's SIP
        // path uses too. Asserted as a SHAPE rather than a value: the fixture's digits come from a
        // stubbed `randomBytes`, so the exact string is a property of the generator and pinning it
        // here would make this test fail on a change to the stub rather than to the contract.
        assertTrue("the call id must be present", response.callId.isNotBlank())
        assertTrue(
            "the call id must look like a call sid, got ${response.callId}",
            Regex("^CA[0-9a-f]{32}$").matches(response.callId),
        )
    }
}
