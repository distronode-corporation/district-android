package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CODE_OVERAGE_CAP_REACHED
import com.distronode.districtai.core.model.CODE_SUBSCRIPTION_INACTIVE
import com.distronode.districtai.core.model.DIRECT_ROOM_PREFIX
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DialApi
import com.distronode.districtai.core.network.DialRequest

/**
 * Placing one outbound call.
 *
 * ⛔ THE ONLY WRITE IN THIS CLIENT WHOSE FAILURE MODE IS A TELEPHONE RINGING WITH NOBODY ON IT.
 * The server writes the `Call` row and instructs the carrier BEFORE it mints the credential it
 * answers with, so anything this layer rejects after a 200 is a call that is already happening. The
 * envelope check below is therefore not the usual "did the server affirm success" hygiene: it is
 * the one place where returning a refusal for a call that WAS placed is possible, and the
 * [DialOutcome.NotPlaced] wording exists so the screen can say so rather than claim nothing
 * happened.
 *
 * ⛔ NO RETRY LIVES HERE, AND NONE MAY BE ADDED. Every other write on this surface is safe to
 * re-send — a duplicate member add is a 409, a duplicate draft save overwrites — and this one
 * places a second call, bills for it, and rings the callee again. The rate limit (20/min per
 * workspace) is a backstop against a stuck finger, not a design.
 *
 * ⛔ THE TWO REFUSALS WITH A `code` ARE TRANSLATED HERE, ONCE, AND NEVER STRING-MATCHED ABOVE —
 * the same rule [MembersRepository] applies to its 409s. `subscription_inactive` and
 * `overage_cap_reached` mean opposite things about the account (one is unpaid, the other is paid
 * and capped by its own choice) and need opposite remedies on screen, and the server's sentences
 * are operator-facing prose it is free to reword.
 *
 * ⛔ AND THE DNC REFUSAL DELIBERATELY HAS NO CASE OF ITS OWN, WHICH IS A FINDING RATHER THAN AN
 * OVERSIGHT. `POST /api/district/calls/dial` answers an opted-out number with a bare
 * `{success:false, error}` at HTTP 403 and NO `code` — structurally identical to the role refusal
 * the same route emits a few lines earlier. The client cannot tell them apart without matching
 * English, which is exactly what the rule above forbids, so both arrive as
 * [DialOutcome.NotPlaced] carrying the server's own sentence — which for DNC does say "This number
 * has opted out of calls from this workspace (DNC)." That is a distinct message; it is just the
 * SERVER's, not one this client chose. Giving DNC a `code` server-side is the fix, and until it
 * has one no client can branch on it honestly.
 */
class DialRepository(private val api: DialApi) {

    /**
     * Dial [to] on behalf of [workspaceId].
     *
     * ⚠️ THE NUMBER IS SENT AS TYPED. Normalisation is the server's, and it runs BEFORE the DNC
     * lookup and the dial, so a client-side canonicaliser that disagreed by one character would
     * place a call the compliance check never saw. See [DialRequest.to].
     *
     * ⚠️ A SUCCESS HERE MEANS THE CARRIER ACCEPTED THE DIAL, NOT THAT ANYONE ANSWERED. The route
     * returns without waiting so the app can be in the room hearing call progress while the far
     * end rings; answered-ness is observable only as a participant arriving.
     */
    suspend fun dial(workspaceId: String, to: String): DialOutcome =
        when (val result = api.dial(DialRequest(workspaceId = workspaceId, to = to))) {
            is ApiResult.Success -> placed(result.value)
            is ApiResult.HttpFailure -> when (result.code) {
                CODE_SUBSCRIPTION_INACTIVE -> DialOutcome.SubscriptionInactive
                CODE_OVERAGE_CAP_REACHED -> DialOutcome.OverageCapReached
                else -> DialOutcome.NotPlaced(result)
            }
            is ApiResult.Failure -> DialOutcome.NotPlaced(result)
        }

