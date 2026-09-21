package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/district/contacts/block` body: block or unblock ONE caller.
 *
 * ⛔ EXACTLY ONE OF [contactId] AND [phoneNumber] SHOULD BE SENT. A thread whose counterpart never
 * resolved to a contact has only the number, which is why both exist. Nulls are dropped by the
 * extras encoder (the route also accepts an explicit null for either).
 *
 * ⛔ [blocked] IS THE DESIRED STATE, NOT A TOGGLE, which is what makes a retry after an ambiguous
 * failure converge instead of undoing the first attempt.
 */
@Serializable
data class ContactBlockRequest(
    val workspaceId: String,
    val contactId: String? = null,
    val phoneNumber: String? = null,
    val blocked: Boolean,
)

/**
 * The block write's answer: the contact as it now stands.
 *
 * ⚠️ [blockedAt] is null after an UNBLOCK, and is the only field that says which way the write went.
 */
@Serializable
data class ContactBlockResponse(
    val success: Boolean = false,
    val contactId: String = "",
    val name: String = "",
    val phoneNumber: String? = null,
    /** ISO-8601 instant, or null when the caller is not blocked. */
    val blockedAt: String? = null,
)

/**
 * `GET /api/district/contacts/blocked?workspaceId=`: everyone the workspace has blocked, newest
 * block first.
 *
 * ⛔ AN EMPTY LIST IS A REAL ANSWER AND THE COMMON ONE; a failed read is an error result, never an
 * empty list. ⚠️ Not paged. ⛔ The set is NOT on the contact row (the contact wire is pinned byte
 * for byte), which is why it is a read of its own.
 */
@Serializable
data class BlockedContactsResponse(
    val success: Boolean = false,
    val blocked: List<BlockedContact> = emptyList(),
)

/** One blocked caller. */
@Serializable
data class BlockedContact(
    val contactId: String,
    val name: String,
    val phoneNumber: String? = null,
    /** ISO-8601 instant. The route guarantees one; nullable because the key type is `string | null`. */
    val blockedAt: String? = null,
)
