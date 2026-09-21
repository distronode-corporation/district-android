package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.OverviewMetrics
import com.distronode.districtai.core.model.OverviewResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * The dashboard landing screen's data.
 *
 * Thin on purpose: one request, and the only real work is turning the wire response into
 * something the UI cannot misread — a parsed role, and a liveness flag derived from the
 * display status rather than recomputed from a raw one.
 */
class OverviewRepository(private val api: DistrictApi) {

    /**
     * @param workspaceId the ACTIVE workspace, resolved by [WorkspaceRepository]. ⛔ Required
     *   rather than nullable: making it optional invites call sites to omit it, and the
     *   server's fallback then silently reports on a workspace the user did not choose. If
     *   there is no active workspace there is nothing to show, so the caller has a different
     *   screen to render, not a request to make.
     */
    suspend fun load(workspaceId: String): ApiResult<Overview> =
        when (val result = api.overview(workspaceId)) {
            is ApiResult.Success -> toOverview(workspaceId, result.value)
            is ApiResult.Failure -> result
        }

    /**
     * Both guards on the way in, applied here rather than offered to the caller.
     *
     * ⛔ THE WORKSPACE CHECK USED TO BE A FUNCTION ON [Overview] (`disagreesWith`) AND NOTHING
     * CALLED IT. A guard a caller has to remember to invoke — with the right argument — is not a
     * guard, and the behaviour it was supposed to prevent was therefore the shipped behaviour:
     * the overview drawn under the selected workspace's NAME while the numbers came from
     * whichever workspace the server fell back to. The local selection drifts on its own, with
     * no user action, whenever a membership is removed or a subscription lapses mid-session.
     * Detecting it in here means it cannot be bypassed by forgetting, and the requested id is
     * the one the request was actually made with rather than one passed in again by hand.
     */
    private fun toOverview(
        requestedWorkspaceId: String,
        response: OverviewResponse,
    ): ApiResult<Overview> {
        rejectedEnvelope(ENVELOPE_NAME, response.success)?.let { return it }

        // ⛔ REPORTED AS A FAILURE, NOT AS A FLAG ON A SUCCESS. The remedy this needs is "reload
        // the workspace list", which is a thing the screen does instead of rendering — so the
        // success path must not be able to carry another tenant's numbers at all. A boolean on
        // [Overview] would be exactly as ignorable as the method it replaces.
        //
        // ⚠️ A NULL `workspaceId` IS NOT A MISMATCH. The server only echoes it back; older
        // deployments and the fixtures omit it, and treating absent as disagreement would fail
        // every load rather than the drifted ones.
        val served = response.workspaceId
        if (served != null && served != requestedWorkspaceId) {
            // ⛔ ApiResult.HttpFailure WITH A SYNTHETIC STATUS, BECAUSE A NEW ApiResult CASE IS A
            // CROSS-MODULE BREAK. The exhaustive `when` over that sealed hierarchy lives in the
            // ui module, so this layer cannot add a case without editing one it does not own.
            // 409 is the honest analogue — the selection conflicts with what the server
            // resolved — and [CODE_WORKSPACE_MISMATCH] is what a caller should branch on, never
            // the status. Retryable on purpose: reloading is what fixes it.
            return ApiResult.HttpFailure(
                status = WORKSPACE_MISMATCH_STATUS,
                message = WORKSPACE_MISMATCH_MESSAGE,
                code = CODE_WORKSPACE_MISMATCH,
            )
        }

        return ApiResult.Success(
            Overview(
                workspaceId = served,
                role = WorkspaceRole.fromWire(response.role),
                metrics = response.metrics,
                avgDurationLabel = response.avgDurationLabel,
                recentCalls = response.recentCalls.map(::toActivity),
            ),
        )
    }

