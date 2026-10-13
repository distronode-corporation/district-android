package com.distronode.districtai.core.network.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptFramesTest {

    @Test
    fun `ops are written in the contract's key order`() {
        assertEquals(
            """{"op":"transcript.subscribe","v":1,"callId":"call_A-9"}""",
            TranscriptClientOp.Subscribe("call_A-9").text,
        )
        assertEquals(
            """{"op":"transcript.unsubscribe","v":1,"callId":"c"}""",
            TranscriptClientOp.Unsubscribe("c").text,
        )
        assertEquals("""{"op":"socket.mode","v":1,"broadcast":false}""", TranscriptClientOp.SocketMode(false).text)
    }

    @Test
    fun `a call id the server would refuse makes no frame`() {
        assertNull(TranscriptClientOp.Subscribe("").text)
        assertNull(TranscriptClientOp.Subscribe("a".repeat(65)).text)
        assertNull(TranscriptClientOp.Unsubscribe("a\"}").text)
        assertTrue(TranscriptClientOp.isValidCallId("a".repeat(64)))
        assertFalse(TranscriptClientOp.isValidCallId("a.b"))
    }

    @Test
    fun `every transcript fixture decodes for its workspace`() {
        val expected = mapOf(
            "telemetry-event-transcript-snapshot.json" to TranscriptEvent.Snapshot::class,
            "telemetry-event-transcript-segment.json" to TranscriptEvent.Segment::class,
            "telemetry-event-transcript-segment-interim.json" to TranscriptEvent.Segment::class,
            "telemetry-event-transcript-ended.json" to TranscriptEvent.Ended::class,
            "telemetry-event-transcript-retracted.json" to TranscriptEvent.Retracted::class,
            "telemetry-event-transcript-error.json" to TranscriptEvent.Error::class,
        )
        expected.forEach { (file, type) ->
            val message = TelemetryMessages.read(contract(file), "ws_1")
            val event = (message as TelemetryMessage.Transcript).event
            assertTrue(file, type.isInstance(event))
            assertEquals(file, "call_1", event.callId)
        }
    }

    @Test
    fun `a call-less error reads as the empty call id`() {
        val text = contract(
            "telemetry-event-transcript-error.json"
        ).replace("\"callId\": \"call_1\",\n    \"op\"", "\"callId\": null,\n    \"op\"")
        val event = (TelemetryMessages.read(text, "ws_1") as TelemetryMessage.Transcript).event
        assertEquals("", event.callId)
    }

    @Test
    fun `another workspace, a non-envelope, or a broken payload is discarded unread`() {
        val snapshot = contract("telemetry-event-transcript-snapshot.json")
        assertEquals(TelemetryMessage.Discarded, TelemetryMessages.read(snapshot, "ws_2"))
        assertEquals(TelemetryMessage.Discarded, TelemetryMessages.read("not json", "ws_1"))
        assertEquals(TelemetryMessage.Discarded, TelemetryMessages.read("""{"workspaceId":"ws_1"}""", "ws_1"))
        val broken =
            """{"workspaceId":"ws_1","callId":"c","eventType":"transcript_segment","data":{"v":1},"timestamp":"t"}"""
        assertEquals(TelemetryMessage.Discarded, TelemetryMessages.read(broken, "ws_1"))
    }

    @Test
    fun `an event this client does not model is passed on as other`() {
        val call = """{"workspaceId":"ws_1","callId":"c","eventType":"call_started","data":{"x":1},"timestamp":"t"}"""
        assertEquals(TelemetryMessage.Other, TelemetryMessages.read(call, "ws_1"))
    }
}
