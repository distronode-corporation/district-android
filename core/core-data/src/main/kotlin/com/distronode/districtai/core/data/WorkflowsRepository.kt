package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CampaignStatus
import com.distronode.districtai.core.model.CampaignStatusResponse
import com.distronode.districtai.core.model.WorkflowListResponse
import com.distronode.districtai.core.model.WorkflowRunsResponse
import com.distronode.districtai.core.model.WorkflowToggleResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CampaignPauseRequest
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.WorkflowToggleRequest

/**
 * The automation monitor: what is configured, whether it is on, and what it did.
 *
 * ⛔ THREE INDEPENDENT READS BEHIND ONE SCREEN, AND THEY MUST NEVER SHARE A FAILURE STATE. The
 * workflow list, one workflow's run history and the SDR campaign status hit three different routes
 * against two different data layers — the first two are RLS-scoped reads on the regional database,
 * the third is a region-resolved read of a Workspace row that has RLS disabled by design. Folding
 * them into one result means a campaign read that failed would blank a workflow list that was
 * answered correctly, which is the same mistake [NumbersRepository] documents for its two.
 *
 * ⛔ NO CACHE. This screen exists to answer "is the automation working right now" — the one
 * question a stale answer is worst at. The run history in particular is what somebody opens after
 * a customer says they never got the follow-up.
 *
 * ⛔ TWO WRITES, AND NEITHER OF THEM IS A GENERAL EDIT. The workflow toggle flips one boolean on
 * one row; the campaign pause flips one key inside one Json column. There is no create, no edit and
 * no delete for a workflow — see `WorkflowsApi` — and the campaign pause reaches
 * `workspace/campaign-status` rather than `workspace/campaign-settings` precisely because the
 * latter is destructive on a partial body.
 */
class WorkflowsRepository(private val api: DistrictApi) {

