package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.CallHangUpRequest
import com.distronode.districtai.core.model.CallHangUpResponse

/** [CallControlApi] over [DistrictApiClient]. */
class HttpCallControlApi(private val client: DistrictApiClient) : CallControlApi {

    /**
     * ⚠️ [DistrictApiClient.send] RE-SENDS ONCE ON A 401 AND THAT IS SAFE HERE, unlike on `dial`,
     * for the reason the interface states: this route is idempotent by contract.
     */
    override suspend fun hangUpCall(
        workspaceId: String,
        callId: String,
    ): ApiResult<CallHangUpResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.callHangUp(callId),
            serializer = CallHangUpResponse.serializer(),
            // ⛔ THE WORKSPACE TRAVELS IN THE BODY. The route parses `req.json()` with a zod schema
            // requiring a non-empty `workspaceId`; a query parameter would leave it undefined and
            // answer 400 "Missing required parameters" while the URL looked perfectly correct.
            body = CallHangUpRequest(workspaceId = workspaceId)
                .toExtraJson(CallHangUpRequest.serializer()),
        )
}
