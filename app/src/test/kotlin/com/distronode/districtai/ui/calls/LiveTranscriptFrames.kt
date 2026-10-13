package com.distronode.districtai.ui.calls

import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_ENDED
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_ERROR
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_SEGMENT
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_SNAPSHOT
import com.distronode.districtai.core.model.TelemetryEvent
import com.distronode.districtai.core.model.TranscriptEndedData
import com.distronode.districtai.core.model.TranscriptErrorData
import com.distronode.districtai.core.model.TranscriptSegment
import com.distronode.districtai.core.model.TranscriptSegmentData
import com.distronode.districtai.core.model.TranscriptSnapshotData
import com.distronode.districtai.core.network.live.TelemetrySocketFrame
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Socket frames for the live transcript tests, encoded the way the server sends them. */
internal object LiveTranscriptFrames {
    const val WORKSPACE = "ws-1"
    const val CALL = "c1"
    const val EPOCH = 1_000L

    @Suppress("LongParameterList")
    fun line(
        id: String,
        index: Int,
        seq: Int,
        speaker: String = "caller",
        speakerName: String? = null,
        text: String = "line $id",
        isFinal: Boolean = true,
        interrupted: Boolean = false,
    ) = TranscriptSegment(
        segmentId = id,
        index = index,
        epoch = EPOCH,
        seq = seq,
        rev = 0,
        speaker = speaker,
        speakerName = speakerName,
        text = text,
        isFinal = isFinal,
        interrupted = interrupted,
        language = "en",
        startedAt = "2026-10-06T14:30:03.100Z",
        endedAt = null,
    )

    fun snapshot(vararg lines: TranscriptSegment, complete: Boolean = true): TelemetrySocketFrame =
        frame(
            TRANSCRIPT_EVENT_SNAPSHOT,
            TranscriptSnapshotData.serializer(),
            TranscriptSnapshotData(
                v = 1,
                callId = CALL,
                live = true,
                endedReason = null,
                complete = complete,
                epoch = EPOCH,
                lastSeq = lines.maxOfOrNull { it.seq } ?: 0,
                segments = lines.toList(),
                part = 0,
                more = false,
            ),
        )

    fun segment(line: TranscriptSegment): TelemetrySocketFrame =
        frame(TRANSCRIPT_EVENT_SEGMENT, TranscriptSegmentData.serializer(), TranscriptSegmentData(1, CALL, line))

    fun ended(seq: Int, reason: String): TelemetrySocketFrame = frame(
        TRANSCRIPT_EVENT_ENDED,
        TranscriptEndedData.serializer(),
        TranscriptEndedData(v = 1, callId = CALL, epoch = EPOCH, seq = seq, lastIndex = null, reason = reason),
    )

    fun error(code: String, retryAfterMs: Long? = null): TelemetrySocketFrame = frame(
        TRANSCRIPT_EVENT_ERROR,
        TranscriptErrorData.serializer(),
        TranscriptErrorData(
            v = 1,
            callId = CALL,
            op = "transcript.subscribe",
            code = code,
            retryAfterMs = retryAfterMs
        ),
    )

    /** A call event the transcript has no use for. */
    fun callEvent(): TelemetrySocketFrame = TelemetrySocketFrame.Text(
        """{"workspaceId":"$WORKSPACE","callId":"$CALL","eventType":"call_updated","data":{},"timestamp":"t"}""",
    )

    private fun <T> frame(type: String, serializer: KSerializer<T>, data: T): TelemetrySocketFrame =
        TelemetrySocketFrame.Text(
            Json.encodeToString(
                TelemetryEvent.serializer(serializer),
                TelemetryEvent(WORKSPACE, CALL, type, data, "2026-10-06T14:30:41.000Z"),
            ),
        )
}