    private fun toActivity(call: CallSummary): RecentActivity = RecentActivity(
        id = call.id,
        // The browser prefers a resolved name, falls back to the raw number, and shows an
        // explicit "no caller ID" placeholder rather than the literal "Unknown" the API sends.
        displayName = call.callerName.takeUnless { it.isBlank() || it == UNKNOWN_CALLER }
            ?: call.from?.takeUnless { it.isBlank() },
        outbound = call.direction == DIRECTION_OUTBOUND,
        status = call.status,
        // ⚠️ DERIVED FROM THE DISPLAY STATUS, WHICH IS THE WHOLE POINT. The server has already
        // downgraded a stale in-progress call — one whose terminal webhook was lost — to
        // "no-answer", so only a genuinely live call still carries these values. Recomputing
        // liveness from a raw status here would resurrect the bug where a day-old stuck row
        // renders a pulsing Live badge forever.
        live = call.status == STATUS_IN_PROGRESS || call.status == STATUS_RINGING,
        transferred = call.transferStatus == TRANSFER_SUCCESS,
        transferFailed = call.transferStatus == TRANSFER_FAILED,
        // Pre-formatted by the server in the OPERATOR's timezone. ⚠️ Not a parseable instant;
        // use CallSummary.createdAt for anything that sorts or re-formats.
        time = call.time,
    )

    companion object {
        /**
         * The `code` on the [ApiResult.HttpFailure] this repository raises when the server
         * answered about a different workspace than was asked for.
         *
         * ⚠️ BRANCH ON THIS, NOT ON THE STATUS. The 409 is synthetic — it never came off the
         * wire — and exists only because a dedicated `ApiResult` case would mean editing an
         * exhaustive `when` in another module. A caller that recognises this code should reload
         * the workspace list and retry rather than showing the message; a caller that does not
         * gets an ordinary retryable failure, which is still strictly better than the numbers
         * being drawn under the wrong workspace's name.
         */
        const val CODE_WORKSPACE_MISMATCH = "WORKSPACE_MISMATCH"

        private const val WORKSPACE_MISMATCH_STATUS = 409

        private const val WORKSPACE_MISMATCH_MESSAGE =
            "The workspace you selected is no longer the one we can report on. Reloading your workspaces."

        private const val ENVELOPE_NAME = "OverviewResponse"
        private const val UNKNOWN_CALLER = "Unknown"
        private const val DIRECTION_OUTBOUND = "outbound"
        private const val STATUS_IN_PROGRESS = "in-progress"
        private const val STATUS_RINGING = "ringing"
        private const val TRANSFER_SUCCESS = "success"
        private const val TRANSFER_FAILED = "failed"
    }
}

/**
 * Everything the overview screen renders.
 *
 * ⛔ AN INSTANCE OF THIS IS NOW A PROMISE THAT [workspaceId] AGREES WITH WHAT WAS REQUESTED.
 * There used to be a `disagreesWith(requestedWorkspaceId)` method here for the caller to check,
 * and no caller checked it, so the mislabelling it described was simply how the app behaved.
 * [OverviewRepository.load] performs the comparison itself and answers
 * [OverviewRepository.CODE_WORKSPACE_MISMATCH] instead of a success — do not reintroduce a
 * caller-invoked variant.
 */
data class Overview(
    /**
     * As resolved by the SERVER, and therefore equal to the requested id or null.
     *
     * ⚠️ Null means the server did not echo it, which is not a disagreement — see
     * [OverviewRepository.load].
     */
    val workspaceId: String?,
    /**
     * The caller's effective role, or null if it could not be parsed.
     *
     * ⚠️ Gate mutating controls on THIS, not on the role from the workspace list: support access
     * resolves to "agency" here even with no membership row. Null
     * means no privileges — see [WorkspaceRole.fromWire].
     */
    val role: WorkspaceRole?,
    val metrics: OverviewMetrics,
    /** Already formatted. ⛔ Do not reformat `metrics.avgDuration` — two formats disagree. */
    val avgDurationLabel: String,
    val recentCalls: List<RecentActivity>,
)

/** One row of Recent Activity. */
data class RecentActivity(
    val id: String,
    /** Null when the call had no caller ID at all — render a placeholder, not "Unknown". */
    val displayName: String?,
    val outbound: Boolean,
    val status: String,
    val live: Boolean,
    val transferred: Boolean,
    val transferFailed: Boolean,
    /** Pre-formatted in the operator's timezone by the server. */
    val time: String,
)
