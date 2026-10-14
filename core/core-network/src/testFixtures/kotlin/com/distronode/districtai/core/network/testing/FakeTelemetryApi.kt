package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.TelemetryTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.TelemetryApi

/**
 * A [TelemetryApi] that answers from a queue, then [fallback].
 *
 * ⚠️ The default answer is a usable credential for a loopback `ws://` address, so a test about the
 * transcript does not have to spell one out.
 */
class FakeTelemetryApi : TelemetryApi {

    val results: ArrayDeque<ApiResult<TelemetryTokenResponse>> = ArrayDeque()

    var fallback: ApiResult<TelemetryTokenResponse> = ApiResult.Success(
        TelemetryTokenResponse(
            success = true,
            token = "header.payload.signature",
            expiresAt = Long.MAX_VALUE / 2,
            wsUrl = "ws://127.0.0.1:9/ws/telemetry",
        ),
    )

    /** Every workspace a credential was minted for, in order. */
    val mints: MutableList<String> = mutableListOf()

    override suspend fun telemetryToken(workspaceId: String): ApiResult<TelemetryTokenResponse> {
        mints += workspaceId
        return results.removeFirstOrNull() ?: fallback
    }
}
