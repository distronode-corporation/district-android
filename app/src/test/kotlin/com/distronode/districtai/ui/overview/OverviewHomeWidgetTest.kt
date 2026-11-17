package com.distronode.districtai.ui.overview

import com.distronode.districtai.core.auth.ReauthReason
import com.distronode.districtai.core.data.OverviewRepository
import com.distronode.districtai.core.data.WorkspaceRepository
import com.distronode.districtai.core.data.WorkspaceSelectionStore
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.widget.HomeWidgetSnapshot
import com.distronode.districtai.widget.PublisherHarness
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * What the Overview hands the home-screen widget: the selected workspace from the list, its 7-day
 * call count from the overview, and a clear when there is nothing to show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewHomeWidgetTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val harness = PublisherHarness(now = 7L)

    private class Selection(private var id: String? = null) : WorkspaceSelectionStore {
        override fun selectedWorkspaceId(): String? = id
        override fun setSelectedWorkspaceId(workspaceId: String?) {
            id = workspaceId
        }
    }

    private fun entry(id: String, name: String) = WorkspaceEntry(id = id, name = name, region = "us", role = "client")

    private fun api(vararg workspaces: WorkspaceEntry, calls: Int = 12) = FakeDistrictApi().apply {
        workspaceListResult = ApiResult.Success(WorkspaceListResponse(success = true, workspaces = workspaces.toList()))
        overviewResult = ApiResult.Success(
            OverviewResponse(success = true, role = "client", metrics = OverviewMetrics(callsThisWeek = calls)),
        )
    }

    private fun viewModel(api: DistrictApi) = OverviewViewModel(
        workspaceRepository = WorkspaceRepository(api, Selection()),
        overviewRepository = OverviewRepository(api),
        widget = harness.publisher,
    )

    @Test
    fun `a loaded overview writes the workspace and its calls in the last 7 days`() = runTest(dispatcher) {
        viewModel(api(entry("ws-a", "Alpha")))
        advanceUntilIdle()

        assertEquals(
            HomeWidgetSnapshot("ws-a", "Alpha", callsLast7Days = 12, callsReadAtMillis = 7L),
            harness.store.snapshot,
        )
    }

    @Test
    fun `a switch whose overview fails shows the new workspace with no counts`() = runTest(dispatcher) {
        val api = api(entry("ws-a", "Alpha"), entry("ws-b", "Bravo"))
        val vm = viewModel(api)
        advanceUntilIdle()
        harness.publisher.recordUnread("ws-a", 4)

        api.overviewResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.selectWorkspace("ws-b")
        advanceUntilIdle()

        assertEquals(HomeWidgetSnapshot("ws-b", "Bravo"), harness.store.snapshot)
    }

    @Test
    fun `a failed refresh keeps what the widget shows`() = runTest(dispatcher) {
        val api = api(entry("ws-a", "Alpha"))
        val vm = viewModel(api)
        advanceUntilIdle()
        val shown = harness.store.snapshot

        api.workspaceListResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.load(refreshing = true)
        advanceUntilIdle()

        assertEquals(shown, harness.store.snapshot)
    }

    @Test
    fun `a session the server ended clears the widget`() = runTest(dispatcher) {
        val api = api(entry("ws-a", "Alpha"))
        val vm = viewModel(api)
        advanceUntilIdle()

        api.workspaceListResult = ApiResult.Unauthorized(ReauthReason.RefreshRejected)
        vm.load()
        advanceUntilIdle()

        assertNull(harness.store.snapshot)
    }

    @Test
    fun `a complete list with nothing to select clears the widget`() = runTest(dispatcher) {
        val api = api(entry("ws-a", "Alpha"))
        val vm = viewModel(api)
        advanceUntilIdle()

        api.workspaceListResult = ApiResult.Success(WorkspaceListResponse(success = true))
        vm.load()
        advanceUntilIdle()
        assertNull("no workspaces", harness.store.snapshot)

        api.workspaceListResult = ApiResult.Success(
            WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a", "Alpha"))),
        )
        vm.load()
        advanceUntilIdle()
        api.workspaceListResult = ApiResult.Success(WorkspaceListResponse(success = true, inactiveCount = 2))
        vm.load()
        advanceUntilIdle()
        assertNull("all lapsed", harness.store.snapshot)
    }

    @Test
    fun `a workspace list that answers after a sign-out writes nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = api(entry("ws-a", "Alpha"))
        val gated = object : DistrictApi by api {
            override suspend fun workspaceList(): ApiResult<WorkspaceListResponse> {
                gate.await()
                return api.workspaceList()
            }
        }
        viewModel(gated)
        advanceUntilIdle()

        // The sign-out lands while the list is in flight (AppContainer.signOut clears the widget).
        harness.publisher.clear()
        gate.complete(Unit)
        advanceUntilIdle()

        assertNull(harness.store.snapshot)
    }
}
