package com.distronode.districtai.ui.contacts

import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.data.ContactBlockingRepository
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.ContactBlockResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Who this account has blocked, shared by every screen that has to say so (iOS
 * `BlockedContactsStore`).
 *
 * ⛔ ONE INSTANCE ON THE CONTAINER, NOT ONE PER SCREEN: blocking a caller from a thread must take
 * the thread out of the inbox list and badge the contact at once, and those are different screens.
 *
 * ⛔ A CACHE IN FRONT OF THE SERVER, NEVER THE AUTHORITY. The conversations route already leaves a
 * blocked contact's threads out; this exists for immediacy. It may disagree with the server for one
 * refresh, in either direction, and nothing reports that as a failure.
 *
 * ⛔ KEYED ON `contactId`, NEVER A PHONE NUMBER: the server answers a contact id for every block,
 * including one asked for by number, precisely so a client never normalises a number itself.
 */
class BlockedContactsStore(private val repository: ContactBlockingRepository) {

    /**
     * The blocked callers of [workspaceId], newest first.
     *
     * ⚠️ THE WORKSPACE TRAVELS WITH THE LIST: a list loaded for one tenant is never read as
     * another's, and an unknown workspace reads as "not blocked" (no badge) rather than a guess.
     */
    data class Snapshot(val workspaceId: String? = null, val contacts: List<BlockedContact> = emptyList()) {
        fun isBlocked(workspaceId: String, contactId: String?): Boolean =
            contactId != null && this.workspaceId == workspaceId && contacts.any { it.contactId == contactId }

        /** The blocked contact ids of [workspaceId]; empty for one this snapshot is not about. */
        fun idsFor(workspaceId: String): Set<String> =
            if (this.workspaceId == workspaceId) contacts.mapTo(mutableSetOf()) { it.contactId } else emptySet()
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /**
     * Read the list again. ⛔ A FAILURE LEAVES THE PREVIOUS LIST IN PLACE, so a transient outage does
     * not take a badge off a caller who is still blocked; the caller decides whether to say so.
     */
    suspend fun refresh(workspaceId: String): ApiResult<List<BlockedContact>> {
        val result = repository.blocked(workspaceId)
        if (result is ApiResult.Success) _state.value = Snapshot(workspaceId, result.value)
        return result
    }

    /**
     * Block or unblock [subject].
     *
     * ⛔ THE LIST IS UPDATED FROM THE REPLY, NEVER FROM THE REQUEST: the reply names the contact the
     * server applied it to, which for a block by number is a row this client had never seen.
     */
    suspend fun setBlocked(
        workspaceId: String,
        subject: BlockSubject,
        blocked: Boolean,
    ): ApiResult<ContactBlockResponse> {
        val result = repository.setBlocked(workspaceId, subject, blocked)
        if (result is ApiResult.Success) adopt(workspaceId, result.value)
        return result
    }

    /**
     * ⚠️ IT ALSO CLAIMS THE WORKSPACE, so a block made before any list read (a thread opened from a
     * notification) still shows at once. Safe: the answer is for this workspace, which the request
     * named.
     */
    private fun adopt(workspaceId: String, response: ContactBlockResponse) {
        _state.update { current ->
            val kept = if (current.workspaceId == workspaceId) current.contacts else emptyList()
            val others = kept.filterNot { it.contactId == response.contactId }
            val contacts = if (response.blockedAt != null) {
                listOf(response.asBlocked()) + others
            } else {
                others
            }
            Snapshot(workspaceId, contacts)
        }
    }
}

private fun ContactBlockResponse.asBlocked(): BlockedContact = BlockedContact(contactId, name, phoneNumber, blockedAt)
