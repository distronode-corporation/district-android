package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.ContactBlockResponse
import kotlinx.serialization.json.JsonPrimitive

/**
 * Blocking a caller, and reading back who is blocked (App Store Guideline 1.2 on iOS; the Play
 * user-generated content policy here).
 *
 * ⛔ TWO ROUTES ONE LETTER APART WITH DIFFERENT VERBS: a POST to `contacts/block` and a GET of
 * `contacts/blocked`. `ContactBlockingRequestTest` pins both, and that an unblock still sends
 * `blocked: false` rather than dropping the key. A sibling interface rather than a section of
 * `ContactsApi` for the reason [ExtraPaths] gives.
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

/** [ContactBlockingApi] over [DistrictApiClient]. */
class HttpContactBlockingApi(private val client: DistrictApiClient) : ContactBlockingApi {

    /**
     * ⚠️ THE ABSENT IDENTITY IS LEFT OUT OF THE BODY, NOT SENT AS NULL, and [blocked] is always sent:
     * the route takes the desired STATE, so an unblock without `blocked: false` would be a 400.
     */
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
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "contactId" to contactId?.let(::JsonPrimitive),
                "phoneNumber" to phoneNumber?.let(::JsonPrimitive),
                "blocked" to JsonPrimitive(blocked),
            ),
        )

    override suspend fun blockedContacts(workspaceId: String): ApiResult<BlockedContactsResponse> =
        client.get(
            segments = ExtraPaths.CONTACTS_BLOCKED,
            serializer = BlockedContactsResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )
}
