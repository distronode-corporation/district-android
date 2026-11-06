package com.distronode.districtai.core.model

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live supervise fixtures (listen, take over, whisper and the whisper receipt), decoded strictly
 * and held to [WireMirror] so each model keeps every key it reads.
 *
 * A class of its own for the reason [AppParityContractFixtureTest] gives: `ContractFixtureTest` is
 * at detekt's LargeClass ceiling.
 */
class SuperviseContractFixtureTest {

    @Test
    fun `the listen token carries a credential and the room's deployment`() {
        val grant = WireMirror.assertMirrors(CallMonitorTokenResponse.serializer(), "district-call-monitor-token.json")

        assertTrue(grant.success)
        assertTrue("the token must not be blank", grant.token.isNotBlank())
        // ⛔ The room's deployment, used verbatim: never the workspace's default LiveKit URL.
        assertTrue(grant.url.startsWith("wss://"))
    }

    @Test
    fun `the takeover grant carries a credential, the deployment and the room`() {
        val grant = WireMirror.assertMirrors(CallTakeoverResponse.serializer(), "district-call-takeover.json")

        assertTrue(grant.success)
        assertTrue(grant.token.isNotBlank())
        assertTrue(grant.url.startsWith("wss://"))
        // A live AI call's room, never a team member's own `direct_` one (the route refuses those).
        assertTrue(grant.roomName.startsWith("call_"))
    }

    @Test
    fun `a whisper answers success alone`() {
        val sent = WireMirror.assertMirrors(CallWhisperResponse.serializer(), "district-call-whisper.json")

        assertEquals(CallWhisperResponse(success = true), sent)
    }

    @Test
    fun `an applied whisper receipt carries the first words and no reason`() {
        val frame = WireMirror.assertMirrors(
            TelemetryEvent.serializer(WhisperAppliedData.serializer()),
            "telemetry-event-whisper-applied.json",
        )

        assertEquals(TELEMETRY_EVENT_WHISPER_APPLIED, frame.eventType)
        assertTrue(frame.callId.isNotBlank())
        assertTrue(frame.data.applied)
        assertTrue(!frame.data.firstWords.isNullOrBlank())
        assertNull(frame.data.reason)
    }

    @Test
    fun `a whisper held back names why and says nothing`() {
        val frame = WireMirror.assertMirrors(
            TelemetryEvent.serializer(WhisperAppliedData.serializer()),
            "telemetry-event-whisper-not-applied.json",
        )

        assertEquals(TELEMETRY_EVENT_WHISPER_APPLIED, frame.eventType)
        assertFalse(frame.data.applied)
        assertNull(frame.data.firstWords)
        assertEquals(WHISPER_NOT_APPLIED_TAKEN_OVER, frame.data.reason)
    }

    @Test
    fun `an empty body reads as unusable, never as a grant`() {
        WireMirror.assertWire(CallMonitorTokenResponse.serializer(), CallMonitorTokenResponse(), "{}")
        WireMirror.assertWire(CallTakeoverResponse.serializer(), CallTakeoverResponse(), "{}")
        WireMirror.assertWire(CallWhisperResponse.serializer(), CallWhisperResponse(), "{}")
        WireMirror.assertWire(WhisperAppliedData.serializer(), WhisperAppliedData(), "{}")
    }

    @Test
    fun `the request bodies are the routes' own keys`() {
        WireMirror.assertRequiredKeys(
            CallMonitorTokenRequest.serializer(),
            CallMonitorTokenRequest(roomName = "cm1call", identity = "supervisor-android"),
            """{"roomName":"cm1call","identity":"supervisor-android"}""",
        )
        WireMirror.assertRequiredKeys(
            CallTakeoverRequest.serializer(),
            CallTakeoverRequest(workspaceId = "ws_1"),
            """{"workspaceId":"ws_1"}""",
        )
        WireMirror.assertRequiredKeys(
            CallWhisperRequest.serializer(),
            CallWhisperRequest(workspaceId = "ws_1", callId = "cm1call", whisper = "Offer Thursday."),
            """{"workspaceId":"ws_1","callId":"cm1call","whisper":"Offer Thursday."}""",
        )
    }

    @Test
    fun `every call summary says whether the receptionist takes part, on all three routes`() {
        // ⛔ The two calls with no receptionist on them are the fixtures' own: a carrier ring-out
        // with no room and a busy line. Every other call has the receptionist on it.
        val expected = mapOf(
            "call_contract_answered" to true,
            "call_contract_missed" to true,
            "call_contract_outbound" to true,
            "call_contract_outbound_unanswered" to false,
            "call_contract_busy" to false,
        )
        val log = WireMirror.assertMirrors(ListSerializer(CallSummary.serializer()), "district-calls.json")
        assertEquals(expected, log.associate { it.id to it.receptionistOnCall })

        val overview = WireMirror.assertMirrors(OverviewResponse.serializer(), "district-overview.json")
        assertEquals(expected, overview.recentCalls.associate { it.id to it.receptionistOnCall })

        val detail = WireMirror.assertMirrors(CallDetailResponse.serializer(), "district-call-detail.json")
        assertEquals(true, detail.call?.receptionistOnCall)
    }

    @Test
    fun `a call summary without the key reads as the receptionist taking part`() {
        val body = ContractFixtures.read("district-call-detail.json")
            .replace(Regex(",\\s*\"receptionistOnCall\":\\s*true"), "")
        assertFalse("the substitution must have happened", body.contains("receptionistOnCall"))

        val detail = ContractFixtures.json.decodeFromString(CallDetailResponse.serializer(), body)

        assertEquals(true, detail.call?.receptionistOnCall)
    }

    @Test
    fun `the new DTOs compare by value, field by field`() {
        val grant = CallTakeoverResponse()
        listOf(
            grant.copy(success = true),
            grant.copy(url = "u"),
            grant.copy(token = "t"),
            grant.copy(roomName = "r"),
        ).forEach { assertTrue(grant != it) }
        val token = CallMonitorTokenResponse()
        listOf(token.copy(success = true), token.copy(token = "t"), token.copy(url = "u"))
            .forEach { assertTrue(token != it) }
        val receipt = WhisperAppliedData()
        listOf(receipt.copy(applied = true), receipt.copy(firstWords = "w"), receipt.copy(reason = "r"))
            .forEach { assertTrue(receipt != it) }
    }
}
