package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/district/messages/{id}?workspaceId=` — one message id exchanged for the thread it
 * belongs to.
 *
 * ⛔ THE RESOLVER A MESSAGE PUSH CANNOT DO WITHOUT. The payload carries
 * `{type, category, workspaceId, messageId}` and every other endpoint on this surface is addressed
 * by THREAD, so a notification can neither deep-link into the conversation nor offer Reply /
 * Mark read without this one request. ⛔ Widening the push payload instead is the bug this route
 * exists to prevent: a `threadKey` is `addr:<address>` whenever the thread has no `Contact` row,
 * which would put a customer's phone number or email address on a lock screen and in front of
 * every installed notification-listener app.
 *
 * ⛔ ANSWERS **409** FOR A ROW WITH NO ADDRESSABLE COUNTERPART, and **404** identically for "not in
 * this workspace" and "does not exist". Neither is a retry: the first is a malformed row and the
 * second is a deliberate refusal to confirm whether an id exists elsewhere on the platform.
 */
@Serializable
data class MessageThreadResponse(
    val success: Boolean = false,
    val message: MessageThreadMessage = MessageThreadMessage(),
    val thread: MessageThreadTarget = MessageThreadTarget(),
)

/**
 * The row the push named, reduced to what a notification needs.
 *
 * ⚠️ NO `body` AND NO `subject`. The route deliberately publishes envelope facts only; the content
 * is read through the thread timeline, which is scoped by RLS the same way.
 */
@Serializable
data class MessageThreadMessage(
    val id: String = "",
    val direction: String = "",
    val type: String? = null,
    /** ⚠️ null for an unread message. An ISO-8601 string otherwise; this module parses no dates. */
    val readAt: String? = null,
    val createdAt: String = "",
)

/**
 * Where to navigate.
 *
 * ⚠️ [threadKey] IS THE SAME VALUE THE CONVERSATION LIST COMPUTES — `contact:<id>` when the thread
 * has a `Contact` row and `addr:<address>` when it does not — which is what lets a push open the
 * already-loaded conversation instead of forking a new one.
 */
@Serializable
data class MessageThreadTarget(
    val threadKey: String = "",
    val contactId: String? = null,
    val counterpart: String = "",
    val channel: String = "",
)
