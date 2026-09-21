package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.core.model.WorkspaceListResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * The set of workspaces the user may operate on, and which one is active.
 *
 * ⛔ THE ACTIVE WORKSPACE IS CHOSEN HERE AND SENT EXPLICITLY. It cannot be left to the server:
 * its fallback is index 0 of its own membership listing, which on the web is the httpOnly
 * selection cookie promoted to the front, and this client has no cookie to promote. Omitting
 * `workspaceId` on a multi-workspace account therefore reports on whichever workspace the
 * server's owned-first sort happens to put first — not the one the user picked, and with no
 * error to reveal the disagreement.
 */
class WorkspaceRepository(
    private val api: DistrictApi,
    private val selectionStore: WorkspaceSelectionStore,
) {

    /**
     * Fetch the list and resolve the active workspace in one step.
     *
     * ⚠️ Returns the API failure UNCHANGED rather than folding it into an empty list. In
     * particular [ApiResult.RegionsDegraded] must survive to the UI: it means the list would
     * have been INCOMPLETE, and drawing it as "no workspaces" is the mistake that sent a
     * paying customer to a checkout page on the web.
     */
    suspend fun load(): ApiResult<WorkspaceState> = when (val result = api.workspaceList()) {
        // ⛔ THE ENVELOPE IS CHECKED BEFORE THE LIST IS TRUSTED, AND THIS IS THE MOST
        // CONSEQUENTIAL USE OF THAT CHECK IN THE APP. Every field of WorkspaceListResponse has a
        // default, so a 200 carrying `{}` used to decode into an empty list with no degraded
        // regions and no inactive count — which [WorkspaceState.hasNoWorkspaces] then reports as
        // "this account genuinely has nothing". That is the same conflation that sent a
        // subscribed customer to a checkout page on the web. See [rejectedEnvelope].
        is ApiResult.Success -> rejectedEnvelope(ENVELOPE_NAME, result.value.success)
            ?: ApiResult.Success(toState(result.value))
        is ApiResult.Failure -> result
    }

    /**
     * Record the user's choice.
     *
     * ⚠️ Accepts an id rather than a [WorkspaceEntry] so a caller cannot pass an entry from a
     * stale list, but does NOT validate it here — validation belongs at the point the list is
     * known, in [toState], which drops a selection that no longer appears. The server is the
     * final authority regardless and answers 403 for an id the caller is not a member of.
     */
    fun select(workspaceId: String) {
        selectionStore.setSelectedWorkspaceId(workspaceId)
    }

    /** Forget the local choice, returning to the server's own ordering on next load. */
    fun clearSelection() {
        selectionStore.setSelectedWorkspaceId(null)
    }

    private fun toState(response: WorkspaceListResponse): WorkspaceState {
        val stored = selectionStore.selectedWorkspaceId()

        // ⛔ THE PRECEDENCE HERE IS NOT ARBITRARY, AND EVERY STEP RESOLVES AGAINST THE LIST
        // THE SERVER JUST SENT. An id is only ever adopted if it appears in `workspaces`,
        // which is what makes a stale, revoked or lapsed selection harmless: it simply fails
        // to match and the next candidate is used.
        //
        //   1. This device's explicit choice — the user's most recent, most local intent.
        //   2. The account's stored default ("remember my choice"), so a fresh install lands
        //      where the browser would. ⚠️ The server echoes this WITHOUT checking it against
        //      the list, so it genuinely can name a workspace that is not there.
        //   3. Index 0, which is the server's own answer to "which is active" — it has
        //      already applied owned-first ordering, so a workspace the user OWNS beats a
        //      shared org they merely belong to.
        val active = response.workspaces.firstOrNull { it.id == stored }
            ?: response.workspaces.firstOrNull { it.id == response.defaultWorkspaceId }
            ?: response.workspaces.firstOrNull()

        // Drop a selection that no longer resolves, so the app stops re-reading a dead id on
        // every launch. Only when the list is genuinely usable: doing it on an incomplete
        // answer would discard a valid choice because one region was briefly unreachable.
        if (stored != null && active?.id != stored && response.degradedRegions.isEmpty()) {
            selectionStore.setSelectedWorkspaceId(null)
        }

        return WorkspaceState(
            workspaces = response.workspaces,
            active = active,
            degradedRegions = response.degradedRegions,
            inactiveCount = response.inactiveCount,
        )
    }

    private companion object {
        const val ENVELOPE_NAME = "WorkspaceListResponse"
    }
}

/**
 * The resolved workspace picture.
 *
 * ⚠️ [active] being null does NOT by itself mean "this account has nothing". Check
 * [inactiveCount] and [degradedRegions] before saying so — see [hasNoWorkspaces].
 */
data class WorkspaceState(
    val workspaces: List<WorkspaceEntry>,
    /** The workspace to operate on, or null when there is none to choose. */
    val active: WorkspaceEntry?,
    /**
     * Regions that did not answer. Non-empty means [workspaces] is a SUBSET of the truth, and
     * the UI must say so rather than presenting it as complete.
     */
    val degradedRegions: List<String> = emptyList(),
    /**
     * Workspaces withheld because their subscription is not active. When [workspaces] is empty
     * and this is positive, the account exists and billing lapsed — a different screen from
     * "no workspaces", and the only thing that distinguishes them.
     *
     * ⚠️ Billing is read-only in this app; report the state, do not offer a way to pay.
     */
    val inactiveCount: Int = 0,
) {
    /** True only when the account genuinely has nothing — not when the answer was incomplete. */
    val hasNoWorkspaces: Boolean
        get() = workspaces.isEmpty() && inactiveCount == 0 && degradedRegions.isEmpty()

    /** True when everything the account has is unpaid. */
    val isBillingBlocked: Boolean
        get() = workspaces.isEmpty() && inactiveCount > 0

    /** True when at least one workspace is known but the list may be short. */
    val isPartial: Boolean
        get() = degradedRegions.isNotEmpty()
}
