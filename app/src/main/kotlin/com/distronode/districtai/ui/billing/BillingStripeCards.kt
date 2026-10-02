package com.distronode.districtai.ui.billing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.BillingSubscription
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.ui.analytics.formatUsageAmount

/**
 * The cards built from STRIPE's answer, plus the two states that answer stands in for.
 *
 * ⛔ EVERY MONEY VALUE THAT REACHES THIS FILE IS IN **CENTS** and goes through [formatCents]. There
 * is no other conversion anywhere in the screen; adding a second one is how the two surfaces start
 * quoting different numbers for the same invoice.
 *
 * ⛔ AND NOTHING HERE MUTATES ANYTHING. The only tappable element is an invoice row, which hands a
 * Stripe-hosted URL to the system browser. No upgrade, no cancel, no card editor — Google Play's
 * Payments policy, not a preference. See `BillingScreen`.
 */

/**
 * Every active subscription, its price, and the date it turns over.
 *
 * ⛔ "renews" VERSUS "ends" IS DECIDED BY `cancelAtPeriodEnd`, AND GETTING IT BACKWARDS TELLS A
 * CUSTOMER WHO HAS ALREADY CANCELLED THAT THEY ARE ABOUT TO BE BILLED AGAIN. Same date, opposite
 * meaning.
 *
 * ⚠️ An empty list is a legitimate answer — this account has no Stripe subscription — and it is NOT
 * the same as [StripeUnavailableCard], which is a separate state entirely. The two shapes differ on
 * the wire by one key; see `StripeBilling`.
 */
@Composable
internal fun SubscriptionsCard(detail: StripeBilling) {
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_SUBSCRIPTIONS_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.billing_subscriptions_title))
        if (detail.subscriptions.isEmpty()) {
            Text(
                text = stringResource(R.string.billing_subscriptions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = BILLING_SUBSCRIPTIONS_EMPTY_DESCRIPTION },
            )
            return@DistrictCard
        }
        detail.subscriptions.forEach { SubscriptionRow(it) }
    }
}

@Composable
private fun SubscriptionRow(subscription: BillingSubscription) {
    Column(modifier = Modifier.padding(top = DistrictTheme.spacing.tight)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = subscription.tierName,
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.weight(1f),
            )
            // ⛔ CENTS. An absent amount renders NOTHING rather than "0.00", which would quote a
            // free plan to someone who is paying.
            subscription.amount?.let {
                Text(
                    text = stringResource(R.string.billing_amount_monthly, formatCents(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.foreground,
                )
            }
        }
        subscription.currentPeriodEnd?.let { end ->
            Text(
                // ⛔ See the ⛔ on the card. The flag chooses the sentence, not the date.
                text = if (subscription.cancelAtPeriodEnd) {
                    stringResource(R.string.billing_ends_on, formatUnixSeconds(end))
                } else {
                    stringResource(R.string.billing_renews_on, formatUnixSeconds(end))
                },
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.hairline)
                    .semantics { contentDescription = renewalDescription(subscription.id) },
            )
        }
        // ⚠️ EXACTLY ONE OF percentOff/amountOff arrives, and the server sends neither key when
        // Stripe's field was null — so a coupon with neither still renders, by name.
        subscription.discount?.let { discount ->
            Text(
                text = discount.percentOff?.let {
                    stringResource(
                        R.string.billing_discount_percent,
                        discount.couponName,
                        formatUsageAmount(it),
                    )
                } ?: discount.amountOff?.let {
                    stringResource(R.string.billing_discount_amount, discount.couponName, formatCents(it))
                } ?: discount.couponName,
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.success,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.hairline)
                    .semantics { contentDescription = BILLING_DISCOUNT_DESCRIPTION },
            )
        }
    }
}

/**
 * The invoice history.
 *
 * ⛔ A ROW OPENS STRIPE'S OWN HOSTED INVOICE IN THE BROWSER, AND ONLY WHEN THERE IS A URL TO OPEN.
 * `hosted_invoice_url` is null until an invoice is finalised, which is the most ordinary row there
 * is — this month's, before it is paid — so a row without one is rendered without an action rather
 * than with a dead one.
 *
 * ⚠️ THE SYSTEM BROWSER, NOT A WEBVIEW, AND THE SAME LAUNCHER SIGN-IN AND ACCOUNT DELETION USE. The
 * hosted invoice is a Stripe-authenticated page; a WebView could neither carry the user's session
 * nor be trusted to. Wired in `DistrictNavHost`.
 *
 * ⚠️ `invoicesHasMore` IS SURFACED. The server caps the list at 10, so a client that stayed silent
 * would present a truncated history as a complete one — the same truthfulness signal the Inbox's
 * `scanned`/`scanLimit` pair carries.
 */
