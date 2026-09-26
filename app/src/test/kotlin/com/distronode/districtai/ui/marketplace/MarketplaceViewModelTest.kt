package com.distronode.districtai.ui.marketplace

import com.distronode.districtai.core.data.NumbersRepository
import com.distronode.districtai.core.model.AvailableNumber
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.NumberSearchResponse
import com.distronode.districtai.core.model.OwnedNumbersResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The marketplace's state machine.
 *
 * ⛔ THE TWO THINGS THIS SCREEN MUST NEVER DO. It must not render "no carrier connected" as "the
 * carrier has no numbers in your area code" — the first is an unfinished setup and the second is
 * an answer about inventory nobody looked at, and only one of them is fixed by trying a different
 * filter. And it must not render a PARTIAL owned list as a complete one, which is a 200 that
 * decodes perfectly and draws exactly like the real thing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarketplaceViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val available = AvailableNumber(
        phoneNumber = "+14165550111",
        capabilities = listOf("sms", "voice"),
        type = "local",
        monthlyPrice = 1.15,
    )

    private val owned = ListedNumber(
        phoneNumber = "+14165550100",
        capabilities = listOf("sms", "voice"),
        type = "local",
        status = "in-use",
        provider = "twilio",
    )

    private fun api() = TestDistrictApi().apply {
        ownedResult = ApiResult.Success(
            OwnedNumbersResponse(success = true, numbers = listOf(owned)),
        )
        searchResult = ApiResult.Success(
            NumberSearchResponse(success = true, provider = "twilio", numbers = listOf(available)),
        )
    }

    private fun viewModel(api: TestDistrictApi) =
        MarketplaceViewModel(NumbersRepository(api), workspaceId = "ws-1")

    // ── What loads, and what does not ────────────────────────────────────────

    @Test
    fun `the owned list loads on entry and the search does not`() = runTest {
        // ⛔ A SEARCH ON OPEN WOULD SPEND A CARRIER REQUEST ANSWERING A QUESTION NOBODY ASKED, and
        // would fill the tab with US local numbers regardless of where the workspace operates.
        // Listing what the workspace already has is why the screen exists.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), api.ownedRequests)
        assertTrue(api.searchRequests.isEmpty())
        assertTrue(vm.state.value.owned is OwnedState.Ready)
        assertEquals(SearchState.Idle, vm.state.value.search)
    }

    @Test
    fun `the landing tab is the workspace's own numbers`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        assertEquals(MarketplaceTab.OWNED, vm.state.value.tab)
    }

    @Test
    fun `a repeat tap on the selected tab is a no-op`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.selectTab(MarketplaceTab.SEARCH)
        vm.selectTab(MarketplaceTab.SEARCH)

        assertEquals(MarketplaceTab.SEARCH, vm.state.value.tab)
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `the form's filters are what gets sent`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.updateForm(NumberSearchForm(areaCode = "416", country = "CA", type = NUMBER_TYPE_TOLL_FREE))
        vm.search()
        advanceUntilIdle()

        assertEquals(listOf("ws-1", "416", "CA", "tollFree", null), api.searchRequests.single())
        val ready = vm.state.value.search as SearchState.Ready
        assertEquals("twilio", ready.provider)
        assertEquals(1, ready.numbers.size)
    }

    @Test
    fun `an unconfigured workspace is an empty state, not a failure`() = runTest {
        // ⛔ RETRYING CANNOT CONNECT A CARRIER. Rendering this as a red failure with a retry
        // button tells an operator their app is broken when the truth is that their setup is
        // unfinished — and the server's own sentence is the only thing that says which.
        val message = "Messaging provider not configured for workspace"
        val api = api().apply { searchResult = ApiResult.HttpFailure(400, message) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.search()
        advanceUntilIdle()

        val state = vm.state.value.search
        assertTrue("a 400 must not become a generic failure", state is SearchState.NotConfigured)
        assertEquals(message, (state as SearchState.NotConfigured).message)
    }

    @Test
    fun `an unconfigured workspace is not rendered as an empty carrier inventory`() = runTest {
        // The other half of the same rule, stated as the thing that must NOT happen.
        val api = api().apply {
            searchResult = ApiResult.HttpFailure(400, "Messaging provider not configured for workspace")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.search()
        advanceUntilIdle()

        assertTrue(vm.state.value.search !is SearchState.Ready)
    }

    @Test
    fun `any other failure keeps its retry`() = runTest {
        // ⚠️ A 500 IS NOT AN ACCOUNT STATE. Only 400 means "finish connecting a carrier"; a server
        // fault is worth retrying, so it must not be swallowed into the empty state above.
        val api = api().apply { searchResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.search()
        advanceUntilIdle()

        val failed = vm.state.value.search as SearchState.Failed
        assertTrue("a server fault is retryable", failed.failure.retryable)
    }

    @Test
    fun `a search failure leaves the owned list alone`() = runTest {
        // ⛔ TWO INDEPENDENT READS. A carrier inventory query that failed must not blank the list
        // of numbers the workspace already owns, which is a correct answer already on screen.
        val api = api().apply { searchResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.search()
        advanceUntilIdle()

        assertTrue(vm.state.value.owned is OwnedState.Ready)
        assertTrue(vm.state.value.search is SearchState.Failed)
    }

    @Test
    fun `an empty result set after a successful search is genuinely empty`() = runTest {
        // ⚠️ Reachable only once a search SUCCEEDED, so it means the carrier has nothing matching
        // — never "we could not look".
        val api = api().apply {
            searchResult = ApiResult.Success(
                NumberSearchResponse(success = true, provider = "twilio", numbers = emptyList()),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.search()
        advanceUntilIdle()

        assertEquals(emptyList<AvailableNumber>(), (vm.state.value.search as SearchState.Ready).numbers)
    }

    // ── Owned ────────────────────────────────────────────────────────────────

    @Test
    fun `a partial answer keeps its rows and carries the warning`() = runTest {
        // ⛔ THE DANGEROUS SHAPE: a 200 with a REAL but SHORT list. Promoting it to a failure
        // hides inventory the workspace owns; dropping the flag draws an incomplete list as a
        // complete one. Only a Ready carrying the flag can express what actually happened.
        val api = api().apply {
            ownedResult = ApiResult.Success(
                OwnedNumbersResponse(
                    success = true,
                    numbers = listOf(owned),
                    partial = true,
                    failedProviders = listOf("telnyx"),
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val ready = vm.state.value.owned as OwnedState.Ready
        assertTrue(ready.partial)
        assertEquals(listOf("telnyx"), ready.failedProviders)
        assertTrue("the rows must survive the warning", ready.numbers.isNotEmpty())
    }

    @Test
    fun `the managed flag survives into the state`() = runTest {
        val managed = owned.copy(phoneNumber = "+14165550199", provider = "telnyx", managed = true)
        val api = api().apply {
            ownedResult = ApiResult.Success(
                OwnedNumbersResponse(success = true, numbers = listOf(owned, managed)),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(
            listOf(false, true),
            (vm.state.value.owned as OwnedState.Ready).numbers.map { it.managed },
        )
    }

    @Test
    fun `a reload re-reads the owned list and leaves search results in place`() = runTest {
        // ⚠️ A session change re-reads what the screen loaded on entry. Re-running a search the
        // operator typed minutes ago would replace results they are reading, under no visible
        // trigger.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.search()
        advanceUntilIdle()

        vm.load()
        advanceUntilIdle()

        assertEquals(2, api.ownedRequests.size)
        assertEquals(1, api.searchRequests.size)
        assertTrue(vm.state.value.search is SearchState.Ready)
    }

    @Test
    fun `a total owned failure is a failure, not an empty inventory`() = runTest {
        // ⛔ "We could not look" must never be drawn as "you own no numbers" — the same
        // conflation that sent a paying customer to a checkout page on the web.
        val api = api().apply {
            ownedResult = ApiResult.HttpFailure(502, "Could not reach telnyx to list numbers.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.owned is OwnedState.Failed)
    }

    @Test
    fun `a search that never reached the server is a retryable failure, not an account state`() = runTest {
        // ⚠️ Only an HTTP 400 means "finish connecting a carrier"; a dropped connection has no status.
        val api = api().apply { searchResult = ApiResult.NetworkFailure(java.io.IOException("offline")) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.search()
        advanceUntilIdle()

        val failed = vm.state.value.search as SearchState.Failed
        assertTrue(failed.failure.retryable)
    }

    @Test
    fun `a tab switch made while the owned list loads survives the list landing`() = runTest {
        // ⛔ THE BUG THIS PINS. The owned read copied the state it saw when it STARTED, so an
        // operator who switched to Search (or typed a filter) while it loaded was put back.
        val api = HeldOwnedApi().apply { ownedResult = api().ownedResult }
        val vm = viewModel(api)
        runCurrent()

        vm.selectTab(MarketplaceTab.SEARCH)
        vm.updateForm(NumberSearchForm(areaCode = "416"))
        api.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(MarketplaceTab.SEARCH, vm.state.value.tab)
        assertEquals("416", vm.state.value.form.areaCode)
        assertTrue(vm.state.value.owned is OwnedState.Ready)
    }

    /** Holds the owned read until [gate] opens, as a slow network would. */
    private class HeldOwnedApi : TestDistrictApi() {
        val gate = CompletableDeferred<Unit>()

        override suspend fun ownedNumbers(workspaceId: String): ApiResult<OwnedNumbersResponse> {
            gate.await()
            return super.ownedNumbers(workspaceId)
        }
    }
}
