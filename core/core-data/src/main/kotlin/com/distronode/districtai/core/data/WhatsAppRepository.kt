package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.model.sendableWhatsAppTemplates
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.WhatsAppApi

/**
 * The WhatsApp composer's reads (app parity 5): the window, and the templates for a closed one. The
 * template send is [InboxRepository.sendTemplate], beside every other send.
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

    private companion object {
        const val WINDOW_ENVELOPE = "WhatsAppWindowResponse"
        const val TEMPLATES_ENVELOPE = "WhatsAppTemplatesResponse"
    }
}
