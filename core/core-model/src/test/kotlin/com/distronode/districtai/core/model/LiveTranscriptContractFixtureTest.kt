package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live transcript half of the contract gate: the six telemetry socket frames and the token
 * that opens the socket.
 *
 * ⛔ THE FRAMES ARE NOT HTTP RESPONSES, BUT THEY ARE HELD TO THE SAME STRICT DECODER. They come
 * from the server's broadcaster and agent builders, and a renamed key there would otherwise reach
 * this client only as a transcript that silently stops updating. Every fixture goes through
 * [WireMirror.assertMirrors], so each one decodes with `ignoreUnknownKeys = false` AND writes back
 * every key it read.
 *
 * ⚠️ STRICT HERE, LENIENT IN THE APP, AND BOTH ARE THE CONTRACT. §4.9 of the server's wire spec
 * lets it add optional `data` keys without bumping `v`, so the socket client must decode with
 * unknown keys ignored, as `DistrictApiClient.DEFAULT_JSON` does for HTTP. The strict decoder is
 * what makes such an addition fail HERE first, so the DTO learns the field instead of dropping it.
 */
class LiveTranscriptContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun <T> event(data: KSerializer<T>, name: String): TelemetryEvent<T> =
        WireMirror.assertMirrors(TelemetryEvent.serializer(data), name)

    /** The envelope rule every transcript frame follows: `data.callId` repeats the envelope's. */
    private fun assertEnvelope(event: TelemetryEvent<*>, eventType: String, dataCallId: String?) {
        assertEquals(eventType, event.eventType)
        assertTrue("the workspace id must be present", event.workspaceId.isNotBlank())
        assertTrue("the call id must be present", event.callId.isNotBlank())
        assertEquals("data.callId must repeat the envelope's call id", event.callId, dataCallId)
        assertTrue("the timestamp must be ISO 8601 UTC", event.timestamp.endsWith("Z"))
    }

    @Test
    fun `a final segment decodes strictly with every field of the segment object`() {
        val frame = event(TranscriptSegmentData.serializer(), "telemetry-event-transcript-segment.json")
        assertEnvelope(frame, TRANSCRIPT_EVENT_SEGMENT, frame.data.callId)
        assertEquals(1, frame.data.v)

        val segment = frame.data.segment
        assertTrue("a final segment must be frozen", segment.isFinal)
        assertNotNull("a final segment carries its end", segment.endedAt)
        assertTrue("seq starts at 1", segment.seq >= 1)
        assertTrue("rev starts at 0", segment.rev >= 0)
        assertTrue("the text must not be empty", segment.text.isNotEmpty())
        assertEquals("a BCP-47 primary subtag", "en", segment.language)
        assertTrue("the start must be ISO 8601 UTC", segment.startedAt.endsWith("Z"))
        assertEquals("caller", segment.speaker)
        // ⚠️ A caller line never carries a name: the caller's name stays on the call row.
        assertEquals(TranscriptSpeaker.CALLER, segment.speakerRole)
        assertNull(segment.speakerName)
        assertFalse("a caller line is never interrupted", segment.interrupted)
    }

    @Test
    fun `an interim segment keeps the id its final will carry and has no end yet`() {
        val interim = event(TranscriptSegmentData.serializer(), "telemetry-event-transcript-segment-interim.json")
        val final = event(TranscriptSegmentData.serializer(), "telemetry-event-transcript-segment.json")
        assertEnvelope(interim, TRANSCRIPT_EVENT_SEGMENT, interim.data.callId)

        assertFalse("an interim segment is a hypothesis", interim.data.segment.isFinal)
        assertNull("an interim segment has no end", interim.data.segment.endedAt)
        // ⛔ THE ID IS THE DEDUPE KEY. An interim gets its id at the first hypothesis and the final
        // keeps it, so a final under a new id would leave the interim on screen beside it.
        assertEquals(final.data.segment.segmentId, interim.data.segment.segmentId)
    }

    @Test
    fun `a snapshot decodes its segments in display order with its high-water mark`() {
        val frame = event(TranscriptSnapshotData.serializer(), "telemetry-event-transcript-snapshot.json")
        assertEnvelope(frame, TRANSCRIPT_EVENT_SNAPSHOT, frame.data.callId)
        val snapshot = frame.data

        assertEquals(1, snapshot.v)
        assertTrue("the fixture must carry segments to check", snapshot.segments.size >= 2)
        assertTrue(snapshot.live)
        // §4.12 Q9: endedReason is null whenever live is true.
        assertNull(snapshot.endedReason)
        assertTrue(snapshot.complete)
        assertEquals(0, snapshot.part)
        assertFalse("a single-part snapshot is its own last part", snapshot.more)

        val order = compareBy<TranscriptSegment>({ it.epoch }, { it.index })
        assertEquals(
            "segments arrive ordered by (epoch, index)",
            snapshot.segments.sortedWith(order),
            snapshot.segments,
        )
        assertEquals(snapshot.segments.last().epoch, snapshot.epoch)
        assertEquals(snapshot.segments.maxOf { it.seq }, snapshot.lastSeq)

        val agent = snapshot.segments.first { it.speakerRole == TranscriptSpeaker.AGENT }
        assertTrue("an agent line carries the persona's name", !agent.speakerName.isNullOrBlank())
    }

    @Test
    fun `an ended frame names its terminal reason and the last index of its epoch`() {
        val frame = event(TranscriptEndedData.serializer(), "telemetry-event-transcript-ended.json")
        assertEnvelope(frame, TRANSCRIPT_EVENT_ENDED, frame.data.callId)

        assertEquals(1, frame.data.v)
        assertEquals(TRANSCRIPT_ENDED_CALL_ENDED, frame.data.reason)
        assertTrue("seq starts at 1", frame.data.seq >= 1)
        assertTrue("the epoch is a Unix ms start time", frame.data.epoch > 0)
        assertNotNull(frame.data.lastIndex)
    }

    @Test
    fun `a website retraction carries neither epoch nor seq and drops everything`() {
        val frame = event(TranscriptRetractedData.serializer(), "telemetry-event-transcript-retracted.json")
        assertEnvelope(frame, TRANSCRIPT_EVENT_RETRACTED, frame.data.callId)

        // ⛔ BOTH NULL OR BOTH SET (§4.12 Q2). A null pair is the website's retraction, which
        // bypasses seq dedupe; decoding it into a default seq of 0 would let dedupe drop it.
        assertEquals(1, frame.data.v)
        assertNull(frame.data.epoch)
        assertNull(frame.data.seq)
        assertTrue(frame.data.all)
        assertEquals(emptyList<String>(), frame.data.segmentIds)
        assertEquals("erased", frame.data.reason)
    }

    @Test
    fun `an error names its code and echoes the op it refused`() {
        val frame = event(TranscriptErrorData.serializer(), "telemetry-event-transcript-error.json")
        assertEnvelope(frame, TRANSCRIPT_EVENT_ERROR, frame.data.callId)

        assertEquals(1, frame.data.v)
        assertEquals(TRANSCRIPT_ERROR_NOT_LIVE, frame.data.code)
        // §4.12 Q8: a not_live always carries the subscribe it answers.
        assertEquals("transcript.subscribe", frame.data.op)
        assertNull("only rate_limited sets a wait", frame.data.retryAfterMs)
    }

    @Test
    fun `the telemetry token decodes strictly and carries a usable socket credential`() {
        val token = WireMirror.assertMirrors(TelemetryTokenResponse.serializer(), "district-telemetry-token.json")

        assertTrue(token.success)
        assertTrue("the token must not be blank", token.token.isNotBlank())
        assertTrue("the expiry must be set", token.expiresAt > 0)
        // ⛔ A `wss://` URL, USED VERBATIM: the server picks it by the workspace's region.
        assertTrue("the url must be a websocket url", token.wsUrl.startsWith("wss://"))
    }

    @Test
    fun `an absent token body reads as unusable`() {
        val empty = TelemetryTokenResponse()

        assertFalse(empty.success)
        assertEquals("", empty.token)
        assertEquals(0L, empty.expiresAt)
        WireMirror.assertWire(TelemetryTokenResponse.serializer(), empty, "{}")
    }

    @Test
    fun `every frame can be read for its event type before its payload is known`() {
        // ⚠️ How a socket reader dispatches: peek with a JsonElement payload, then decode `data` with
        // the serializer the event type names. Every transcript fixture must peek to its own type.
        val expected = mapOf(
            "telemetry-event-transcript-segment.json" to TRANSCRIPT_EVENT_SEGMENT,
            "telemetry-event-transcript-segment-interim.json" to TRANSCRIPT_EVENT_SEGMENT,
            "telemetry-event-transcript-snapshot.json" to TRANSCRIPT_EVENT_SNAPSHOT,
            "telemetry-event-transcript-ended.json" to TRANSCRIPT_EVENT_ENDED,
            "telemetry-event-transcript-retracted.json" to TRANSCRIPT_EVENT_RETRACTED,
            "telemetry-event-transcript-error.json" to TRANSCRIPT_EVENT_ERROR,
        )
        val peek = TelemetryEvent.serializer(JsonElement.serializer())
        expected.forEach { (name, eventType) ->
            val frame = json.decodeFromString(peek, ContractFixtures.read(name))
            assertEquals(name, eventType, frame.eventType)
        }
        val raw = json.decodeFromString(peek, ContractFixtures.read("telemetry-event-transcript-ended.json"))
        val ended = json.decodeFromJsonElement(TranscriptEndedData.serializer(), raw.data)
        assertEquals(raw.callId, ended.callId)
    }

    @Test
    fun `an unknown speaker reads as other, and the reserved roles are unknown to v1`() {
        assertEquals(TranscriptSpeaker.CALLER, TranscriptSpeaker.fromWire("caller"))
        assertEquals(TranscriptSpeaker.AGENT, TranscriptSpeaker.fromWire("agent"))
        // §4.5: reserved for v1.x and treated as other by clients that do not know them.
        assertEquals(TranscriptSpeaker.OTHER, TranscriptSpeaker.fromWire("human_agent"))
        assertEquals(TranscriptSpeaker.OTHER, TranscriptSpeaker.fromWire("supervisor"))
        assertEquals(TranscriptSpeaker.OTHER, TranscriptSpeaker.fromWire(""))
        // Wire values never localise or change case.
        assertEquals(TranscriptSpeaker.OTHER, TranscriptSpeaker.fromWire("Caller"))
    }

    @Test
    fun `a segment with a reserved speaker still decodes, so the frame and its seq are kept`() {
        val body = ContractFixtures.read("telemetry-event-transcript-segment.json")
            .replace("\"speaker\": \"caller\"", "\"speaker\": \"supervisor\"")
        assertTrue("the substitution must have happened", body.contains("\"supervisor\""))

        val frame = json.decodeFromString(TelemetryEvent.serializer(TranscriptSegmentData.serializer()), body)
        assertEquals(TranscriptSpeaker.OTHER, frame.data.segment.speakerRole)
    }

    @Test
    fun `an added data key fails the strict gate and is ignored by a lenient reader`() {
        val body = ContractFixtures.read("telemetry-event-transcript-ended.json")
            .replace("\"reason\": \"call_ended\"", "\"reason\": \"call_ended\", \"futureKey\": 7")
        assertTrue("the substitution must have happened", body.contains("futureKey"))
        val serializer = TelemetryEvent.serializer(TranscriptEndedData.serializer())

        // The gate: a new server key fails here until the DTO has it.
        val strict = runCatching { json.decodeFromString(serializer, body) }
        assertTrue(strict.exceptionOrNull() is SerializationException)
        // §4.9: shipped builds ignore it, with the same settings as the production HTTP decoder.
        val lenient = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        assertEquals(TRANSCRIPT_ENDED_CALL_ENDED, lenient.decodeFromString(serializer, body).data.reason)
    }

    @Test
    fun `a segment requires every key that is not nullable`() {
        WireMirror.assertRequiredKeys(
            TranscriptSegment.serializer(),
            minimalSegment,
            MINIMAL_SEGMENT_JSON,
        )
    }

    @Test
    fun `every transcript payload and the envelope require their non-nullable keys`() {
        WireMirror.assertRequiredKeys(
            TranscriptSegmentData.serializer(),
            TranscriptSegmentData(v = 1, callId = "call_1", segment = minimalSegment),
            """{"v":1,"callId":"call_1","segment":$MINIMAL_SEGMENT_JSON}""",
        )
        WireMirror.assertRequiredKeys(
            TranscriptSnapshotData.serializer(),
            TranscriptSnapshotData(
                v = 1,
                callId = "call_1",
                live = true,
                endedReason = null,
                complete = false,
                epoch = null,
                lastSeq = null,
                segments = emptyList(),
                part = 0,
                more = false,
            ),
            """{"v":1,"callId":"call_1","live":true,"complete":false,"segments":[],"part":0,"more":false}""",
        )
        WireMirror.assertRequiredKeys(
            TranscriptEndedData.serializer(),
            TranscriptEndedData(
                v = 1,
                callId = "call_1",
                epoch = 5L,
                seq = 3,
                lastIndex = null,
                reason = "agent_error",
            ),
            """{"v":1,"callId":"call_1","epoch":5,"seq":3,"reason":"agent_error"}""",
        )
        WireMirror.assertRequiredKeys(
            TranscriptRetractedData.serializer(),
            TranscriptRetractedData(
                v = 1,
                callId = "call_1",
                epoch = null,
                seq = null,
                all = false,
                segmentIds = listOf("item_a1"),
                reason = "policy",
            ),
            """{"v":1,"callId":"call_1","all":false,"segmentIds":["item_a1"],"reason":"policy"}""",
        )
        WireMirror.assertRequiredKeys(
            TranscriptErrorData.serializer(),
            TranscriptErrorData(v = 1, callId = null, op = null, code = "bad_request", retryAfterMs = null),
            """{"v":1,"code":"bad_request"}""",
        )
        WireMirror.assertRequiredKeys(
            TelemetryEvent.serializer(TranscriptErrorData.serializer()),
            TelemetryEvent(
                workspaceId = "ws_1",
                callId = "",
                eventType = TRANSCRIPT_EVENT_ERROR,
                data = TranscriptErrorData(v = 1, callId = null, op = null, code = "rate_limited", retryAfterMs = 900L),
                timestamp = "2026-10-06T14:30:32.000Z",
            ),
            """{"workspaceId":"ws_1","callId":"","eventType":"transcript_error",""" +
                """"data":{"v":1,"code":"rate_limited","retryAfterMs":900},"timestamp":"2026-10-06T14:30:32.000Z"}""",
        )
    }

    private companion object {
        val minimalSegment = TranscriptSegment(
            segmentId = "item_a1",
            index = 0,
            epoch = 1791297000000L,
            seq = 1,
            rev = 0,
            speaker = "agent",
            speakerName = null,
            text = "Hello",
            isFinal = false,
            interrupted = false,
            language = null,
            startedAt = "2026-10-06T14:30:03.100Z",
            endedAt = null,
        )

        const val MINIMAL_SEGMENT_JSON: String =
            """{"segmentId":"item_a1","index":0,"epoch":1791297000000,"seq":1,"rev":0,"speaker":"agent",""" +
                """"text":"Hello","final":false,"interrupted":false,"startedAt":"2026-10-06T14:30:03.100Z"}"""
    }
}
