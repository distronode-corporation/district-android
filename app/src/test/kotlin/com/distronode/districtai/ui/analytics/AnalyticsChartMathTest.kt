package com.distronode.districtai.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chart arithmetic, exercised on the inputs that actually arrive rather than on the happy path.
 *
 * ⛔ THE DEGENERATE CASES ARE THE POINT, AND THEY ARE NOT HYPOTHETICAL. The analytics route sends
 * one trend point per bucket regardless of data, so a workspace with no calls produces a series of
 * ZEROS — which is the input that divides by zero — and the sentiment breakdown always carries its
 * three bands even when every value is zero. Both are reached on the first analytics load of every
 * new account, so these are the ordinary case rather than the edge.
 *
 * ⚠️ NO ROBOLECTRIC. Nothing here touches Compose, a Context or a resource, which is exactly why
 * the maths was pulled out of the DrawScope in the first place.
 */
class AnalyticsChartMathTest {

    private val tolerance = 0.0001f

    // ── normalizedHeights ────────────────────────────────────────────────────

    @Test
    fun `a populated series scales to fractions of its own maximum`() {
        val heights = normalizedHeights(listOf(5, 10, 0, 2))

        assertEquals(0.5f, heights[0], tolerance)
        assertEquals(1f, heights[1], tolerance)
        assertEquals(0f, heights[2], tolerance)
        assertEquals(0.2f, heights[3], tolerance)
    }

    @Test
    fun `an all-zero series yields zeros rather than NaN`() {
        // ⛔ THE CLASSIC. `value / max` with max == 0 does not throw in floating point — it
        // produces NaN, which propagates silently into a rect offset and draws an empty chart that
        // looks like a rendering bug rather than a division by zero. This is the input a
        // brand-new workspace produces on its very first load.
        val heights = normalizedHeights(listOf(0, 0, 0, 0, 0, 0, 0))

        assertEquals(7, heights.size)
        assertTrue("no value may be NaN", heights.none { it.isNaN() })
        assertTrue(heights.all { it == 0f })
    }

    @Test
    fun `a single point is drawn at full height`() {
        assertEquals(listOf(1f), normalizedHeights(listOf(3)))
    }

    @Test
    fun `a single zero point does not divide by itself`() {
        assertEquals(listOf(0f), normalizedHeights(listOf(0)))
    }

    @Test
    fun `an empty series produces no bars`() {
        assertTrue(normalizedHeights(emptyList()).isEmpty())
    }

    @Test
    fun `a negative value is clamped to the floor rather than inverting the chart`() {
        // ⚠️ Nothing in the contract can produce a negative call count, so this is corrupt data —
        // and a chart that refuses to draw is a worse answer than one that draws the bad point at
        // zero. A negative fraction would place a rect ABOVE the plot area.
        val heights = normalizedHeights(listOf(-4, 8))

        assertEquals(0f, heights[0], tolerance)
        assertEquals(1f, heights[1], tolerance)
    }

    @Test
    fun `an all-negative series does not scale against a negative maximum`() {
        val heights = normalizedHeights(listOf(-4, -9))

        assertTrue(heights.all { it == 0f })
    }

    // ── barSlots ─────────────────────────────────────────────────────────────

    @Test
    fun `bars share the width left over after the gaps`() {
        // Three bars, two gaps of 10 => 100 - 20 = 80 across three bars.
        val slots = barSlots(count = 3, width = 100f, gap = 10f)

        assertEquals(3, slots.size)
        assertEquals(80f / 3f, slots[0].width, tolerance)
        assertEquals(0f, slots[0].left, tolerance)
        assertEquals(80f / 3f + 10f, slots[1].left, tolerance)
        // The last bar's right edge lands exactly on the available width.
        assertEquals(100f, slots[2].left + slots[2].width, tolerance)
    }

    @Test
    fun `a single bar takes the whole width and no gap is applied`() {
        val slots = barSlots(count = 1, width = 100f, gap = 10f)

        assertEquals(1, slots.size)
        assertEquals(0f, slots[0].left, tolerance)
        assertEquals(100f, slots[0].width, tolerance)
    }

