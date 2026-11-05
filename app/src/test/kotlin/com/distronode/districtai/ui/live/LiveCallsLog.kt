package com.distronode.districtai.ui.live

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import kotlinx.coroutines.CompletableDeferred

/**
 * The call log the Live calls list is read from: by default a live call the receptionist is on, a
 * live team member's call, and one that is over. Counts its reads and can hold one in flight.
 */
internal class LiveCallsLog : FakeDistrictApi() {

    var reads = 0

    /** When set, a read waits for it before answering. */
    var gate: CompletableDeferred<Unit>? = null

    init {
        callsResult = ApiResult.Success(
            listOf(FakeSuperviseApi.AI_CALL, FakeSuperviseApi.TEAM_CALL, FakeSuperviseApi.ENDED_CALL),
        )
    }

    override suspend fun calls(workspaceId: String, limit: Int, offset: Int): ApiResult<List<CallSummary>> {
        reads += 1
        gate?.await()
        return callsResult
    }
}
