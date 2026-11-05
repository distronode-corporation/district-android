package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/*
 * Live supervise: listening to a live AI call, whispering to the receptionist on it, and taking it
 * over by voice. The web's Live calls page (`useLiveDashboard`) does the same three things with the
 * same three routes.
 *
 * ⛔ THREE ROUTES AND THREE CREDENTIALS, NEVER ONE WITH A FLAG. `calls/token` mints a listen-only
 * supervisor token (no publish, no data), `calls/{id}/takeover` mints a microphone-only one with
 * the agent's barge-in mode in its metadata, and `calls/whisper` is a server-side send that needs
 * no room at all. A mixed-role token route is the 2026-08-01 incident (the agent unsubscribed a
 * caller's microphone), so the split is kept on this side too.
 */

/**
 * `POST /api/district/calls/token` with a `Call.id`: the listen-only credential for one live call.
 *
 * ⛔ [url] IS THE ROOM'S DEPLOYMENT, USED VERBATIM. The route resolves it by ROOM
 * (`getLivekitWsUrlForRoom`), because a phone call's room lives where the SIP bridge created it,
 * which is the US deployment for an EU workspace too. The workspace's own LiveKit URL would join a
 * deployment that has never heard of the room.
 *
 * ⚠️ Defaults like [DialResponse]: a `{}` body decodes into an empty token and url, so the caller
 * checks [success] and both values before joining.
 */
@Serializable
data class CallMonitorTokenResponse(
    val success: Boolean = false,
    /** ⛔ A credential. Never logged. */
    val token: String = "",
    /** ⛔ The room's deployment, `wss://`. Used verbatim. */
    val url: String = "",
)

/**
 * `POST /api/district/calls/{callId}/takeover`: the microphone-only credential that takes a live
 * call over from the AI ("Whisperers; AI steps back", Sean 2026-10-18).
 *
 * ⛔ [url] IS THE DEPLOYMENT THAT HOLDS THE ROOM, found by asking (`locateSipRoom`), used verbatim.
 * [roomName] is informational: nothing on this client builds or joins a room by name.
 */
@Serializable
data class CallTakeoverResponse(
    val success: Boolean = false,
    /** ⛔ The room's deployment. Used verbatim. */
    val url: String = "",
    /** ⛔ A credential. Never logged. */
    val token: String = "",
    val roomName: String = "",
)

/** `POST /api/district/calls/whisper`: the line was handed to the room. Only [success] comes back. */
@Serializable
data class CallWhisperResponse(
    val success: Boolean = false,
)

/** The listen body. ⛔ `roomName` CARRIES THE `Call.id`, the route's own naming, kept for its callers. */
@Serializable
data class CallMonitorTokenRequest(
    val roomName: String,
    /** Required by the route, and ignored by it: the identity is derived from the session. */
    val identity: String,
)

/** The takeover body. The call id travels in the path. */
@Serializable
data class CallTakeoverRequest(
    val workspaceId: String,
)

/** The whisper body. ⛔ The call id travels in the BODY: there is no per-call whisper path. */
@Serializable
data class CallWhisperRequest(
    val workspaceId: String,
    val callId: String,
    val whisper: String,
)

/** The route's `MAX_WHISPER_CHARS`: a longer line is a 400 `invalid_request`. */
const val MAX_WHISPER_CHARS: Int = 2000

/** The telemetry event that answers a whisper: `data` is a [WhisperAppliedData]. */
const val TELEMETRY_EVENT_WHISPER_APPLIED: String = "whisper_applied"

/** [WhisperAppliedData.reason] when a team member holds the call. */
const val WHISPER_NOT_APPLIED_TAKEN_OVER: String = "taken_over"

/**
 * The voice agent's receipt for a whisper (`whisper_applied`, Sean 2026-11-03).
 *
 * ⚠️ TWO SHAPES IN ONE TYPE, AS THE AGENT WRITES THEM: `{applied: true, firstWords}` when the
 * receptionist acted on it, `{applied: false, reason}` when it held it back (`transfer`,
 * `taken_over`). [reason] is wire vocabulary and is mapped to a sentence, never shown raw.
 *
 * ⛔ [firstWords] IS WHAT THE RECEPTIONIST SAID TO A CUSTOMER. Never logged.
 */
@Serializable
data class WhisperAppliedData(
    val applied: Boolean = false,
    val firstWords: String? = null,
    val reason: String? = null,
)
