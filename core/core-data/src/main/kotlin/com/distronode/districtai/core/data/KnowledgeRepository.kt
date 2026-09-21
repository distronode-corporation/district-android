package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.KnowledgeCreateRequest
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.core.model.KnowledgeModePatchRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.KnowledgeApi

/**
 * The workspace knowledge base: what the agent can answer from, and where the answer is composed.
 *
 * ⛔ NOT PART OF [WorkspaceConfigRepository], AND THE REASON IS THE ROLE CONTRACT RATHER THAN
 * TIDINESS. Every call on that repository — including its READ — excludes `viewer` server-side,
 * because the config payload carries staff transfer numbers and the operator's own prompt. Here the
 * reads ADMIT `viewer` and only the writes exclude them. Folding the two together would put one
 * "who may call this" rule on two surfaces that genuinely differ, and the UI gate would then be
 * derived from the wrong one.
 *
 * ⛔ NOTHING HERE RE-READS AFTER A WRITE, WHICH IS THE OPPOSITE OF [WorkspaceConfigRepository] AND
 * IS CORRECT FOR THIS SURFACE. That repository re-reads because its routes answer a bare
 * `{success:true}` and it is rebuilding the baseline a WHOLESALE-REPLACE save is constructed from.
 * Neither applies here: `knowledge-mode` PATCH echoes the stored config, so there is a body to
 * adopt, and the document routes are per-row create/delete rather than array replacement — nothing
 * a later save is built on. The caller re-reads the LIST after a create or delete because the list
 * is what is on screen, not because a save needs it.
 *
 * ⚠️ [addDocument] IS BILLABLE AND NOT IDEMPOTENT — one call is one embedding run over every chunk
 * the content produced, rate limited at 20/min per workspace behind a FAIL-OPEN Redis limiter. No
 * automatic retry, here or above.
 */
class KnowledgeRepository(private val api: KnowledgeApi) {

    /**
     * The document list.
     *
     * ⚠️ AN EMPTY LIST IS A REAL ANSWER — a workspace that has uploaded nothing — and is returned
     * as [ApiResult.Success] with an empty list, never as a failure. The envelope check is what
     * separates that from a structurally empty 200.
     */
    suspend fun documents(workspaceId: String): ApiResult<List<KnowledgeDocument>> =
        when (val result = api.knowledgeDocuments(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(LIST_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.documents)
            is ApiResult.Failure -> result
        }

    /**
     * Ingest a document.
     *
     * ⛔ ONE CALL IS ONE BILL, AND THE CALLER SIZES IT. See [KnowledgeCreateRequest]. A failed
     * create must never be re-sent automatically: a request that timed out may well have embedded
     * and persisted, and repeating it pays twice for a duplicate document.
     *
     * ⚠️ THE ECHOED ROW IS RETURNED RATHER THAN DISCARDED, but it is deliberately NOT the thing a
     * screen renders: the create response omits `sourceUrl` (the route's `select` is one field
     * shorter than the list's), so a list rebuilt by appending this row would show a document with
     * no source until the next full read.
     */
    suspend fun addDocument(request: KnowledgeCreateRequest): ApiResult<KnowledgeDocument?> =
        when (val result = api.createDocument(request)) {
            is ApiResult.Success ->
                rejectedEnvelope(CREATE_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.document)
            is ApiResult.Failure -> result
        }

    /**
     * Delete a document.
     *
     * ⛔ THE QUERY PARAMETER IS `documentId`, and the route answers 400 "Missing documentId" for
     * any other spelling — see [KnowledgeApi.deleteDocument].
     *
     * ⛔ AND SUCCESS DOES NOT MEAN A ROW WAS REMOVED. The delete is scoped `{id, workspaceId}` and
     * its count is never read, so another tenant's id answers exactly like a real delete. Re-read
     * the list rather than removing the row locally on the strength of this.
     */
    suspend fun deleteDocument(workspaceId: String, documentId: String): ApiResult<Unit> =
        when (val result = api.deleteDocument(workspaceId, documentId)) {
            is ApiResult.Success ->
                rejectedEnvelope(DELETE_ENVELOPE, result.value.success) ?: ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /** ⚠️ `mode` is a top-level key of the envelope, not a nested config object. */
    suspend fun mode(workspaceId: String): ApiResult<String?> =
        when (val result = api.knowledgeMode(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(MODE_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.mode)
            is ApiResult.Failure -> result
        }

    /**
     * Choose the knowledge source.
     *
     * ⛔ THE SERVER'S ECHO IS ADOPTED, NOT THE REQUESTED VALUE. The route re-reads through its own
     * sanitiser before answering, so what comes back is what a later read will see — and returning
     * the value that was ASKED for would report a mode nobody stored if the sanitiser ever
     * disagreed. This is why this write needs no separate re-read.
     */
    suspend fun setMode(workspaceId: String, mode: String): ApiResult<String?> =
        when (
            val result = api.saveKnowledgeMode(
                KnowledgeModePatchRequest(workspaceId = workspaceId, mode = mode),
            )
        ) {
            is ApiResult.Success ->
                rejectedEnvelope(MODE_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.mode)
            is ApiResult.Failure -> result
        }

    private companion object {
        const val LIST_ENVELOPE = "KnowledgeListResponse"
        const val CREATE_ENVELOPE = "KnowledgeCreateResponse"
        const val DELETE_ENVELOPE = "KnowledgeDeleteResponse"
        const val MODE_ENVELOPE = "KnowledgeModeResponse"
    }
}
