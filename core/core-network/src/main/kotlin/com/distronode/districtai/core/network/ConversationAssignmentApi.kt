package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.ConversationAssignResponse
import com.distronode.districtai.core.model.ConversationAssignmentsResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Who owns an Inbox thread (app parity 3): read every owner, and give one thread to a member.
 *
 * A sibling interface rather than a section of `InboxApi` for the reason [ExtraPaths] gives.
 *
 * ⛔ THE WRITE IS `agency` AND `client` ONLY, server-side; the read admits `viewer`. And nobody can
 * be GIVEN a thread while a viewer: the route answers 400 `assignee_is_viewer`.
 */
interface ConversationAssignmentApi {

    /** Every assigned thread in the workspace. A thread with no owner has no row. */
    suspend fun conversationAssignments(workspaceId: String): ApiResult<ConversationAssignmentsResponse>

    /**
     * Give [threadKey] to [assigneeEmail], or clear its owner with null.
     *
     * ⛔ NOT IDEMPOTENT IN EFFECT: an assignment pushes the new owner's phone. A caller sends it once.
     */
    suspend fun assignConversation(
        workspaceId: String,
        threadKey: String,
        assigneeEmail: String?,
    ): ApiResult<ConversationAssignResponse>
}

/** [ConversationAssignmentApi] over [DistrictApiClient]. */
class HttpConversationAssignmentApi(private val client: DistrictApiClient) : ConversationAssignmentApi {

    override suspend fun conversationAssignments(workspaceId: String): ApiResult<ConversationAssignmentsResponse> =
        client.get(
            segments = ExtraPaths.CONVERSATIONS_ASSIGNMENTS,
            serializer = ConversationAssignmentsResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    /**
     * ⛔ AN UNASSIGN SENDS `"assigneeEmail": null`, NOT AN ABSENT KEY. The route clears the owner only
     * for an explicit null and answers 400 `validation_failed` when the key is missing, so this body
     * is built by hand rather than through the omit-nulls encoder.
     */
    override suspend fun assignConversation(
        workspaceId: String,
        threadKey: String,
        assigneeEmail: String?,
    ): ApiResult<ConversationAssignResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.CONVERSATIONS_ASSIGN,
            serializer = ConversationAssignResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "threadKey" to JsonPrimitive(threadKey),
                "assigneeEmail" to (assigneeEmail?.let(::JsonPrimitive) ?: JsonNull),
            ),
        )
}
