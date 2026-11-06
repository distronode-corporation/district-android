package com.distronode.districtai.ui.live

import com.distronode.districtai.core.model.CallDetailResponse
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import kotlinx.coroutines.CompletableDeferred

/**
 * The call log the Live calls list is read from: by default a live call the receptionist is on, a
 * live team member's call, and one that is over. Counts its reads and can hold one in flight.
 *
 * A call read by id answers [detail] when set, else the listed row with that id, else `NotFound`
 * (the log and the detail route agree unless a test says otherwise).
 */
internal class LiveCallsLog : FakeDistrictApi() {

    var reads = 0

    /** When set, a read waits for it before answering. */
    var gate: CompletableDeferred<Unit>? = null

    /** When set, every read by id answers this. */
    var detail: ApiResult<CallDetailResponse>? = null

    /** The ids read one by one, in order. */
    val detailReads = mutableListOf<String>()

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

    override suspend fun callDetail(workspaceId: String, callId: String): ApiResult<CallDetailResponse> {
        detailReads += callId
        detail?.let { return it }
        val row = (callsResult as? ApiResult.Success)?.value?.firstOrNull { it.id == callId }
            ?: return ApiResult.NotFound("Call not found", "call_not_found")
        return ApiResult.Success(CallDetailResponse(success = true, call = row))
    }
}
