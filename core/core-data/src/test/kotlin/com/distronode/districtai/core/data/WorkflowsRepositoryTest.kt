package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.CampaignStatusResponse
import com.distronode.districtai.core.model.WorkflowLatestRun
import com.distronode.districtai.core.model.WorkflowListItem
import com.distronode.districtai.core.model.WorkflowListResponse
import com.distronode.districtai.core.model.WorkflowRun
import com.distronode.districtai.core.model.WorkflowRunsResponse
import com.distronode.districtai.core.model.WorkflowToggleResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The automation monitor's four calls.
 *
 * ⛔ THE THREE DISTINCTIONS THIS LAYER EXISTS TO KEEP.
 *   1. An empty workflow list on an AFFIRMED envelope is "this workspace has built no automation";
 *      the same empty list on a `{}` body is "we could not look". Every field of the response has a
 *      default, so only the envelope check separates them — and on this screen the wrong one reads
 *      as "your automation was deleted".
 *   2. `limit` and `offset` reach the server UNMODIFIED. The clamp lives server-side and its result
 *      is echoed; a second copy of the rule here would drift, and a drifted page size silently
 *      skips or repeats runs rather than erroring.
 *   3. A `campaign` object that is ABSENT is contract drift, not an unconfigured campaign. The
 *      DTO's defaults would render as "Paused, no batch size, no goal" — a confident claim about
 *      somebody's live outbound campaign made from a body that said nothing at all.
 */
class WorkflowsRepositoryTest {

    private val workflow = WorkflowListItem(
        id = "wf-1",
        name = "Missed-call follow-up",
        active = true,
        trigger = "call_ended_unanswered",
        createdAt = "2026-08-18T10:00:00.000Z",
        latestRun = WorkflowLatestRun(status = "success", startedAt = "2026-08-18T11:30:00.000Z"),
    )

    private val run = WorkflowRun(
        id = "run-1",
        workflowId = "wf-1",
        trigger = "call_ended_unanswered",
        status = "partial",
        startedAt = "2026-08-18T11:30:00.000Z",
    )

    // ── The list ─────────────────────────────────────────────────────────────

    @Test
    fun `a well-formed list is carried through`() = runTest {
        val api = FakeDistrictApi().apply {
            workflowsResult = ApiResult.Success(
                WorkflowListResponse(success = true, workflows = listOf(workflow)),
            )
        }

        val result = WorkflowsRepository(api).workflows("ws-1")

        assertEquals(listOf("ws-1"), api.workflowListRequests)
        assertEquals(listOf(workflow), (result as ApiResult.Success).value.workflows)
    }

    @Test
    fun `an empty list on an affirmed envelope is a success`() = runTest {
        // ⚠️ Most workspaces have never created a workflow. This must render as an explanatory
        // empty state, never as a failure — the envelope is what makes that safe to say.
        val api = FakeDistrictApi().apply {
            workflowsResult = ApiResult.Success(WorkflowListResponse(success = true))
        }

        val result = WorkflowsRepository(api).workflows("ws-1")

        assertTrue(result is ApiResult.Success)
        assertEquals(emptyList<WorkflowListItem>(), (result as ApiResult.Success).value.workflows)
    }

