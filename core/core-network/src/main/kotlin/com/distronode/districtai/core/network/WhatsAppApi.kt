package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindowResponse

/**
 * The WhatsApp composer's two reads (app parity 5): the customer-service window for one number, and
 * a sender's templates for when it is closed. The send itself is `InboxApi.sendMessage`.
 *
 * A sibling interface rather than a section of `InboxApi` for the reason [ExtraPaths] gives.
 */
interface WhatsAppApi {

    /**
     * Whether free text may go to [to] now, and from which sender.
     *
     * ⚠️ A PREVIEW ONLY: the send route checks the same window itself, so an answer that is stale
     * by the time of the send is refused there with `whatsapp_window_closed`.
     */
    suspend fun whatsAppWindow(workspaceId: String, to: String): ApiResult<WhatsAppWindowResponse>

    /** [senderId]'s templates, pending ones included; the caller keeps only the sendable ones. */
    suspend fun whatsAppTemplates(workspaceId: String, senderId: String): ApiResult<WhatsAppTemplatesResponse>
}

/** [WhatsAppApi] over [DistrictApiClient]. Two GETs; neither writes anything. */
class HttpWhatsAppApi(private val client: DistrictApiClient) : WhatsAppApi {

    override suspend fun whatsAppWindow(workspaceId: String, to: String): ApiResult<WhatsAppWindowResponse> =
        client.get(
            segments = ExtraPaths.WORKSPACE_WHATSAPP_WINDOW,
            serializer = WhatsAppWindowResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "to" to to),
        )

    override suspend fun whatsAppTemplates(
        workspaceId: String,
        senderId: String,
    ): ApiResult<WhatsAppTemplatesResponse> =
        client.get(
            segments = ExtraPaths.WORKSPACE_WHATSAPP_TEMPLATES,
            serializer = WhatsAppTemplatesResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "senderId" to senderId),
        )
}
