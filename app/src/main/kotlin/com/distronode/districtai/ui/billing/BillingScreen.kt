package com.distronode.districtai.ui.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.InlineFailure

/**
 * Billing: what this workspace is on, what it is using, and what has been invoiced.
 *
 * ⛔ READ ONLY, AND UNLIKE THE MARKETPLACE THAT IS NOT A DECISION THIS PROJECT MAY REVISIT. Google
 * Play's Payments policy is why there is no upgrade button, no cancel, no promo field and no card
 * editor anywhere in this file or its siblings. The caption is load-bearing for the same reason it
 * is on the marketplace: a plan card with no controls reads as a half-built screen unless it says
 * where the change happens.
 *
 * ⛔ THE PLAN CARD AND THE STRIPE SECTION HAVE SEPARATE FAILURE STATES, and the split is not
 * cosmetic. The plan comes from OUR database and the invoices come from Stripe, so the common
 * failure is one-sided — and when Stripe is the half that is down, the surviving half is exactly
 * what the customer needs: the tier, the status, and whether an overage cap is blocking calls right
 * now.
 *
 * ⛔ A STRIPE OUTAGE MUST RENDER AS "TEMPORARILY UNAVAILABLE", NEVER AS A FREE OR EMPTY ACCOUNT.
 * `GET /api/billing` answers 200 with empty arrays in BOTH cases and the only difference is the
 * `billingUnavailable` flag; see [StripeSectionState]. Getting it wrong tells a paying customer
 * they have no plan.
 *
 * ⚠️ THE CARDS LIVE IN TWO SIBLING FILES, SPLIT BY DATA SOURCE. `BillingPlanCards.kt` holds the
 * three built from our own database (plan, overage, usage meter) and `BillingStripeCards.kt` the
 * ones built from the vendor's answer. This file owns the shell — the scaffold, the three top-level
 * states and the read-only caption. Three files rather than two because detekt caps a file at 11
 * functions and the combined card set landed at 13; the seam chosen is the data source, which is
 * also the failure boundary.
 */
@Composable
fun BillingScreen(
    state: BillingUiState,
    role: WorkspaceRole?,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onOpenInvoice: (String) -> Unit,
    onBack: () -> Unit,
) {
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = BILLING_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.billing_title), onBack = onBack)
        },
    ) { inset ->
        when (state) {
            BillingUiState.Loading -> LoadingState(inset)
            // ⛔ Reached when the **workspace plan** read failed, not when both halves did. Without a
            // tier and a status there is no headline for this screen, and the Stripe half cannot be
            // relied on to report a failure at all: its outage arrives as a 200. See
            // `BillingViewModel.publish`.
            is BillingUiState.Failed -> FailureState(
                failure = state.failure,
                onRetry = onRetry,
                onSignIn = onSignIn,
                description = BILLING_FAILED_DESCRIPTION,
                modifier = inset,
                retryDescription = BILLING_RETRY_DESCRIPTION,
            )
            is BillingUiState.Content -> ContentState(state, role, inset, onRetry, onSignIn, onOpenInvoice)
        }
    }
}

/** ⚠️ Skeleton blocks in the shape of the content, matching every other screen in this app. */
@Composable
private fun LoadingState(inset: Modifier) {
    ContentContainer(modifier = inset.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = BILLING_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_CARD_ROWS) { SkeletonBlock(height = SKELETON_CARD_HEIGHT) }
        }
    }
}

@Composable
private fun ContentState(
    state: BillingUiState.Content,
    role: WorkspaceRole?,
    inset: Modifier,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onOpenInvoice: (String) -> Unit,
) {
    // ⚠️ Only [StripeSectionState.Ready] carries a payload. The allowance the usage meter is drawn
    // against lives on it, so during an outage there is nothing to measure against and the meter
    // is deliberately not drawn — see `includedMinutes`.
    val detail = (state.stripe as? StripeSectionState.Ready)?.detail

    Column(
        modifier = inset
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        if (state.refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = BILLING_REFRESHING_DESCRIPTION },
                color = DistrictTheme.colors.district,
                trackColor = DistrictTheme.colors.muted,
            )
        }

        ContentContainer { PlanCard(state.plan) }
        ContentContainer { OverageCard(state.plan) }
        ContentContainer { UsageMeterCard(usage = state.plan.usage, included = includedMinutes(detail)) }

        when (val stripe = state.stripe) {
            // ⛔ ITS OWN CARD, WITH ITS OWN COPY. Never an empty invoice list, which would read as
            // "you have never been invoiced".
            StripeSectionState.Unavailable -> ContentContainer { StripeUnavailableCard() }
            is StripeSectionState.Ready -> {
                ContentContainer { SubscriptionsCard(stripe.detail) }
                ContentContainer { InvoicesCard(stripe.detail, onOpenInvoice) }
            }
            is StripeSectionState.Failed -> ContentContainer {
                // ⚠️ A CARD, NOT A WHOLE-SCREEN STATE: the plan card above came from a different server
                // and is still correct. And this is NOT how a Stripe outage arrives: that is a 200
                // carrying `billingUnavailable`, drawn as StripeUnavailableCard. This one means the
                // request itself failed (an unreachable origin, a dead session, an unparseable shape).
                InlineFailure(
                    title = stringResource(R.string.billing_stripe_failed),
                    failure = stripe.failure,
                    onRetry = onRetry,
                    onSignIn = onSignIn,
                    description = BILLING_STRIPE_FAILURE_DESCRIPTION,
                    modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
                )
            }
        }

        ContentContainer { ReadOnlyCaption(role) }

        // Bottom breathing room; the scroll container clips a modifier padding.
        Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
    }
}