    /**
     * Every workflow, newest first.
     *
     * ⚠️ Envelope first, for the reason [ResponseEnvelope] documents: every field of the response
     * has a default, so a `{}` body decodes into a perfectly well-formed "this workspace has no
     * workflows". On this screen that reads as "your automation was deleted", which is the exact
     * class of confident lie the envelope guard exists to stop.
     *
     * ⚠️ AN EMPTY LIST IS STILL A LEGITIMATE ANSWER once `success` is affirmed — most workspaces
     * have never created a workflow — so the caller renders an explanatory empty state rather
     * than a failure.
     */
    suspend fun workflows(workspaceId: String): ApiResult<WorkflowListResponse> =
        when (val result = api.workflows(workspaceId)) {
            is ApiResult.Success -> rejectedEnvelope(LIST_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    /**
     * One workflow's run history.
     *
     * ⛔ `limit` AND `offset` ARE PASSED THROUGH UNMODIFIED AND ARE NOT PRE-CLAMPED HERE. The
     * server clamps to 1..50 and ECHOES what it applied; a second clamp in this layer would be a
     * copy of a rule that lives elsewhere, and the failure mode of a drifted copy is a client that
     * pages by a size the server never used — every "load more" then either skips rows or repeats
     * them. The echo in the response is what the caller must page from.
     *
     * ⚠️ `hasMore` IS THE SERVER'S, computed from `total`, so end-of-list is KNOWN rather than
     * inferred from a short page. Do not re-derive it from `runs.size < limit`: a run deleted
     * between two requests makes those two answers disagree.
     */
    suspend fun runs(
        workspaceId: String,
        workflowId: String,
        limit: Int,
        offset: Int,
    ): ApiResult<WorkflowRunsResponse> =
        when (
            val result = api.workflowRuns(
                workspaceId = workspaceId,
                workflowId = workflowId,
                limit = limit,
                offset = offset,
            )
        ) {
            is ApiResult.Success -> rejectedEnvelope(RUNS_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    /**
     * Turn one workflow on or off.
     *
     * ⛔ THE ENVELOPE GUARD IS THE ENTIRE CONTENT CHECK, because the response has no content: the
     * route answers a bare `{success:true}` and echoes nothing about the row it wrote. So a caller
     * that toggled optimistically has nothing to reconcile against, and the only two outcomes are
     * "the write landed" and "revert".
     *
     * ⛔ EXCLUDES `viewer` SERVER-SIDE. The refusal is a plain role 403 carrying no machine-
     * readable code, so it is indistinguishable from any other role refusal and is surfaced as
     * one — the UI gates the control instead of relying on this.
     */
    suspend fun setActive(
        workspaceId: String,
        workflowId: String,
        active: Boolean,
    ): ApiResult<WorkflowToggleResponse> =
        when (
            val result = api.setWorkflowActive(
                WorkflowToggleRequest(
                    workspaceId = workspaceId,
                    workflowId = workflowId,
                    active = active,
                ),
            )
        ) {
            is ApiResult.Success -> rejectedEnvelope(TOGGLE_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    /**
     * Whether the always-on SDR campaign is running.
     *
     * ⛔ RETURNS THE UNWRAPPED [CampaignStatus], NOT THE ENVELOPE, AND THAT IS THE POINT RATHER
     * THAN A CONVENIENCE. `CampaignStatusResponse.campaign` is nullable only so a `{}` body can
     * decode at all; the route always sends the object on a 200, because it normalises every field
     * itself — a workspace that has never opened the campaigns tab answers
     * `{success:true, campaign:{infiniteSdrEnabled:false, sdrBatchSize:null, sdrCampaignGoal:null}}`.
     * Handing a caller the nullable would make every screen re-decide what a null means, and the
     * tempting answer is the wrong one: the DTO's defaults render as "Paused, no batch size, no
     * goal", which is a confident claim about somebody's live outbound campaign made from a body
     * that said nothing at all. Unwrapping here means that claim can only come from a real object.
     *
     * ⚠️ AN ABSENT `campaign` IS REPORTED AS [ApiResult.DecodeFailure] for the same reason the
     * envelope guard is: it is contract drift, it is not connectivity, and it should be noisy in
     * debug builds rather than rendered as a state.
     */
    suspend fun campaignStatus(workspaceId: String): ApiResult<CampaignStatus> =
        unwrapCampaign(api.campaignStatus(workspaceId))

    /**
     * Pause or resume the always-on SDR engine.
     *
     * ⛔ RETURNS THE POST-WRITE [CampaignStatus] AND THE CALLER IS EXPECTED TO ADOPT IT. The route
     * answers in the READ's shape, derived from the object it just wrote rather than re-read from
     * a replica — so there is no window in which the client and the server disagree, and no second
     * request needed to close one. That is why this write is not optimistic the way
     * [setActive] is: there the response carries nothing, so a flip-and-revert is the only design
     * available; here the truth arrives with the reply.
     *
     * ⛔ AND IT GOES THROUGH `campaign-status`, WHICH MERGES. The three SDR fields live in one
     * free-form Json column, and `campaign-settings` rebuilds all of them from its request body —
     * so the same `{infiniteSdrEnabled:false}` sent there wipes the goal text and resets the batch
     * size to 1, answering 200. Do not "simplify" the two writes onto one path.
     *
     * ⛔ EXCLUDES `viewer` SERVER-SIDE while the read admits them. The refusal is a plain role 403
     * with no machine-readable code, so the UI gates the control rather than relying on this.
     */
    suspend fun setCampaignEnabled(
        workspaceId: String,
        enabled: Boolean,
    ): ApiResult<CampaignStatus> = unwrapCampaign(
        api.setCampaignEnabled(
            CampaignPauseRequest(workspaceId = workspaceId, infiniteSdrEnabled = enabled),
        ),
    )

    /**
     * ⛔ SHARED BY THE READ AND THE WRITE ON PURPOSE, because they answer in the same shape and a
     * second copy of this unwrapping is a second chance for one of them to render the DTO's
     * defaults — "Paused, no batch size, no goal" — from a body that said nothing at all. That
     * claim is about somebody's live outbound campaign, and it is worse coming from the WRITE
     * path, where it would read as the state the operator just created.
     */
    private fun unwrapCampaign(
        result: ApiResult<CampaignStatusResponse>,
    ): ApiResult<CampaignStatus> = when (result) {
        is ApiResult.Success ->
            rejectedEnvelope(CAMPAIGN_ENVELOPE, result.value.success)
                ?: result.value.campaign?.let { ApiResult.Success(it) }
                ?: ApiResult.DecodeFailure(
                    cause = IllegalStateException("$CAMPAIGN_ENVELOPE carried no campaign"),
                    // ⚠️ No decoded body in the preview: this response carries a workspace's own
                    // outbound campaign goal, which is customer-facing copy rather than a
                    // diagnostic. The only fact worth reporting is that the object was absent.
                    bodyPreview = "$CAMPAIGN_ENVELOPE{campaign=null}",
                )
        is ApiResult.Failure -> result
    }

    private companion object {
        const val LIST_ENVELOPE = "WorkflowListResponse"
        const val RUNS_ENVELOPE = "WorkflowRunsResponse"
        const val TOGGLE_ENVELOPE = "WorkflowToggleResponse"
        const val CAMPAIGN_ENVELOPE = "CampaignStatusResponse"
    }
}
