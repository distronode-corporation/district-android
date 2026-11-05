package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.ConversationAssignmentApi

/**
 * One Inbox thread's owner: read it, and change it (app parity 3).
 *
 * ⚠️ THE OWNER IS READ FROM `conversations/assignments`, NOT CARRIED FROM THE LIST. A thread opened
 * from a push or a search hit has no list row behind it, and one read for every entry point is
 * simpler than two sources that can disagree. Every role may read it.
 */
class ConversationAssignmentRepository(private val api: ConversationAssignmentApi) {

    /** Who owns [threadKey] now, or null for nobody (an unassigned thread has no row). */
    suspend fun owner(workspaceId: String, threadKey: String): ApiResult<String?> =
        when (val result = api.conversationAssignments(workspaceId)) {
            is ApiResult.Success -> rejectedEnvelope(ASSIGNMENTS_ENVELOPE, result.value.success)
                ?: ApiResult.Success(
                    result.value.assignments.firstOrNull { it.threadKey == threadKey }?.assigneeEmail,
                )
            is ApiResult.Failure -> result
        }

    /**
     * Give [threadKey] to [assigneeEmail], or to nobody with null. Answers the owner the server
     * recorded, which is the one to show.
     *
     * ⛔ A REFUSAL (`assignee_not_member`, `assignee_is_viewer`) IS A FAILURE CARRYING THE SERVER'S
     * CODE, and the caller keeps the old owner on screen: nothing changed server-side.
     */
    suspend fun assign(workspaceId: String, threadKey: String, assigneeEmail: String?): ApiResult<String?> =
        when (val result = api.assignConversation(workspaceId, threadKey, assigneeEmail)) {
            is ApiResult.Success -> rejectedEnvelope(ASSIGN_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value.assignment?.assigneeEmail)
            is ApiResult.Failure -> result
        }

    private companion object {
        const val ASSIGNMENTS_ENVELOPE = "ConversationAssignmentsResponse"
        const val ASSIGN_ENVELOPE = "ConversationAssignResponse"
    }
}
