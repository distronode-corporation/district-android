package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AiDraftRequest
import com.distronode.districtai.core.model.DraftSaveRequest
import com.distronode.districtai.core.model.MessageDraft
import com.distronode.districtai.core.model.UploadedMedia
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * Everything the reply composer needs that is not the send itself: attachments, the
 * server-persisted draft, and the AI generator.
 *
 * ⛔ A SEPARATE REPOSITORY FROM [InboxRepository] RATHER THAN SIX MORE METHODS ON IT. Two reasons,
 * and only the second is about the function ceiling:
 *   1. The risk profiles are different. The Inbox's calls are reads plus one billable send;
 *      these are four per-author writes and one that spends a Vertex generation per invocation.
 *      A boundary here means a screen that only reads the Inbox cannot reach the generator.
 *   2. [InboxRepository] sits at five methods and detekt's ceiling is eleven.
 *
 * ⛔ THE TWO DRAFT CONCEPTS SHARE A WORD AND NOTHING ELSE. [loadDraft]/[saveDraft]/[deleteDraft]
 * persist what the operator typed and are cheap and idempotent. [generateDraft] INVENTS text and
 * is billed per call. Autosave must never reach the second one.
 */
class ComposerRepository(private val api: DistrictApi) {

    /**
     * Upload one image and return the URL the send route will accept.
     *
     * ⛔ PRE-CHECKED LOCALLY BEFORE THE UPLOAD, NOT ONLY BY THE SERVER. A 5MB body on a metered
     * connection spent to be told the type is wrong is a real cost, and the failure the operator
     * sees is better when it names the actual rule. The server still enforces both — this is a
     * shortcut, never the boundary.
     *
     * ⚠️ RETURNS THE SERVER'S OWN REFUSAL TEXT VERBATIM on a 200-with-`success:false`, for the
     * same reason [InboxRepository.send] does: "Only JPEG, PNG, GIF, or WebP images can be
     * attached to MMS" tells the operator what to do and "upload failed" does not.
     */
    suspend fun uploadMedia(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ): ApiResult<UploadedMedia> {
        if (mimeType !in ALLOWED_MIME_TYPES) {
            return ApiResult.HttpFailure(status = HTTP_OK, message = UNSUPPORTED_TYPE_MESSAGE)
        }
        if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_BYTES) {
            return ApiResult.HttpFailure(status = HTTP_OK, message = SIZE_MESSAGE)
        }

