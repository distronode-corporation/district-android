package com.distronode.districtai.core.data

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
 * Fakes for the four interfaces that deliberately sit beside `DistrictApi`.
 *
 * ⛔ SMALL AND LOCAL RATHER THAN BOLTED ONTO `Fakes.kt`. That file implements an 85-method
 * interface and is the same merge hazard the production split exists to avoid; these four have one
 * to four methods each, so a per-interface fake costs nothing and records exactly what a test needs
 * to assert.
 */
internal class FakePersonaApi : PersonaApi {

    var optionsResult: ApiResult<PersonaOptionsResponse> =
        ApiResult.Success(PersonaOptionsResponse(success = true))

    var previewResult: ApiResult<PersonaPreviewTokenResponse> =
        ApiResult.Success(PersonaPreviewTokenResponse(success = true))

    /** ⚠️ Every mint, recorded, because the route is billable and NOT idempotent. */
    val previewCalls = mutableListOf<Pair<String, PersonaPreviewForm>>()

    override suspend fun personaOptions(workspaceId: String) = optionsResult

    override suspend fun personaPreviewToken(
        workspaceId: String,
        form: PersonaPreviewForm,
    ): ApiResult<PersonaPreviewTokenResponse> {
        previewCalls += workspaceId to form
        return previewResult
    }
}

internal class FakeCallHandlingApi : CallHandlingApi {

    var handlingResult: ApiResult<CallHandlingResponse> =
        ApiResult.Success(CallHandlingResponse(success = true))

    var availabilityResult: ApiResult<AvailabilityResponse> =
        ApiResult.Success(AvailabilityResponse(success = true))

    /** ⚠️ The exact pair sent, so "only what changed goes on the wire" is checkable. */
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

internal class FakeInboxExtrasApi : InboxExtrasApi {

    var searchResult: ApiResult<MessageSearchResponse> =
        ApiResult.Success(MessageSearchResponse(success = true))

    var threadResult: ApiResult<MessageThreadResponse> =
        ApiResult.Success(MessageThreadResponse(success = true))

    /** ⚠️ Recorded so "a query below the floor sends nothing" is an assertion rather than a hope. */
    val searches = mutableListOf<String>()

    override suspend fun searchMessages(
        workspaceId: String,
        query: String,
    ): ApiResult<MessageSearchResponse> {
        searches += query
        return searchResult
    }

    override suspend fun messageThread(workspaceId: String, messageId: String) = threadResult
}

internal class FakeCallControlApi : CallControlApi {

    var result: ApiResult<CallHangUpResponse> = ApiResult.Success(CallHangUpResponse(success = true))

    val hangUps = mutableListOf<Pair<String, String>>()

    override suspend fun hangUpCall(
        workspaceId: String,
        callId: String,
    ): ApiResult<CallHangUpResponse> {
        hangUps += workspaceId to callId
        return result
    }
}
