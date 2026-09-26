package com.distronode.districtai.ui.billing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.OVERAGE_POLICY_HARD_CAP
import com.distronode.districtai.core.model.SUBSCRIPTION_STATUS_ACTIVE
import com.distronode.districtai.core.model.SUBSCRIPTION_STATUS_CANCELED
import com.distronode.districtai.core.model.SUBSCRIPTION_STATUS_PAST_DUE
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.ui.analytics.formatUsageAmount

/**
 * The three cards built from OUR OWN data — plan, overage handling, and this month's minutes.
 *
 * ⛔ NOT ONE OF THESE TOUCHES STRIPE, WHICH IS WHY THEY ARE GROUPED. Every value here comes from
 * `GET /api/district/workspace/billing`, so this half of the screen renders correctly during a
 * Stripe outage — and it is the half a customer needs then: which plan, whether it is in good
 * standing, and whether an overage cap is currently blocking their calls. The Stripe-backed cards
 * live in `BillingStripeCards.kt`.
 *
 * ⛔ NOTHING HERE MUTATES ANYTHING, AND NOTHING MAY. Google Play's Payments policy, not a
 * preference — see `BillingScreen`.
 *
 * ⚠️ A THIRD FILE RATHER THAN A LONGER ONE, because detekt caps a file at 11 functions and the
 * combined card set landed at 13. The seam is the data source, which is also the failure boundary.
 */

/**
 * The headline: which plan, and whether it is in good standing.
 *
 * ⛔ A NULL TIER IS NOT "Free". The column is nullable and the route passes it through untouched, so
 * the honest rendering is "no plan on this workspace" rather than a plan name this client invented.
 * `GET /api/settings` substitutes a capitalised "Free" for its own callers and this route
 * deliberately does not; mirroring that substitution here would put a word on screen that no row
 * contains.
 */
@Composable
internal fun PlanCard(billing: WorkspaceBilling) {
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_PLAN_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.billing_plan_title))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = billing.subscriptionTier ?: stringResource(R.string.billing_plan_none),
                style = DistrictTheme.text.metric,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.weight(1f),
            )
            DistrictBadge(
                text = statusLabel(billing.subscriptionStatus),
                tone = statusTone(billing.subscriptionStatus),
                modifier = Modifier.semantics { contentDescription = BILLING_STATUS_DESCRIPTION },
            )
        }
        // ⚠️ Explains the badge rather than repeating it. "Past due" on its own is a Stripe word;
        // what a customer needs to know is what it means for their service.
        statusCaption(billing.subscriptionStatus)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
    }
}

/**
 * ⚠️ The wire values, mapped in ONE place. `subscriptionStatus` is free text server-side with no
 * enum behind it, so an unrecognised value falls through to the neutral label rather than to a
 * guess — the same "unknown means no claim" rule `WorkspaceRole.fromWire` applies to privileges.
 */
@Composable
private fun statusLabel(status: String): String = when (status) {
    SUBSCRIPTION_STATUS_ACTIVE -> stringResource(R.string.billing_status_active)
    SUBSCRIPTION_STATUS_PAST_DUE -> stringResource(R.string.billing_status_past_due)
    SUBSCRIPTION_STATUS_CANCELED -> stringResource(R.string.billing_status_canceled)
    else -> stringResource(R.string.billing_status_none)
}

/**
 * ⛔ `past_due` AND `canceled` ARE WARNINGS, NOT ERRORS. Neither is an app fault and neither is
 * something the customer can fix by tapping, so a destructive tone would read as a failure of the
 * app rather than as a state of the account. Warning is the tone that says "this needs attention
 * somewhere else", which is exactly what is true.
 */
private fun statusTone(status: String): Tone = when (status) {
    SUBSCRIPTION_STATUS_ACTIVE -> Tone.Success
    SUBSCRIPTION_STATUS_PAST_DUE, SUBSCRIPTION_STATUS_CANCELED -> Tone.Warning
    else -> Tone.Neutral
}

@Composable
private fun statusCaption(status: String): String? = when (status) {
    SUBSCRIPTION_STATUS_PAST_DUE -> stringResource(R.string.billing_status_past_due_caption)
    SUBSCRIPTION_STATUS_CANCELED -> stringResource(R.string.billing_status_canceled_caption)
    else -> null
}

