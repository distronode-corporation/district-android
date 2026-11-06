package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.MetaWindowResponse
import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindowResponse

/**
 * The composer's channel-window reads: WhatsApp's customer-service window for one number and a
 * sender's templates for when it is closed (app parity 5), and the Messenger and Instagram reply
 * window. The send itself is `InboxApi.sendMessage`.
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

    /**
     * Messenger's or Instagram's reply window for [to] (`fb:<psid>` or `ig:<igsid>`) on [channel]
     * (`messenger` or `instagram`): open, open for a human agent's reply only, or closed.
     *
     * ⚠️ A PREVIEW ONLY, as [whatsAppWindow] is: the send refuses a closed window itself with
     * `messenger_window_closed`. ⛔ THE GET BESIDE THE SEND POST, on the same path: it writes
     * nothing and sends nothing.
     */
    suspend fun metaWindow(workspaceId: String, channel: String, to: String): ApiResult<MetaWindowResponse>
}

/** [WhatsAppApi] over [DistrictApiClient]. Three GETs; none writes anything. */
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

    override suspend fun metaWindow(
        workspaceId: String,
        channel: String,
        to: String,
    ): ApiResult<MetaWindowResponse> =
        client.get(
            // ⛔ The send's own path, read with GET: the server answers the window there.
            segments = DistrictPaths.MESSAGES_SEND,
            serializer = MetaWindowResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "channel" to channel, "to" to to),
        )
}
