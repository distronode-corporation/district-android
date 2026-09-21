package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.ContactBlockRequest
import com.distronode.districtai.core.model.ContactBlockResponse

/** [ContactBlockingApi] over [DistrictApiClient]. One POST, one GET. */
class HttpContactBlockingApi(private val client: DistrictApiClient) : ContactBlockingApi {

    override suspend fun setContactBlocked(
        workspaceId: String,
        contactId: String?,
        phoneNumber: String?,
        blocked: Boolean,
    ): ApiResult<ContactBlockResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.CONTACTS_BLOCK,
            serializer = ContactBlockResponse.serializer(),
            // ⛔ `blocked` is non-nullable, so the null-dropping encoder can never lose a `false`,
            // which is exactly the value an unblock sends.
            body = ContactBlockRequest(
                workspaceId = workspaceId,
                contactId = contactId,
                phoneNumber = phoneNumber,
                blocked = blocked,
            ).toExtraJson(ContactBlockRequest.serializer()),
        )

    override suspend fun blockedContacts(workspaceId: String): ApiResult<BlockedContactsResponse> =
        client.get(
            segments = ExtraPaths.CONTACTS_BLOCKED,
            serializer = BlockedContactsResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )
}
