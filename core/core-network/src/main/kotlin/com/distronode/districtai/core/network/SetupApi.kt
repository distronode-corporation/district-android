package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.DistrictSetupResponse

/**
 * The setup wizard's state, read-only. The wizard itself runs on the web.
 *
 * ⚠️ A SECTION OF ITS OWN, like [SupportApi] and [DeskApi], rather than one more method on
 * `DistrictApi`: one endpoint with its own owner-only gate, sharing [DistrictApiClient]'s token
 * handling and 401 retry.
 */
interface SetupApi {

    /**
     * `GET /api/district/setup?workspaceId=…`. Owner only: a member gets [ApiResult.Forbidden],
     * which callers must treat as "nothing to show", never as a failure to report.
     */
    suspend fun districtSetup(workspaceId: String): ApiResult<DistrictSetupResponse>
}