    @Test
    fun `gaps wider than the available space give zero-width bars, never negative ones`() {
        // ⛔ 13 WEEKLY BUCKETS ON A NARROW PHONE IS THE REAL CASE — a 90d window buckets weekly, so
        // this is reachable with ordinary data. A negative width is not an error in DrawScope: it
        // simply draws nothing, so the failure would present as a blank chart with no clue why.
        val slots = barSlots(count = 13, width = 40f, gap = 10f)

        assertEquals(13, slots.size)
        assertTrue("no bar may be negative", slots.all { it.width >= 0f })
        assertTrue(slots.all { it.width == 0f })
    }

    @Test
    fun `a zero-width canvas yields zero-width slots at the origin`() {
        // ⚠️ Compose measures a Canvas before it has a size on the first frame, so this is normal
        // operation rather than corruption.
        val slots = barSlots(count = 4, width = 0f, gap = 4f)

        assertEquals(4, slots.size)
        assertTrue(slots.all { it.width == 0f && it.left == 0f })
    }

    @Test
    fun `a non-finite width does not produce NaN geometry`() {
        val slots = barSlots(count = 3, width = Float.NaN, gap = 4f)

        assertTrue(slots.none { it.width.isNaN() || it.left.isNaN() })
    }

    @Test
    fun `a negative gap is treated as no gap`() {
        val slots = barSlots(count = 2, width = 100f, gap = -8f)

        assertEquals(50f, slots[0].width, tolerance)
        assertEquals(50f, slots[1].left, tolerance)
    }

    @Test
    fun `no bars are laid out for an empty series`() {
        assertTrue(barSlots(count = 0, width = 100f, gap = 4f).isEmpty())
        assertTrue(barSlots(count = -1, width = 100f, gap = 4f).isEmpty())
    }

    @Test
    fun `a slot names its left edge and its width, rather than being two loose floats`() {
        // ⚠️ THE REASON BarSlot IS A TYPE. Two anonymous floats at a call site that also handles
        // heights, gaps and offsets is exactly where a left and a width get swapped — and the
        // result is a chart that draws, just wrongly.
        val slot = BarSlot(left = 12f, width = 30f)

        assertEquals(12f, slot.left, tolerance)
        assertEquals(30f, slot.width, tolerance)
        assertEquals(slot, slot.copy())
        assertEquals(slot.hashCode(), slot.copy().hashCode())
        assertTrue(slot != slot.copy(left = 0f))
        assertTrue(slot != slot.copy(width = 0f))
        assertTrue("a slot is not equal to an unrelated value", !slot.equals("12,30"))
        assertTrue(slot.toString().contains("12"))
    }

    // ── proportions ──────────────────────────────────────────────────────────

    @Test
    fun `shares of a populated total sum to one`() {
        val shares = proportions(listOf(21, 16, 11))

        assertEquals(1f, shares.sum(), tolerance)
        assertEquals(21f / 48f, shares[0], tolerance)
    }

    @Test
    fun `a total of zero yields zeros, NOT an even split`() {
        // ⛔ AN EVEN SPLIT IS THE TEMPTING FALLBACK AND IT IS A LIE — it draws a confident
        // three-way sentiment analysis of a workspace that has taken no calls. Zeros let the
        // screen render an empty track, which is what "nothing yet" actually looks like.
        val shares = proportions(listOf(0, 0, 0))

        assertTrue("no value may be NaN", shares.none { it.isNaN() })
        assertTrue(shares.all { it == 0f })
    }

    @Test
    fun `a single non-zero band takes the whole bar`() {
        assertEquals(listOf(0f, 1f, 0f), proportions(listOf(0, 7, 0)))
    }

    @Test
    fun `negatives are clamped rather than shrinking the total`() {
        val shares = proportions(listOf(-5, 10))

        assertEquals(0f, shares[0], tolerance)
        assertEquals(1f, shares[1], tolerance)
    }

    @Test
    fun `an empty breakdown produces no segments`() {
        assertTrue(proportions(emptyList()).isEmpty())
    }

    // ── parseHexArgb ─────────────────────────────────────────────────────────