    /**
     * Accept a 200 only when it is actually usable as a call.
     *
     * ⛔ THREE CHECKS, AND EACH ONE GUARDS A DIFFERENT SILENT FAILURE. Every field of
     * [DialResponse] defaults, so a `{}` body decodes into a well-formed response holding an empty
     * token and an empty URL — the engine would then fail to connect and the operator would read a
     * media-plane error for what was a server refusal. Blank credentials get the same treatment for
     * the same reason: the connect is where they would surface otherwise, and by then the screen is
     * already showing a call.
     *
     * ⛔ THE PREFIX CHECK IS THE ONE THAT IS NOT HYGIENE. `direct_` is what the voice agent's
     * `request_fnc` refuses BY NAME; a `call_` room would have the AI dispatched into a human's own
     * conversation, talking over them. Nothing client-side can prevent that — the room is already
     * created — but joining it silently would mean the operator is the last to know. So this is
     * reported rather than rendered as a working call.
     *
     * ⚠️ ALL THREE ARE [ApiResult.DecodeFailure], WHICH IS CORRECT AND NOT A CONVENIENCE: each one
     * means the server's answer did not match the contract this build was compiled against, which
     * is precisely what that case is for and what should be noisy in a debug build. The committed
     * `district-dial.json` fixture exists so it can never be a shape this client merely forgot.
     */
    private fun placed(response: DialResponse): DialOutcome {
        rejectedEnvelope(DIAL_ENVELOPE, response.success)?.let { return DialOutcome.NotPlaced(it) }
        if (response.token.isBlank() || response.url.isBlank()) {
            return DialOutcome.NotPlaced(
                ApiResult.DecodeFailure(
                    IllegalStateException("$DIAL_ENVELOPE carried no join credential"),
                    "$DIAL_ENVELOPE{token=${response.token.isNotBlank()},url=${response.url.isNotBlank()}}",
                ),
            )
        }
        if (!response.roomName.startsWith(DIRECT_ROOM_PREFIX)) {
            return DialOutcome.NotPlaced(
                ApiResult.DecodeFailure(
                    IllegalStateException("$DIAL_ENVELOPE room is not a $DIRECT_ROOM_PREFIX room"),
                    // ⚠️ The prefix only. The rest of the name embeds the workspace id and the call
                    // id, and a diagnostic preview is not a place to put either.
                    "$DIAL_ENVELOPE{roomName=${response.roomName.substringBefore('_')}_…}",
                ),
            )
        }
        return DialOutcome.Placed(response)
    }

    private companion object {
        const val DIAL_ENVELOPE = "DialResponse"
    }
}

/**
 * What happened to a dial.
 *
 * ⛔ FOUR CASES, AND THE TWO IN THE MIDDLE EXIST BECAUSE THEIR REMEDIES ARE OPPOSITE. Collapsing
 * them into [NotPlaced] would leave a screen with the server's operator-facing prose and nothing to
 * branch on, so a ViewModel wanting to say "this workspace has used its included minutes and chose
 * a hard cap" would have to substring-match English the server may reword. The server publishes a
 * stable `code` for exactly this; it is matched once, in [DialRepository].
 *
 * ⚠️ [NotPlaced] CARRIES AN [ApiResult.Failure], NOT RENDERED TEXT, for the reason
 * [MemberMutationOutcome.Failed] does: this module cannot see the UI's `FailureText`, which lives
 * beside the string resources it names, and single-homing that mapping is what makes every screen
 * say the same thing about the same fault.
 */
sealed interface DialOutcome {

    /**
     * The carrier accepted the dial and the credential is usable.
     *
     * ⚠️ NOT "THE CALLEE ANSWERED". Nothing in this response says whether anyone picked up, and
     * nothing can: the route returns while the far end is still ringing, deliberately.
     */
    data class Placed(val session: DialResponse) : DialOutcome

    /**
     * ⛔ 402 `subscription_inactive`. The workspace's plan is delinquent or terminated, so every
     * billable action is refused — not this call specifically. ⚠️ The remedy is on the web
     * (Google Play's Payments policy keeps subscription changes out of this app), so the wording
     * must point there rather than offering a control that does not exist.
     */
    data object SubscriptionInactive : DialOutcome

    /**
     * ⛔ 409 `overage_cap_reached`. NOT a payment failure: the account is in good standing and has
     * used its included voice minutes under a hard-cap overage policy it chose. Wording this as a
     * billing problem would send an operator to check a card that is working fine.
     */
    data object OverageCapReached : DialOutcome

    /**
     * Everything else — offline, signed out, rate limited (20/min per workspace), a 400 for an
     * unusable number or a Sinch-only workspace, a 500, contract drift, and ⚠️ **the DNC refusal**,
     * which arrives as a 403 with no `code` and is therefore indistinguishable from a role refusal
     * without matching the server's English. See the ⛔ on [DialRepository].
     */
    data class NotPlaced(val failure: ApiResult.Failure) : DialOutcome
}
