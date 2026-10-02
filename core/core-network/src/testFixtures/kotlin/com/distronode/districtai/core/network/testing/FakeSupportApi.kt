package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.SupportCloseResponse
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportReplyResponse
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestDetailResponse
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestListResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SupportApi

/**
 * A settable [SupportApi] for the support repository and the support ViewModels alike.
 *
 * ⚠️ RECORDS THE IDEMPOTENCY KEYS, which is the one thing this feature's tests must be able to see:
 * a retry has to carry the SAME key (a fresh one puts a second ticket in a human's queue) while the
 * desk's rule is the exact inverse.
 */
class FakeSupportApi : SupportApi {

    var listResult: ApiResult<SupportRequestListResponse> =
        ApiResult.Success(SupportRequestListResponse(success = true))
    var detailResult: ApiResult<SupportRequestDetailResponse> =
        ApiResult.Success(
            SupportRequestDetailResponse(success = true, request = SupportRequestDetail()),
        )
    var createResult: ApiResult<SupportRequestCreateResponse> =
        ApiResult.Success(SupportRequestCreateResponse(success = true, issueKey = "DA-1"))
    var replyResult: ApiResult<SupportReplyResponse> =
        ApiResult.Success(SupportReplyResponse(success = true, message = SupportMessage()))
    var closeResult: ApiResult<SupportCloseResponse> =
        ApiResult.Success(SupportCloseResponse(success = true, statusName = "Done"))

    var listReads = 0
    var detailReads = 0

    /** The workspace every call named, in order: a support request belongs to exactly one. */
    val workspaceIds = mutableListOf<String>()

    val createDrafts = mutableListOf<SupportRequestDraft>()
    val createKeys = mutableListOf<String?>()
    val replyBodies = mutableListOf<String>()
    val closedKeys = mutableListOf<String>()

    /** How many closes were attempted; the same count as [closedKeys], read as a number. */
    val closeCalls: Int get() = closedKeys.size

    override suspend fun supportRequests(workspaceId: String) = listResult.also {
        listReads++
        workspaceIds += workspaceId
    }

    override suspend fun createSupportRequest(
        workspaceId: String,
        draft: SupportRequestDraft,
        idempotencyKey: String?,
    ) = createResult.also {
        workspaceIds += workspaceId
        createDrafts += draft
        createKeys += idempotencyKey
    }

    override suspend fun supportRequest(workspaceId: String, key: String) =
        detailResult.also {
            detailReads++
            workspaceIds += workspaceId
        }

    override suspend fun replyToSupportRequest(workspaceId: String, key: String, body: String) =
        replyResult.also {
            workspaceIds += workspaceId
            replyBodies += body
        }

    override suspend fun closeSupportRequest(workspaceId: String, key: String) =
        closeResult.also {
            workspaceIds += workspaceId
            closedKeys += key
        }
}
