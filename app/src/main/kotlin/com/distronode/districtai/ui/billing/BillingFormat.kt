package com.distronode.districtai.ui.billing

import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.UsageData
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The billing screen's arithmetic, kept out of the composables so it can be tested without
 * rendering anything.
 *
 * ⛔ EVERY MONEY VALUE ON `/api/billing` IS IN **CENTS**, AND THAT IS THE FACT THIS FILE EXISTS TO
 * CONTAIN. `amount`, `amount_paid`, `total`, `tax` and a coupon's `amountOff` are all Stripe minor
 * units — 24900 is $249.00. There is exactly one conversion, here, so a screen cannot render a raw
 * integer and overstate a bill by a factor of a hundred.
 *
 * ⛔ AND EVERY TIMESTAMP IS UNIX **SECONDS**, not milliseconds. `current_period_end` and an
 * invoice's `created` both are. Treating either as milliseconds dates the whole billing history to
 * January 1970, which is wrong in a way that looks like a rendering bug rather than a unit mistake.
 */

/**
 * Cents as a plain decimal amount — "24900" becomes "249.00".
 *
 * ⛔ NO CURRENCY SYMBOL AND NO CURRENCY CODE, BECAUSE THE ROUTE PUBLISHES NEITHER. Nothing on
 * `/api/billing` carries a currency: not the subscription amount, not the invoice total, not the
 * tax. The web console prefixes a bare "$" and this client mirrors that from a STRING RESOURCE
 * (`billing_amount`) rather than concatenating a symbol here — so the assumption lives in one
 * translatable place and shows up in a diff if the catalogue ever stops being USD.
 *
 * ⚠️ ALWAYS TWO DECIMALS, unlike `formatUsageAmount` which drops a trailing ".00". A price of
 * "249" beside a price of "249.99" reads as a different KIND of number; money is padded and
 * quantities are not.
 *
 * ⚠️ `Locale.US` PINS THE SEPARATOR, deliberately, and matches what the web console renders. A
 * locale-formatted "249,00" beside an unlocalised "$" would be a half-localised amount, which is
 * worse than a consistent one.
 */
internal fun formatCents(cents: Long): String =
    String.format(Locale.US, "%d.%02d", cents / CENTS_PER_UNIT, kotlin.math.abs(cents % CENTS_PER_UNIT))

/**
 * What an invoice row should show as its amount.
 *
 * ⛔ AN UNPAID INVOICE HAS `amount_paid: 0`, AND SHOWING THAT ZERO WOULD BE THE WRONG ANSWER TO THE
 * QUESTION THE ROW IS ASKING. An open invoice is money OWED, so its `total` is the figure that
 * matters; a paid one shows what was actually taken, which can differ from the total when a credit
 * or a proration applied. Mirrors the web dashboard's billing page exactly, so the two surfaces
 * cannot quote different numbers for the same invoice.
 */
internal fun invoiceAmountCents(invoice: BillingInvoice): Long =
    if (invoice.status == INVOICE_STATUS_PAID) invoice.amountPaid else invoice.total

/** ⚠️ The one status that means "this money has been taken". Everything else is owed or void. */
internal const val INVOICE_STATUS_PAID: String = "paid"

/** ⚠️ Owed, and therefore worth a warning tone and an "open" action if a link exists. */
internal const val INVOICE_STATUS_OPEN: String = "open"
internal const val INVOICE_STATUS_UNCOLLECTIBLE: String = "uncollectible"

/**
 * A unix-SECONDS timestamp as a localised date.
 *
 * ⚠️ SECONDS. The multiplication is here and nowhere else.
 *
 * ⚠️ THE ZONE AND LOCALE ARE PARAMETERS WITH DEFAULTS so a test can pin them. Without that, an
 * assertion on a rendered date passes on the machine that wrote it and fails on a runner in
 * another timezone — and the failure reads as a data problem rather than as an environment one.
 *
 * ⚠️ `java.time` on minSdk 26 rides core-library desugaring, which the base convention plugin
 * already enables for exactly this reason.
 */
internal fun formatUnixSeconds(
    seconds: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String = DateTimeFormatter
    .ofLocalizedDate(FormatStyle.MEDIUM)
    .withLocale(locale)
    .format(Instant.ofEpochSecond(seconds).atZone(zone))

/**
 * The billable call minutes this month, or null when the month has none.
 *
 * ⛔ NULL IS NOT ZERO, AND THE DISTINCTION IS THE WHOLE REASON THIS RETURNS A NULLABLE. An absent
 * metric has NO KEY in the usage response — see `UsageData` — so "we have not metered inbound
 * minutes for this workspace" and "inbound minutes were measured at zero" are different facts.
 * Summing absent-as-zero would draw a usage meter at 0% for a workspace whose minutes were never
 * measured, next to an allowance, which reads as "you have used none of your plan".
 *
 * ⚠️ ONE PRESENT AND ONE ABSENT SUMS THE PRESENT ONE. That is not the same compromise: absent
 * genuinely means the metric contributed nothing to the meter, and refusing to draw the meter
 * because one direction was never metered would discard a real number.
 *
 * ⛔ OUTBOUND **PLUS** INBOUND. The overage cron meters both against the plan's included minutes;
 * showing only one direction would understate consumption against the exact allowance the customer
 * is billed on.
 */
internal fun billableMinutes(usage: UsageData?): Double? {
    if (usage == null) return null
    val outbound = usage.callMinutesOutbound
    val inbound = usage.callMinutesInbound
    if (outbound == null && inbound == null) return null
    return (outbound ?: 0.0) + (inbound ?: 0.0)
}

/**
 * The plan's included call minutes, if any subscription publishes one.
 *
 * ⛔ THE ALLOWANCE LIVES ON THE **STRIPE** HALF AND THE USAGE ON THE **WORKSPACE** HALF, so a meter
 * can only be drawn when BOTH reads landed. That is why this takes the whole [StripeBilling] and
 * returns null rather than a zero: during a Stripe outage there is no allowance to measure
 * against, and a meter drawn against a zero allowance would show every workspace at 100%.
 *
 * ⚠️ THE **LARGEST** ACROSS SUBSCRIPTIONS, not the sum and not the first. `includedMinutes` is
 * derived per price from the tier catalogue, and a customer holding a plan plus a metered add-on
 * has one real voice allowance; summing would inflate it and taking the first would depend on
 * Stripe's list order. Absent on every row (a legacy or custom price) means no allowance is
 * KNOWN — which is not the same as none existing, so the meter is simply not drawn.
 */
internal fun includedMinutes(stripe: StripeBilling?): Int? =
    stripe?.subscriptions?.mapNotNull { it.includedMinutes }?.maxOrNull()

/**
 * How full the meter bar is drawn, in 0f..1f.
 *
 * ⛔ CLAMPED FOR THE **BAR ONLY**, NEVER FOR THE LABEL. A workspace over its allowance is the case
 * this screen matters most for, and a bar cannot draw past its own width — but the label beside it
 * must state the real numbers ("1,812 of 1,500 minutes"), because clamping the text would hide the
 * overage that is about to be billed or that is already blocking calls.
 *
 * ⚠️ A zero or negative allowance yields 0f rather than dividing. It cannot occur through
 * [includedMinutes] (the catalogue's values are all positive) but a division here would be an
 * infinity that silently becomes a NaN-width rectangle.
 */
internal fun meterFraction(used: Double, included: Int): Float {
    if (included <= 0) return 0f
    return (used / included).toFloat().coerceIn(0f, 1f)
}

private const val CENTS_PER_UNIT = 100L
