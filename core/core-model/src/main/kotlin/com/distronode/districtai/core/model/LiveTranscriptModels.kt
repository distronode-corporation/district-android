package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One frame from the telemetry socket (`/ws/telemetry`): the five-key envelope every server frame
 * carries, with the event's payload in [data].
 *
 * ⛔ THE ENVELOPE IS FIXED AT FIVE KEYS, AND EVERYTHING NEW LIVES IN [data]. The server's wire
 * contract (`docs/design/live_transcript_contract.md` §4.1) keeps `{workspaceId, callId, eventType,
 * data, timestamp}` exactly as the existing call events have it, so a sixth top-level key would be
 * a breaking change on the server's side rather than an additive one here.
 *
 * ⚠️ GENERIC OVER [T] SO ONE TYPE SERVES EVERY EVENT. A reader that does not yet know the event
 * decodes with `TelemetryEvent.serializer(JsonElement.serializer())`, reads [eventType], and then
 * decodes [data] with the payload's own serializer ([TranscriptSegmentData] and its siblings).
 * There is deliberately no polymorphic dispatcher here: an `eventType` this client does not know
 * must be dropped by the reader, never thrown at, and that is a decision for the socket client
 * rather than for the model.
 *
 * ⚠️ [callId] IS `""` ON A CALL-LESS FRAME (§4.12 Q3), such as a [TranscriptErrorData] answering
 * a malformed op. It stays a string so the envelope keeps its shape; key nothing on the empty one.
 */
@Serializable
data class TelemetryEvent<T>(
    val workspaceId: String,
    /** The `Call.id`, repeated as `data.callId` on every transcript payload. `""` when call-less. */
    val callId: String,
    /** One of the `TRANSCRIPT_EVENT_*` constants for the payloads this module models. */
    val eventType: String,
    val data: T,
    /** ISO 8601 UTC with milliseconds. When the server sent the frame, not when anything was said. */
    val timestamp: String,
)

/** `transcript_snapshot`: the whole buffered transcript, in reply to every `transcript.subscribe`. */
const val TRANSCRIPT_EVENT_SNAPSHOT: String = "transcript_snapshot"

/** `transcript_segment`: one new or updated [TranscriptSegment]. */
const val TRANSCRIPT_EVENT_SEGMENT: String = "transcript_segment"

/** `transcript_ended`: the agent stopped transcribing this call. */
const val TRANSCRIPT_EVENT_ENDED: String = "transcript_ended"

/** `transcript_retracted`: content that must come off the screen (contact erase, policy). */
const val TRANSCRIPT_EVENT_RETRACTED: String = "transcript_retracted"

/** `transcript_error`: a subscribe or op the server could not honour. */
const val TRANSCRIPT_EVENT_ERROR: String = "transcript_error"

/**
 * One utterance of a live call transcript (§4.5), inside [TranscriptSegmentData] and
 * [TranscriptSnapshotData.segments].
 *
 * ⛔ EVERY NON-NULL KEY IS REQUIRED, UNLIKE MOST RESPONSE DTOS IN THIS MODULE. A segment that
 * defaulted its way through a structurally wrong frame would render as a line with no text at
 * index 0 of epoch 0, and the ordering and dedupe state the client keeps (§4.6) would then be
 * wrong for every frame after it. Refusing the frame is the contained failure: the next snapshot
 * heals it.
 *
 * ⚠️ ORDER IS `(epoch, index)`, NEVER `seq`. [seq] counts every message the agent publishes in
 * that epoch (updates, retractions and the ended frame too), so it is for dedupe and gap detection,
 * not for display.
 */
@Serializable
data class TranscriptSegment(
    /** Stable within the call. An interim keeps its id when it becomes final. */
    val segmentId: String,
    /** Display order within [epoch], assigned when the segment opens and never reused. */
    val index: Int,
    /** The agent session that produced the segment (its start, Unix ms). */
    val epoch: Long,
    /** Per `(callId, epoch)`, 1 or more, incremented on every agent message. */
    val seq: Int,
    /** Revision of this segment. The final carries the highest. */
    val rev: Int,
    /** The raw wire role. Read it through [speakerRole]; see [TranscriptSpeaker] for why. */
    val speaker: String,
    /** The persona name for an agent line. Always null for the caller. */
    val speakerName: String?,
    /** What was said, 1 to 2000 UTF-16 units. ⛔ Never logged (§4.8): lengths and counts only. */
    val text: String,
    /** `false` is an interim hypothesis that will be replaced; `true` is frozen. */
    @SerialName("final")
    val isFinal: Boolean,
    /** For an agent final: the speech was cut off and [text] is what was actually played. */
    val interrupted: Boolean,
    /** BCP-47 primary subtag (`en`, `fr`), or null when neither STT nor the persona knows it. */
    val language: String?,
    /** ISO 8601 UTC with milliseconds. */
    val startedAt: String,
    /** Null while interim. */
    val endedAt: String?,
) {
    /** The wire role, with every value this client does not know read as [TranscriptSpeaker.OTHER]. */
    val speakerRole: TranscriptSpeaker
        get() = TranscriptSpeaker.fromWire(speaker)
}

