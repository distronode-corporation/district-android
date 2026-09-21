package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MessagingAccountRequest
import com.distronode.districtai.core.model.MessagingChannelDefaultRequest
import com.distronode.districtai.core.model.MessagingChannelDefaultResponse
import com.distronode.districtai.core.model.MessagingDefaultRequest
import com.distronode.districtai.core.model.MessagingDefaultResponse
import com.distronode.districtai.core.model.MessagingDeleteRequest
import com.distronode.districtai.core.model.MessagingMetaRequest
import com.distronode.districtai.core.model.MessagingResponse
import com.distronode.districtai.core.model.MessagingTestRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.MessagingApi

/**
 * The workspace's outbound carrier accounts: one read and five writes.
 *
 * ⛔ TWO FACTS ABOUT THE WRITES, CHECKED AGAINST THE ROUTE. An edit form does NOT need an operator
 * to retype a live carrier secret (a blank secret means "keep the stored ciphertext", so an ordinary
 * edit types none). A delete DOES release the phone-number claims protecting this workspace's
 * sending identity, which is why [deleteAccount] is documented as consequential and the UI
 * confirms it by naming what is freed.
 *
 * ⛔ ITS OWN REPOSITORY RATHER THAN A METHOD ON [WorkspaceConfigRepository], BECAUSE THE ROLE
 * CONTRACT DIFFERS. Everything on that repository excludes `viewer` including its read; here the
 * READ admits a viewer and every WRITE excludes one. One repository whose calls disagree about who
 * may make them is how a UI gate ends up derived from the wrong rule, and a viewer genuinely
 * arrives at this screen, so being wrong in the permissive direction lands a real user on a 403
 * they were invited to.
 *
 * ⛔ NOTHING HERE PATCHES THE ON-SCREEN LIST FROM A WRITE'S RESPONSE. The upsert echoes ids only,
 * the delete echoes a default id (and ABSENT means "there is no default now", indistinguishable
 * from a dropped key), and the route trims labels and filters numbers before storing them. Every
 * write is followed by a re-read; that is the caller's job because the list is what is on screen.
 *
 * ⚠️ NO CACHE, LIKE EVERY SIBLING. The numbers here are what an operator checks when a send went
 * out from the wrong identity, and a process-scoped copy would answer with the state from before
 * whatever they just changed.
 *
 * ⚠️ THE READ RETURNS THE WHOLE ENVELOPE rather than a narrowed value, because four of its fields
 * are one answer: the accounts, the platform-owned account, which one is the default, and the
 * per-channel overrides.
 */
class MessagingRepository(private val api: MessagingApi) {

