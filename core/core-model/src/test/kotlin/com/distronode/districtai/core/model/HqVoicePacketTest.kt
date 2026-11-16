package com.distronode.districtai.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The packets the voice agent sends on `hq.proposal`, read by the web client's rules (as iOS reads
 * them). ⚠️ Anything that does not validate is ignored, never an error.
 */
class HqVoicePacketTest {

    private fun read(json: String) = HqVoicePacket.read(json.encodeToByteArray())

    @Test
    fun `a proposal carries its id, tool, arguments and summary untouched`() {
        val packet = read(
            """{"type":"HQ_PROPOSAL","proposalId":"p1","tool":"update_persona",
               "args":{"greeting":"Hi"},"summary":"Change the greeting.","extra":1}""",
        )

        assertEquals(
            HqVoicePacket.Proposal(
                HqVoiceProposal(
                    "p1",
                    "update_persona",
                    JsonObject(mapOf("greeting" to JsonPrimitive("Hi"))),
                    "Change the greeting."
                ),
            ),
            packet,
        )
    }

    @Test
    fun `a proposal's summary may be empty, but every other field is required`() {
        assertEquals(
            "",
            (
                read("""{"type":"HQ_PROPOSAL","proposalId":"p","tool":"t","args":{},"summary":""}""")
                    as HqVoicePacket.Proposal
                ).proposal.summary
        )
        assertNull(read("""{"type":"HQ_PROPOSAL","proposalId":"","tool":"t","args":{},"summary":"s"}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL","proposalId":"p","tool":"","args":{},"summary":"s"}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL","proposalId":"p","tool":"t","args":[],"summary":"s"}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL","proposalId":"p","tool":"t","args":{}}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL","proposalId":"p","tool":"t","args":{},"summary":3}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL","proposalId":7,"tool":"t","args":{},"summary":"s"}"""))
    }

    @Test
    fun `a resolution needs a known status and via voice, and keeps only a non-empty error`() {
        assertEquals(
            HqVoicePacket.Resolved(HqVoiceResolution("p1", HqResolutionStatus.FAILED, "boom")),
            read(
                """{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"p1","status":"failed","via":"voice","error":"boom"}"""
            ),
        )
        assertEquals(
            HqVoicePacket.Resolved(HqVoiceResolution("p1", HqResolutionStatus.APPLIED, null)),
            read("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"p1","status":"applied","via":"voice","error":""}"""),
        )
        assertEquals(
            HqResolutionStatus.CANCELLED,
            (
                read("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"p","status":"cancelled","via":"voice"}""")
                    as HqVoicePacket.Resolved
                ).resolution.status,
        )
        assertNull(read("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"p","status":"applied","via":"click"}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"p","status":"done","via":"voice"}"""))
        assertNull(read("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"","status":"applied","via":"voice"}"""))
    }

    @Test
    fun `anything that is not a packet is ignored`() {
        assertNull(read("not json"))
        assertNull(read("[1,2]"))
        assertNull(read("""{"type":"HQ_SOMETHING"}"""))
        assertNull(read("""{"proposalId":"p"}"""))
        assertNull(read("""{"type":null}"""))
    }

    @Test
    fun `the agent's state is read from its wire value, and an unknown one is ignored`() {
        assertEquals(HqAgentState.LISTENING, HqAgentState.fromWire("listening"))
        assertEquals(HqAgentState.THINKING, HqAgentState.fromWire("thinking"))
        assertEquals(HqAgentState.SPEAKING, HqAgentState.fromWire("speaking"))
        assertNull(HqAgentState.fromWire("initializing"))
        assertNull(HqAgentState.fromWire(null))
        assertNull(HqResolutionStatus.fromWire(null))
    }
}
