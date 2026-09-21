package com.distronode.districtai.ui.overview

import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.model.WorkspaceEntry
import com.distronode.districtai.ui.SignedOutCause
import com.distronode.districtai.ui.UiText

/**
 * Everything the overview screen can be showing.
 *
 * ⛔ THE "NOTHING TO SHOW" CASES ARE SEPARATE ON PURPOSE, AND COLLAPSING THEM IS THE BUG THIS
 * GUARDS AGAINST. An empty screen has at least four distinct causes here, and only one of them
 * means what an empty screen looks like it means:
 *
 *   [NoWorkspaces]     the account genuinely has none — contact support
 *   [BillingBlocked]   it has some, all unpaid — a billing message, resolved on the web
 *   [Unavailable]      a region did not answer, so we could not finish looking — RETRY
 *   [SignedOut]        the session ended — sign in again
 *
 * Rendering [Unavailable] as [NoWorkspaces] is indistinguishable from account loss to the person
 * holding the phone, and on the web the same conflation can route a paying customer to a checkout
 * page. That is why the server refuses to answer an empty list when it knows a region was down,
 * and why this type refuses to have one empty state.
 */
sealed interface OverviewUiState {

    /** First load, or a workspace switch. */
    data object Loading : OverviewUiState

    /**
     * The screen has data.
     *
     * @param degradedRegions non-empty when the workspace LIST was incomplete. ⚠️ The overview
     *   figures themselves are fine — they came from one workspace — but the switcher is missing
     *   entries, so the UI must say so rather than implying the user has fewer workspaces than
     *   they do.
     * @param refreshing true during a pull-to-refresh, so content stays visible instead of
     *   flashing back to [Loading].
     * @param showFinishSetup true when this workspace's OWNER is mid-way through the web setup
     *   wizard, so the screen offers "Finish setting up on the web". Filled in after the rest of the
     *   screen by a separate owner-only read; false for everyone else and on any failure of it.
     */
    data class Content(
        val overview: Overview,
        val workspaces: List<WorkspaceEntry>,
        val active: WorkspaceEntry,
        val degradedRegions: List<String> = emptyList(),
        val refreshing: Boolean = false,
        val showFinishSetup: Boolean = false,
    ) : OverviewUiState {
        /** Whether to offer a switcher at all. */
        val canSwitchWorkspace: Boolean get() = workspaces.size > 1

        /**
         * Whether to offer mutating controls.
         *
         * ⚠️ Read off the OVERVIEW's role, not the workspace list's: support access resolves to
         * "agency" for the request even with no membership row, so the per-request role is the
         * effective one. Null (an unparsed role) means no privileges.
         */
        val canMutate: Boolean get() = overview.role?.canMutate == true
    }

    /** The account belongs to no workspace at all. Genuinely empty, verified not degraded. */
    data object NoWorkspaces : OverviewUiState

    /**
     * Every workspace this account has is unpaid.
     *
     * ⚠️ Billing is READ-ONLY in this app — Play's Payments policy. State the situation and point
     * at the browser; do not present a purchase path.
     */
    data class BillingBlocked(val inactiveCount: Int) : OverviewUiState

    /**
     * We could not complete the read. ⛔ NOT an empty account — always retryable.
     *
     * @param degradedRegions named when the cause was specifically unreachable regions, so the
     *   message can be concrete instead of a generic failure.
     */
    data class Unavailable(
        /**
         * ⚠️ A [UiText] rather than a `String`, because the ViewModel that builds this has no
         * `Context` and was therefore holding English literals. See [UiText].
         */
        val message: UiText,
        val degradedRegions: List<String> = emptyList(),
    ) : OverviewUiState

    /** There is no usable session. [cause] decides what the user is told. */
    data class SignedOut(val cause: SignedOutCause) : OverviewUiState
}
