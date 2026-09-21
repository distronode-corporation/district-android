package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallAnswerResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CallAnswerRequest
import com.distronode.districtai.core.network.InboundCallApi

/**
 * Taking a call this device is ringing on.
 *
 * ⛔ SEPARATE FROM [DialRepository] EVEN THOUGH BOTH END IN A LiveKit CREDENTIAL, AND THE SPLIT IS
 * THE SERVER'S OWN. `calls/answer` exists as a distinct route because folding a third case into
 * `calls/token` is how a supervisor token was once minted for a primary participant — the voice
 * agent's whisper handler unsubscribed the human's microphone, and the AI greeted somebody it could
 * not hear. Keeping the two repositories apart means a screen that answers cannot dial and a screen
 * that dials cannot answer.
 *
 * ⛔ AND THE RISK SHAPES ARE OPPOSITE, WHICH IS WORTH STATING BECAUSE THE CODE LOOKS ALIKE.
 * `DialRepository`'s warning is that a refusal after a 200 describes a call that is ALREADY
 * happening and billed. Here nothing is placed and nothing is billed: the call exists, somebody is
 * on it, and the only thing this call does is decide whether a human joins. What it DOES do is
 * write the Redis rendezvous the agent's transfer is blocked on — so calling it early, to pre-warm
 * a credential, would tell the agent a human took the call while the phone was still ringing in a
 * pocket. It is called from the ANSWER press and from nowhere else.
 *
 * ⛔ NO RETRY LIVES HERE AND NONE MAY BE ADDED. The single 401 retry inside `DistrictApiClient` is
 * safe (a 401 is refused before the handler runs, so the rendezvous was provably not written), but
 * a retry after a 5xx would race a transfer that may already have been released.
 *
 * ⚠️ THE TWO "ALREADY OVER" REFUSALS ARE COLLAPSED INTO ONE OUTCOME **HERE**, ONCE, RATHER THAN
 * MATCHED ON STATUS IN A ViewModel. 404 is a call this workspace cannot see (indistinguishable from
 * another tenant's, deliberately) and 409 is a call whose status is no longer answerable or that
 * never had a room. Both are the ordinary race — the caller hung up while the phone was ringing —
 * and both need the same sentence on screen.
 */
class InboundCallRepository(private val api: InboundCallApi) {

    /**
     * Answer [callId] in [workspaceId] and get the credential to join its room.
     *
     * ⚠️ THE `workspaceId` COMES FROM THE PUSH PAYLOAD, WHICH IS NOT A TRUST DECISION THIS CLIENT
     * IS MAKING. The server sent that push to devices belonging to members of that workspace, and
     * the route re-checks membership and the role against the bearer before it mints anything — so
     * an id echoed back from a payload cannot widen what this account may reach. It is carried
     * rather than derived because the app's SELECTED workspace need not be the one that is ringing.
     */
    suspend fun answer(workspaceId: String, callId: String): AnswerOutcome =
        when (val result = api.answerCall(callId, CallAnswerRequest(workspaceId = workspaceId))) {
            is ApiResult.Success -> joinable(result.value)
            // ⛔ BOTH ARMS MEAN "THE CALL ENDED WHILE YOU WERE REACHING FOR IT". See the ⚠️ on the
            // class: 404 is deliberately indistinguishable from another tenant's id, and 409 is a
            // status that is no longer answerable. Rendering either as an error would blame the
            // operator for a caller hanging up.
            is ApiResult.NotFound -> AnswerOutcome.AlreadyEnded
            is ApiResult.HttpFailure -> if (result.status == HTTP_CONFLICT) {
                AnswerOutcome.AlreadyEnded
            } else {
                AnswerOutcome.NotAnswered(result)
            }
            is ApiResult.Failure -> AnswerOutcome.NotAnswered(result)
        }

    /**
     * Accept a 200 only when it is actually usable as a call.
     *
     * ⛔ THE SAME THREE-FIELD CHECK [DialRepository] MAKES, FOR THE SAME REASON: every field of
     * [CallAnswerResponse] defaults, so a `{}` body decodes into a well-formed response holding an
     * empty token and an empty URL. The failure would then surface at `engine.connect` as a
     * media-plane error — on a screen already showing a connected call, for what was really a
     * server refusal.
     *
     * ⚠️ THERE IS NO ROOM-PREFIX ASSERTION HERE, UNLIKE THE DIAL PATH, AND ITS ABSENCE IS
     * DELIBERATE. `DialRepository` checks for `direct_` because that prefix is what keeps the AI off
     * a room this app created. An ANSWERED call is a `call_` room that the agent is already in and
     * is supposed to be in — the handoff metadata inside the token is what tells it to step back —
     * so a prefix check here would refuse every legitimate answer.
     */
    private fun joinable(response: CallAnswerResponse): AnswerOutcome {
        rejectedEnvelope(ANSWER_ENVELOPE, response.success)
            ?.let { return AnswerOutcome.NotAnswered(it) }
        if (response.token.isBlank() || response.url.isBlank()) {
            return AnswerOutcome.NotAnswered(
                ApiResult.DecodeFailure(
                    IllegalStateException("$ANSWER_ENVELOPE carried no join credential"),
                    "$ANSWER_ENVELOPE{token=${response.token.isNotBlank()}," +
                        "url=${response.url.isNotBlank()}}",
                ),
            )
        }
        return AnswerOutcome.Joinable(response)
    }

    private companion object {
        const val ANSWER_ENVELOPE = "CallAnswerResponse"

        /** ⚠️ Named because detekt counts a bare 409 as a magic number. */
        const val HTTP_CONFLICT = 409
    }
}

/**
 * What happened when the operator pressed Answer.
 *
 * ⛔ THREE CASES, AND [AlreadyEnded] IS THE ONE THAT EARNS ITS PLACE. It is not an error and must
 * not be worded as one: the overwhelmingly common way to reach it is that the caller hung up, or
 * the AI finished with them, in the seconds between the phone ringing and a thumb arriving. A
 * screen that said "could not answer" there would be blaming the operator for somebody else's
 * decision — and on a ringing screen that is the difference between "you missed it" and "the app is
 * broken".
 *
 * ⚠️ [NotAnswered] CARRIES AN [ApiResult.Failure], NOT RENDERED TEXT, for the reason
 * [DialOutcome.NotPlaced] does: this module cannot see the UI's `FailureText`, which lives beside
 * the string resources it names, and single-homing that mapping is what makes every screen say the
 * same thing about the same fault. ⚠️ It is also where the `viewer` 403 lands — a viewer's phone
 * genuinely rings, because the server fans a push out to every registered device in the workspace
 * without consulting roles.
 */
sealed interface AnswerOutcome {

    /**
     * The server minted a credential. ⛔ [session]'s url and token are used VERBATIM — the room
     * lives on the deployment that created it, which for an inbound call is the US SIP bridge
     * regardless of the workspace's own region.
     */
    data class Joinable(val session: CallAnswerResponse) : AnswerOutcome

    /**
     * 404 or 409 — the call is no longer joinable.
     *
     * ⚠️ NOT DISTINGUISHED FURTHER, AND IT COULD NOT BE HONESTLY. The server answers 404 for a call
     * id this workspace cannot see on purpose, so "wrong id" and "gone" are one answer by design.
     */
    data object AlreadyEnded : AnswerOutcome

    /** Everything else: offline, signed out, a 403 for a viewer, a 5xx, contract drift. */
    data class NotAnswered(val failure: ApiResult.Failure) : AnswerOutcome
}