/**
 * Who said a [TranscriptSegment].
 *
 * ⚠️ NOT A `@Serializable` ENUM, FOR THE REASON [WorkspaceRole] IS NOT ONE. The contract reserves
 * `human_agent` and `supervisor` for v1.x without bumping `v` (§4.5), so a value this build does
 * not know is a planned arrival rather than a malformed frame. Decoding an enum directly would
 * throw and lose the whole frame, and with it the seq the client needs for gap detection; parsing
 * through [fromWire] keeps the line and gives it a neutral label.
 *
 * ⚠️ The label is localised by the client; the wire value never is.
 */
enum class TranscriptSpeaker {
    /** The person who called (or was called). */
    CALLER,

    /** The AI agent. [TranscriptSegment.speakerName] carries the persona's name. */
    AGENT,

    /** Any other role, including the reserved `human_agent` and `supervisor`. */
    OTHER,
    ;

    companion object {
        /** Exact, case-sensitive: wire values never localise, so `"Caller"` is not `"caller"`. */
        fun fromWire(raw: String): TranscriptSpeaker = when (raw) {
            "caller" -> CALLER
            "agent" -> AGENT
            else -> OTHER
        }
    }
}

/** The payload of a `transcript_segment` frame: `{v, callId, segment}`. */
@Serializable
data class TranscriptSegmentData(
    /** The `transcript` payload version. This client reads `1`. */
    val v: Int,
    val callId: String,
    val segment: TranscriptSegment,
)

/**
 * The payload of a `transcript_snapshot` frame: the buffered transcript, possibly in parts.
 *
 * ⛔ PARTS ARE A UNION, NOT A SEQUENCE OF REPLACEMENTS. The client replaces its state for the call
 * with the union of every part's [segments] once the part with [more] `false` arrives (§4.5). The
 * other fields repeat, identical, on every part (§4.12 Q6).
 *
 * ⚠️ [epoch] AND [lastSeq] ARE BOTH NULL AFTER AN `all:true` PURGE (§4.12 Q12), which means
 * "purged", not "nothing yet": the server never sends an empty `lastSeq:0` snapshot.
 */
@Serializable
data class TranscriptSnapshotData(
    val v: Int,
    val callId: String,
    /** `false` once a `transcript_ended` is buffered. */
    val live: Boolean,
    /** The buffered ended frame's reason (`TRANSCRIPT_ENDED_*`), null whenever [live] is true. */
    val endedReason: String?,
    /** `false` when earlier lines were evicted or missed; the post-call transcript has them. */
    val complete: Boolean,
    /** The newest epoch in the buffer, which [lastSeq] covers. */
    val epoch: Long?,
    /** The highest seq seen in [epoch]. A later frame at or below it is a duplicate. */
    val lastSeq: Int?,
    /** The latest revision of each segment, ordered by `(epoch, index)`. */
    val segments: List<TranscriptSegment>,
    /** 0-based part number. */
    val part: Int,
    /** `true` until the last part. */
    val more: Boolean,
)

/**
 * The payload of a `transcript_ended` frame.
 *
 * ⚠️ ONLY [TRANSCRIPT_ENDED_AGENT_ERROR] MAY BE FOLLOWED BY A NEW EPOCH (§4.12 Q7). The client shows
 * "reconnecting" after it and "ended" after the other two, which are terminal.
 */
@Serializable
data class TranscriptEndedData(
    val v: Int,
    val callId: String,
    val epoch: Long,
    val seq: Int,
    /** The last segment index in [epoch], or null when the epoch produced none. */
    val lastIndex: Int?,
    /** One of the `TRANSCRIPT_ENDED_*` constants. */
    val reason: String,
)

/** `transcript_ended.reason` (and `transcript_snapshot.endedReason`): the call ended. Terminal. */
const val TRANSCRIPT_ENDED_CALL_ENDED: String = "call_ended"

