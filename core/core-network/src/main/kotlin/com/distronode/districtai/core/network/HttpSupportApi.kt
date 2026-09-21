package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.SupportCloseResponse
import com.distronode.districtai.core.model.SupportReplyResponse
import com.distronode.districtai.core.model.SupportRequestCreateResponse
import com.distronode.districtai.core.model.SupportRequestDetailResponse
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestListResponse
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [SupportApi] over [DistrictApiClient].
 *
 * ⚠️ BODIES BUILT AS `JsonObject`s, MATCHING [HttpDeskApi] RATHER THAN THE REST OF THE MODULE. The
 * desk needs it (a three-state field the shared encoder cannot express); this one does not, and
 * follows it so the two adjacent, easily-confused surfaces read the same way. The keys asserted by
 * `SupportRequestBodyTest` are the contract either way.
 */
class HttpSupportApi(private val client: DistrictApiClient) : SupportApi {

    override suspend fun supportRequests(
        workspaceId: String,
    ): ApiResult<SupportRequestListResponse> =
        client.get(
            segments = SupportPaths.REQUESTS,
            serializer = SupportRequestListResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    /**
     * ⛔ THE PAYLOAD IS EXACTLY THESE FOUR KEYS AND NOTHING MAY BE ADDED. See the ⛔ on
     * `SupportRequestDraft`: an unknown field is a hard 400 at Atlassian rather than an ignored key.
     */
    override suspend fun createSupportRequest(
        workspaceId: String,
        draft: SupportRequestDraft,
        idempotencyKey: String?,
    ): ApiResult<SupportRequestCreateResponse> =
        client.send(
            method = "POST",
            segments = SupportPaths.REQUESTS,
            serializer = SupportRequestCreateResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = jsonObjectOf(
                "kind" to JsonPrimitive(draft.kind.wire),
                "subject" to JsonPrimitive(draft.subject),
                "message" to JsonPrimitive(draft.message),
                "idempotencyKey" to idempotencyKey?.let(::JsonPrimitive),
            ),
        )

    override suspend fun supportRequest(
        workspaceId: String,
        key: String,
    ): ApiResult<SupportRequestDetailResponse> =
        client.get(
            segments = SupportPaths.request(key),
            serializer = SupportRequestDetailResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )

    override suspend fun replyToSupportRequest(
        workspaceId: String,
        key: String,
        body: String,
    ): ApiResult<SupportReplyResponse> =
        client.send(
            method = "POST",
            segments = SupportPaths.requestReply(key),
            serializer = SupportReplyResponse.serializer(),
            query = workspaceQuery(workspaceId),
            // ⛔ `body`, NOT `message`. The desk's reply one family over is the other way round.
            body = jsonObjectOf("body" to JsonPrimitive(body)),
        )

    /**
     * ⛔ NO BODY AT ALL. `send` sends none when `body` is null, which is what this route expects —
     * the handler never calls `req.json()`.
     */
    override suspend fun closeSupportRequest(
        workspaceId: String,
        key: String,
    ): ApiResult<SupportCloseResponse> =
        client.send(
            method = "POST",
            segments = SupportPaths.requestClose(key),
            serializer = SupportCloseResponse.serializer(),
            query = workspaceQuery(workspaceId),
        )
}

private fun workspaceQuery(workspaceId: String): Map<String, String?> =
    mapOf("workspaceId" to workspaceId)

/** ⚠️ Null-valued pairs are dropped. Nothing on this surface sends an explicit null. */
private fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

private object SupportPaths {
    private val SUPPORT = listOf("api", "district", "support")

    val REQUESTS = SUPPORT + "requests"

    /** ⚠️ Either the Jira issue key (`DA-42`) or our own row id; both resolve server-side. */
    fun request(key: String) = REQUESTS + key

    fun requestReply(key: String) = request(key) + "reply"

    fun requestClose(key: String) = request(key) + "close"
}
