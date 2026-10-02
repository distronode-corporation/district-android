package com.distronode.districtai.ui.workflows

import com.distronode.districtai.core.data.WorkflowsRepository
import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.CampaignStatusResponse
import com.distronode.districtai.core.model.WorkflowLatestRun
import com.distronode.districtai.core.model.WorkflowListItem
import com.distronode.districtai.core.model.WorkflowListResponse
import com.distronode.districtai.core.model.WorkflowRun
import com.distronode.districtai.core.model.WorkflowRunsResponse
import com.distronode.districtai.core.model.WorkflowToggleResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The automation monitor's state machine.
 *
 * ⛔ THE FOUR THINGS THIS SCREEN MUST NEVER DO. It must not render "we could not read the workflow
 * list" as "you have no workflows". It must not let a viewer's tap reach a route that would 403.
 * It must not leave a switch showing a write that was refused. And it must not discard the pages of
 * run history already on screen when a later page fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val active = WorkflowListItem(
        id = "wf-active",
        name = "Missed-call follow-up",
        active = true,
        trigger = "call_ended_unanswered",
        createdAt = "2026-08-18T10:00:00.000Z",
        latestRun = WorkflowLatestRun(status = "success", startedAt = "2026-08-18T11:30:00.000Z"),
    )

    private val paused = WorkflowListItem(
        id = "wf-paused",
        name = "New contact welcome",
        active = false,
        trigger = "contact_created",
        createdAt = "2026-08-01T09:00:00.000Z",
    )

    private fun run(id: String) = WorkflowRun(
        id = id,
        workflowId = "wf-active",
        trigger = "call_ended_unanswered",
        status = "success",
        startedAt = "2026-08-18T11:30:00.000Z",
    )

    private fun api() = FakeDistrictApi().apply {
        workflowsResult = ApiResult.Success(
            WorkflowListResponse(success = true, workflows = listOf(active, paused)),
        )
        campaignStatusResult = ApiResult.Success(
            CampaignStatusResponse(
                success = true,
                campaign = CampaignStatus(
                    infiniteSdrEnabled = true,
                    sdrBatchSize = 25,
                    sdrCampaignGoal = "Book demos",
                ),
            ),
        )
        workflowRunsResult = ApiResult.Success(
            WorkflowRunsResponse(
                success = true,
                runs = listOf(run("run-1"), run("run-2")),
                total = 5,
                limit = RUNS_PAGE_SIZE,
                offset = 0,
                hasMore = true,
            ),
        )
    }

    private fun viewModel(api: FakeDistrictApi, role: WorkspaceRole? = WorkspaceRole.CLIENT) =
        WorkflowsViewModel(WorkflowsRepository(api), workspaceId = "ws-1", role = role)

    // ── The two reads on entry ───────────────────────────────────────────────

    @Test
    fun `both reads run on entry and neither fetches run history`() = runTest {
        // ⛔ NO RUN HISTORY ON OPEN. Reading every workflow's runs eagerly would be one request per
        // row for history nobody has asked to see, on a screen whose list is unbounded.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), api.workflowListRequests)
        assertEquals(listOf("ws-1"), api.campaignStatusRequests)
        assertTrue(api.workflowRunRequests.isEmpty())
        assertTrue(vm.state.value.workflows is WorkflowListState.Ready)
        assertTrue(vm.state.value.campaign is CampaignState.Ready)
    }

    @Test
    fun `a campaign failure leaves the workflow list alone`() = runTest {
        // ⛔ TWO INDEPENDENT READS AGAINST TWO DIFFERENT DATABASES. A campaign read that 500'd must
        // not blank a workflow list that answered correctly.
        val api = api().apply { campaignStatusResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.campaign is CampaignState.Failed)
        assertTrue(vm.state.value.workflows is WorkflowListState.Ready)
    }

    @Test
    fun `a list failure leaves the campaign card alone`() = runTest {
        val api = api().apply { workflowsResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.workflows is WorkflowListState.Failed)
        assertTrue(vm.state.value.campaign is CampaignState.Ready)
    }

    @Test
    fun `an unaffirmed list envelope is a failure, not an empty workspace`() = runTest {
        // ⛔ "We could not look" must never be drawn as "you have built no automation".
        val api = api().apply {
            workflowsResult = ApiResult.Success(WorkflowListResponse())
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.workflows is WorkflowListState.Failed)
    }

    // ── Lazy run history ─────────────────────────────────────────────────────

    @Test
    fun `expanding fetches the first page once and caches it`() = runTest {
        // ⛔ THE CACHE IS WHAT MAKES THE NATURAL GESTURE CHEAP: open one, close it, open the next,
        // go back. Re-reading on every expand would charge a request for each of those.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleExpanded("wf-active")
        advanceUntilIdle()
        vm.toggleExpanded("wf-active")
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()

        assertEquals(listOf("ws-1/wf-active/$RUNS_PAGE_SIZE/0"), api.workflowRunRequests)
        assertEquals("wf-active", vm.state.value.expanded)
        assertEquals(2, vm.state.value.runs.getValue("wf-active").runs.size)
    }

    @Test
    fun `tapping the open row closes it`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.toggleExpanded("wf-active")
        advanceUntilIdle()
        vm.toggleExpanded("wf-active")

        assertNull(vm.state.value.expanded)
    }

    @Test
    fun `load more appends rather than replacing, and pages by rows held`() = runTest {
        // ⛔ THE OFFSET IS THE NUMBER OF ROWS ALREADY HELD, NOT A PAGE COUNTER. The server may apply
        // a different limit than was asked for, and a page counter drifts the moment those two
        // disagree — silently skipping or repeating runs.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()

        api.workflowRunsResult = ApiResult.Success(
            WorkflowRunsResponse(
                success = true,
                runs = listOf(run("run-3")),
                total = 3,
                limit = RUNS_PAGE_SIZE,
                offset = 2,
                hasMore = false,
            ),
        )
        vm.loadMoreRuns("wf-active")
        advanceUntilIdle()

        assertEquals(
            listOf("ws-1/wf-active/$RUNS_PAGE_SIZE/0", "ws-1/wf-active/$RUNS_PAGE_SIZE/2"),
            api.workflowRunRequests,
        )
        val history = vm.state.value.runs.getValue("wf-active")
        assertEquals(listOf("run-1", "run-2", "run-3"), history.runs.map { it.id })
        assertFalse("the server's hasMore ends the list", history.hasMore)
    }

    @Test
    fun `load more is a no-op once the server says there is no more`() = runTest {
        val api = api().apply {
            workflowRunsResult = ApiResult.Success(
                WorkflowRunsResponse(success = true, runs = listOf(run("run-1")), total = 1),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()

        vm.loadMoreRuns("wf-active")
        advanceUntilIdle()

        assertEquals(1, api.workflowRunRequests.size)
    }

    @Test
    fun `a failed page keeps the pages already on screen`() = runTest {
        // ⛔ THE ROWS ALREADY FETCHED ARE A CORRECT ANSWER. A third page that failed must not
        // discard the first two.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()

        api.workflowRunsResult = ApiResult.HttpFailure(500, "Server error")
        vm.loadMoreRuns("wf-active")
        advanceUntilIdle()

        val history = vm.state.value.runs.getValue("wf-active")
        assertEquals(listOf("run-1", "run-2"), history.runs.map { it.id })
        assertTrue("the failure is carried beside the rows", history.failure != null)
    }

    // ── The toggle ───────────────────────────────────────────────────────────

    @Test
    fun `a toggle flips immediately and stays flipped when the write lands`() = runTest {
        // ⛔ OPTIMISTIC, BECAUSE THE RESPONSE HAS NOTHING TO ADOPT. The PATCH answers a bare
        // `{success:true}` with no echo of the row, so the alternatives were a visibly stuck switch
        // or a full list re-read that moves every other row on screen.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)

        // Before the request has even resolved.
        assertEquals(false, activeFlag(vm, "wf-active"))
        assertTrue("wf-active" in vm.state.value.pendingToggles)

        advanceUntilIdle()

        assertEquals(false, activeFlag(vm, "wf-active"))
        assertTrue(vm.state.value.pendingToggles.isEmpty())
        assertNull(vm.state.value.toggleFailure)
        assertEquals(false, api.workflowToggles.single().active)
    }

    @Test
    fun `a refused toggle reverts to the value the client last read`() = runTest {
        // ⛔ REVERT TO THE READ VALUE, NOT TO `!attempted`. They agree for a single tap and they do
        // not for a refused tap on a row somebody else changed — the negation would then invent a
        // third value nobody ever wrote.
        val api = api().apply {
            workflowToggleResult = ApiResult.Forbidden("Insufficient permissions")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)
        assertEquals(false, activeFlag(vm, "wf-active"))
        advanceUntilIdle()

        assertEquals(true, activeFlag(vm, "wf-active"))
        assertTrue(vm.state.value.pendingToggles.isEmpty())
        assertTrue("the refusal is surfaced", vm.state.value.toggleFailure != null)
    }

    @Test
    fun `a refused toggle does not blank the list`() = runTest {
        // ⛔ ALONGSIDE, NEVER INSTEAD OF. The list on screen is the truth once the row is reverted.
        val api = api().apply { workflowToggleResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)
        advanceUntilIdle()

        assertTrue(vm.state.value.workflows is WorkflowListState.Ready)
        assertEquals(2, (vm.state.value.workflows as WorkflowListState.Ready).workflows.size)
    }

    @Test
    fun `an unaffirmed toggle envelope reverts too`() = runTest {
        // ⚠️ The envelope is the entire content check on a response that carries no content, so a
        // `{}` body has to revert exactly as a 403 does.
        val api = api().apply {
            workflowToggleResult = ApiResult.Success(WorkflowToggleResponse())
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)
        advanceUntilIdle()

        assertEquals(true, activeFlag(vm, "wf-active"))
    }

    @Test
    fun `a viewer cannot toggle, and the request is never even made`() = runTest {
        // ⛔ GATED IN THE ViewModel AS WELL AS IN THE SCREEN. The route excludes `viewer`, and a
        // ViewModel that would issue the request if asked is one refactor from an optimistic flip
        // that shows a workflow as changed for the second before the 403 lands.
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(vm.canToggle)
        vm.setActive("wf-active", active = false)
        advanceUntilIdle()

        assertTrue(api.workflowToggles.isEmpty())
        assertEquals(true, activeFlag(vm, "wf-active"))
    }

    @Test
    fun `an unparsed role fails closed to no toggling`() = runTest {
        // ⚠️ Null means the role could not be established, never "assume the default" — the same
        // rule `WorkspaceRole.fromWire` states.
        val api = api()
        val vm = viewModel(api, role = null)
        advanceUntilIdle()

        assertFalse(vm.canToggle)
        vm.setActive("wf-active", active = false)
        advanceUntilIdle()

        assertTrue(api.workflowToggles.isEmpty())
    }

    @Test
    fun `a second tap on the same switch while one is in flight is dropped`() = runTest {
        // ⛔ TWO WRITES ON ONE ROW WOULD LEAVE THE OPTIMISTIC VALUE AND THE EVENTUAL REVERT
        // DISAGREEING about which way the row ended up.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)
        vm.setActive("wf-active", active = true)
        advanceUntilIdle()

        assertEquals(1, api.workflowToggles.size)
    }

    @Test
    fun `two different switches are not serialised against each other`() = runTest {
        // ⚠️ UNLIKE THE DEVICE SCREEN'S SINGLE `busy` FLAG. These writes touch different rows and
        // nothing re-reads afterwards, so they do not race.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)
        vm.setActive("wf-paused", active = true)
        advanceUntilIdle()

        assertEquals(
            listOf("wf-active", "wf-paused"),
            api.workflowToggles.map { it.workflowId },
        )
    }

    @Test
    fun `dismissing the toggle failure clears it without a re-read`() = runTest {
        val api = api().apply { workflowToggleResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.setActive("wf-active", active = false)
        advanceUntilIdle()

        vm.dismissToggleFailure()

        assertNull(vm.state.value.toggleFailure)
        assertEquals(1, api.workflowListRequests.size)
    }

    // ── Reload ───────────────────────────────────────────────────────────────

    @Test
    fun `a reload re-reads both and drops the cached run history`() = runTest {
        // ⛔ A CACHED HISTORY CARRIED ACROSS A SESSION CHANGE COULD BELONG TO ANOTHER ACCOUNT. It
        // costs one re-fetch on the rare case where the same row is reopened, which is the right
        // side of that trade.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()

        vm.load()
        advanceUntilIdle()

        assertEquals(2, api.workflowListRequests.size)
        assertEquals(2, api.campaignStatusRequests.size)
        assertTrue(vm.state.value.runs.isEmpty())
        assertNull(vm.state.value.expanded)
        // ⚠️ And re-expanding fetches again, which is what proves the cache was really dropped
        // rather than merely hidden.
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()
        assertEquals(2, api.workflowRunRequests.size)
    }

    // ── Pausing and resuming the campaign ────────────────────────────────────

    @Test
    fun `asking to pause opens a confirmation and writes NOTHING`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        advanceUntilIdle()

        assertEquals(CampaignConfirm(enable = false), vm.state.value.campaignConfirm)
        assertTrue("the dialog must not be a write", api.campaignPauses.isEmpty())
    }

    @Test
    fun `confirming sends the merge-only body and ADOPTS the response`() = runTest {
        // ⛔ NOT OPTIMISTIC. The route answers in the GET's shape, derived from the object it
        // merged, so the post-write truth arrives with the reply — there is nothing to guess and
        // nothing to revert. The fake echoes the request for exactly this reason: a ViewModel that
        // re-rendered its own guess instead of the response would pass a fixed-reply fake.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.confirmCampaignChange()
        advanceUntilIdle()

        assertEquals(1, api.campaignPauses.size)
        val sent = api.campaignPauses.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals(false, sent.infiniteSdrEnabled)

        val state = vm.state.value
        assertNull("the dialog closes on submit", state.campaignConfirm)
        assertFalse(state.campaignPending)
        assertNull(state.campaignFailure)
        assertEquals(
            false,
            (state.campaign as CampaignState.Ready).status.infiniteSdrEnabled,
        )
    }

    @Test
    fun `a viewer can neither open the confirmation nor commit one`() = runTest {
        // ⛔ THE GUARD IS IN THE ViewModel AS WELL AS THE SCREEN. The route excludes `viewer`, so
        // the button is not drawn — but a state machine that would issue the request if asked is
        // one refactor away from a confirmation whose submit 403s.
        val api = api()
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.confirmCampaignChange()
        advanceUntilIdle()

        assertFalse(vm.canToggle)
        assertNull(vm.state.value.campaignConfirm)
        assertTrue("a viewer's tap must never reach the route", api.campaignPauses.isEmpty())
    }

    @Test
    fun `asking for the value already held is a no-op`() = runTest {
        // ⚠️ The write is idempotent, so this is not a safety guard — it is the same call the
        // analytics range chips make: a control that spends a round trip to change nothing is
        // worse than one that does not respond.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = true)
        advanceUntilIdle()

        assertNull(vm.state.value.campaignConfirm)
        assertTrue(api.campaignPauses.isEmpty())
    }

    @Test
    fun `a refused pause keeps the last state the SERVER sent and explains itself`() = runTest {
        // ⛔ THE CARD MUST NOT SHOW THE VALUE THAT WAS REFUSED, and it must not blank either. The
        // campaign is still running, and "is my campaign running" is exactly the question being
        // asked at that moment.
        val api = api().apply {
            campaignPauseResult = ApiResult.HttpFailure(403, "Insufficient permissions")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.confirmCampaignChange()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(true, (state.campaign as CampaignState.Ready).status.infiniteSdrEnabled)
        assertFalse(state.campaignPending)
        assertTrue(state.campaignFailure != null)

        // ⚠️ AND ASKING AGAIN CLEARS IT. There is no dedicated dismiss: the failure sits on the
        // card whose own button is the way out of it, so every route forward already clears it.
        vm.requestCampaignChange(enable = false)
        assertNull(vm.state.value.campaignFailure)
    }

    @Test
    fun `a 200 carrying no campaign is drift rather than a paused campaign`() = runTest {
        // ⛔ THE DTO'S DEFAULTS RENDER AS "Paused, no batch size, no goal" — a confident claim
        // about somebody's live outbound campaign built from a body that said nothing. It is
        // worse on the WRITE path than on the read, because there it would read as the state the
        // operator just created.
        val api = api().apply {
            campaignPauseResult = ApiResult.Success(CampaignStatusResponse(success = true))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.confirmCampaignChange()
        advanceUntilIdle()

        assertEquals(true, (vm.state.value.campaign as CampaignState.Ready).status.infiniteSdrEnabled)
        assertTrue(vm.state.value.campaignFailure != null)
    }

    @Test
    fun `backing out of the confirmation writes nothing`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.dismissCampaignConfirm()
        vm.confirmCampaignChange()
        advanceUntilIdle()

        assertNull(vm.state.value.campaignConfirm)
        assertTrue(api.campaignPauses.isEmpty())
    }

    @Test
    fun `a reload drops an open confirmation rather than carrying it`() = runTest {
        // ⛔ A DIALOG SURVIVING A SESSION CHANGE WOULD BE ASKING ABOUT THE PREVIOUS ACCOUNT'S
        // CAMPAIGN, and its submit would then write to whatever workspace this ViewModel holds.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.load()
        advanceUntilIdle()

        assertNull(vm.state.value.campaignConfirm)
        assertTrue(api.campaignPauses.isEmpty())
    }

    // ── Taps with nothing to act on, and answers that land after a reload ────

    @Test
    fun `run history that lands after a reload is dropped, so the next expand reads again`() = runTest {
        // ⛔ THE BUG THIS PINS. The reload empties the run cache (after a session change, the cached
        // history may be another account's). A page still in flight used to write itself back into
        // the emptied cache, and the next expand then showed it without a fetch.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleExpanded("wf-active")
        vm.load()
        advanceUntilIdle()

        assertTrue("the stale page must not refill the cache", vm.state.value.runs.isEmpty())
        vm.toggleExpanded("wf-active")
        advanceUntilIdle()
        assertEquals("the reopened row reads its history again", 2, api.workflowRunRequests.size)
    }

    @Test
    fun `load more is dropped for a row never opened, and while a page is in flight`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadMoreRuns("wf-active")
        advanceUntilIdle()
        assertTrue(api.workflowRunRequests.isEmpty())

        vm.toggleExpanded("wf-active")
        advanceUntilIdle()
        vm.loadMoreRuns("wf-active")
        vm.loadMoreRuns("wf-active")
        advanceUntilIdle()

        assertEquals("the first page and ONE next page", 2, api.workflowRunRequests.size)
    }

    @Test
    fun `a toggle is dropped before the list has loaded, and for a workflow not in it`() = runTest {
        val api = api()
        val vm = viewModel(api)

        vm.setActive("wf-active", active = false)
        advanceUntilIdle()
        vm.setActive("wf-gone", active = false)
        advanceUntilIdle()

        assertTrue(api.workflowToggles.isEmpty())
    }

    @Test
    fun `a refused toggle that lands during a reload leaves the fresh list as the server sent it`() = runTest {
        val api = api().apply { workflowToggleResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setActive("wf-active", active = false)
        vm.load()
        advanceUntilIdle()

        val list = (vm.state.value.workflows as WorkflowListState.Ready).workflows
        assertTrue("the server's own value, not a revert over a list that was loading", list.first().active)
        assertTrue(vm.state.value.toggleFailure != null)
    }

    @Test
    fun `the campaign cannot be asked about before it loads, or while a write is in flight`() = runTest {
        val api = api()
        val vm = viewModel(api)

        vm.requestCampaignChange(enable = false)
        assertNull("nothing to ask about before the card loads", vm.state.value.campaignConfirm)
        advanceUntilIdle()

        vm.requestCampaignChange(enable = false)
        vm.confirmCampaignChange()
        vm.requestCampaignChange(enable = true)
        vm.confirmCampaignChange()
        advanceUntilIdle()

        assertEquals("one write for one confirmation", 1, api.campaignPauses.size)
    }
}

/** Reads one workflow's `active` flag out of the list state. */
private fun activeFlag(vm: WorkflowsViewModel, id: String): Boolean? =
    (vm.state.value.workflows as? WorkflowListState.Ready)
        ?.workflows
        ?.firstOrNull { it.id == id }
        ?.active
