package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.testCall
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Live calls list: the call log's newest page, kept to the calls in progress or ringing. */
class CallsLiveRepositoryTest {

    private class Recording : FakeDistrictApi() {
        val pages = mutableListOf<Pair<Int, Int>>()

        override suspend fun calls(workspaceId: String, limit: Int, offset: Int): ApiResult<List<CallSummary>> {
            pages += limit to offset
            return callsResult
        }
    }

    @Test
    fun `only in-progress and ringing calls are live, read from one page at the server's ceiling`() = runTest {
        val api = Recording()
        api.callsResult = ApiResult.Success(
            listOf("in-progress", "ringing", "completed", "no-answer", "busy", "failed", "In-Progress")
                .mapIndexed { index, status -> testCall(id = "c$index", status = status) },
        )

        val live = (CallsRepository(api).liveCalls("ws-1") as ApiResult.Success).value

        assertEquals(listOf("c0", "c1"), live.map { it.id })
        assertEquals(listOf(100 to 0), api.pages)
    }

    @Test
    fun `a failed read is handed back as it came`() = runTest {
        val api = Recording()
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        api.callsResult = offline

        assertEquals(offline, CallsRepository(api).liveCalls("ws-1"))
    }
}
