package com.distronode.districtai.ui

import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHangUpResponse
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.core.model.MessageSearchResponse
import com.distronode.districtai.core.model.MessageThreadResponse
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CallControlApi
import com.distronode.districtai.core.network.CallHandlingApi
import com.distronode.districtai.core.network.InboxExtrasApi
import com.distronode.districtai.core.network.PersonaApi

/**
 * Fakes for the four interfaces that sit beside `DistrictApi`.
 *
 * ⛔ SEPARATE FROM [TestDistrictApi] RATHER THAN FOLDED IN. That class already crossed detekt's
 * `LargeClass` ceiling once and had to delegate a slice out; these four have one to four methods
 * each, and keeping them apart is also what keeps the production split's whole point — that adding
 * an endpoint does not touch a file everyone else is editing — true of the test tier too.
 */
internal class TestPersonaApi : PersonaApi {

    var optionsResult: ApiResult<PersonaOptionsResponse> =
        ApiResult.Success(PersonaOptionsResponse(success = true))

    var previewResult: ApiResult<PersonaPreviewTokenResponse> =
        ApiResult.Success(PersonaPreviewTokenResponse(success = true))

    /** ⚠️ Every mint, recorded, because the route is billable and NOT idempotent. */
    val previewCalls = mutableListOf<PersonaPreviewForm>()

    override suspend fun personaOptions(workspaceId: String) = optionsResult

    override suspend fun personaPreviewToken(
        workspaceId: String,
        form: PersonaPreviewForm,
    ): ApiResult<PersonaPreviewTokenResponse> {
        previewCalls += form
        return previewResult
    }
}

internal class TestCallHandlingApi : CallHandlingApi {

    var handlingResult: ApiResult<CallHandlingResponse> =
        ApiResult.Success(CallHandlingResponse(success = true))

    var availabilityResult: ApiResult<AvailabilityResponse> =
        ApiResult.Success(AvailabilityResponse(success = true))

    val handlingWrites = mutableListOf<Pair<String?, Int?>>()
    val availabilityWrites = mutableListOf<Boolean>()

    override suspend fun callHandling(workspaceId: String) = handlingResult

    override suspend fun saveCallHandling(
        workspaceId: String,
        callHandling: String?,
        appRingSeconds: Int?,
    ): ApiResult<CallHandlingResponse> {
        handlingWrites += callHandling to appRingSeconds
        return handlingResult
    }

    override suspend fun availability(workspaceId: String) = availabilityResult

    override suspend fun saveAvailability(
        workspaceId: String,
        availableForCalls: Boolean,
    ): ApiResult<AvailabilityResponse> {
        availabilityWrites += availableForCalls
        return availabilityResult
    }
}

internal class TestInboxExtrasApi : InboxExtrasApi {

    var searchResult: ApiResult<MessageSearchResponse> =
        ApiResult.Success(MessageSearchResponse(success = true))

    var threadResult: ApiResult<MessageThreadResponse> =
        ApiResult.Success(MessageThreadResponse(success = true))

    val searches = mutableListOf<String>()

    override suspend fun searchMessages(
        workspaceId: String,
        query: String,
    ): ApiResult<MessageSearchResponse> {
        searches += query
        return searchResult
    }

    /** (workspaceId, messageId) pairs: the push resolver must send the PUSH's workspace, not a fallback. */
    val threadRequests = mutableListOf<Pair<String, String>>()

    override suspend fun messageThread(workspaceId: String, messageId: String): ApiResult<MessageThreadResponse> {
        threadRequests += workspaceId to messageId
        return threadResult
    }
}

internal class TestCallControlApi : CallControlApi {

    var result: ApiResult<CallHangUpResponse> =
        ApiResult.Success(CallHangUpResponse(success = true, ended = true))

    /** ⛔ The count matters as much as the content: nothing may retry a hang-up. */
    val hangUps = mutableListOf<Pair<String, String>>()

    override suspend fun hangUpCall(
        workspaceId: String,
        callId: String,
    ): ApiResult<CallHangUpResponse> {
        hangUps += workspaceId to callId
        return result
    }
}
