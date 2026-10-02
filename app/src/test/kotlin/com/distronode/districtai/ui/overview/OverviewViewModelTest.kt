package com.distronode.districtai.ui.overview

import com.distronode.districtai.R
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
import com.distronode.districtai.ui.SignedOutCause
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.resourceIdOrNull
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
 * The failure-to-UI mapping.
 *
 * ⛔ WHY THIS IS THE MOST IMPORTANT TEST IN THE APP MODULE. Every branch below is a different
 * thing said to the user, and the cheap wrong answer — treat any failure as "empty" — reads as
 * account loss. The web side has already shipped that mistake once, routing a paying customer to
 * a checkout page because an unreachable region produced an empty workspace list. These
 * assertions are what stop the native client repeating it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        // viewModelScope is hard-wired to Dispatchers.Main; without this substitution every
        // launch below would throw "Module with the Main dispatcher had failed to initialize".
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun entry(id: String, name: String = id, role: String = "client") =
        WorkspaceEntry(id = id, name = name, region = "us", role = role)

    private class FakeStore(private var id: String? = null) : WorkspaceSelectionStore {
        override fun selectedWorkspaceId(): String? = id
        override fun setSelectedWorkspaceId(workspaceId: String?) {
            id = workspaceId
        }
    }

    private fun viewModel(api: TestDistrictApi, store: FakeStore = FakeStore()) = OverviewViewModel(
        workspaceRepository = WorkspaceRepository(api, store),
        overviewRepository = OverviewRepository(api),
    )

    // ── Happy path ───────────────────────────────────────────────────────────

    @Test
    fun `loads the active workspace's overview`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a", "Alpha"))),
            )
            overviewResult = ApiResult.Success(
                OverviewResponse(
                    success = true,
                    workspaceId = "ws-a",
                    role = "client",
                    metrics = OverviewMetrics(totalCalls = 412),
                    avgDurationLabel = "3m 12s",
                ),
            )
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Content
        assertEquals("Alpha", state.active.name)
        assertEquals(412, state.overview.metrics.totalCalls)
        assertEquals("3m 12s", state.overview.avgDurationLabel)
    }

    @Test
    fun `asks the server about the workspace it resolved, never omitting it`() = runTest(dispatcher) {
        // ⛔ THE ASSERTION THAT PREVENTS A CROSS-TENANT DISPLAY. Omitting workspaceId is not an
        // error: the server falls back to index 0 of its OWN listing, which cannot know what the
        // user selected in the app because that selection is local state, not a cookie. The
        // screen would be labelled with one workspace and show another's numbers.
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(
                    success = true,
                    workspaces = listOf(entry("ws-a"), entry("ws-b")),
                ),
            )
        }

        viewModel(api, FakeStore("ws-b"))
        advanceUntilIdle()

        assertEquals(listOf("ws-b"), api.overviewRequests)
    }

    @Test
    fun `switching workspace persists the choice and re-reads`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(
                    success = true,
                    workspaces = listOf(entry("ws-a"), entry("ws-b")),
                ),
            )
        }
        val store = FakeStore()
        val vm = viewModel(api, store)
        advanceUntilIdle()

        vm.selectWorkspace("ws-b")
        advanceUntilIdle()

        assertEquals("ws-b", store.selectedWorkspaceId())
        assertEquals(listOf("ws-a", "ws-b"), api.overviewRequests)
    }

    @Test
    fun `offers a switcher only when there is more than one workspace`() = runTest(dispatcher) {
        val single = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            )
        }
        val vmSingle = viewModel(single)
        advanceUntilIdle()
        assertFalse((vmSingle.state.value as OverviewUiState.Content).canSwitchWorkspace)

        val many = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("a"), entry("b"))),
            )
        }
        val vmMany = viewModel(many)
        advanceUntilIdle()
        assertTrue((vmMany.state.value as OverviewUiState.Content).canSwitchWorkspace)
    }

    // ── Role gating ──────────────────────────────────────────────────────────

    @Test
    fun `gates mutations on the request's role, not the list's`() = runTest(dispatcher) {
        // ⚠️ The per-request role is the EFFECTIVE one: support access resolves to "agency" even
        // with no membership row, so the list's role can understate access. Here
        // the list says viewer and the overview says agency — the overview wins.
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(
                    success = true,
                    workspaces = listOf(entry("ws-a", role = "viewer")),
                ),
            )
            overviewResult = ApiResult.Success(OverviewResponse(success = true, role = "agency"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue((vm.state.value as OverviewUiState.Content).canMutate)
    }

    @Test
    fun `denies mutations to a viewer`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            )
            overviewResult = ApiResult.Success(OverviewResponse(success = true, role = "viewer"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertFalse((vm.state.value as OverviewUiState.Content).canMutate)
    }

    @Test
    fun `denies mutations when the role could not be parsed`() = runTest(dispatcher) {
        // Fails closed. Assuming the mutating role would offer actions that all 403.
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            )
            overviewResult = ApiResult.Success(OverviewResponse(success = true, role = "superuser"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        assertFalse((vm.state.value as OverviewUiState.Content).canMutate)
    }

    // ── The four kinds of "nothing", kept apart ──────────────────────────────

    @Test
    fun `a genuinely empty account reports no workspaces`() = runTest(dispatcher) {
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.Success(WorkspaceListResponse(success = true)) }
        )
        advanceUntilIdle()

        assertEquals(OverviewUiState.NoWorkspaces, vm.state.value)
    }

    @Test
    fun `a lapsed account reports billing, not emptiness`() = runTest(dispatcher) {
        val vm = viewModel(
            TestDistrictApi().apply {
                workspaceListResult = ApiResult.Success(
                    WorkspaceListResponse(success = true, inactiveCount = 3),
                )
            },
        )
        advanceUntilIdle()

        assertEquals(OverviewUiState.BillingBlocked(3), vm.state.value)
    }

    @Test
    fun `an incomplete list is unavailable and retryable, never empty`() = runTest(dispatcher) {
        // ⛔ THE INCIDENT THIS PREVENTS. Nothing resolved because a region was down. Reporting
        // NoWorkspaces here is indistinguishable to the user from having lost their account.
        val vm = viewModel(
            TestDistrictApi().apply {
                workspaceListResult = ApiResult.Success(
                    WorkspaceListResponse(success = true, degradedRegions = listOf("eu")),
                )
            },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("expected Unavailable, got $state", state is OverviewUiState.Unavailable)
        assertEquals(listOf("eu"), (state as OverviewUiState.Unavailable).degradedRegions)
    }

    @Test
    fun `a degraded-regions failure from the API is unavailable, never empty`() = runTest(dispatcher) {
        val vm = viewModel(
            TestDistrictApi().apply {
                workspaceListResult = ApiResult.RegionsDegraded("unreachable", listOf("apac"))
            },
        )
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Unavailable
        assertEquals(listOf("apac"), state.degradedRegions)
    }

    @Test
    fun `a partial list still renders content, flagged`() = runTest(dispatcher) {
        // One region down but another answered: the figures are correct, the SWITCHER is short.
        // Surfacing that is not optional — implying fewer workspaces than the user has is the
        // same class of error as showing none.
        val vm = viewModel(
            TestDistrictApi().apply {
                workspaceListResult = ApiResult.Success(
                    WorkspaceListResponse(
                        success = true,
                        workspaces = listOf(entry("ws-a")),
                        degradedRegions = listOf("ca"),
                    ),
                )
                overviewResult = ApiResult.Success(OverviewResponse(success = true, role = "client"))
            },
        )
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Content
        assertEquals(listOf("ca"), state.degradedRegions)
    }

    // ── Sign-out causes ──────────────────────────────────────────────────────

    @Test
    fun `never having signed in is not an error state`() = runTest(dispatcher) {
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.Unauthorized(ReauthReason.NoSession) }
        )
        advanceUntilIdle()

        assertEquals(OverviewUiState.SignedOut(SignedOutCause.NEVER_SIGNED_IN), vm.state.value)
    }

    @Test
    fun `an interrupted refresh is routine, not a security event`() = runTest(dispatcher) {
        // ⚠️ Signs the user out, but re-sending that token would revoke the whole family and log
        // a replay warning describing an attack that did not happen. The wording has to match.
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.Unauthorized(ReauthReason.InterruptedRefresh) },
        )
        advanceUntilIdle()

        assertEquals(OverviewUiState.SignedOut(SignedOutCause.ROUTINE), vm.state.value)
    }

    @Test
    fun `a refused credential is reported as an invalid session`() = runTest(dispatcher) {
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.Unauthorized(ReauthReason.RefreshRejected) },
        )
        advanceUntilIdle()

        assertEquals(OverviewUiState.SignedOut(SignedOutCause.SESSION_INVALID), vm.state.value)
    }

    @Test
    fun `a bare 401 with no reason is an invalid session`() = runTest(dispatcher) {
        val vm = viewModel(TestDistrictApi().apply { workspaceListResult = ApiResult.Unauthorized(reason = null) })
        advanceUntilIdle()

        assertEquals(OverviewUiState.SignedOut(SignedOutCause.SESSION_INVALID), vm.state.value)
    }

    // ── Other failures ───────────────────────────────────────────────────────

    @Test
    fun `a rate limit keeps the session and stays retryable`() = runTest(dispatcher) {
        // ⛔ NOT A SIGN-OUT. The server rate-limits before rotating, so nothing was spent, and
        // its limits are sized to tolerate several devices behind one NAT.
        val vm = viewModel(TestDistrictApi().apply { workspaceListResult = ApiResult.RateLimited("slow down") })
        advanceUntilIdle()

        assertTrue(vm.state.value is OverviewUiState.Unavailable)
    }

    @Test
    fun `a 404 no-workspace answer reports no workspaces`() = runTest(dispatcher) {
        // ⚠️ The auth guard answers 404, not 403, for an account with no workspace at all.
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.NotFound("User has no workspace") }
        )
        advanceUntilIdle()

        assertEquals(OverviewUiState.NoWorkspaces, vm.state.value)
    }

    @Test
    fun `contract drift does not masquerade as being offline`() = runTest(dispatcher) {
        // ⛔ "Check your connection" would be wrong AND unactionable — retrying can never fix a
        // shape the app cannot parse. The message has to point at updating the app.
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.DecodeFailure(RuntimeException("nope"), "{}") },
        )
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Unavailable
        assertTrue(
            "should point at the contract, was '${state.message}'",
            state.message.resourceIdOrNull == R.string.failure_unexpected_response,
        )
    }

    @Test
    fun `being offline says so`() = runTest(dispatcher) {
        val vm = viewModel(
            TestDistrictApi().apply { workspaceListResult = ApiResult.NetworkFailure(IOException("no route")) },
        )
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Unavailable
        assertTrue(
            "should point at connectivity, was '${state.message}'",
            state.message.resourceIdOrNull == R.string.failure_offline,
        )
    }

    // ── Refresh ──────────────────────────────────────────────────────────────

    @Test
    fun `a refresh keeps existing content visible instead of flashing a spinner`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            )
            overviewResult = ApiResult.Success(OverviewResponse(success = true, role = "client"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()
        assertTrue(vm.state.value is OverviewUiState.Content)

        vm.load(refreshing = true)

        // Still Content, now marked refreshing — asserted BEFORE advancing, which is the window
        // in which a naive implementation would have shown Loading.
        val during = vm.state.value as OverviewUiState.Content
        assertTrue(during.refreshing)

        advanceUntilIdle()
        assertFalse((vm.state.value as OverviewUiState.Content).refreshing)
    }

    @Test
    fun `a failed overview read after a good workspace list is unavailable, never empty`() = runTest(dispatcher) {
        // ⛔ The list answered and named a workspace; only its figures failed. That is "we could not
        // finish looking", and the screen must say so rather than show a workspace with no numbers.
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a"))),
            )
            overviewResult = ApiResult.NetworkFailure(IOException("dropped"))
        }

        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Unavailable
        assertEquals(R.string.failure_offline, state.message.resourceIdOrNull)
        assertEquals(listOf("ws-a"), api.overviewRequests)
    }

    @Test
    fun `the factory builds a model that reads through the repositories it was given`() = runTest(dispatcher) {
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(success = true, workspaces = listOf(entry("ws-a", "Alpha"))),
            )
            overviewResult = ApiResult.Success(OverviewResponse(success = true, role = "client"))
        }

        val vm = OverviewViewModel.factory(
            workspaceRepository = WorkspaceRepository(api, FakeStore()),
            overviewRepository = OverviewRepository(api),
            setupRepository = null,
        ).create(OverviewViewModel::class.java)
        advanceUntilIdle()

        assertEquals("Alpha", (vm.state.value as OverviewUiState.Content).active.name)
        assertEquals(listOf("ws-a"), api.overviewRequests)
    }

    @Test
    fun `a first load shows Loading`() = runTest(dispatcher) {
        val vm = viewModel(TestDistrictApi())

        assertEquals(OverviewUiState.Loading, vm.state.value)
        advanceUntilIdle()
    }

    @Test
    fun `a slower load for the previous workspace cannot overwrite the one switched to`() = runTest(dispatcher) {
        // ⛔ LAST TO ARRIVE IS NOT LAST ASKED. The cold-start read for A is parked on its overview
        // while the user switches to B; B answers first, then A lands. Before the fix A's figures
        // were painted over B's while the stored selection said B.
        val api = TestDistrictApi().apply {
            workspaceListResult = ApiResult.Success(
                WorkspaceListResponse(
                    success = true,
                    workspaces = listOf(entry("ws-a", "Alpha"), entry("ws-b", "Bravo")),
                ),
            )
        }
        val gates = mapOf(
            "ws-a" to CompletableDeferred<Unit>(),
            "ws-b" to CompletableDeferred<Unit>(),
        )
        val gated = object : DistrictApi by api {
            override suspend fun overview(workspaceId: String?): ApiResult<OverviewResponse> {
                gates.getValue(workspaceId!!).await()
                return ApiResult.Success(
                    OverviewResponse(success = true, workspaceId = workspaceId, role = "client"),
                )
            }
        }
        val store = FakeStore()
        val vm = OverviewViewModel(WorkspaceRepository(gated, store), OverviewRepository(gated))
        advanceUntilIdle()

        vm.selectWorkspace("ws-b")
        advanceUntilIdle()
        gates.getValue("ws-b").complete(Unit)
        advanceUntilIdle()
        gates.getValue("ws-a").complete(Unit)
        advanceUntilIdle()

        val state = vm.state.value as OverviewUiState.Content
        assertEquals("ws-b", state.active.id)
        assertEquals("ws-b", state.overview.workspaceId)
    }
}
