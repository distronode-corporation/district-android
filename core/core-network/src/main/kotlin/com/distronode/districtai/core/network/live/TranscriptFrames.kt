package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_ENDED
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_ERROR
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_RETRACTED
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_SEGMENT
import com.distronode.districtai.core.model.TRANSCRIPT_EVENT_SNAPSHOT
import com.distronode.districtai.core.model.TelemetryEvent
import com.distronode.districtai.core.model.TranscriptEndedData
import com.distronode.districtai.core.model.TranscriptErrorData
import com.distronode.districtai.core.model.TranscriptRetractedData
import com.distronode.districtai.core.model.TranscriptSegmentData
import com.distronode.districtai.core.model.TranscriptSnapshotData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The three ops a client sends on the telemetry socket, as text frames.
 *
 * ⛔ A SUBSCRIPTION IS PER CONNECTION. After any reconnect or renewal every subscription is sent
 * again; [TelemetryConnection] does that.
 */
sealed interface TranscriptClientOp {
    /** Start receiving one call's transcript. The server answers with a snapshot. */
    data class Subscribe(val callId: String) : TranscriptClientOp

    /** Stop receiving it. */
    data class Unsubscribe(val callId: String) : TranscriptClientOp

    /** `broadcast: false` stops the workspace-wide `call_*` and `message_*` relay to this socket. */
    data class SocketMode(val broadcast: Boolean) : TranscriptClientOp

    /**
     * The frame to send, or null for a call id the server would refuse.
     *
     * ⚠️ WRITTEN OUT RATHER THAN ENCODED, in the contract's key order. Safe only because a valid
     * call id holds nothing JSON escapes, which is why it is validated first.
     */
    val text: String?
        get() = when (this) {
            is Subscribe -> op(SUBSCRIBE_OP, callId)
            is Unsubscribe -> op(UNSUBSCRIBE_OP, callId)
            is SocketMode -> """{"op":"socket.mode","v":$VERSION,"broadcast":$broadcast}"""
        }

    companion object {
        /** The `transcript` payload version this client speaks. */
        const val VERSION: Int = 1

        /** The subscribe op's name, which a `transcript_error` echoes. */
        const val SUBSCRIBE_OP: String = "transcript.subscribe"
        private const val UNSUBSCRIBE_OP = "transcript.unsubscribe"
        private const val MAX_CALL_ID_LENGTH = 64

        /** Whether [callId] is one the server accepts: 1 to 64 of `A-Z a-z 0-9 _ -`. */
        fun isValidCallId(callId: String): Boolean =
            callId.length in 1..MAX_CALL_ID_LENGTH &&
                callId.all { TelemetryProtocol.isAsciiAlphanumeric(it) || it == '_' || it == '-' }

        private fun op(name: String, callId: String): String? =
            if (isValidCallId(callId)) """{"op":"$name","v":$VERSION,"callId":"$callId"}""" else null
    }
}

/**
 * One transcript frame from the telemetry socket, decoded.
 *
 * ⚠️ AN `eventType` THIS CLIENT DOES NOT KNOW IS DROPPED, NEVER THROWN AT (see [TelemetryEvent]):
 * the socket also relays call and message events, and the server adds kinds over time.
 */
sealed interface TranscriptEvent {
    /** The call the frame is about. `""` on a call-less error. */
    val callId: String

    data class Snapshot(val data: TranscriptSnapshotData) : TranscriptEvent {
        override val callId: String get() = data.callId
    }

    data class Segment(val data: TranscriptSegmentData) : TranscriptEvent {
        override val callId: String get() = data.callId
    }

    data class Ended(val data: TranscriptEndedData) : TranscriptEvent {
        override val callId: String get() = data.callId
    }

    data class Retracted(val data: TranscriptRetractedData) : TranscriptEvent {
        override val callId: String get() = data.callId
    }

    data class Error(val data: TranscriptErrorData) : TranscriptEvent {
        override val callId: String get() = data.callId.orEmpty()
    }
}

/** What one text message from the socket turned out to be. */
sealed interface TelemetryMessage {
    /** A transcript frame for this workspace. */
    data class Transcript(val event: TranscriptEvent) : TelemetryMessage

    /** An envelope for this workspace that is not a transcript frame (a call or message event). */
    data object Other : TelemetryMessage

    /**
     * Not an envelope this client can read, or one naming another workspace.
     *
     * ⛔ ITS CONTENT IS DROPPED UNREAD. Call events are customer data; nothing about an unreadable
     * one is kept, logged or passed on.
     */
    data object Discarded : TelemetryMessage
}

/** Decodes socket messages for one workspace. */
object TelemetryMessages {
    private val json = Json { ignoreUnknownKeys = true }
    private val envelope = TelemetryEvent.serializer(JsonElement.serializer())

    /** Read [text] as an envelope for [workspaceId]. */
    fun read(text: String, workspaceId: String): TelemetryMessage {
        val frame = try {
            json.decodeFromString(envelope, text)
        } catch (_: IllegalArgumentException) {
            // ⚠️ `SerializationException` IS an `IllegalArgumentException`: one catch for both.
            return TelemetryMessage.Discarded
        }
        if (frame.workspaceId != workspaceId) return TelemetryMessage.Discarded
        return try {
            transcript(frame)?.let(TelemetryMessage::Transcript) ?: TelemetryMessage.Other
        } catch (_: IllegalArgumentException) {
            // ⚠️ A STRUCTURALLY WRONG TRANSCRIPT PAYLOAD IS REFUSED WHOLE (see `TranscriptSegment`):
            // the next snapshot heals it.
            TelemetryMessage.Discarded
        }
    }

    private fun transcript(frame: TelemetryEvent<JsonElement>): TranscriptEvent? {
        val data = frame.data
        return when (frame.eventType) {
            TRANSCRIPT_EVENT_SNAPSHOT ->
                TranscriptEvent.Snapshot(json.decodeFromJsonElement(TranscriptSnapshotData.serializer(), data))
            TRANSCRIPT_EVENT_SEGMENT ->
                TranscriptEvent.Segment(json.decodeFromJsonElement(TranscriptSegmentData.serializer(), data))
            TRANSCRIPT_EVENT_ENDED ->
                TranscriptEvent.Ended(json.decodeFromJsonElement(TranscriptEndedData.serializer(), data))
            TRANSCRIPT_EVENT_RETRACTED ->
                TranscriptEvent.Retracted(json.decodeFromJsonElement(TranscriptRetractedData.serializer(), data))
            TRANSCRIPT_EVENT_ERROR ->
                TranscriptEvent.Error(json.decodeFromJsonElement(TranscriptErrorData.serializer(), data))
            else -> null
        }
    }
}
