package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SetupApi

/**
 * Whether the active workspace's owner still has setup to finish on the web.
 *
 * ⛔ EVERY FAILURE IS "NO", AND THAT IS THE DESIGN RATHER THAN SWALLOWING. The answer drives one
 * optional card on the overview. A 403 is the ordinary answer for anyone who is not the owner, and
 * a network error or a decode failure must never block or replace the screen the card sits on, so
 * none of them has anything to show. The overview's own read reports real failures.
 */
class SetupRepository(
    private val api: SetupApi,
) {

    suspend fun needsWebSetup(workspaceId: String): Boolean =
        when (val result = api.districtSetup(workspaceId)) {
            is ApiResult.Success -> result.value.needsWebSetup
            is ApiResult.Failure -> false
        }
}
