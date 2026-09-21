package com.distronode.districtai.ui.billing

import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.BillingSubscription
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.UsageData
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The billing screen's arithmetic — the two unit mistakes that would put a wrong number in front of
 * a customer, and the three-way distinction the usage meter has to keep.
 *
 * ⚠️ NO ROBOLECTRIC. Every function under test is pure, which is the reason they were extracted
 * from the composables at all: the branches below are not reachable by rendering.
 */
class BillingFormatTest {

    // ── Money: minor units ───────────────────────────────────────────────────

    @Test
    fun `cents render as a two-decimal amount`() {
        // ⛔ 24900 IS $249.00. Rendering the integer verbatim overstates a price by a factor of a
        // hundred on the one screen where a wrong number becomes a support ticket.
        assertEquals("249.00", formatCents(24900))
        assertEquals("15.00", formatCents(1500))
    }

    @Test
    fun `a whole number of dollars keeps its trailing zeros`() {
        // ⚠️ ALWAYS TWO DECIMALS, unlike `formatUsageAmount` which drops them. "249" beside
        // "249.99" reads as a different KIND of number; money is padded and quantities are not.
        assertEquals("5.00", formatCents(500))
        assertEquals("0.00", formatCents(0))
    }

    @Test
    fun `a sub-dollar amount keeps its leading zero and both decimal places`() {
        assertEquals("0.99", formatCents(99))
        assertEquals("0.05", formatCents(5))
    }

    @Test
    fun `a credit renders as a negative amount rather than as a mangled one`() {
        // ⚠️ Stripe can report a negative total on a credited invoice. The naive
        // "$cents/100 . cents%100" produces "-2.-50" because the remainder carries the sign; the
        // absolute value on the fractional part is what stops that.
        assertEquals("-2.50", formatCents(-250))
    }

    // ── An invoice's amount ──────────────────────────────────────────────────

    @Test
    fun `a paid invoice shows what was taken`() {
        val invoice = BillingInvoice(id = "in_1", amountPaid = 24900, total = 24900, status = "paid")
        assertEquals(24900L, invoiceAmountCents(invoice))
    }

    @Test
    fun `an OPEN invoice shows what is owed, not the zero it has been paid`() {
        // ⛔ AN UNPAID INVOICE HAS `amount_paid: 0`, AND SHOWING THAT ZERO ANSWERS THE WRONG
        // QUESTION. An open invoice is money owed, so its total is the figure that matters — and a
        // row reading "$0.00" beside "open" tells a customer they owe nothing.
        val invoice = BillingInvoice(id = "in_2", amountPaid = 0, total = 1500, status = "open")
        assertEquals(1500L, invoiceAmountCents(invoice))
    }

    @Test
    fun `an invoice with no status falls back to the total rather than to zero`() {
        // ⚠️ `status` is nullable on the wire. Anything that is not literally "paid" is money that
        // has not been taken, so the total is the honest figure — the same direction the web
        // console's `isPaid ? amount_paid : total` takes.
        val invoice = BillingInvoice(id = "in_3", amountPaid = 0, total = 7900, status = null)
        assertEquals(7900L, invoiceAmountCents(invoice))
    }

    // ── Dates: unix SECONDS ──────────────────────────────────────────────────

    @Test
    fun `a unix seconds timestamp formats as the date it names`() {
        // ⛔ SECONDS, NOT MILLISECONDS. 1754231400 is 2025-08-03; read as millis it would be
        // 1970-01-21, and every invoice in the history would date to January 1970 — which looks
        // like a data problem rather than a unit mistake.
        //
        // ⚠️ THE ZONE AND LOCALE ARE PINNED. Without that this assertion passes on the machine
        // that wrote it and fails on a runner in another timezone.
        val formatted = formatUnixSeconds(
            seconds = 1_754_231_400,
            zone = ZoneId.of("UTC"),
            locale = Locale.US,
        )
        assertEquals("Aug 3, 2025", formatted)
    }

    @Test
    fun `a millisecond-scale value would land in a wildly different year`() {
        // ⚠️ THE GUARD AGAINST THE UNIT BEING "FIXED". If someone multiplies by 1000 upstream, this
        // is what changes — the same value read as millis is 1970, and pinning both readings makes
        // the mistake a failing assertion rather than a plausible-looking date.
        val asSeconds = formatUnixSeconds(1_754_231_400, ZoneId.of("UTC"), Locale.US)
        val asMillisWouldBe = formatUnixSeconds(1_754_231, ZoneId.of("UTC"), Locale.US)
        assertEquals("Aug 3, 2025", asSeconds)
        assertEquals("Jan 21, 1970", asMillisWouldBe)
    }

