package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.ConversationAssignResponse
import com.distronode.districtai.core.model.ConversationAssignment
import com.distronode.districtai.core.model.ConversationAssignmentsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.ConversationAssignmentApi
import kotlinx.coroutines.CompletableDeferred

/**
 * A [ConversationAssignmentApi] that records every write and answers as configured.
 *
 * ⚠️ By default a write answers the owner it asked for, as the route does, and the read answers
 * nobody owning anything.
 */
class FakeConversationAssignmentApi : ConversationAssignmentApi {

    /** One write as sent. */
    data class Write(val workspaceId: String, val threadKey: String, val assigneeEmail: String?)

    val writes: MutableList<Write> = mutableListOf()
    val reads: MutableList<String> = mutableListOf()

    var assignmentsResult: ApiResult<ConversationAssignmentsResponse> =
        ApiResult.Success(ConversationAssignmentsResponse(success = true))

    /** When set, every write answers this instead of echoing the request. */
    var assignResult: ApiResult<ConversationAssignResponse>? = null

    /** When set, a write waits for it before answering: a write still in flight. */
    var assignGate: CompletableDeferred<Unit>? = null

    override suspend fun conversationAssignments(workspaceId: String): ApiResult<ConversationAssignmentsResponse> {
        reads += workspaceId
        return assignmentsResult
    }

    override suspend fun assignConversation(
        workspaceId: String,
        threadKey: String,
        assigneeEmail: String?,
    ): ApiResult<ConversationAssignResponse> {
        writes += Write(workspaceId, threadKey, assigneeEmail)
        assignGate?.await()
        return assignResult ?: ApiResult.Success(
            ConversationAssignResponse(
                success = true,
                assignment = assigneeEmail?.let {
                    ConversationAssignment(threadKey, it, "2026-11-05T12:00:00.000Z")
                },
            ),
        )
    }
}
