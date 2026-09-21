package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestFiling
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SupportApi

/**
 * The data layer for this workspace's own support requests with Distronode.
 *
 * ⛔ THE FAILURE THIS FILE EXISTS FOR HAS ALREADY HAPPENED ON THE WEB. Every list failure was
 * swallowed into `[]`, so a customer with three open tickets was told they had none; they stopped
 * chasing and nobody here ever saw the request. [rejectedEnvelope] is what keeps a structurally
 * empty 200 from reading as "you have raised nothing", and an [ApiResult.Failure] is passed
 * through untouched so the screen can offer a retry instead of an empty state.
 *
 * ⛔ NOTHING HERE TAKES AN IDENTITY FROM ITS CALLER, AND NOTHING MAY BE ADDED THAT DOES. The only
 * scope on any of these five calls is the `workspaceId`, which the server verifies against the
 * caller's own membership before the handler runs; the requester is derived from the session and
 * hashed server-side. There is no parameter here for an email address, a phone number or a
 * requester hash, and the app never computes one. That is the boundary between this surface and
 * the voice lookup, which takes its identity from call metadata precisely because caller ID is
 * spoofable and a model-supplied phone number would be a disclosure tool.
 *
 * ⚠️ THE CONTENT THIS SURFACE READS IS FULL, AND THAT IS CORRECT HERE — the request detail carries
 * the whole thread. It runs under the operator's own session bearer inside an authenticated app,
 * so withholding the answer the customer came for would be the wrong trade. The status-only rule
 * belongs to `/api/internal/support-lookup` and must not be imported onto this one, nor this one's
 * latitude exported onto that.
 */
class SupportRepository(
    private val api: SupportApi,
) {

    /**
     * ⛔ AN EMPTY LIST IS A REAL ANSWER AND A FAILURE IS NOT ONE. The two are returned as different
     * things so the screen cannot collapse them: `Success(emptyList())` means "nothing raised",
     * a `Failure` means "we could not ask".
     *
     * ⚠️ CAPPED SERVER-SIDE AT 100 AND NOT PAGED, so a long-lived workspace does not see its oldest
     * requests here at all.
     */
    suspend fun requests(workspaceId: String): ApiResult<List<SupportRequestSummary>> =
        when (val result = api.supportRequests(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(LIST_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.requests)
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ A 404 IS PASSED THROUGH AS A 404 AND MUST NOT BE INTERPRETED. The server answers "no such
     * request", "not this workspace's request" and "erased" identically so a sequential key like
     * `DA-41` cannot be probed with a session and a loop; distinguishing them here would rebuild
     * the oracle the server declined to offer.
     */
    suspend fun request(workspaceId: String, key: String): ApiResult<SupportRequestDetail> =
        when (val result = api.supportRequest(workspaceId, key)) {
            is ApiResult.Success ->
                rejectedEnvelope(DETAIL_ENVELOPE, result.value.success)
                    ?: result.value.request?.let { ApiResult.Success(it) }
                    ?: drift(DETAIL_ENVELOPE, "request")
            is ApiResult.Failure -> result
        }

    /**
     * Raise a request.
     *
     * ⛔ THE IDEMPOTENCY KEY IS THE CALLER'S, NOT THIS REPOSITORY'S, AND THAT IS THE OPPOSITE OF
     * [DeskRepository.createTicket]. Here it must be **minted once per composed draft and reused on
     * every retry**: the server answers a re-used key with `deduplicated:true`, so a retry carrying
     * the same key collapses onto the first request, while a retry that mints a fresh one puts a
     * SECOND ticket in a human's queue. Minting it here — per call, as the desk does — would
     * therefore defeat it exactly when it is needed. The ViewModel holds the key for the life of a
     * draft and discards it when the draft is cleared.
     *
     * ⚠️ A 429 AND A 503 BOTH CARRY THE SERVER'S OWN SENTENCE, which names the remedy (reply on an
     * existing request; the public form at `/support/report`). Both should be shown verbatim rather
     * than replaced with a generic message, and both arrive as an [ApiResult.Failure] carrying that
     * text.
     */
    suspend fun createRequest(
        workspaceId: String,
        draft: SupportRequestDraft,
        idempotencyKey: String,
    ): ApiResult<SupportRequestFiling> =
        when (
            val result = api.createSupportRequest(
                workspaceId = workspaceId,
                draft = draft.copy(
                    subject = draft.subject.trim(),
                    message = draft.message.trim(),
                ),
                idempotencyKey = idempotencyKey,
            )
        ) {
            is ApiResult.Success ->
                rejectedEnvelope(CREATE_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.filing)
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ NEVER RETRIED AUTOMATICALLY, AND NOTHING HERE MAY ADD A RETRY. The reply is posted as a
     * PUBLIC Jira comment, so a repeat leaves a second copy in the customer's own thread and
     * notifies the agent twice. A failed reply is the operator's decision to make again.
     */
    suspend fun reply(
        workspaceId: String,
        key: String,
        body: String,
    ): ApiResult<SupportMessage> =
        when (val result = api.replyToSupportRequest(workspaceId, key, body.trim())) {
            is ApiResult.Success ->
                rejectedEnvelope(REPLY_ENVELOPE, result.value.success)
                    ?: result.value.message?.let { ApiResult.Success(it) }
                    ?: drift(REPLY_ENVELOPE, "message")
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ NOT IDEMPOTENT AND NOT RETRIED. The server posts a PUBLIC audit comment naming who asked
     * BEFORE it applies the transition, deliberately, so the attribution survives a transition that
     * fails — which means a repeat that still finds a transition leaves a second "Closed at the
     * requester's request by …" in the customer's own thread.
     *
     * ⚠️ RETURNS THE SERVER'S OWN `statusName`, which is the desk's word for the state and is
     * localised. A client that substituted "Closed" would print English over a status Atlassian
     * spells in another language.
     */
    suspend fun close(workspaceId: String, key: String): ApiResult<String> =
        when (val result = api.closeSupportRequest(workspaceId, key)) {
            is ApiResult.Success ->
                rejectedEnvelope(CLOSE_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.statusName)
            is ApiResult.Failure -> result
        }

    private fun <T> drift(envelope: String, field: String): ApiResult<T> = ApiResult.DecodeFailure(
        cause = IllegalStateException("$envelope affirmed success without $field"),
        bodyPreview = "$envelope{success=true, $field=null}",
    )

    private companion object {
        const val LIST_ENVELOPE = "SupportRequestListResponse"
        const val DETAIL_ENVELOPE = "SupportRequestDetailResponse"
        const val CREATE_ENVELOPE = "SupportRequestCreateResponse"
        const val REPLY_ENVELOPE = "SupportReplyResponse"
        const val CLOSE_ENVELOPE = "SupportCloseResponse"
    }
}
