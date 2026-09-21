package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.MessageThreadResponse

/** [InboxExtrasApi] over [DistrictApiClient]. Two GETs; neither writes anything. */
class HttpInboxExtrasApi(private val client: DistrictApiClient) : InboxExtrasApi {

    /**
     * ⚠️ THE QUERY IS SENT AS-TYPED, NOT PRE-TRIMMED HERE. The server trims and then measures, and
     * a client that trimmed as well would be a second opinion about the same rule; the repository
     * above decides whether the request is worth making at all.
     */
    override suspend fun searchMessages(
        workspaceId: String,
        query: String,
    ): ApiResult<MessageSearchResponse> =
        client.get(
            segments = ExtraPaths.MESSAGES_SEARCH,
            serializer = MessageSearchResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "q" to query),
        )

    override suspend fun messageThread(
        workspaceId: String,
        messageId: String,
    ): ApiResult<MessageThreadResponse> =
        client.get(
            // ⛔ The id is a PATH SEGMENT. `addPathSegment` percent-encodes it, so a hostile value
            // arriving from a push cannot climb out of the `messages/` family.
            segments = ExtraPaths.messageThread(messageId),
            serializer = MessageThreadResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )
}
