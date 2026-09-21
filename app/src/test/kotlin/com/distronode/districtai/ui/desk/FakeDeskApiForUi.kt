package com.distronode.districtai.ui.desk

import com.distronode.districtai.core.model.DeskLogoRemovalResponse
import com.distronode.districtai.core.model.DeskReplyResponse
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.DeskSettingsResponse
import com.distronode.districtai.core.model.DeskTicketCreateResponse
import com.distronode.districtai.core.model.DeskTicketDetail
import com.distronode.districtai.core.model.DeskTicketDraft
import com.distronode.districtai.core.model.DeskTicketResponse
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketStatusResponse
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.core.model.DeskTicketsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DeskApi

/**
 * A settable [DeskApi] for the desk ViewModels.
 *
 * ⛔ ITS OWN FAKE RATHER THAN AN ENTRY ON `TestDistrictApi`. [DeskApi] is a separate interface from
 * `DistrictApi` — see the ⛔ on `HttpDeskApi` — so there is nothing to add there, and a per-feature
 * fake is also what keeps three parallel workstreams out of one 800-line file.
 *
 * ⚠️ COUNTS READS AS WELL AS RECORDING WRITES, because several of the assertions in this package are
 * about a request NOT being sent: a viewer must spend none, a disabled desk must not fetch a queue,
 * and a local filter must not re-read.
 */
internal class FakeDeskApiForUi : DeskApi {

    var settingsResult: ApiResult<DeskSettingsResponse> =
        ApiResult.Success(DeskSettingsResponse(success = true, settings = DeskSettings()))
    var logoRemovalResult: ApiResult<DeskLogoRemovalResponse> =
        ApiResult.Success(DeskLogoRemovalResponse(success = true, settings = DeskSettings()))
    var ticketsResult: ApiResult<DeskTicketsResponse> =
        ApiResult.Success(DeskTicketsResponse(success = true))
    var ticketResult: ApiResult<DeskTicketResponse> =
        ApiResult.Success(DeskTicketResponse(success = true, ticket = DeskTicketDetail()))
    var createResult: ApiResult<DeskTicketCreateResponse> =
        ApiResult.Success(DeskTicketCreateResponse(success = true, ticket = DeskTicketSummary()))
    var replyResult: ApiResult<DeskReplyResponse> =
        ApiResult.Success(DeskReplyResponse(success = true))
    var statusResult: ApiResult<DeskTicketStatusResponse> =
        ApiResult.Success(DeskTicketStatusResponse(success = true, ticket = DeskTicketSummary()))

    var settingsReads = 0
    var ticketReads = 0
    var ticketDetailReads = 0
    var logoUploads = 0
    var logoDeletes = 0

    val patches = mutableListOf<DeskSettingsPatch>()
    val createDrafts = mutableListOf<DeskTicketDraft>()
    val replyBodies = mutableListOf<String>()
    val statuses = mutableListOf<DeskTicketStatus>()
    val uploadedMimeTypes = mutableListOf<String>()

    override suspend fun deskSettings(workspaceId: String) =
        settingsResult.also { settingsReads++ }

    override suspend fun saveDeskSettings(workspaceId: String, patch: DeskSettingsPatch) =
        settingsResult.also { patches += patch }

    override suspend fun uploadDeskLogo(
        workspaceId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) = settingsResult.also {
        logoUploads++
        uploadedMimeTypes += mimeType
    }

    override suspend fun deleteDeskLogo(workspaceId: String) =
        logoRemovalResult.also { logoDeletes++ }

    override suspend fun deskTickets(workspaceId: String, status: DeskTicketStatus?) =
        ticketsResult.also { ticketReads++ }

    override suspend fun createDeskTicket(
        workspaceId: String,
        draft: DeskTicketDraft,
        idempotencyKey: String?,
    ) = createResult.also { createDrafts += draft }

    override suspend fun deskTicket(workspaceId: String, ticketId: String) =
        ticketResult.also { ticketDetailReads++ }

    override suspend fun replyToDeskTicket(
        workspaceId: String,
        ticketId: String,
        message: String,
        idempotencyKey: String?,
    ) = replyResult.also { replyBodies += message }

    override suspend fun setDeskTicketStatus(
        workspaceId: String,
        ticketId: String,
        status: DeskTicketStatus,
    ) = statusResult.also { statuses += status }
}
