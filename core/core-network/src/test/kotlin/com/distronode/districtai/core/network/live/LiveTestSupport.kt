package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.model.TranscriptEndedData
import com.distronode.districtai.core.model.TranscriptErrorData
import com.distronode.districtai.core.model.TranscriptRetractedData
import com.distronode.districtai.core.model.TranscriptSegment
import com.distronode.districtai.core.model.TranscriptSegmentData
import com.distronode.districtai.core.model.TranscriptSnapshotData
import java.io.File

/** The call every reducer test is about. */
internal const val CALL = "call_1"

/** The epoch most frames use. */
internal const val EPOCH = 1_000L

@Suppress("LongParameterList")
internal fun segment(
    id: String,
    index: Int,
    seq: Int,
    epoch: Long = EPOCH,
    rev: Int = 0,
    isFinal: Boolean = true,
    speaker: String = "caller",
    speakerName: String? = null,
    text: String = "line $id",
    interrupted: Boolean = false,
): TranscriptSegment = TranscriptSegment(
    segmentId = id,
    index = index,
    epoch = epoch,
    seq = seq,
    rev = rev,
    speaker = speaker,
    speakerName = speakerName,
    text = text,
    isFinal = isFinal,
    interrupted = interrupted,
    language = "en",
    startedAt = "2026-10-06T14:30:03.100Z",
    endedAt = if (isFinal) "2026-10-06T14:30:06.300Z" else null,
)

internal fun segmentEvent(segment: TranscriptSegment, callId: String = CALL): TranscriptEvent =
    TranscriptEvent.Segment(TranscriptSegmentData(v = 1, callId = callId, segment = segment))

@Suppress("LongParameterList")
internal fun snapshotEvent(
    segments: List<TranscriptSegment> = emptyList(),
    live: Boolean = true,
    endedReason: String? = null,
    complete: Boolean = true,
    epoch: Long? = EPOCH,
    lastSeq: Int? = segments.maxOfOrNull { it.seq } ?: 0,
    part: Int = 0,
    more: Boolean = false,
): TranscriptEvent = TranscriptEvent.Snapshot(
    TranscriptSnapshotData(
        v = 1,
        callId = CALL,
        live = live,
        endedReason = endedReason,
        complete = complete,
        epoch = epoch,
        lastSeq = lastSeq,
        segments = segments,
        part = part,
        more = more,
    ),
)

internal fun endedEvent(seq: Int, reason: String, epoch: Long = EPOCH, lastIndex: Int? = null): TranscriptEvent =
    TranscriptEvent.Ended(
        TranscriptEndedData(v = 1, callId = CALL, epoch = epoch, seq = seq, lastIndex = lastIndex, reason = reason),
    )

internal fun retractedEvent(
    ids: List<String> = emptyList(),
    all: Boolean = false,
    epoch: Long? = null,
    seq: Int? = null,
): TranscriptEvent = TranscriptEvent.Retracted(
    TranscriptRetractedData(
        v = 1,
        callId = CALL,
        epoch = epoch,
        seq = seq,
        all = all,
        segmentIds = ids,
        reason = "erased"
    ),
)

internal fun errorEvent(
    code: String,
    op: String? = TranscriptClientOp.SUBSCRIBE_OP,
    retryAfterMs: Long? = null,
    callId: String? = CALL,
): TranscriptEvent = TranscriptEvent.Error(
    TranscriptErrorData(v = 1, callId = callId, op = op, code = code, retryAfterMs = retryAfterMs),
)

/** A contract fixture's text, from the repo's `contracts/` (see the module's build file). */
internal fun contract(name: String): String =
    File(requireNotNull(System.getProperty("district.contracts.dir")), name).readText()