/**
 * How the workspace handles going over its included minutes — and whether it already has.
 *
 * ⛔ THE POLICY AND THE FLAG MUST BE READ TOGETHER, AND THE COMBINATION IS THE WHOLE CARD. Under
 * `auto_bill` an exceeded cap means the overage is being charged: a billing note. Under
 * `hard_cap` it means **calls are being refused right now** — an outage the operator is living
 * through, with no other way to learn about it from this app. Rendering the flag without the policy
 * states neither fact; rendering the policy without the flag states a rule rather than a condition.
 */
@Composable
internal fun OverageCard(billing: WorkspaceBilling) {
    val hardCap = billing.overagePolicy == OVERAGE_POLICY_HARD_CAP
    val blocked = hardCap && billing.overageCapExceeded

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_OVERAGE_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.billing_overage_title))
        Text(
            text = if (hardCap) {
                stringResource(R.string.billing_overage_hard_cap)
            } else {
                stringResource(R.string.billing_overage_auto_bill)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        if (billing.overageCapExceeded) {
            // ⛔ TWO DIFFERENT SENTENCES FOR THE SAME FLAG. "Calls are being declined" and "you are
            // over your included minutes and the extra is being billed" are not the same news, and
            // the first one is the reason this screen is worth having on a phone at all.
            DistrictBadge(
                text = if (blocked) {
                    stringResource(R.string.billing_overage_blocked)
                } else {
                    stringResource(R.string.billing_overage_exceeded)
                },
                tone = if (blocked) Tone.Danger else Tone.Warning,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics {
                        contentDescription = if (blocked) {
                            BILLING_OVERAGE_BLOCKED_DESCRIPTION
                        } else {
                            BILLING_OVERAGE_EXCEEDED_DESCRIPTION
                        }
                    },
            )
        }
    }
}

/**
 * Call minutes used this month, against the plan's allowance when both are known.
 *
 * ⛔ THREE DISTINCT STATES, AND COLLAPSING ANY TWO OF THEM STATES SOMETHING FALSE:
 *
 *   - **Nothing metered** (`usage: null`, or both minute metrics absent) — a sentence, never a
 *     zero. A "0 minutes" beside an allowance asserts, with the authority of a bill, that the
 *     workspace made no calls; the honest claim is that nothing has been recorded yet.
 *   - **Minutes but no allowance** — the number alone, no bar. The allowance lives on the STRIPE
 *     half, so this is what an outage looks like, and a bar drawn against an unknown allowance
 *     would have to invent a denominator.
 *   - **Both** — the bar, and a label carrying the REAL numbers even when they exceed the
 *     allowance. See [meterFraction]: the bar clamps and the label does not.
 *
 * ⚠️ DRAWN WITH `Canvas` RATHER THAN A FRACTIONAL `fillMaxWidth`, for the reason `AnalyticsCards`
 * gives: a zero-width bar is the common case on a new workspace, and a rect of zero width is
 * simply nothing while a `fillMaxWidth(0f)` has opinions.
 */
@Composable
internal fun UsageMeterCard(usage: UsageData?, included: Int?) {
    val used = billableMinutes(usage)
    val track = DistrictTheme.colors.muted
    val accent = DistrictTheme.colors.district

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_METER_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.billing_meter_title))
        // ⚠️ `usage == null` also makes `used` null; naming it here smart-casts `usage` below, where
        // a safe call could never meet a null.
        if (usage == null || used == null) {
            Text(
                text = stringResource(R.string.billing_meter_unmetered),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = BILLING_METER_UNMETERED_DESCRIPTION },
            )
            return@DistrictCard
        }

        Text(
            text = if (included == null) {
                stringResource(R.string.billing_meter_used, formatUsageAmount(used))
            } else {
                stringResource(R.string.billing_meter_used_of, formatUsageAmount(used), included)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        if (included != null) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(METER_HEIGHT)
                    .padding(top = DistrictTheme.spacing.tight),
            ) {
                drawRect(color = track, size = size)
                drawRect(
                    color = accent,
                    size = Size(size.width * meterFraction(used, included), size.height),
                )
            }
        }
        usage.month.takeIf { it.isNotBlank() }?.let {
            Text(
                text = stringResource(R.string.billing_meter_month, it),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
            )
        }
    }
}

private val METER_HEIGHT: Dp = 10.dp
