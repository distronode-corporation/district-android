package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.ContactBlockResponse

/**
 * Blocking a caller, and reading back who is blocked (required on iOS by App Store Guideline 1.2).
 *
 * ⛔ THE CONTRACT ONLY, WITH NO IMPLEMENTATION. No screen on this client blocks anyone yet; the
 * interface exists so the endpoint parity gate (which reads interfaces) holds against iOS. Its HTTP
 * implementation was unreachable code and is not kept. The block UI that needs it adds one: a POST
 * to `contacts/block` and a GET of `contacts/blocked` (one letter apart, different verbs), with a
 * request test asserting that an unblock still sends `blocked: false`. A sibling interface rather
 * than a section of `ContactsApi` for the reason [ExtraPaths] gives.
 *
 * ⛔ NOT AVAILABLE TO `viewer`, server-side: a block changes who can reach the business.
 */
interface ContactBlockingApi {

    /**
     * Leave one caller blocked ([blocked] true) or unblocked (false). Idempotent.
     *
     * ⛔ Pass exactly one of [contactId] and [phoneNumber]; this layer does not enforce it.
     */
    suspend fun setContactBlocked(
        workspaceId: String,
        contactId: String?,
        phoneNumber: String?,
        blocked: Boolean,
    ): ApiResult<ContactBlockResponse>

    /** Everyone this workspace has blocked. An empty list is a real answer. */
    suspend fun blockedContacts(workspaceId: String): ApiResult<BlockedContactsResponse>
}
