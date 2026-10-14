package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.ContactBlockResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.ContactBlockingApi

/**
 * Who to block: exactly one identity.
 *
 * ⛔ A SEALED TYPE, NOT A PAIR OF OPTIONALS, so "both" and "neither" cannot be spelled at a call
 * site. The route takes `contactId` OR `phoneNumber` and answers 400 for neither.
 */
sealed interface BlockSubject {
    /** A caller who resolved to a `Contact` row. */
    data class Contact(val contactId: String) : BlockSubject

    /**
     * A caller known only by an address (an `addr:` thread). The server upserts the contact row.
     *
     * ⚠️ AN EMAIL ADDRESS GOES OUT AS ONE TOO, AND THE SERVER REFUSES IT: there is no block by email
     * on the route, and its own sentence reaches the screen (iOS does the same).
     */
    data class Phone(val phoneNumber: String) : BlockSubject
}

/** Blocking callers, and the workspace's blocked list. */
class ContactBlockingRepository(private val api: ContactBlockingApi) {

    /**
     * Leave [subject] blocked or unblocked.
     *
     * ⚠️ THE ROUTE TAKES THE DESIRED STATE, NOT A TOGGLE, so a stale screen converges on the same
     * answer instead of undoing somebody else's block. The answer names the `contactId` it applied
     * to, which for a block by number is a row the caller had never seen.
     */
    suspend fun setBlocked(
        workspaceId: String,
        subject: BlockSubject,
        blocked: Boolean,
    ): ApiResult<ContactBlockResponse> {
        val result = api.setContactBlocked(
            workspaceId = workspaceId,
            contactId = (subject as? BlockSubject.Contact)?.contactId,
            phoneNumber = (subject as? BlockSubject.Phone)?.phoneNumber,
            blocked = blocked,
        )
        return when (result) {
            is ApiResult.Success -> rejectedEnvelope(BLOCK_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }
    }

    /**
     * Everyone this workspace has blocked, newest first.
     *
     * ⛔ AN EMPTY LIST IS A REAL ANSWER AND A FAILURE IS NOT ONE. ⚠️ Only the live blocks: a row
     * whose `blockedAt` is null is not a blocked caller.
     */
    suspend fun blocked(workspaceId: String): ApiResult<List<BlockedContact>> =
        when (val result = api.blockedContacts(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(BLOCKED_ENVELOPE, result.value.success)
                    ?: ApiResult.Success(result.value.blocked.filter { it.blockedAt != null })
            is ApiResult.Failure -> result
        }

    private companion object {
        const val BLOCK_ENVELOPE = "ContactBlockResponse"
        const val BLOCKED_ENVELOPE = "BlockedContactsResponse"
    }
}
