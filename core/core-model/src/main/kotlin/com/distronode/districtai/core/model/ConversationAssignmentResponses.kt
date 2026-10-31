package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * One Inbox thread's owner, as `GET /api/district/conversations/assignments` reports it.
 *
 * ⛔ KEYED ON [threadKey], THE SAME KEY [ConversationSummary.threadKey] CARRIES. The assignment is
 * its own endpoint rather than a field on the conversation list, so a client joins the two on this
 * string and needs no second identity for a thread.
 *
 * ⚠️ WHO MOVED THE THREAD IS NOT ON THE WIRE. The server stores it and deliberately leaves it out:
 * the apps show who owns a thread, not who assigned it.
 *
 * ⚠️ [assignedAt] IS AN ISO-8601 STRING. This module owns no date parsing.
 */
@Serializable
data class ConversationAssignment(
    val threadKey: String = "",
    val assigneeEmail: String = "",
    val assignedAt: String? = null,
)

/**
 * `GET /api/district/conversations/assignments?workspaceId=`: every assigned thread in the
 * workspace. Every member role may read it.
 *
 * ⚠️ A THREAD WITH NO OWNER HAS NO ROW. An unassigned thread is absent from [assignments] rather
 * than present with an empty assignee, so "not in the list" is the unassigned state.
 */
@Serializable
data class ConversationAssignmentsResponse(
    val success: Boolean = false,
    val assignments: List<ConversationAssignment> = emptyList(),
)
