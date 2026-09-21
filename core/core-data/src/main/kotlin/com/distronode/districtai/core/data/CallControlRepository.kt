package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CallControlApi

/**
 * Ending a live call at the CARRIER.
 *
 * ⛔ BEST EFFORT, AND THE CALLER MUST NEVER WAIT ON IT OR SHOW ITS RESULT. Every local teardown has
 * already happened by the time this is sent: the OS has been told, the socket is closing, and the
 * operator is looking at a call summary. A failure here is a log line. Rendering one would put an
 * error over a call that ended correctly from the user's point of view, and blocking on it would
 * hold the call screen open on a network timeout.
 *
 * ⛔ SAFE TO SEND TWICE, WHICH IS THE ONE WAY IT DIFFERS FROM [DialRepository]. `dial` may never be
 * re-sent because a re-send places a second call; this route is idempotent by contract and the
 * design leans on it, because the case it exists for is a hang-up racing a dial response that has
 * not arrived yet. ⚠️ That is a licence to send it on paths that may already have sent it, NOT a
 * licence to add a retry.
 */
class CallControlRepository(private val api: CallControlApi) {

    /**
     * ⛔ THE THREE REFUSALS ARE OUTCOMES RATHER THAN FAILURES. 404 ("no such call for this
     * workspace") and 409 ("not a direct softphone call") are answers: by the time this is sent the
     * call is over locally and there is nothing for either of them to change. Only a genuine fault
     * — offline, signed out, the viewer 403, a 5xx, contract drift — comes back as
     * [HangUpOutcome.NotEnded], and even that is recorded rather than shown.
     *
     * ⚠️ `ended: false` IS A SUCCESS, NOT A REFUSAL. It means no deployment reported the room, i.e.
     * it is already gone. ⛔ An unconfigured LiveKit is a **503** instead, because "I could not
     * check" must never be reported as "it is over" — and that lands in [HangUpOutcome.NotEnded].
     */
    suspend fun hangUp(workspaceId: String, callId: String): HangUpOutcome =
        when (val result = api.hangUpCall(workspaceId, callId)) {
            is ApiResult.Success ->
                rejectedEnvelope(HANG_UP_ENVELOPE, result.value.success)
                    ?.let { HangUpOutcome.NotEnded(it) }
                    ?: if (result.value.ended) HangUpOutcome.Ended else HangUpOutcome.AlreadyEnded
            is ApiResult.NotFound -> HangUpOutcome.AlreadyEnded
            is ApiResult.HttpFailure ->
                if (result.status == HTTP_CONFLICT) {
                    HangUpOutcome.NotDirectCall
                } else {
                    HangUpOutcome.NotEnded(result)
                }
            is ApiResult.Failure -> HangUpOutcome.NotEnded(result)
        }

    private companion object {
        const val HANG_UP_ENVELOPE = "CallHangUpResponse"
        const val HTTP_CONFLICT = 409
    }
}

/** What the server did with a hang-up request. */
sealed interface HangUpOutcome {

    /** The room was found and deleted, so the carrier received a BYE. */
    data object Ended : HangUpOutcome

    /**
     * ⚠️ A SUCCESS. No deployment held the room — the callee hung up, the room emptied, or a
     * previous hangup already ran — or the workspace cannot see the call at all. Both mean there is
     * nothing left to end.
     */
    data object AlreadyEnded : HangUpOutcome

    /**
     * ⚠️ A 409: the call is an AI campaign call or an inbound one, owned by the voice agent's own
     * finalization. Not a fault, and not something a screen can act on.
     */
    data object NotDirectCall : HangUpOutcome

    /**
     * ⛔ THE ONLY BRANCH WHERE A CARRIER LEG MAY STILL BE BILLING. Recorded for diagnostics; never
     * retried and never shown, because the operator has already finished with the call.
     */
    data class NotEnded(val failure: ApiResult.Failure) : HangUpOutcome
}
