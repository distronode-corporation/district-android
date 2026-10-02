package com.distronode.districtai.ui.analytics

import com.distronode.districtai.R
import com.distronode.districtai.core.data.AnalyticsRepository
import com.distronode.districtai.core.model.AnalyticsMetrics
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.UsageHistoryResponse
import com.distronode.districtai.core.model.UsageResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.resourceIdOrNull
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The analytics screen's state machine, and the two guarantees it exists to keep: the three reads
 * are genuinely concurrent, and any one can fail without taking the others' answers off the screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: TestDistrictApi) =
        AnalyticsViewModel(AnalyticsRepository(api), workspaceId = "ws-1")

    private fun healthyApi() = TestDistrictApi().apply {
        analyticsResult = ApiResult.Success(
            AnalyticsResponse(success = true, metrics = AnalyticsMetrics(totalCalls = 48)),
        )
        usageResult = ApiResult.Success(
            UsageResponse(success = true, usage = UsageData(month = "2026-08", smsOutbound = 412.0)),
        )
        usageHistoryResult = ApiResult.Success(
            UsageHistoryResponse(
                success = true,
                usage = listOf(
                    UsageData(month = "2026-08", smsOutbound = 412.0),
                    UsageData(month = "2026-07", smsOutbound = 355.0),
                ),
            ),
        )
    }

    private fun content(vm: AnalyticsViewModel) = vm.state.value as AnalyticsUiState.Content

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun `both halves land before anything is published`() = runTest(dispatcher) {
        // ⛔ ONE STATE CHANGE, BOTH ANSWERS. Emitting the first arrival would make the screen
        // assemble itself in whatever order the network answered — and a reader could see a
        // populated usage card beside an empty analytics area and read the emptiness as data.
        val api = healthyApi()
        val vm = viewModel(api)

        assertEquals(AnalyticsUiState.Loading, vm.state.value)
        advanceUntilIdle()

        val state = content(vm)
        assertTrue(state.analytics is AnalyticsCardState.Ready)
        assertTrue(state.usage is UsageCardState.Ready)
        assertFalse(state.refreshing)
    }

    @Test
    fun `the two reads are issued CONCURRENTLY, not one after the other`() = runTest(dispatcher) {
        // ⛔ THE ONLY WAY TO OBSERVE THIS AT ALL. Against a fake both calls complete instantly, so
        // a sequential implementation and a concurrent one produce identical call lists and
        // identical state. Parking the analytics read makes the ordering visible: a usage request
        // that has ALREADY been recorded while analytics is still suspended proves the second call
        // was not waiting on the first.
        //
        // ⚠️ This matters beyond latency. The obvious sequential shape — await analytics, then
        // read usage — means a FAILING analytics query prevents the usage read from being issued
        // at all, so the usage card could never render its own answer.
        val gate = CompletableDeferred<Unit>()
        val api = healthyApi().apply { analyticsGate = gate }
        val vm = viewModel(api)

        advanceUntilIdle()

        assertEquals("analytics was requested", 1, api.analyticsRequests.size)
        assertEquals("and usage was too, without waiting for it", 1, api.usageRequests.size)
        assertEquals("nothing may be published yet", AnalyticsUiState.Loading, vm.state.value)

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(vm.state.value is AnalyticsUiState.Content)
    }

    @Test
    fun `the default window is seven days`() = runTest(dispatcher) {
        val api = healthyApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to AnalyticsRange.SEVEN_DAYS), api.analyticsRequests)
        assertEquals(AnalyticsRange.SEVEN_DAYS, content(vm).range)
    }

    // ── Range switching ──────────────────────────────────────────────────────

    @Test
    fun `choosing a window re-reads with it and moves the selection immediately`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val api = healthyApi()
            val vm = viewModel(api)
            advanceUntilIdle()

            api.analyticsGate = gate
            vm.selectRange(AnalyticsRange.NINETY_DAYS)
            advanceUntilIdle()

            // ⚠️ THE CHIP MOVES BEFORE THE RESPONSE LANDS. A selector that only updated on arrival
            // would leave the tap unacknowledged for the length of a full-window aggregate, which
            // reads as a dead control and invites a second tap.
            val inFlight = content(vm)
            assertEquals(AnalyticsRange.NINETY_DAYS, inFlight.range)
            assertTrue("and it says so", inFlight.refreshing)
            // ⛔ THE FIGURES STAY ON SCREEN. Blanking them for a reload would flash the whole
            // screen on every chip tap.
            assertTrue(inFlight.analytics is AnalyticsCardState.Ready)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf("ws-1" to AnalyticsRange.SEVEN_DAYS, "ws-1" to AnalyticsRange.NINETY_DAYS),
                api.analyticsRequests,
            )
            assertFalse(content(vm).refreshing)
        }

    @Test
    fun `usage is re-read alongside a range switch`() = runTest(dispatcher) {
        // ⚠️ Usage is NOT windowed — it is always the current month — so re-reading it on a range
        // change is one redundant request. It is done anyway because the alternative is two
        // load paths, and the one that skipped usage would be the one a session-expiry retry
        // needed.
        val api = healthyApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.selectRange(AnalyticsRange.THIRTY_DAYS)
        advanceUntilIdle()

        assertEquals(2, api.usageRequests.size)
        // ⚠️ And so is the history, which the chips cannot affect either. Same trade: one load
        // path that re-reads everything, rather than two of which the lean one is the one a
        // session-expiry retry would need.
        assertEquals(2, api.usageHistoryRequests.size)
        assertEquals(AnalyticsRange.THIRTY_DAYS, content(vm).range)
    }

    @Test
    fun `tapping the window already selected costs nothing`() = runTest(dispatcher) {
        // ⚠️ The chips are a SELECTOR, not three buttons. Re-issuing the same window on an
        // impatient double tap would cost two full-window aggregates over the Call table.
        val api = healthyApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.selectRange(AnalyticsRange.SEVEN_DAYS)
        advanceUntilIdle()

        assertEquals(1, api.analyticsRequests.size)
    }

    @Test
    fun `the chosen window SURVIVES a total failure and its retry`() = runTest(dispatcher) {
        // ⛔ THE REASON THE RANGE IS HELD IN THE ViewModel RATHER THAN READ BACK OUT OF THE STATE.
        // AnalyticsUiState.Failed carries no range, so a reload that re-derived it from the state
        // would silently drop the operator back to 7d — quietly answering a different question
        // from the one they asked.
        val api = healthyApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.selectRange(AnalyticsRange.NINETY_DAYS)
        advanceUntilIdle()

        api.analyticsResult = ApiResult.HttpFailure(503, "Service unavailable")
        api.usageResult = ApiResult.HttpFailure(503, "Service unavailable")
        api.usageHistoryResult = ApiResult.HttpFailure(503, "Service unavailable")
        vm.load()
        advanceUntilIdle()
        assertTrue(vm.state.value is AnalyticsUiState.Failed)

        api.analyticsResult = ApiResult.Success(AnalyticsResponse(success = true))
        api.usageResult = ApiResult.Success(UsageResponse(success = true))
        api.usageHistoryResult = ApiResult.Success(UsageHistoryResponse(success = true))
        vm.load()
        advanceUntilIdle()

        assertEquals(AnalyticsRange.NINETY_DAYS, content(vm).range)
        assertEquals(AnalyticsRange.NINETY_DAYS, api.analyticsRequests.last().second)
    }

    // ── Independent sub-state failures ───────────────────────────────────────

    @Test
    fun `a failed analytics read leaves the usage card intact`() = runTest(dispatcher) {
        // ⛔ THE CENTRAL GUARANTEE OF THIS SCREEN. The two reads share nothing server-side, so a
        // single failure state would discard a correct answer the client already holds.
        val api = healthyApi().apply {
            analyticsResult = ApiResult.HttpFailure(503, "Service unavailable")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = content(vm)
        assertTrue(state.analytics is AnalyticsCardState.Failed)
        val usage = state.usage as UsageCardState.Ready
        assertEquals("2026-08", usage.usage?.month)
    }

    @Test
    fun `a failed usage read leaves the analytics figures intact`() = runTest(dispatcher) {
        val api = healthyApi().apply {
            usageResult = ApiResult.NetworkFailure(java.io.IOException("dropped"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = content(vm)
        val analytics = state.analytics as AnalyticsCardState.Ready
        assertEquals(48, analytics.report.metrics.totalCalls)
        assertTrue(state.usage is UsageCardState.Failed)
    }

    @Test
    fun `only a failure of EVERY read blanks the screen`() = runTest(dispatcher) {
        // ⚠️ The one case where there is nothing left to preserve, so a single retry is the honest
        // control rather than three identical ones on three empty cards.
        val api = TestDistrictApi().apply {
            analyticsResult = ApiResult.HttpFailure(503, "Service unavailable")
            usageResult = ApiResult.HttpFailure(503, "Service unavailable")
            usageHistoryResult = ApiResult.HttpFailure(503, "Service unavailable")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value is AnalyticsUiState.Failed)
    }

    @Test
    fun `a surviving history read is enough to keep the screen in Content`() = runTest(dispatcher) {
        // ⛔ THE THIRD READ COUNTS TOWARD THE ALL-FAILED RULE, WHICH IS THE REGRESSION THIS PINS.
        // The obvious way to add a card is to leave the `analytics && usage` condition alone —
        // and then a history card holding real months is blanked by a whole-screen failure while
        // its answer is already in hand.
        val api = TestDistrictApi().apply {
            analyticsResult = ApiResult.HttpFailure(503, "Service unavailable")
            usageResult = ApiResult.HttpFailure(503, "Service unavailable")
            usageHistoryResult = ApiResult.Success(
                UsageHistoryResponse(
                    success = true,
                    usage = listOf(UsageData(month = "2026-08", smsOutbound = 412.0)),
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = content(vm)
        assertTrue(state.analytics is AnalyticsCardState.Failed)
        assertTrue(state.usage is UsageCardState.Failed)
        val history = state.history as UsageHistoryCardState.Ready
        assertEquals(listOf("2026-08"), history.months.map { it.month })
    }

    @Test
    fun `a failed history read leaves this month's usage intact`() = runTest(dispatcher) {
        val api = healthyApi().apply {
            usageHistoryResult = ApiResult.NetworkFailure(java.io.IOException("dropped"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = content(vm)
        assertTrue(state.history is UsageHistoryCardState.Failed)
        assertEquals("2026-08", (state.usage as UsageCardState.Ready).usage?.month)
    }

    @Test
    fun `an empty history is a READY card carrying no months, never a failure`() =
        runTest(dispatcher) {
            // ⛔ The server appends only months that had rows, so `[]` on a 200 means "never
            // metered". Promoting that to a failure would put a retry in front of a correct
            // answer.
            val api = healthyApi().apply {
                usageHistoryResult = ApiResult.Success(UsageHistoryResponse(success = true))
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            val history = content(vm).history as UsageHistoryCardState.Ready
            assertTrue(history.months.isEmpty())
        }

    @Test
    fun `the history is read CONCURRENTLY and over the shared default span`() =
        runTest(dispatcher) {
            // ⛔ Same proof as the other two reads: with analytics parked, a history request that
            // has ALREADY been recorded shows it was not queued behind the aggregate.
            //
            // ⚠️ AND THE SPAN IS THE REPOSITORY'S DEFAULT, not a number this ViewModel chose. A
            // literal here would be a second copy of "what the web console asks for", which is
            // how two surfaces end up showing different windows under one heading.
            val gate = CompletableDeferred<Unit>()
            val api = healthyApi().apply { analyticsGate = gate }
            val vm = viewModel(api)
            advanceUntilIdle()

            assertEquals(listOf("ws-1" to 3), api.usageHistoryRequests)
            assertEquals(AnalyticsUiState.Loading, vm.state.value)

            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(vm.state.value is AnalyticsUiState.Content)
        }

    @Test
    fun `an empty month is a READY card carrying null, never a failure`() = runTest(dispatcher) {
        // ⛔ "Nothing has been metered yet" and "we could not read the meter" are different
        // answers, and only the second is worth a retry. Rendering the first as zeros would state
        // a billing figure that was never measured.
        val api = healthyApi().apply {
            usageResult = ApiResult.Success(UsageResponse(success = true, usage = null))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val usage = content(vm).usage as UsageCardState.Ready
        assertNull(usage.usage)
    }

    @Test
    fun `a 200 that does not affirm success is drift, not a quiet week`() = runTest(dispatcher) {
        // ⛔ Every field of AnalyticsResponse has a default, so an empty body decodes into a
        // complete report of zeros. Only the envelope check separates that from a genuinely quiet
        // window — and the failure text says "update the app" rather than "check your connection".
        val api = healthyApi().apply {
            analyticsResult = ApiResult.Success(AnalyticsResponse())
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val failed = content(vm).analytics as AnalyticsCardState.Failed
        assertEquals(R.string.failure_unexpected_response, failed.failure.message.resourceIdOrNull)
        assertFalse("retrying cannot fix a shape this build cannot parse", failed.failure.retryable)
    }

    @Test
    fun `an expired session is reported as signed out rather than as no data`() =
        runTest(dispatcher) {
            val api = TestDistrictApi().apply {
                analyticsResult = ApiResult.Unauthorized(null)
                usageResult = ApiResult.Unauthorized(null)
                usageHistoryResult = ApiResult.Unauthorized(null)
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            val failed = vm.state.value as AnalyticsUiState.Failed
            assertEquals(R.string.failure_signed_out, failed.failure.message.resourceIdOrNull)
        }

    // ── Session epoch ────────────────────────────────────────────────────────

    @Test
    fun `a session change re-reads every half`() = runTest(dispatcher) {
        // ⛔ WITHOUT THIS, A SCREEN THAT FAILED ON Unauthorized STAYS FAILED FOREVER: signing in
        // successfully would leave the operator looking at "your session has ended" behind a
        // button that has already done its job. Both reads are idempotent GETs, so unlike HQ's
        // confirm there is nothing here that must NOT be replayed.
        val api = TestDistrictApi().apply {
            analyticsResult = ApiResult.Unauthorized(null)
            usageResult = ApiResult.Unauthorized(null)
            usageHistoryResult = ApiResult.Unauthorized(null)
        }
        val vm = viewModel(api)
        advanceUntilIdle()
        assertTrue(vm.state.value is AnalyticsUiState.Failed)

        api.analyticsResult = ApiResult.Success(AnalyticsResponse(success = true))
        api.usageResult = ApiResult.Success(UsageResponse(success = true))
        api.usageHistoryResult = ApiResult.Success(UsageHistoryResponse(success = true))
        // What DistrictNavHost's OnSessionChanged calls.
        vm.load()
        advanceUntilIdle()

        assertTrue(vm.state.value is AnalyticsUiState.Content)
        assertEquals(2, api.analyticsRequests.size)
        assertEquals(2, api.usageRequests.size)
        assertEquals(2, api.usageHistoryRequests.size)
    }

    @Test
    fun `a reload over healthy content keeps the figures visible while it runs`() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val api = healthyApi()
            val vm = viewModel(api)
            advanceUntilIdle()

            api.analyticsGate = gate
            vm.load()
            advanceUntilIdle()

            val state = content(vm)
            assertTrue("the reload is announced", state.refreshing)
            assertTrue("and nothing is blanked", state.analytics is AnalyticsCardState.Ready)

            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(content(vm).refreshing)
        }

    @Test
    fun `the factory builds a ViewModel bound to its workspace`() = runTest(dispatcher) {
        val api = healthyApi()
        val vm = AnalyticsViewModel
            .factory(AnalyticsRepository(api), "ws-other")
            .create(AnalyticsViewModel::class.java)
        advanceUntilIdle()

        assertEquals("ws-other", api.analyticsRequests.single().first)
        assertEquals(listOf("ws-other"), api.usageRequests)
        assertTrue(vm.state.value is AnalyticsUiState.Content)
    }

    @Test
    fun `a slower read for an earlier window is never published under a later chip`() = runTest(dispatcher) {
        // ⛔ 30d THEN 7d, AND THE 30d AGGREGATE IS THE SLOWER ONE. Before the fix the 30-day
        // figures landed last and were labelled with whatever chip was selected at that moment.
        val api = healthyApi()
        val gates = mapOf(
            AnalyticsRange.THIRTY_DAYS to CompletableDeferred<Unit>(),
            AnalyticsRange.SEVEN_DAYS to CompletableDeferred<Unit>(),
        )
        var released = false
        val gated = object : DistrictApi by api {
            override suspend fun analytics(
                workspaceId: String,
                range: AnalyticsRange,
            ): ApiResult<AnalyticsResponse> {
                // The initial 7d read on entry runs ungated; only the two switches are parked.
                if (released) gates.getValue(range).await()
                return ApiResult.Success(
                    AnalyticsResponse(
                        success = true,
                        metrics = AnalyticsMetrics(totalCalls = range.wire.removeSuffix("d").toInt()),
                    ),
                )
            }
        }
        val vm = AnalyticsViewModel(AnalyticsRepository(gated), workspaceId = "ws-1")
        advanceUntilIdle()
        released = true

        vm.selectRange(AnalyticsRange.THIRTY_DAYS)
        advanceUntilIdle()
        vm.selectRange(AnalyticsRange.SEVEN_DAYS)
        advanceUntilIdle()
        gates.getValue(AnalyticsRange.SEVEN_DAYS).complete(Unit)
        advanceUntilIdle()
        gates.getValue(AnalyticsRange.THIRTY_DAYS).complete(Unit)
        advanceUntilIdle()

        val state = content(vm)
        assertEquals(AnalyticsRange.SEVEN_DAYS, state.range)
        assertEquals(7, (state.analytics as AnalyticsCardState.Ready).report.metrics.totalCalls)
        assertFalse(state.refreshing)
    }
}
