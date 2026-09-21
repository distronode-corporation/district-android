package com.distronode.districtai.ui.overview

import com.distronode.districtai.core.data.OverviewRepository
import com.distronode.districtai.core.data.SetupRepository
import com.distronode.districtai.core.data.WorkspaceRepository
import com.distronode.districtai.core.data.WorkspaceSelectionStore
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.SetupProgress
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SetupApi
import com.distronode.districtai.ui.TestDistrictApi
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The overview's "Finish setting up on the web" card, from the ViewModel's side.
 *
 * ⛔ THE SETUP READ MUST NEVER COST THE SCREEN ANYTHING. It is owner-only, so for most users it is
 * a 403; the screen has to reach Content without waiting for it, and no failure of it may turn the
 * screen into an error. The first test holds the read open to prove the first half.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewSetupCardTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore(private var id: String? = null) : WorkspaceSelectionStore {
        override fun selectedWorkspaceId(): String? = id
        override fun setSelectedWorkspaceId(workspaceId: String?) {
            id = workspaceId
        }
    }

    private class FakeSetupApi(
        var result: ApiResult<DistrictSetupResponse>,
        var gate: CompletableDeferred<Unit>? = null,
    ) : SetupApi {
        val requested = mutableListOf<String>()

        override suspend fun districtSetup(workspaceId: String): ApiResult<DistrictSetupResponse> {
            requested += workspaceId
            // ⚠️ The answer is fixed at REQUEST time. Reading `result` after the gate would let a
            // test that changes it mid-flight pass without the late answer ever being the stale one.
            val answer = result
            gate?.await()
            return answer
        }
    }

    private val midWizard: ApiResult<DistrictSetupResponse> =
        ApiResult.Success(DistrictSetupResponse(setupProgress = SetupProgress()))

    private fun districtApi(vararg ids: String) = TestDistrictApi().apply {
        workspaceListResult = ApiResult.Success(
            WorkspaceListResponse(
                success = true,
                workspaces = ids.map { WorkspaceEntry(id = it, name = it, region = "ca", role = "client") },
            ),
        )
        // ⚠️ `workspaceId` NOT ECHOED: one canned overview serves both workspaces in the switch test,
        // and a null echo is the one value `OverviewRepository` does not treat as a mismatch.
        overviewResult = ApiResult.Success(
            OverviewResponse(success = true, workspaceId = null, role = "client", metrics = OverviewMetrics()),
        )
    }

    private fun viewModel(api: TestDistrictApi, setup: SetupApi?, store: FakeStore = FakeStore()) =
        OverviewViewModel(
            workspaceRepository = WorkspaceRepository(api, store),
            overviewRepository = OverviewRepository(api),
            setupRepository = setup?.let(::SetupRepository),
        )

    private val OverviewViewModel.content: OverviewUiState.Content
        get() = state.value as OverviewUiState.Content

    @Test
    fun `the screen renders before the setup read answers, then shows the card`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val setup = FakeSetupApi(midWizard, gate)

        val vm = viewModel(districtApi("ws-a"), setup)
        advanceUntilIdle()

        assertFalse("content first, card later", vm.content.showFinishSetup)
        assertEquals(listOf("ws-a"), setup.requested)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.content.showFinishSetup)
    }

    @Test
    fun `a member's 403 leaves the screen whole and cardless`() = runTest(dispatcher) {
        val vm = viewModel(districtApi("ws-a"), FakeSetupApi(ApiResult.Forbidden("owner only")))
        advanceUntilIdle()

        assertFalse(vm.content.showFinishSetup)
    }

    @Test
    fun `a network failure leaves the screen whole and cardless`() = runTest(dispatcher) {
        val vm = viewModel(districtApi("ws-a"), FakeSetupApi(ApiResult.NetworkFailure(IOException("offline"))))
        advanceUntilIdle()

        assertFalse(vm.content.showFinishSetup)
    }

    @Test
    fun `without a setup repository the card never shows`() = runTest(dispatcher) {
        val vm = viewModel(districtApi("ws-a"), setup = null)
        advanceUntilIdle()

        assertFalse(vm.content.showFinishSetup)
    }

    @Test
    fun `a refresh of the same workspace keeps the card while it re-reads`() = runTest(dispatcher) {
        val setup = FakeSetupApi(midWizard)
        val vm = viewModel(districtApi("ws-a"), setup)
        advanceUntilIdle()
        assertTrue(vm.content.showFinishSetup)

        val gate = CompletableDeferred<Unit>()
        setup.gate = gate
        vm.load(refreshing = true)
        advanceUntilIdle()

        assertTrue("no blink while the setup read is in flight", vm.content.showFinishSetup)
        gate.complete(Unit)
    }

    @Test
    fun `a late answer for a workspace no longer showing is dropped`() = runTest(dispatcher) {
        // ⛔ ws-a's answer arrives after the user switched to ws-b. Applying it would put ws-a's
        // setup card on ws-b's overview.
        val gate = CompletableDeferred<Unit>()
        val setup = FakeSetupApi(midWizard, gate)
        val api = districtApi("ws-a", "ws-b")
        val vm = viewModel(api, setup)
        advanceUntilIdle()

        setup.result = ApiResult.Forbidden("owner only")
        setup.gate = null
        vm.selectWorkspace("ws-b")
        advanceUntilIdle()
        assertEquals("ws-b", vm.content.active.id)

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.content.showFinishSetup)
    }
}