    // ── Billable minutes: null is not zero ───────────────────────────────────

    @Test
    fun `outbound and inbound minutes are SUMMED`() {
        // ⛔ THE OVERAGE CRON METERS BOTH DIRECTIONS against the included allowance, so showing one
        // would understate consumption against the exact number the customer is billed on.
        val usage = UsageData(callMinutesOutbound = 318.5, callMinutesInbound = 1204.25)
        assertEquals(1522.75, billableMinutes(usage)!!, 0.0)
    }

    @Test
    fun `a null usage yields null, never zero`() {
        // ⛔ A ZERO BESIDE AN ALLOWANCE ASSERTS, WITH THE AUTHORITY OF A BILL, THAT NO CALLS WERE
        // MADE. `usage: null` means the month has no metering rows at all, which is a different
        // claim and the only one that was measured.
        assertNull(billableMinutes(null))
    }

    @Test
    fun `both minute metrics absent yields null even when other metrics were metered`() {
        // ⚠️ A workspace that sent SMS and placed no calls has a usage object with no call keys at
        // all — an absent metric has NO KEY, which is not the same as a metric measured at zero.
        val usage = UsageData(month = "2026-08", smsOutbound = 412.0)
        assertNull(billableMinutes(usage))
    }

    @Test
    fun `one direction present and one absent sums the present one`() {
        // ⚠️ NOT THE SAME COMPROMISE AS THE CASE ABOVE. Absent genuinely means the metric
        // contributed nothing, and refusing to draw the meter because one direction was never
        // metered would discard a real number.
        val usage = UsageData(callMinutesOutbound = 60.0, callMinutesInbound = null)
        assertEquals(60.0, billableMinutes(usage)!!, 0.0)
    }

    @Test
    fun `a genuine zero is a zero, not an absence`() {
        val usage = UsageData(callMinutesOutbound = 0.0, callMinutesInbound = 0.0)
        assertEquals(0.0, billableMinutes(usage)!!, 0.0)
    }

    // ── The allowance, which lives on the OTHER half ─────────────────────────

    @Test
    fun `the allowance is the LARGEST across subscriptions, not the sum`() {
        // ⚠️ `includedMinutes` is derived per price from the tier catalogue, and a customer holding
        // a plan plus a metered add-on has ONE real voice allowance. Summing would inflate it;
        // taking the first would depend on Stripe's list order.
        val detail = StripeBilling(
            subscriptions = listOf(
                BillingSubscription(id = "add_on", includedMinutes = 250),
                BillingSubscription(id = "plan", includedMinutes = 1500),
            ),
        )
        assertEquals(1500, includedMinutes(detail))
    }

    @Test
    fun `an allowance absent on every row yields null, so no meter is drawn`() {
        // ⛔ A LEGACY OR CUSTOM PRICE MATCHES NO TIER, so the key is not on the wire at all. Null
        // means "no allowance is KNOWN" rather than "there is none", and a bar drawn against a zero
        // denominator would show every workspace at 100%.
        val detail = StripeBilling(subscriptions = listOf(BillingSubscription(id = "legacy")))
        assertNull(includedMinutes(detail))
    }

    @Test
    fun `a null Stripe half yields no allowance at all`() {
        // ⛔ THIS IS WHAT A STRIPE OUTAGE LOOKS LIKE TO THE METER. The allowance lives on the
        // vendor half and the usage on ours, so during an outage there is nothing to measure
        // against and the number is shown alone.
        assertNull(includedMinutes(null))
    }

    // ── The bar ──────────────────────────────────────────────────────────────

    @Test
    fun `the meter fills proportionally`() {
        assertEquals(0.5f, meterFraction(used = 750.0, included = 1500), 0.0001f)
        assertEquals(0f, meterFraction(used = 0.0, included = 1500), 0.0001f)
    }

    @Test
    fun `an over-allowance workspace clamps the BAR at full`() {
        // ⛔ CLAMPED FOR THE BAR ONLY. A bar cannot draw past its own width, but the LABEL beside it
        // states the real numbers — clamping the text would hide the overage that is about to be
        // billed, or that is already blocking calls under a hard cap.
        assertEquals(1f, meterFraction(used = 1812.0, included = 1500), 0.0001f)
    }

    @Test
    fun `a zero allowance yields an empty bar rather than a division`() {
        // ⚠️ Unreachable through `includedMinutes` (every catalogue value is positive), but a
        // division here would be an infinity that becomes a NaN-width rectangle rather than an
        // error anyone would see.
        assertEquals(0f, meterFraction(used = 100.0, included = 0), 0.0001f)
        assertEquals(0f, meterFraction(used = 100.0, included = -5), 0.0001f)
    }
}
