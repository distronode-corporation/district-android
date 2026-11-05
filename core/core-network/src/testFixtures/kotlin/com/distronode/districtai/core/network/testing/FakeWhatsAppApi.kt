package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.WhatsAppTemplatesResponse
import com.distronode.districtai.core.model.WhatsAppWindow
import com.distronode.districtai.core.model.WhatsAppWindowResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.WhatsAppApi
import kotlinx.coroutines.CompletableDeferred

/**
 * A [WhatsAppApi] that records every read and answers as configured.
 *
 * ⚠️ The defaults are an OPEN window from `wa_sender_1` and no templates, so a test that opens a
 * WhatsApp thread for another reason sends free text as before.
 */
class FakeWhatsAppApi : WhatsAppApi {

    /** (workspaceId, to) pairs. */
    val windowReads: MutableList<Pair<String, String>> = mutableListOf()

    /** (workspaceId, senderId) pairs. */
    val templateReads: MutableList<Pair<String, String>> = mutableListOf()

    var windowResult: ApiResult<WhatsAppWindowResponse> = ApiResult.Success(
        WhatsAppWindowResponse(
            success = true,
            window = WhatsAppWindow(open = true, expiresAt = "2026-11-06T12:00:00.000Z"),
            senderId = "wa_sender_1",
        ),
    )

    var templatesResult: ApiResult<WhatsAppTemplatesResponse> =
        ApiResult.Success(WhatsAppTemplatesResponse(success = true))

    /** When set, a window read waits for it: an answer still on its way. */
    var windowGate: CompletableDeferred<Unit>? = null

    /** When set, a template read waits for it. */
    var templatesGate: CompletableDeferred<Unit>? = null

    override suspend fun whatsAppWindow(workspaceId: String, to: String): ApiResult<WhatsAppWindowResponse> {
        windowReads += workspaceId to to
        windowGate?.await()
        return windowResult
    }

    override suspend fun whatsAppTemplates(
        workspaceId: String,
        senderId: String,
    ): ApiResult<WhatsAppTemplatesResponse> {
        templateReads += workspaceId to senderId
        templatesGate?.await()
        return templatesResult
    }
}
