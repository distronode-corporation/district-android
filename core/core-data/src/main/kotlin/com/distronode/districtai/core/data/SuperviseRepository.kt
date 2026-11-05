package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ActiveCall
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SuperviseApi

/**
 * Live supervise: the Live calls list, and listening to, whispering into and taking over one call.
 *
 * ⛔ NO RETRY LIVES HERE AND NONE MAY BE ADDED. Each action is sent once and its failure is handed
 * back as it came: a whisper re-sent after a 5xx may be spoken twice, and a listen or takeover
 * re-sent mints a second credential into a customer's call. See [SuperviseApi].
 *
 * ⛔ AN ID WITH AN UNDERSCORE IS REFUSED HERE, BEFORE ANY REQUEST. `calls/token` reads a `meet_` or
 * `video_` value as a standalone ROOM rather than a call, and a `video_` join starts a billed avatar
 * session; a `direct_` value is a room name, never a call id. A `Call.id` is a cuid and never holds
 * one, so the rule costs no real call and closes all three at once.
 *
 * ⛔ A 200 THAT DOES NOT AFFIRM `success: true`, OR A GRANT WITH A BLANK TOKEN OR URL, IS A FAILURE.
 * Every field of the grants defaults, so a `{}` body would otherwise decode into a credential the
 * engine fails on later, on a screen already saying "connecting".
 *
 * ⚠️ REFUSALS KEEP THE SERVER'S CODE. `call_ended`, `call_has_no_receptionist` and
 * `call_not_in_progress` arrive as the failure's `code`, and the screen renders them through the
 * shared failure mapper, so each reads in the catalog's words once the catalog has them.
 */
class SuperviseRepository(private val api: SuperviseApi) {

    /** The workspace's calls in progress. ⚠️ A 200 without `success: true` is drift, not "no calls". */
    suspend fun activeCalls(workspaceId: String): ApiResult<List<ActiveCall>> =
        when (val result = api.activeCalls(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(ACTIVE_ENVELOPE, result.value.success) ?: ApiResult.Success(result.value.calls)
            is ApiResult.Failure -> result
        }

    /** The listen-only credential for [callId]. */
    suspend fun monitor(callId: String): SuperviseOutcome<LiveCallGrant> {
        if (!isSupervisableId(callId)) return SuperviseOutcome.NotACall
        return when (val result = api.monitorCall(callId)) {
            is ApiResult.Success -> grant(MONITOR_ENVELOPE, result.value.success, result.value.url, result.value.token)
            is ApiResult.Failure -> SuperviseOutcome.Refused(result)
        }
    }

    /** The microphone-only credential that takes [callId] over from the AI. */
    suspend fun takeOver(workspaceId: String, callId: String): SuperviseOutcome<LiveCallGrant> {
        if (!isSupervisableId(callId)) return SuperviseOutcome.NotACall
        return when (val result = api.takeOverCall(workspaceId, callId)) {
            is ApiResult.Success -> grant(TAKEOVER_ENVELOPE, result.value.success, result.value.url, result.value.token)
            is ApiResult.Failure -> SuperviseOutcome.Refused(result)
        }
    }

    /** One line to the receptionist on [callId]. ⚠️ The caller trims and bounds [text]. */
    suspend fun whisper(workspaceId: String, callId: String, text: String): SuperviseOutcome<Unit> {
        if (!isSupervisableId(callId)) return SuperviseOutcome.NotACall
        return when (val result = api.whisper(workspaceId, callId, text)) {
            is ApiResult.Success -> rejectedEnvelope(WHISPER_ENVELOPE, result.value.success)
                ?.let { SuperviseOutcome.Refused(it) }
                ?: SuperviseOutcome.Done(Unit)
            is ApiResult.Failure -> SuperviseOutcome.Refused(result)
        }
    }

    private fun grant(envelope: String, success: Boolean, url: String, token: String): SuperviseOutcome<LiveCallGrant> {
        rejectedEnvelope(envelope, success)?.let { return SuperviseOutcome.Refused(it) }
        if (url.isBlank() || token.isBlank()) {
            return SuperviseOutcome.Refused(
                ApiResult.DecodeFailure(
                    IllegalStateException("$envelope carried no join credential"),
                    "$envelope{token=${token.isNotBlank()},url=${url.isNotBlank()}}",
                ),
            )
        }
        return SuperviseOutcome.Done(LiveCallGrant(url = url, token = token))
    }

    companion object {
        private const val ACTIVE_ENVELOPE = "ActiveCallsResponse"
        private const val MONITOR_ENVELOPE = "CallMonitorTokenResponse"
        private const val TAKEOVER_ENVELOPE = "CallTakeoverResponse"
        private const val WHISPER_ENVELOPE = "CallWhisperResponse"

        /** A `Call.id`: not blank, and no underscore (see the class). */
        fun isSupervisableId(callId: String): Boolean = callId.isNotBlank() && '_' !in callId
    }
}

/**
 * A credential to join one live call's room.
 *
 * ⛔ [url] IS THE ROOM'S DEPLOYMENT, AS THE ROUTE ANSWERED IT, AND IS USED VERBATIM: never the
 * workspace's default LiveKit URL, which for a phone call is often not where the room is.
 * [token] is a credential and is never logged.
 */
data class LiveCallGrant(val url: String, val token: String) {
    override fun toString(): String = "LiveCallGrant(url=$url, token=<redacted>)"
}

/** What one supervise action came to. */
sealed interface SuperviseOutcome<out T> {
    data class Done<out T>(val value: T) : SuperviseOutcome<T>

    /** Refused locally: the id is not a call's. ⛔ Nothing was sent. */
    data object NotACall : SuperviseOutcome<Nothing>

    /** The server refused, the request failed, or the answer was unusable. Sent once, never retried. */
    data class Refused(val failure: ApiResult.Failure) : SuperviseOutcome<Nothing>
}