    @Test
    fun `the colours the server actually sends parse to opaque ARGB`() {
        // The three values in district-analytics.json, verbatim.
        assertEquals(0xFF10B981L, parseHexArgb("#10b981"))
        assertEquals(0xFFF59E0BL, parseHexArgb("#f59e0b"))
        assertEquals(0xFFEF4444L, parseHexArgb("#ef4444"))
    }

    @Test
    fun `a leading hash is optional and case does not matter`() {
        assertEquals(parseHexArgb("#10B981"), parseHexArgb("10b981"))
    }

    @Test
    fun `shorthand and explicit alpha are both accepted`() {
        assertEquals(0xFFAABBCCL, parseHexArgb("#abc"))
        assertEquals(0x8010B981L, parseHexArgb("#8010b981"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(0xFF10B981L, parseHexArgb("  #10b981 "))
    }

    @Test
    fun `an unreadable colour is null so the caller can fall back`() {
        // ⛔ NULL RATHER THAN A THROW, AND NOT PARSED IN THE DTO AT ALL. Nothing server-side
        // constrains this string; failing the decode would lose every metric on the screen over a
        // shade. Each of these is a shape a careless server change could produce.
        assertNull(parseHexArgb(""))
        assertNull(parseHexArgb("#"))
        assertNull(parseHexArgb("rgb(16,185,129)"))
        assertNull(parseHexArgb("#12345"))
        assertNull(parseHexArgb("#zzzzzz"))
        assertNull(parseHexArgb("emerald"))
    }

    // ── formatDurationSeconds ────────────────────────────────────────────────

    @Test
    fun `durations use the TILE form, which omits a zero minutes component`() {
        // ⛔ TWO DURATION FORMATS SHIP IN THIS PRODUCT AND THEY DISAGREE. A tile says "45s"; a call
        // row says "0m 45s". This is the tile, because that is what AnalyticsMetrics feeds.
        assertEquals("45s", formatDurationSeconds(45))
        assertEquals("2m 0s", formatDurationSeconds(120))
        assertEquals("3m 12s", formatDurationSeconds(192))
        assertEquals("0s", formatDurationSeconds(0))
    }

    @Test
    fun `a negative duration reads as zero rather than showing a minus sign`() {
        assertEquals("0s", formatDurationSeconds(-30))
    }

    // ── formatUsageAmount ────────────────────────────────────────────────────

    @Test
    fun `whole amounts print whole and fractional ones keep their decimals`() {
        // ⛔ THESE ARE BILLING FIGURES. A bare toString renders "412.0" for a whole number of SMS,
        // and a toInt() truncates 1204.25 minutes of inbound calling into 1204.
        assertEquals("412", formatUsageAmount(412.0))
        assertEquals("1204.25", formatUsageAmount(1204.25))
        assertEquals("318.5", formatUsageAmount(318.5))
        assertEquals("0", formatUsageAmount(0.0))
    }

    @Test
    fun `a long fraction is rounded rather than printed in full`() {
        assertEquals("12.35", formatUsageAmount(12.3456))
        // Rounds up into a whole number, and then prints as one.
        assertEquals("13", formatUsageAmount(12.999))
    }

    @Test
    fun `a non-finite amount degrades to a placeholder instead of printing Infinity`() {
        assertEquals("—", formatUsageAmount(Double.NaN))
        assertEquals("—", formatUsageAmount(Double.POSITIVE_INFINITY))
    }

    // ── normalizedAmounts ────────────────────────────────────────────────────

    @Test
    fun `metered amounts scale to fractions of the largest month`() {
        val widths = normalizedAmounts(listOf(500.75, 250.0, 125.375))

        assertEquals(1f, widths[0], tolerance)
        assertEquals(0.4992511f, widths[1], tolerance)
        assertEquals(0.2503744f, widths[2], tolerance)
    }

    @Test
    fun `an absent month draws a zero-width bar without being counted as zero elsewhere`() {
        // ⛔ THE SPLIT THIS FUNCTION EXISTS FOR. There is no honest bar to draw for a metric that
        // was never metered, so the GEOMETRY is zero — but the null is still the caller's to
        // render as a dash. What must not happen is the absence turning into a number here.
        val widths = normalizedAmounts(listOf(100.0, null, 50.0))

        assertEquals(1f, widths[0], tolerance)
        assertEquals(0f, widths[1], tolerance)
        assertEquals(0.5f, widths[2], tolerance)
    }

    @Test
    fun `an all-absent series yields zeros rather than NaN`() {
        // ⛔ Reached on a workspace whose only metered rows are phone-number counts: every
        // call-minute key is absent, the maximum is zero, and `value / 0.0` poisons every offset
        // downstream without throwing.
        assertEquals(listOf(0f, 0f), normalizedAmounts(listOf<Double?>(null, null)))
        assertEquals(listOf(0f, 0f), normalizedAmounts(listOf(0.0, 0.0)))
        assertEquals(emptyList<Float>(), normalizedAmounts(emptyList<Double?>()))
    }

    @Test
    fun `a NaN is treated as absent in the maximum as well as in the output`() {
        // ⛔ A NaN ADMITTED INTO THE MAXIMUM MAKES EVERY COMPARISON AGAINST IT FALSE, so the whole
        // series would scale to zero and the card would draw flat bars over real usage — a silent
        // wrong answer rather than a visible fault.
        val widths = normalizedAmounts(listOf(Double.NaN, 200.0, 100.0))

        assertEquals(0f, widths[0], tolerance)
        assertEquals(1f, widths[1], tolerance)
        assertEquals(0.5f, widths[2], tolerance)
    }

    @Test
    fun `a negative amount is clamped to the floor rather than inverting the bar`() {
        val widths = normalizedAmounts(listOf(-5.0, 10.0))

        assertEquals(0f, widths[0], tolerance)
        assertEquals(1f, widths[1], tolerance)
    }

    // ── sumMetered ───────────────────────────────────────────────────────────

    @Test
    fun `all-absent sums to null, never to zero`() {
        // ⛔ THE WHOLE REASON THIS IS NOT `values.sumOf { it ?: 0.0 }`. A zero under a billing
        // label is a measurement somebody acts on; "we do not meter this for you" is not.
        assertNull(sumMetered(null, null, null))
        assertNull(sumMetered())
    }

    @Test
    fun `a partial sum counts only what was metered`() {
        // ⚠️ The absent side contributes nothing; it is NOT being asserted to have been zero,
        // which is why the caller's label says "metered".
        assertEquals(300.25, sumMetered(null, 300.25)!!, 0.0001)
        assertEquals(500.75, sumMetered(200.5, 300.25)!!, 0.0001)
    }

    @Test
    fun `a metered zero is a real answer and survives the sum`() {
        // ⚠️ The mirror of the case above: `0.0` is PRESENT on the wire, so it must produce a
        // zero rather than a null. Collapsing it back into "absent" would be the same error in
        // the other direction.
        assertEquals(0.0, sumMetered(0.0, null)!!, 0.0001)
        assertEquals(0.0, sumMetered(0.0, 0.0)!!, 0.0001)
    }

    @Test
    fun `a non-finite operand is skipped rather than poisoning the total`() {
        assertEquals(12.0, sumMetered(12.0, Double.NaN)!!, 0.0001)
        assertNull(sumMetered(Double.POSITIVE_INFINITY))
    }

    // ── monthLabel ───────────────────────────────────────────────────────────

    @Test
    fun `a YYYY-MM key becomes a short month and year`() {
        assertEquals("Aug 2026", monthLabel("2026-08"))
        assertEquals("Jan 2026", monthLabel("2026-01"))
        assertEquals("Dec 2025", monthLabel("2025-12"))
    }

    @Test
    fun `anything the lookup does not recognise is returned verbatim`() {
        // ⛔ NEVER BLANK AND NEVER A GUESS. A formatter would throw on these and take out the
        // whole card; the raw key is still a true statement about which month the row is.
        assertEquals("2026-13", monthLabel("2026-13"))
        assertEquals("2026-00", monthLabel("2026-00"))
        assertEquals("2026", monthLabel("2026"))
        assertEquals("26-08", monthLabel("26-08"))
        assertEquals("20x6-08", monthLabel("20x6-08"))
        assertEquals("2026-08-15", monthLabel("2026-08-15"))
        assertEquals("", monthLabel(""))
    }
}
