package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.MetaWindow
import com.distronode.districtai.core.model.MetaWindowResponse
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
 * WhatsApp thread for another reason sends free text as before. ⚠️ With no `expiresAt`: the
 * composer reads a past one as closed and re-reads at a future one, so a fixed date here would
 * change what every such test sees once the clock passes it.
 */
class FakeWhatsAppApi : WhatsAppApi {

    /** (workspaceId, to) pairs. */
    val windowReads: MutableList<Pair<String, String>> = mutableListOf()

    /** (workspaceId, senderId) pairs. */
    val templateReads: MutableList<Pair<String, String>> = mutableListOf()

    var windowResult: ApiResult<WhatsAppWindowResponse> = ApiResult.Success(
        WhatsAppWindowResponse(
            success = true,
            window = WhatsAppWindow(open = true),
            senderId = "wa_sender_1",
        ),
    )

    var templatesResult: ApiResult<WhatsAppTemplatesResponse> =
        ApiResult.Success(WhatsAppTemplatesResponse(success = true))

    /** When set, a window read waits for it: an answer still on its way. */
    var windowGate: CompletableDeferred<Unit>? = null

    /** When set, a template read waits for it. */
    var templatesGate: CompletableDeferred<Unit>? = null

    /** (workspaceId, channel, to) triples. */
    val metaWindowReads: MutableList<Triple<String, String, String>> = mutableListOf()

    /** ⚠️ The default is an OPEN Messenger window, so a Messenger thread sends free text as before. */
    var metaWindowResult: ApiResult<MetaWindowResponse> = ApiResult.Success(
        MetaWindowResponse(success = true, window = MetaWindow(state = "open")),
    )

    /** When set, a Messenger or Instagram window read waits for it: an answer still on its way. */
    var metaWindowGate: CompletableDeferred<Unit>? = null

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

    override suspend fun metaWindow(workspaceId: String, channel: String, to: String): ApiResult<MetaWindowResponse> {
        metaWindowReads += Triple(workspaceId, channel, to)
        metaWindowGate?.await()
        return metaWindowResult
    }
}
