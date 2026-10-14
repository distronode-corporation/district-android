package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.ContactBlockResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.ContactBlockingApi
import kotlinx.coroutines.CompletableDeferred

/**
 * A [ContactBlockingApi] that records every write and answers as configured.
 *
 * ⚠️ By default a write answers the state it asked for, for a contact id derived from the request,
 * the way the route does.
 */
class FakeContactBlockingApi : ContactBlockingApi {

    /** One write as sent. */
    data class Write(val workspaceId: String, val contactId: String?, val phoneNumber: String?, val blocked: Boolean)

    val writes: MutableList<Write> = mutableListOf()
    val reads: MutableList<String> = mutableListOf()

    /** When set, every write answers this instead of echoing the request. */
    var writeResult: ApiResult<ContactBlockResponse>? = null

    var blockedResult: ApiResult<BlockedContactsResponse> =
        ApiResult.Success(BlockedContactsResponse(success = true))

    /** When set, a write waits for it before answering: a write still in flight. */
    var writeGate: CompletableDeferred<Unit>? = null

    override suspend fun setContactBlocked(
        workspaceId: String,
        contactId: String?,
        phoneNumber: String?,
        blocked: Boolean,
    ): ApiResult<ContactBlockResponse> {
        writes += Write(workspaceId, contactId, phoneNumber, blocked)
        writeGate?.await()
        return writeResult ?: ApiResult.Success(
            ContactBlockResponse(
                success = true,
                contactId = contactId ?: "contact-for-$phoneNumber",
                name = "Caller",
                phoneNumber = phoneNumber,
                blockedAt = if (blocked) "2026-10-13T12:00:00.000Z" else null,
            ),
        )
    }

    override suspend fun blockedContacts(workspaceId: String): ApiResult<BlockedContactsResponse> {
        reads += workspaceId
        return blockedResult
    }
}
