package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.TelemetryTokenResponse
import kotlinx.serialization.json.JsonPrimitive

/**
 * The credential for one telemetry socket (`/ws/telemetry`), which carries a call's live
 * transcript.
 *
 * ⚠️ A SIBLING OF `DistrictApi` FOR THE REASON [InboxExtrasApi] IS ONE: `DistrictApi.kt` is the
 * shared file parallel work collides in, and only the live transcript reads this.
 */
interface TelemetryApi {

    /**
     * `POST /api/district/telemetry/token` with `{workspaceId}`.
     *
     * ⛔ THE ANSWER'S `wsUrl` IS USED VERBATIM (see [TelemetryTokenResponse]): the server picks the
     * broadcaster by the workspace's region.
     *
     * ⚠️ USER-SCOPED RATE LIMIT: 30 a minute per account. One socket mints about every 14 minutes.
     */
    suspend fun telemetryToken(workspaceId: String): ApiResult<TelemetryTokenResponse>
}

/** [TelemetryApi] over [DistrictApiClient]. */
class HttpTelemetryApi(private val client: DistrictApiClient) : TelemetryApi {

    override suspend fun telemetryToken(workspaceId: String): ApiResult<TelemetryTokenResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.TELEMETRY_TOKEN,
            serializer = TelemetryTokenResponse.serializer(),
            // ⚠️ IN THE BODY, NOT THE QUERY: the route reads `body.workspaceId` and answers 400
            // without it.
            body = jsonObjectOf("workspaceId" to JsonPrimitive(workspaceId)),
        )
}