@Composable
internal fun InvoicesCard(detail: StripeBilling, onOpenInvoice: (String) -> Unit) {
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_INVOICES_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.billing_invoices_title))
        if (detail.invoices.isEmpty()) {
            Text(
                text = stringResource(R.string.billing_invoices_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = BILLING_INVOICES_EMPTY_DESCRIPTION },
            )
            return@DistrictCard
        }
        detail.invoices.forEach { invoice ->
            InvoiceRow(invoice, onOpenInvoice)
            DistrictRowDivider()
        }
        if (detail.invoicesHasMore) {
            Text(
                text = stringResource(R.string.billing_invoices_truncated),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = BILLING_INVOICES_TRUNCATED_DESCRIPTION },
            )
        }
    }
}

@Composable
private fun InvoiceRow(invoice: BillingInvoice, onOpenInvoice: (String) -> Unit) {
    val url = invoice.hostedInvoiceUrl?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ Clickable ONLY when there is somewhere to go. A row that looks tappable and does
            // nothing is worse than a plain one, and an unfinalised invoice has no page yet.
            .then(if (url == null) Modifier else Modifier.clickable { onOpenInvoice(url) })
            .padding(vertical = DistrictTheme.spacing.tight)
            .semantics { contentDescription = invoiceDescription(invoice.id) },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                // ⚠️ UNIX SECONDS. The multiplication lives in `formatUnixSeconds` and nowhere else.
                text = formatUnixSeconds(invoice.created),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.weight(1f),
            )
            Text(
                // ⛔ CENTS, and an OPEN invoice shows what is OWED rather than the zero it has been
                // paid. See [invoiceAmountCents].
                text = stringResource(
                    R.string.billing_amount,
                    formatCents(invoiceAmountCents(invoice)),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = DistrictTheme.spacing.hairline),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            invoice.status?.takeIf { it.isNotBlank() }?.let {
                DistrictBadge(text = it, tone = invoiceTone(it))
            }
            // ⚠️ Tax is SUMMED server-side, so a zero here is measured rather than absent. Shown
            // only when non-zero: an "incl. $0.00 tax" line is noise on every untaxed invoice.
            if (invoice.tax > 0) {
                Text(
                    text = stringResource(R.string.billing_invoice_tax, formatCents(invoice.tax)),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier.padding(start = DistrictTheme.spacing.tight),
                )
            }
            Text(
                text = "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (url != null) {
                DistrictButton(
                    text = stringResource(R.string.billing_invoice_open),
                    onClick = { onOpenInvoice(url) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    modifier = Modifier.semantics {
                        contentDescription = invoiceOpenDescription(invoice.id)
                    },
                )
            }
        }
    }
}

/** ⚠️ Stripe's own words. Anything unrecognised stays neutral rather than being guessed at. */
private fun invoiceTone(status: String): Tone = when (status) {
    INVOICE_STATUS_PAID -> Tone.Success
    INVOICE_STATUS_OPEN, INVOICE_STATUS_UNCOLLECTIBLE -> Tone.Warning
    else -> Tone.Neutral
}

/**
 * ⛔ THE CARD THAT MUST NEVER BE AN EMPTY INVOICE LIST. `billingUnavailable: true` means we could
 * not reach Stripe — the subscription and the invoices still exist, we simply could not read them.
 * Drawing an empty list instead would tell a paying customer they have no plan: the "we could not
 * look" / "there is nothing" conflation, which on the web can route a paying customer to a checkout
 * page.
 *
 * ⚠️ NO RETRY BUTTON HERE SPECIFICALLY. The plan card beside this one is correct and current, and
 * the screen-level reload is still available; a retry attached to a vendor outage invites a
 * customer to tap repeatedly at something they cannot fix.
 */
@Composable
internal fun StripeUnavailableCard() {
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = BILLING_UNAVAILABLE_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.billing_unavailable_title))
        Text(
            text = stringResource(R.string.billing_unavailable_body),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}
