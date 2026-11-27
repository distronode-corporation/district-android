// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * District HQ by voice: the answers of the voice routes under `/api/district/hq/`, read off the
 * iOS client (district-core-swift `HQVoiceResponses.swift`), which reads them off the desktop core.
 *
 * ⛔ A ROOM IS JOINED ONLY WHEN IT IS AN HQ ROOM AND ENCRYPTED ([HqVoiceTokenResponse.isJoinable]),
 * and only on the member's action: each token starts a billed session of at most
 * [HqVoice.SESSION_SECONDS].
 */
object HqVoice {
    /** Every HQ voice room's name starts with this; the client never builds one. */
    const val ROOM_PREFIX: String = "hq_"

    /** The data topic the agent sends proposals and their resolutions on. */
    const val PROPOSAL_TOPIC: String = "hq.proposal"

    /** The text-stream topic of the transcription. */
    const val TRANSCRIPTION_TOPIC: String = "lk.transcription"

    /** The stream attribute naming one transcribed segment. */
    const val SEGMENT_ID_ATTRIBUTE: String = "lk.segment_id"

    /** The stream attribute naming the transcribed track: the member's own microphone means their words. */
    const val TRANSCRIBED_TRACK_ID_ATTRIBUTE: String = "lk.transcribed_track_id"

    /** The agent participant's attribute carrying [HqAgentState]. */
    const val AGENT_STATE_ATTRIBUTE: String = "lk.agent.state"

    /** How long a session lasts. Each token starts one, billed. */
    const val SESSION_SECONDS: Long = 30L * 60L

    /** An agent that has not joined within this long is not coming: the session is discarded. */
    const val AGENT_JOIN_SECONDS: Long = 20L

    /** The most earlier console turns a token request carries. */
    const val HISTORY_MAX_TURNS: Int = 6

    /** The most characters of one turn a token request carries. */
    const val HISTORY_MAX_CHARACTERS: Int = 500

    /** The service's `code` when the workspace has used its voice minutes. The text console still works. */
    const val ALLOWANCE_REACHED_CODE: String = "hq_voice_allowance_reached"

    /** The service's `code` when voice mode is at capacity. */
    const val BUSY_CODE: String = "hq_voice_busy"

    /**
     * The turns a token request carries: the LAST [HISTORY_MAX_TURNS] with non-blank text, each
     * trimmed and cut to [HISTORY_MAX_CHARACTERS], as the service bounds them (and as iOS does).
     */
    fun boundedHistory(turns: List<HqTurn>): List<HqTurn> =
        turns.mapNotNull { turn ->
            val text = turn.text.trim()
            if (text.isEmpty()) null else HqTurn(turn.role, text.cut(HISTORY_MAX_CHARACTERS))
        }.takeLast(HISTORY_MAX_TURNS)

    /** ⚠️ Never splits a surrogate pair: a lone half would reach the wire as a replacement character. */
    private fun String.cut(max: Int): String {
        val cut = take(max)
        return if (cut.length == max && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }
}

/**
 * `POST /api/district/hq/voice-token`: a credential for one HQ voice session.
 *
 * ⛔ [token], [sessionId] AND THE KEY ARE SECRETS: [toString] leaves them out, so logging this value
 * never prints them.
 */
@Serializable
data class HqVoiceTokenResponse(
    val success: Boolean = false,
    val token: String = "",
    /** The server to join, exactly as sent. */
    val url: String = "",
    /** `hq_<workspace>_<uuid>`, new for each session. */
    val roomName: String = "",
    /**
     * Where the voice is processed (`us` today). Shown before the microphone starts: for a ca, eu
     * or apac workspace it is the disclosure that voice leaves the region.
     */
    val processingRegion: String = "",
    /** `push`, or anything else for an open microphone; see [micModeValue]. */
    val micMode: String = "",
    val sessionId: String = "",
    val e2ee: HqVoiceE2ee? = null,
) {
    /**
     * Whether the room may be joined: an HQ room, a non-blank key, and the token, server and session
     * id to join and end it with. A room without a key is never joined.
     */
    val isJoinable: Boolean
        get() = roomName.startsWith(HqVoice.ROOM_PREFIX) && !e2ee?.key.isNullOrBlank() &&
            listOf(token, url, sessionId).none { it.isBlank() }

    val micModeValue: HqMicMode get() = HqMicMode.fromWire(micMode)

    /** The key a join hands to the media SDK; empty only for a credential that is not [isJoinable]. */
    val joinKey: String get() = e2ee?.key.orEmpty()

    override fun toString(): String {
        val key = if (e2ee == null) "none" else "<redacted>"
        return "HqVoiceTokenResponse(roomName=$roomName, url=$url, processingRegion=$processingRegion, " +
            "micMode=$micMode, token=<redacted>, sessionId=<redacted>, e2ee=$key)"
    }
}

/** The room's passphrase, handed to the media SDK verbatim. */
@Serializable
data class HqVoiceE2ee(val key: String = "") {
    override fun toString(): String = "HqVoiceE2ee(key=<redacted>)"
}

/** How the member's microphone behaves in a session. */
enum class HqMicMode {
    /** Muted until held. */
    PUSH,

    /** Open while the session is live. */
    OPEN,
    ;

    companion object {
        /** [PUSH] only for `push`; an open microphone for anything else, an unknown value included. */
        fun fromWire(wire: String): HqMicMode = if (wire == "push") PUSH else OPEN
    }
}

/**
 * The member's own HQ voice settings in one workspace.
 *
 * ⚠️ THE VALUES ARE THE SERVICE'S AND KEPT AS STRINGS, so a value this build does not list is
 * kept and saved back as it is, never rewritten.
 */
@Serializable
data class HqVoiceSettings(
    val voice: String = "",
    val length: String = "",
    val pace: String = "",
    val language: String = "",
    val micMode: String = "",
)

/** `GET` and `PUT /api/district/hq/voice-settings`. */
@Serializable
data class HqVoiceSettingsResponse(
    val success: Boolean = false,
    val settings: HqVoiceSettings = HqVoiceSettings(),
)

/** `POST /api/district/hq/voice-context`: the screen was recorded. */
@Serializable
data class HqVoiceContextResponse(val success: Boolean = false)

/** `POST /api/district/hq/proposals/cancel`: the proposal was cancelled. */
@Serializable
data class HqProposalCancelResponse(val success: Boolean = false)

/**
 * `POST /api/district/hq/voice-end`: whether this call ended the session. ⚠️ False when it had
 * already ended, was discarded, or is not the member's: the route is idempotent.
 */
@Serializable
data class HqVoiceEndResponse(
    val success: Boolean = false,
    val ended: Boolean = false,
)

/** `POST /api/district/hq/voice-discard`: whether the session was discarded (false once started, or after 90 s). */
@Serializable
data class HqVoiceDiscardResponse(
    val success: Boolean = false,
    val discarded: Boolean = false,
)
