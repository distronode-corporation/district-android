package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AvailabilityPatchRequest
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandlingPatchRequest
import com.distronode.districtai.core.model.CallHandlingResponse

/**
 * [CallHandlingApi] over [DistrictApiClient].
 *
 * ⛔ BOTH WRITES ARE **PATCH**, NOT POST. Each route exports PATCH only; a POST would 405.
 */
class HttpCallHandlingApi(private val client: DistrictApiClient) : CallHandlingApi {

    override suspend fun callHandling(workspaceId: String): ApiResult<CallHandlingResponse> =
        client.get(
            segments = ExtraPaths.WORKSPACE_CALL_HANDLING,
            serializer = CallHandlingResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun saveCallHandling(
        workspaceId: String,
        callHandling: String?,
        appRingSeconds: Int?,
    ): ApiResult<CallHandlingResponse> =
        client.send(
            method = "PATCH",
            segments = ExtraPaths.WORKSPACE_CALL_HANDLING,
            serializer = CallHandlingResponse.serializer(),
            // ⛔ Nulls are DROPPED, which is what lets either field be sent alone. Sending an
            // explicit `null` would fail the route's zod schema rather than being ignored.
            body = CallHandlingPatchRequest(
                workspaceId = workspaceId,
                callHandling = callHandling,
                appRingSeconds = appRingSeconds,
            ).toExtraJson(CallHandlingPatchRequest.serializer()),
        )

    override suspend fun availability(workspaceId: String): ApiResult<AvailabilityResponse> =
        client.get(
            segments = ExtraPaths.WORKSPACE_AVAILABILITY,
            serializer = AvailabilityResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId),
        )

    override suspend fun saveAvailability(
        workspaceId: String,
        availableForCalls: Boolean,
    ): ApiResult<AvailabilityResponse> =
        client.send(
            method = "PATCH",
            segments = ExtraPaths.WORKSPACE_AVAILABILITY,
            serializer = AvailabilityResponse.serializer(),
            // ⛔ `availableForCalls` IS NON-NULLABLE ON THE REQUEST TYPE, so the null-dropping
            // encoder cannot lose it — which matters because the route's schema requires it and
            // `false` is exactly the value a careless optional would omit.
            body = AvailabilityPatchRequest(
                workspaceId = workspaceId,
                availableForCalls = availableForCalls,
            ).toExtraJson(AvailabilityPatchRequest.serializer()),
        )
}