/**
 * ⛔ THE CAPTION THAT MAKES THE ABSENCE OF CONTROLS LEGIBLE, AND IT IS A STORE REQUIREMENT RATHER
 * THAN A COURTESY. A plan card with no upgrade button reads as broken; saying where a change
 * happens turns it into a deliberate boundary. Worded per role for the same reason the marketplace
 * does it: pointing a viewer at the web dashboard sends them somewhere that will also refuse them.
 *
 * ⚠️ IT NAMES NO PRICE AND LINKS NOWHERE. A tappable link to a purchase flow is exactly what Play's
 * Payments policy prohibits, so this is prose naming the site and nothing more — the same shape the
 * overview's billing-blocked state already uses.
 */
@Composable
private fun ReadOnlyCaption(role: WorkspaceRole?) {
    Text(
        text = if (role.allowsMutation()) {
            stringResource(R.string.billing_read_only)
        } else {
            stringResource(R.string.billing_read_only_viewer)
        },
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_READ_ONLY_DESCRIPTION },
    )
}

private val SKELETON_CARD_HEIGHT: Dp = 96.dp
private const val SKELETON_CARD_ROWS = 3

/**
 * Stable handles for tests.
 *
 * ⚠️ Constants rather than literals duplicated in the test: a renamed description in one place and
 * not the other produces a test that silently matches nothing.
 */
const val BILLING_ROOT_DESCRIPTION: String = "district-billing-root"
const val BILLING_LOADING_DESCRIPTION: String = "district-billing-loading"
const val BILLING_FAILED_DESCRIPTION: String = "district-billing-failed"
const val BILLING_RETRY_DESCRIPTION: String = "district-billing-retry"
const val BILLING_REFRESHING_DESCRIPTION: String = "district-billing-refreshing"
const val BILLING_PLAN_DESCRIPTION: String = "district-billing-plan"
const val BILLING_STATUS_DESCRIPTION: String = "district-billing-status"
const val BILLING_OVERAGE_DESCRIPTION: String = "district-billing-overage"

/**
 * ⛔ TWO HANDLES FOR ONE FLAG, DELIBERATELY. `overageCapExceeded` renders as "calls are being
 * declined" under `hard_cap` and as "the extra is being billed" under `auto_bill`, and a single
 * handle would let a test assert the badge exists while the wrong sentence was inside it.
 */
const val BILLING_OVERAGE_BLOCKED_DESCRIPTION: String = "district-billing-overage-blocked"
const val BILLING_OVERAGE_EXCEEDED_DESCRIPTION: String = "district-billing-overage-exceeded"
const val BILLING_METER_DESCRIPTION: String = "district-billing-meter"
const val BILLING_METER_UNMETERED_DESCRIPTION: String = "district-billing-meter-unmetered"
const val BILLING_SUBSCRIPTIONS_DESCRIPTION: String = "district-billing-subscriptions"
const val BILLING_SUBSCRIPTIONS_EMPTY_DESCRIPTION: String = "district-billing-subscriptions-empty"
const val BILLING_DISCOUNT_DESCRIPTION: String = "district-billing-discount"
const val BILLING_INVOICES_DESCRIPTION: String = "district-billing-invoices"
const val BILLING_INVOICES_EMPTY_DESCRIPTION: String = "district-billing-invoices-empty"
const val BILLING_INVOICES_TRUNCATED_DESCRIPTION: String = "district-billing-invoices-truncated"
const val BILLING_UNAVAILABLE_DESCRIPTION: String = "district-billing-unavailable"
const val BILLING_STRIPE_FAILURE_DESCRIPTION: String = "district-billing-stripe-failure"
const val BILLING_READ_ONLY_DESCRIPTION: String = "district-billing-read-only"

/** A stable per-invoice handle, derived once so a row and its test cannot drift apart. */
internal fun invoiceDescription(invoiceId: String): String = "district-billing-invoice-$invoiceId"

/** The row's open action, separate from the row itself: a row with no URL has no button. */
internal fun invoiceOpenDescription(invoiceId: String): String =
    "district-billing-invoice-open-$invoiceId"

/**
 * ⛔ PER SUBSCRIPTION, because "renews" and "ends" render into the SAME node for different
 * subscriptions on the same screen. A shared handle would match whichever came first and a test
 * asserting the cancelled row said "ends" could pass against the active row saying "renews".
 */
internal fun renewalDescription(subscriptionId: String): String =
    "district-billing-renewal-$subscriptionId"

// ⚠️ INTERNAL RATHER THAN PRIVATE so `BillingScreenTest` can render it: a preview that stopped
// composing would break Android Studio's renderer without failing anything else.
@Preview(showBackground = true)
@Composable
internal fun BillingScreenPreview() {
    DistrictTheme {
        BillingScreen(
            state = BillingUiState.Content(
                plan = WorkspaceBilling(
                    subscriptionTier = "VoicePro",
                    subscriptionStatus = "active",
                    plan = "voicepro",
                    overagePolicy = "auto_bill",
                    overageCapExceeded = false,
                    usage = UsageData(month = "2026-08", callMinutesOutbound = 318.5, callMinutesInbound = 1204.25),
                ),
                stripe = StripeSectionState.Ready(StripeBilling()),
            ),
            role = WorkspaceRole.CLIENT,
            onRetry = {},
            onSignIn = {},
            onOpenInvoice = {},
            onBack = {},
        )
    }
}