/** The call was handed off to a human. Terminal. */
const val TRANSCRIPT_ENDED_HANDED_OFF: String = "handed_off"

/** The agent failed; a re-dispatched agent may start a new epoch. */
const val TRANSCRIPT_ENDED_AGENT_ERROR: String = "agent_error"

/**
 * The payload of a `transcript_retracted` frame: text that must come off the screen.
 *
 * ⛔ [epoch] AND [seq] ARE BOTH SET OR BOTH NULL (§4.12 Q2). Both are null when the website sent it
 * (a contact erase or the retention sweep), and such a retraction bypasses seq dedupe and is
 * applied idempotently. With [all] true the client drops everything it holds for the call.
 */
@Serializable
data class TranscriptRetractedData(
    val v: Int,
    val callId: String,
    val epoch: Long?,
    val seq: Int?,
    val all: Boolean,
    /** The segments to drop when [all] is false. */
    val segmentIds: List<String>,
    /** `erased` (a contact erase) or `policy` (the retention sweep). */
    val reason: String,
)

/**
 * The payload of a `transcript_error` frame (§4.7). It never closes the socket.
 *
 * ⚠️ [code] IS MATCHED, NEVER ANY SENTENCE: the `TRANSCRIPT_ERROR_*` constants are the whole
 * vocabulary, and a code this build does not know should read as "no live transcript" and fall
 * back to the post-call path.
 */
@Serializable
data class TranscriptErrorData(
    val v: Int,
    /** Null when the refused frame named no call (malformed, or `socket.mode`). */
    val callId: String?,
    /** The exact `op` string the client sent, or null when the frame had no string `op`. */
    val op: String?,
    /** One of the `TRANSCRIPT_ERROR_*` constants. */
    val code: String,
    /** Set on [TRANSCRIPT_ERROR_RATE_LIMITED]: how long to wait, in milliseconds. */
    val retryAfterMs: Long?,
)

/** Malformed JSON, an invalid `callId`, or a frame over 1 KB. A client bug: no retry. */
const val TRANSCRIPT_ERROR_BAD_REQUEST: String = "bad_request"

/** `v` was not 1. Fall back to the post-call transcript. */
const val TRANSCRIPT_ERROR_UNSUPPORTED_VERSION: String = "unsupported_version"

/**
 * No live transcript for this call within 30 s of the subscribe. Final for that subscribe.
 *
 * ⛔ NEVER WORDED AS "FORBIDDEN": another workspace's call is indistinguishable from a missing one
 * by construction, and the copy must not suggest otherwise.
 */
const val TRANSCRIPT_ERROR_NOT_LIVE: String = "not_live"

/** The member's role may not read live transcripts. Hide the live pane. */
const val TRANSCRIPT_ERROR_FORBIDDEN_ROLE: String = "forbidden_role"

/** More than 5 concurrent transcript subscriptions on one socket. */
const val TRANSCRIPT_ERROR_TOO_MANY_SUBSCRIPTIONS: String = "too_many_subscriptions"

/** More than 20 ops per 10 s on one socket. Wait [TranscriptErrorData.retryAfterMs]. */
const val TRANSCRIPT_ERROR_RATE_LIMITED: String = "rate_limited"

/**
 * `POST /api/district/telemetry/token`: the credential for one telemetry socket.
 *
 * ⛔ [wsUrl] IS USED VERBATIM. It is chosen by the workspace's region on the server, the same rule
 * [DialResponse.url] follows, so a client that built its own URL would connect to a broadcaster
 * that never sees this workspace's frames and would wait, silently, for a transcript that never
 * comes.
 *
 * ⚠️ [token] IS A CREDENTIAL. It travels in the `Sec-WebSocket-Protocol` header as
 * `distronode.token.<token>` and is never logged. Renew it before [expiresAt]: the server closes the
 * socket with 4401 at expiry, and every subscription is per connection, so a renewal re-sends each
 * `transcript.subscribe`.
 *
 * ⚠️ Defaults like [DialResponse]: a `{}` body decodes into an empty token and an [expiresAt] of 0,
 * which reads as already expired, so a caller must check [success] and the token before dialling.
 */
@Serializable
data class TelemetryTokenResponse(
    val success: Boolean = false,
    /** ⛔ A credential. Never logged. */
    val token: String = "",
    /** Unix ms. */
    val expiresAt: Long = 0L,
    /** ⛔ The region-chosen `wss://` URL. Used verbatim. */
    val wsUrl: String = "",
)
