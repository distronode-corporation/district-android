package com.distronode.districtai.core.model

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
        WireMirror.assertWire(ActiveCallsResponse.serializer(), ActiveCallsResponse(), "{}")
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
    fun `an active call row needs only its id`() {
        WireMirror.assertRequiredKeys(ActiveCall.serializer(), ActiveCall(id = "cm1call"), """{"id":"cm1call"}""")
    }

    @Test
    fun `a direct room is a team member's own call and nothing else is`() {
        assertTrue(ActiveCall(id = "a", roomName = "direct_ws_1-CA0123").isTeamMemberCall)
        assertFalse(ActiveCall(id = "a", roomName = "call_ws_1-CA0123").isTeamMemberCall)
        assertFalse("no room is not a softphone call", ActiveCall(id = "a").isTeamMemberCall)
        // Case-sensitive, as the server's `startsWith` is.
        assertFalse(ActiveCall(id = "a", roomName = "Direct_ws_1").isTeamMemberCall)
    }

    @Test
    fun `the party is the callee on an outbound call and the caller otherwise`() {
        val inbound = ActiveCall(id = "a", from = "+15550100", to = "+15550199", direction = "inbound")
        assertEquals("+15550100", inbound.party)
        assertEquals("+15550199", inbound.copy(direction = "outbound").party)
        assertEquals("+15550100", inbound.copy(direction = "outbound", to = "").party)
        assertEquals("+15550100", inbound.copy(direction = "outbound", to = null).party)
        assertEquals("", ActiveCall(id = "a").party)
    }

    @Test
    fun `the new DTOs compare by value, field by field`() {
        val row = ActiveCall(id = "a")
        assertEquals(row, row.copy())
        listOf(
            row.copy(id = "b"),
            row.copy(from = "f"),
            row.copy(to = "t"),
            row.copy(callerName = "n"),
            row.copy(direction = "inbound"),
            row.copy(status = "in-progress"),
            row.copy(roomName = "call_x"),
            row.copy(createdAt = "2026-11-05T00:00:00.000Z"),
        ).forEach { assertTrue("each field must participate in equality", row != it) }
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
        assertTrue(ActiveCallsResponse() != ActiveCallsResponse(success = true))
        assertTrue(ActiveCallsResponse() != ActiveCallsResponse(calls = listOf(row)))
    }
}
