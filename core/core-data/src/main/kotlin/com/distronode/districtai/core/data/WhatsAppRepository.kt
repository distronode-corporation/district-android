package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MetaWindowResponse
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.model.sendableWhatsAppTemplates
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.WhatsAppApi

/**
 * The composer's channel-window reads: WhatsApp's window and the templates for a closed one (app
 * parity 5), and the Messenger and Instagram reply window. The template send is
 * [InboxRepository.sendTemplate], beside every other send.
 */
class WhatsAppRepository(private val api: WhatsAppApi) {

    /** Whether free text may go to [to] now, and the sender the send route would use. */
    suspend fun window(workspaceId: String, to: String): ApiResult<WhatsAppWindowResponse> =
        when (val result = api.whatsAppWindow(workspaceId, to)) {
            is ApiResult.Success -> rejectedEnvelope(WINDOW_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * The templates the composer may offer for [senderId], in the server's order.
     *
     * ⛔ ONLY THE SENDABLE ONES ([sendableWhatsAppTemplates]): the route also lists pending and named
     * templates for Settings, and the send route refuses every one of those.
     */
    suspend fun templates(workspaceId: String, senderId: String): ApiResult<List<WhatsAppTemplate>> =
        when (val result = api.whatsAppTemplates(workspaceId, senderId)) {
            is ApiResult.Success -> rejectedEnvelope(TEMPLATES_ENVELOPE, result.value.success)
                ?: ApiResult.Success(sendableWhatsAppTemplates(result.value.templates, senderId))
            is ApiResult.Failure -> result
        }

    /**
     * Messenger's or Instagram's reply window for [to] on [channel].
     *
     * ⚠️ A 200 that does not affirm `success: true` is a failure, as for the WhatsApp window; the
     * caller reads a failure as "unknown", which blocks nothing.
     */
    suspend fun metaWindow(workspaceId: String, channel: String, to: String): ApiResult<MetaWindowResponse> =
        when (val result = api.metaWindow(workspaceId, channel, to)) {
            is ApiResult.Success -> rejectedEnvelope(META_WINDOW_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    private companion object {
        const val META_WINDOW_ENVELOPE = "MetaWindowResponse"
        const val WINDOW_ENVELOPE = "WhatsAppWindowResponse"
        const val TEMPLATES_ENVELOPE = "WhatsAppTemplatesResponse"
    }
}