        return when (val result = api.uploadMedia(workspaceId, fileName, mimeType, bytes)) {
            is ApiResult.Success -> {
                val media = result.value.media
                // ⛔ `success && media != null`, NOT just `success`. The envelope's media is
                // nullable in the DTO because a refusal omits it; treating a success-shaped
                // response with no media as an upload would attach an empty URL to a billable
                // send, which the carrier then fails on.
                if (result.value.success && media != null) {
                    ApiResult.Success(media)
                } else {
                    ApiResult.HttpFailure(
                        status = HTTP_OK,
                        message = result.value.error.orEmpty(),
                    )
                }
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * This thread's saved draft, or `null` when there is none.
     *
     * ⛔ `null` IS A SUCCESSFUL ANSWER. Almost every thread has no draft, and the server returns
     * `{success, draft: null}` rather than 404 so the composer's ordinary open path is not an
     * error. Mapping it to a failure here would put an error banner on every clean thread.
     */
    suspend fun loadDraft(workspaceId: String, threadKey: String): ApiResult<MessageDraft?> =
        when (val result = api.draft(workspaceId, threadKey)) {
            is ApiResult.Success -> ApiResult.Success(result.value.draft)
            is ApiResult.Failure -> result
        }

    /**
     * Every thread this author has a draft on, for the Inbox list's badge.
     *
     * ⚠️ RETURNS THE THREAD KEYS, NOT THE BODIES. The list only needs to know WHICH threads have
     * one; carrying ten thousand characters of unsent text into a list screen so it can draw a
     * chip is waste, and it puts a colleague-invisible draft into a screenshot of the Inbox.
     */
    suspend fun draftThreadKeys(workspaceId: String): ApiResult<Set<String>> =
        when (val result = api.drafts(workspaceId)) {
            is ApiResult.Success ->
                ApiResult.Success(result.value.drafts.map { it.threadKey }.toSet())
            is ApiResult.Failure -> result
        }

    /**
     * Save (upsert) this thread's draft.
     *
     * ⛔ REFUSES A BLANK BODY LOCALLY RATHER THAN LEARNING IT FROM A 400. The server answers
     * `code: "empty_body"` and means "send DELETE"; a client that fired the PUT anyway would burn
     * one of the 60 writes/min the workspace has and get nothing for it. The local refusal is an
     * assertion that the caller's own clear-the-box path went to [deleteDraft] instead.
     */
    suspend fun saveDraft(
        workspaceId: String,
        threadKey: String,
        body: String,
        subject: String? = null,
        mediaUrls: List<String> = emptyList(),
    ): ApiResult<MessageDraft?> {
        if (body.isBlank()) {
            return ApiResult.HttpFailure(status = HTTP_BAD_REQUEST, message = EMPTY_BODY_MESSAGE)
        }
        val request = DraftSaveRequest(
            workspaceId = workspaceId,
            threadKey = threadKey,
            body = body,
            subject = subject,
            mediaUrls = mediaUrls,
        )
        return when (val result = api.saveDraft(request)) {
            is ApiResult.Success -> ApiResult.Success(result.value.draft)
            is ApiResult.Failure -> result
        }
    }

    /** Idempotent — deleting a draft that is not there succeeds. */
    suspend fun deleteDraft(workspaceId: String, threadKey: String): ApiResult<Unit> =
        when (val result = api.deleteDraft(workspaceId, threadKey)) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /**
     * Ask the model for a reply.
     *
     * ⛔ NON-IDEMPOTENT AND BILLABLE — one Vertex generation per call, 20/min per workspace. Never
     * retried here and never called on a timer. The caller drives it from an explicit tap.
     *
     * ⚠️ AN EMPTY GENERATION IS A SUCCESS, NOT A FAILURE. The route sends `""` when the model
     * returns nothing rather than erroring, so this returns the empty string and the caller
     * decides — which matters because the wrong decision (writing it into the composer) would
     * blank text the operator had already typed.
     */
    suspend fun generateDraft(
        workspaceId: String,
        contactId: String?,
        address: String?,
    ): ApiResult<String> {
        val request = AiDraftRequest(
            workspaceId = workspaceId,
            contactId = contactId,
            // ⚠️ The server's historical name for the address selector; it carries an email too.
            // The same asymmetry the timeline route has, and renaming it here would 400.
            phoneNumber = address,
        )
        return when (val result = api.generateDraft(request)) {
            is ApiResult.Success ->
                if (result.value.success) {
                    ApiResult.Success(result.value.draft)
                } else {
                    ApiResult.HttpFailure(status = HTTP_OK, message = result.value.error.orEmpty())
                }
            is ApiResult.Failure -> result
        }
    }

    companion object {
        /** ⛔ Must equal the route's own allowlist. A wider set here is a round trip wasted. */
        val ALLOWED_MIME_TYPES: Set<String> = setOf(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
        )

        /** Twilio's MMS ceiling, which is where the server's limit comes from. */
        const val MAX_UPLOAD_BYTES: Int = 5 * 1024 * 1024

        /**
         * ⛔ THE SERVER'S CAP, AND THE COMPOSER'S OWN. `messages/send` rejects a sixth URL with a
         * 400 for the whole message, so the chip row stops accepting at five rather than letting
         * an operator attach a sixth and lose the send.
         */
        const val MAX_ATTACHMENTS: Int = 5

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400

        private const val UNSUPPORTED_TYPE_MESSAGE =
            "Only JPEG, PNG, GIF or WebP images can be attached."
        private const val SIZE_MESSAGE = "Attachments must be between 1 byte and 5MB."
        private const val EMPTY_BODY_MESSAGE =
            "A blank draft is a deleted draft — delete it instead of saving an empty body."
    }
}