    suspend fun messaging(workspaceId: String): ApiResult<MessagingResponse> =
        when (val result = api.messaging(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * Create or edit one carrier account.
     *
     * ⛔ THE 502 IS TRANSLATED HERE AND NOWHERE ELSE, BECAUSE THIS IS THE LAYER THAT KNOWS THE
     * ROUTE. `FailureText` refuses to render ANY 5xx body verbatim, for a good reason it must keep:
     * the contacts routes return a raw exception message on an unhandled error, and a Postgres
     * constraint name reaching a customer's screen is a leak. But this route's **502 specifically**
     * is an authored sentence — "Could not verify ownership of +1... with twilio right now. Please
     * try again shortly." — constructed by `handleUpsert` when the carrier-ownership probe cannot
     * reach the carrier. It is the most actionable thing the operator can be told, and it is the
     * one refusal on this surface that IS worth retrying. Matching on the status here keeps the
     * global 5xx rule intact; this route's own 500 arm, which does carry `getErrorMessage(rawError)`,
     * deliberately falls through to [MessagingWriteOutcome.NotSaved] and stays unrendered.
     *
     * ⚠️ THE THREE 403s ARE NOT TRANSLATED, ON PURPOSE. `ApiResult.Forbidden` already carries the
     * server's own sentence and `FailureText` already renders a 4xx verbatim and non-retryably,
     * which is exactly right for "not entitled to managed credentials", "phone number not found in
     * this workspace" and "managed numbers can only be added by purchasing them". Inventing local
     * copy for them would replace a specific message with a vaguer one.
     */
    suspend fun saveAccount(request: MessagingAccountRequest): MessagingWriteOutcome =
        when (val result = api.saveMessagingAccount(request)) {
            is ApiResult.Success ->
                rejectedEnvelope(SAVE_ENVELOPE, result.value.success)
                    ?.let { MessagingWriteOutcome.NotSaved(it) }
                    ?: MessagingWriteOutcome.Saved(
                        accountId = result.value.accountId,
                        defaultAccountId = result.value.defaultAccountId,
                    )
            is ApiResult.HttpFailure ->
                if (result.status == HTTP_BAD_GATEWAY) {
                    MessagingWriteOutcome.Unverifiable(result.message)
                } else {
                    MessagingWriteOutcome.NotSaved(result)
                }
            is ApiResult.Failure -> MessagingWriteOutcome.NotSaved(result)
        }

    /** Point every outbound send at one account. ⚠️ Idempotent; the response echoes the new default. */
    suspend fun setDefaultAccount(
        workspaceId: String,
        accountId: String,
    ): ApiResult<MessagingDefaultResponse> =
        defaultResult(
            api.setDefaultAccount(
                MessagingDefaultRequest(workspaceId = workspaceId, accountId = accountId),
            ),
        )

    /** Override one channel's sender. ⚠️ Merges one key; the whole map comes back. */
    suspend fun setChannelDefault(
        workspaceId: String,
        channel: String,
        accountId: String,
    ): ApiResult<MessagingChannelDefaultResponse> =
        when (
            val result = api.setChannelDefault(
                MessagingChannelDefaultRequest(
                    workspaceId = workspaceId,
                    channel = channel,
                    accountId = accountId,
                ),
            )
        ) {
            is ApiResult.Success ->
                rejectedEnvelope(CHANNEL_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * Remove one carrier account.
     *
     * ⛔ IT FREES EVERY PHONE NUMBER ONLY THIS ACCOUNT HELD. The hub's `PhoneNumberIndex` row is
     * what routes an inbound call or SMS to this workspace and what four sibling routes check
     * ownership against; once released, the number is unclaimed and another tenant can take it.
     * There is no undo that does not involve re-proving ownership at the carrier. The confirmation
     * is the caller's, and it has to say that rather than "are you sure".
     *
     * ⚠️ A FAILED HUB RELEASE IS NOT REPORTED AT ALL. The route logs it and still answers success,
     * because the account is gone either way and the stale row points at THIS workspace rather than
     * leaking anywhere. So a green result here means "the account is removed", not "every claim was
     * released".
     */
    suspend fun deleteAccount(
        workspaceId: String,
        accountId: String,
    ): ApiResult<MessagingDefaultResponse> =
        defaultResult(
            api.deleteMessagingAccount(
                MessagingDeleteRequest(workspaceId = workspaceId, accountId = accountId),
            ),
        )

    /** ⚠️ A bare `{success:true}` with no echo — nothing in this client can read the stored value. */
    suspend fun saveCreatorCell(
        workspaceId: String,
        creatorCellNumber: String,
    ): ApiResult<Unit> =
        when (
            val result = api.saveCreatorCell(
                MessagingMetaRequest(
                    workspaceId = workspaceId,
                    creatorCellNumber = creatorCellNumber,
                ),
            )
        ) {
            is ApiResult.Success ->
                rejectedEnvelope(META_ENVELOPE, result.value.success) ?: ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /**
     * Ask the carrier whether these credentials authenticate.
     *
     * ⛔ THE ONE CALL IN THIS CLASS THAT MUST NOT RUN [rejectedEnvelope], AND THE REASON IS THE
     * WHOLE POINT OF THE BUTTON. The route answers a failed credential check with HTTP **200** and
     * `{success:false, error}` — a deliberate design, so that "these keys are wrong" is a normal
     * answer rather than a server fault. Running the envelope guard over it would turn the button's
     * only interesting outcome into "this version of the app does not understand the response",
     * with no retry offered, for a working app talking to a working server.
     *
     * ⛔ THE CREDENTIALS TRAVEL IN PLAINTEXT AND HAVE NOT BEEN SAVED, which is what makes this
     * callable at all — and what bounds it to a form that actually holds them. Rate limited to
     * 10/min per WORKSPACE server-side, so it must never be driven by a recomposition or a loop.
     *
     * @return [MessagingTestOutcome.Passed] with whatever the provider volunteered about the
     *   account, [MessagingTestOutcome.Rejected] carrying the server's own sentence, or
     *   [MessagingTestOutcome.Unreachable] for a transport or status failure — which is NOT
     *   evidence about the credentials and must not be worded as if it were.
     */
    suspend fun testCredentials(request: MessagingTestRequest): MessagingTestOutcome =
        when (val result = api.testMessagingCredentials(request)) {
            is ApiResult.Success ->
                if (result.value.success) {
                    MessagingTestOutcome.Passed(
                        // ⚠️ Twilio answers `{friendlyName, status}` and the other two answer
                        // `{message}`; whichever is present is the only thing worth showing, and an
                        // empty `details` is a legitimate pass rather than a decode problem.
                        detail = result.value.details?.friendlyName
                            ?: result.value.details?.message,
                    )
                } else {
                    MessagingTestOutcome.Rejected(result.value.error.orEmpty())
                }
            is ApiResult.Failure -> MessagingTestOutcome.Unreachable(result)
        }

    /** ⚠️ `setDefault` and `delete` share one response shape, so they share one envelope check. */
    private fun defaultResult(
        result: ApiResult<MessagingDefaultResponse>,
    ): ApiResult<MessagingDefaultResponse> = when (result) {
        is ApiResult.Success -> rejectedEnvelope(DEFAULT_ENVELOPE, result.value.success) ?: result
        is ApiResult.Failure -> result
    }

    private companion object {
        const val ENVELOPE = "MessagingResponse"
        const val SAVE_ENVELOPE = "MessagingAccountSaveResponse"
        const val DEFAULT_ENVELOPE = "MessagingDefaultResponse"
        const val CHANNEL_ENVELOPE = "MessagingChannelDefaultResponse"
        const val META_ENVELOPE = "MessagingMetaResponse"

        /** ⛔ The carrier-probe outage status. See the ⛔ on [saveAccount]. */
        const val HTTP_BAD_GATEWAY = 502
    }
}

/**
 * What happened to an account save.
 *
 * ⛔ THREE CASES, AND THE MIDDLE ONE IS WHY THIS TYPE EXISTS. Collapsing [Unverifiable] into
 * [NotSaved] would send the route's 502 through `FailureText`'s blanket 5xx rule and render it as
 * "something went wrong on our side" — losing both the number that could not be verified and the
 * fact that trying again in a minute is the correct response. It is the only 5xx in this client
 * whose body is an authored sentence rather than a possible exception message.
 *
 * ⚠️ [NotSaved] CARRIES AN [ApiResult.Failure], NOT RENDERED TEXT, for the reason
 * [MemberMutationOutcome.Failed] does: this module cannot see the UI's `FailureText`, and the
 * mapping from a failure to what a user reads is single-homed in the app module so every screen
 * says the same thing about the same fault.
 */
sealed interface MessagingWriteOutcome {

    /**
     * The account was written.
     *
     * ⚠️ NEITHER ID IS A SUBSTITUTE FOR RE-READING. [accountId] is the server's on a create, and
     * [defaultAccountId] can name this account even when the operator did not ask for it — a
     * workspace's first account always becomes the default.
     */
    data class Saved(val accountId: String?, val defaultAccountId: String?) : MessagingWriteOutcome

    /**
     * ⛔ 502 FROM THE CARRIER-OWNERSHIP PROBE. **Not a refusal.** The route separates this from its
     * 403 precisely so a carrier outage or a throttled key does not read as "that number is not
     * yours" — the number stays unclaimed either way, so retrying is safe and is the right advice.
     *
     * @param message the server's own sentence, which names the number and the carrier.
     */
    data class Unverifiable(val message: String) : MessagingWriteOutcome

    /** Anything else: offline, signed out, 400, the three 403s, a 404, a 500, or contract drift. */
    data class NotSaved(val failure: ApiResult.Failure) : MessagingWriteOutcome
}

/**
 * What the credential probe found.
 *
 * ⛔ [Rejected] AND [Unreachable] ARE DIFFERENT ANSWERS AND MUST STAY SO. "Your keys do not
 * authenticate" is a fact about what the operator typed; "we could not ask" is a fact about the
 * network or about us. Merging them would tell someone their perfectly good credentials are wrong
 * because their phone lost signal — on the one screen where believing that leads to re-typing a
 * live carrier secret.
 */
sealed interface MessagingTestOutcome {

    /** @param detail whatever the provider volunteered — an account name, or a bare confirmation. */
    data class Passed(val detail: String?) : MessagingTestOutcome

    /**
     * ⚠️ ARRIVED AS AN HTTP **200**. The route reports a carrier's 401 this way on purpose; see the
     * ⛔ on [MessagingRepository.testCredentials].
     *
     * @param message the server's sentence, which quotes the carrier's own words.
     */
    data class Rejected(val message: String) : MessagingTestOutcome

    /** ⛔ Says nothing about the credentials. Includes the 429 when the 10/min cap is hit. */
    data class Unreachable(val failure: ApiResult.Failure) : MessagingTestOutcome
}