    @Test
    fun `an unaffirmed envelope is rejected rather than read as an empty workspace`() = runTest {
        // ⛔ THE `{}` BODY. It decodes into a perfectly well-formed "no workflows", which on this
        // screen reads as "your automation was deleted" — the same conflation that sent a paying
        // customer to a checkout page on the web.
        val api = FakeDistrictApi().apply {
            workflowsResult = ApiResult.Success(WorkflowListResponse())
        }

        val result = WorkflowsRepository(api).workflows("ws-1")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    // ── Run history ──────────────────────────────────────────────────────────

    @Test
    fun `paging parameters reach the server unmodified`() = runTest {
        // ⛔ NO CLIENT-SIDE CLAMP. The server clamps to 1..50 and ECHOES what it applied; a second
        // copy of that rule here would be one refactor from disagreeing with it, and the symptom
        // is a client paging by a size the server never used — silently skipped or repeated runs.
        val api = FakeDistrictApi().apply {
            workflowRunsResult = ApiResult.Success(
                WorkflowRunsResponse(success = true, runs = listOf(run), total = 9, limit = 50, hasMore = true),
            )
        }

        WorkflowsRepository(api).runs("ws-1", "wf-1", limit = 999, offset = 20)

        assertEquals(listOf("ws-1/wf-1/999/20"), api.workflowRunRequests)
    }

    @Test
    fun `the server's own hasMore and echoed limit are carried through`() = runTest {
        // ⚠️ `hasMore` is computed from a real `total`, so it stays correct on a short page. A
        // caller re-deriving it from `runs.size < limit` would end the list early whenever a run
        // was written between two requests.
        val api = FakeDistrictApi().apply {
            workflowRunsResult = ApiResult.Success(
                WorkflowRunsResponse(
                    success = true,
                    runs = listOf(run),
                    total = 9,
                    limit = 4,
                    offset = 0,
                    hasMore = true,
                ),
            )
        }

        val value = (WorkflowsRepository(api).runs("ws-1", "wf-1", 4, 0) as ApiResult.Success).value

        assertTrue(value.hasMore)
        assertEquals(9, value.total)
        assertEquals(4, value.limit)
    }

    @Test
    fun `an unaffirmed runs envelope is rejected rather than read as no history`() = runTest {
        // ⛔ "This workflow has never run" and "we could not read its history" are different
        // answers, and only one of them means the automation is fine.
        val api = FakeDistrictApi().apply {
            workflowRunsResult = ApiResult.Success(WorkflowRunsResponse())
        }

        assertTrue(WorkflowsRepository(api).runs("ws-1", "wf-1", 10, 0) is ApiResult.DecodeFailure)
    }

    // ── The toggle ───────────────────────────────────────────────────────────

    @Test
    fun `the toggle sends all three fields and nothing else`() = runTest {
        // ⛔ THE PATCH ACCEPTS FOUR MORE FIELDS AND WRITES `actions` WHOLESALE. A request body one
        // field wider is one empty array from deleting every action a workflow has, with a 200.
        val api = FakeDistrictApi()

        WorkflowsRepository(api).setActive("ws-1", "wf-1", active = false)

        val sent = api.workflowToggles.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals("wf-1", sent.workflowId)
        // ⚠️ `false` SPECIFICALLY. The route tells "sent" from "absent" with `"active" in body`
        // rather than by truthiness, and false is the most common PATCH it receives.
        assertEquals(false, sent.active)
    }

    @Test
    fun `an unaffirmed toggle envelope is a failure, so the caller reverts`() = runTest {
        // ⛔ THE RESPONSE HAS NO CONTENT — a bare `{success:true}` with no echo of the row — so the
        // envelope IS the entire check, and it is the only thing standing between an optimistic
        // flip and a switch left showing a write that never landed.
        val api = FakeDistrictApi().apply {
            workflowToggleResult = ApiResult.Success(WorkflowToggleResponse())
        }

        assertTrue(WorkflowsRepository(api).setActive("ws-1", "wf-1", true) is ApiResult.DecodeFailure)
    }

    @Test
    fun `a role refusal on the toggle is passed through untranslated`() = runTest {
        // ⚠️ The route's viewer refusal is a plain 403 with no machine-readable code, so this layer
        // cannot tell it from any other role refusal and does not pretend to.
        val api = FakeDistrictApi().apply {
            workflowToggleResult = ApiResult.Forbidden("Insufficient permissions")
        }

        val result = WorkflowsRepository(api).setActive("ws-1", "wf-1", true)

        assertEquals("Insufficient permissions", (result as ApiResult.Forbidden).message)
    }

    // ── Campaign status ──────────────────────────────────────────────────────

    @Test
    fun `campaign status is unwrapped from its envelope`() = runTest {
        val api = FakeDistrictApi().apply {
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
        }

        val value = (WorkflowsRepository(api).campaignStatus("ws-1") as ApiResult.Success).value

        assertEquals(listOf("ws-1"), api.campaignStatusRequests)
        assertEquals(true, value.infiniteSdrEnabled)
        assertEquals(25, value.sdrBatchSize)
        assertEquals("Book demos", value.sdrCampaignGoal)
    }

    @Test
    fun `an all-empty campaign is a real state, not a failure`() = runTest {
        // ⚠️ A workspace that never opened the campaigns tab and one that switched the campaign off
        // are the SAME value on the wire — the route normalises both — so this has to decode as a
        // paused campaign rather than as "nothing configured", which is not a distinction that
        // exists.
        val api = FakeDistrictApi().apply {
            campaignStatusResult =
                ApiResult.Success(CampaignStatusResponse(success = true, campaign = CampaignStatus()))
        }

        val value = (WorkflowsRepository(api).campaignStatus("ws-1") as ApiResult.Success).value

        assertEquals(false, value.infiniteSdrEnabled)
        assertEquals(null, value.sdrBatchSize)
        assertEquals(null, value.sdrCampaignGoal)
    }

    @Test
    fun `an absent campaign object is drift, not an unconfigured campaign`() = runTest {
        // ⛔ THE ONE THAT WOULD LIE. The DTO's null default renders as "Paused, no batch size, no
        // goal" — a confident claim about somebody's live outbound campaign, made from a body that
        // said nothing at all. The route always sends the object on a 200, so its absence is drift.
        val api = FakeDistrictApi().apply {
            campaignStatusResult = ApiResult.Success(CampaignStatusResponse(success = true))
        }

        assertTrue(WorkflowsRepository(api).campaignStatus("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `an unaffirmed campaign envelope is rejected before the null check`() = runTest {
        val api = FakeDistrictApi().apply {
            campaignStatusResult = ApiResult.Success(CampaignStatusResponse())
        }

        assertTrue(WorkflowsRepository(api).campaignStatus("ws-1") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a transport failure is returned unchanged by every call`() = runTest {
        // ⚠️ The envelope guard must not swallow a real failure into a decode one: the two mean
        // different things to the screen, and only one of them is retryable.
        val api = FakeDistrictApi().apply {
            workflowsResult = ApiResult.HttpFailure(503, "Service unavailable")
            workflowRunsResult = ApiResult.HttpFailure(503, "Service unavailable")
            workflowToggleResult = ApiResult.HttpFailure(503, "Service unavailable")
            campaignStatusResult = ApiResult.HttpFailure(503, "Service unavailable")
        }
        val repository = WorkflowsRepository(api)

        assertTrue(repository.workflows("ws-1") is ApiResult.HttpFailure)
        assertTrue(repository.runs("ws-1", "wf-1", 10, 0) is ApiResult.HttpFailure)
        assertTrue(repository.setActive("ws-1", "wf-1", true) is ApiResult.HttpFailure)
        assertTrue(repository.campaignStatus("ws-1") is ApiResult.HttpFailure)
        api.campaignPauseResult = ApiResult.HttpFailure(503, "Service unavailable")
        assertTrue(repository.setCampaignEnabled("ws-1", false) is ApiResult.HttpFailure)
    }

    // ── Pausing the campaign ─────────────────────────────────────────────────

    @Test
    fun `a pause sends only the workspace and the one boolean`() = runTest {
        // ⛔ TWO FIELDS AND NO MORE. `campaign-settings` — the route this must never reach —
        // rebuilds `sdrCampaignGoal` and `sdrBatchSize` from its request body, so a client that
        // "helpfully" included them would be one wrong path away from writing them.
        val api = FakeDistrictApi()

        val result = WorkflowsRepository(api).setCampaignEnabled("ws-1", enabled = false)

        val sent = api.campaignPauses.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals(false, sent.infiniteSdrEnabled)
        assertEquals(false, (result as ApiResult.Success).value.infiniteSdrEnabled)
    }

    @Test
    fun `a resume sends the flag switched on, not a pause`() = runTest {
        val api = FakeDistrictApi()

        WorkflowsRepository(api).setCampaignEnabled("ws-1", enabled = true)

        assertEquals(true, api.campaignPauses.single().infiniteSdrEnabled)
    }

    @Test
    fun `the post-write campaign is unwrapped exactly as the read is`() = runTest {
        // ⚠️ The route derives its reply from what it MERGED, so the goal and batch size come back
        // untouched — and this layer hands the caller the same unwrapped type either verb produced.
        val api = FakeDistrictApi().apply {
            campaignPauseResult = ApiResult.Success(
                CampaignStatusResponse(
                    success = true,
                    campaign = CampaignStatus(
                        infiniteSdrEnabled = false,
                        sdrBatchSize = 25,
                        sdrCampaignGoal = "Book demos with lapsed trials",
                    ),
                ),
            )
        }

        val value = (WorkflowsRepository(api).setCampaignEnabled("ws-1", false) as ApiResult.Success)
            .value

        assertEquals(false, value.infiniteSdrEnabled)
        assertEquals(25, value.sdrBatchSize)
        assertEquals("Book demos with lapsed trials", value.sdrCampaignGoal)
    }

    @Test
    fun `a pause answering with no campaign object is drift, not a paused campaign`() = runTest {
        // ⛔ WORSE HERE THAN ON THE READ. The DTO's defaults render as "Paused, no batch size, no
        // goal", and on the write path that reads as the state the operator just created — so a
        // route regressed to a bare `{success:true}` would silently report a wiped campaign as the
        // result of their tap.
        val api = FakeDistrictApi().apply {
            campaignPauseResult = ApiResult.Success(CampaignStatusResponse(success = true))
        }

        assertTrue(
            WorkflowsRepository(api).setCampaignEnabled("ws-1", false) is ApiResult.DecodeFailure,
        )
    }

    @Test
    fun `an unaffirmed pause envelope is rejected before the null check`() = runTest {
        val api = FakeDistrictApi().apply {
            campaignPauseResult = ApiResult.Success(
                CampaignStatusResponse(campaign = CampaignStatus()),
            )
        }

        assertTrue(
            WorkflowsRepository(api).setCampaignEnabled("ws-1", false) is ApiResult.DecodeFailure,
        )
    }
}
