package com.distronode.districtai.ui.billing

import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.ui.FailureText

/**
 * The billing screen.
 *
 * ⛔ TWO INDEPENDENT READS AND THEY NEVER SHARE A FAILURE, for a stronger reason than the analytics
 * screen has. There, both halves come from our own database and tend to fail together. Here the
 * plan card comes from OUR columns and the invoice list comes from STRIPE, so the common failure is
 * one-sided by construction — and the half that survives a Stripe outage is precisely the half a
 * customer needs when their billing looks wrong: the tier, the status, and whether an overage cap
 * is currently blocking their calls.
 *
 * ⚠️ [Failed] AT THE TOP LEVEL THEREFORE MEANS THE **WORKSPACE** READ FAILED, not both. That is a
 * deliberate asymmetry from `AnalyticsUiState`, where the top-level failure needs both halves to
 * fail: the Stripe half has an "unavailable" answer of its own that arrives on a 200, so it cannot
 * be relied on to report a failure at all, and a screen with no plan card has nothing to show.
 */
sealed interface BillingUiState {

    data object Loading : BillingUiState

    /**
     * @param refreshing a reload is in flight over content already on screen. The figures stay
     *   visible; blanking a plan card to re-fetch the same plan would flash the screen for nothing.
     */
    data class Content(
        val plan: WorkspaceBilling,
        val stripe: StripeSectionState,
        val refreshing: Boolean = false,
    ) : BillingUiState

    /** ⛔ The workspace plan read failed. See the ⚠️ on the interface. */
    data class Failed(val failure: FailureText) : BillingUiState
}

/**
 * The Stripe half of [BillingUiState.Content].
 *
 * ⛔ **THREE** STATES, NOT TWO, AND THE THIRD IS WHY THIS TYPE EXISTS. `GET /api/billing` answers
 * 200 in all of these cases:
 *
 *   - [Ready] with rows — a real subscription and real invoices.
 *   - [Ready] with EMPTY rows and no flag — this account has no Stripe customer. Legitimately "no
 *     billing set up".
 *   - [Unavailable] — `billingUnavailable: true`, empty rows. **Stripe could not be reached.**
 *
 * The last two are the same body apart from one key. Collapsing them means either telling a
 * subscribed customer they have no plan during a Stripe outage, or telling an unstarted account
 * that billing is broken. The first is the expensive direction: the "we could not look" / "there
 * is nothing" conflation can route a paying customer to a checkout page.
 *
 * ⚠️ [Failed] is reserved for a genuine transport or contract failure — an unreachable origin, a
 * dead session, a shape this build cannot parse. It is NOT how a Stripe outage arrives.
 */
sealed interface StripeSectionState {

    // ⚠️ No `Loading` member: the ViewModel publishes `Content` only once both reads have landed, so
    // the Stripe half is never still loading beside a drawn plan card.

    /**
     * @param detail ⚠️ May legitimately carry empty lists. Empty here means "no subscription and no
     *   invoices on this account", which is a real answer — see [Unavailable] for the one that is
     *   not.
     */
    data class Ready(val detail: StripeBilling) : StripeSectionState

    /**
     * ⛔ STRIPE IS DOWN, THE ACCOUNT IS UNCHANGED, AND THE SCREEN MUST SAY EXACTLY THAT. Never
     * "free tier", never "no plan", never an empty invoice list presented as a complete one. The
     * plan card beside it is still correct, because it never went to Stripe.
     */
    data object Unavailable : StripeSectionState

    data class Failed(val failure: FailureText) : StripeSectionState
}
