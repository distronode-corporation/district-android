package com.distronode.districtai.ui.dialer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two display-only formatters.
 *
 * ⛔ WHAT IS BEING PINNED IS THAT `formatDialEntry` CANNOT CHANGE WHAT IS DIALLED. It groups
 * characters that are already there and removes nothing, so even a wrong grouping cannot produce a
 * different number — which matters because the server normalises its own copy and runs the DNC
 * check and the dial against THAT, and a client-side canonicaliser that disagreed would place a
 * call the compliance check never saw.
 */
class DialFormatTest {

    // ── The number ───────────────────────────────────────────────────────────

    @Test
    fun `a NANP number groups around its last ten digits, and the plus is kept`() {
        // ⛔ ANCHORED ON THE RIGHT, WHICH IS WHY THIS READS CORRECTLY. A left-to-right chunker
        // renders the same number "+141 655 50100" — legible, wrong-looking, and the first thing
        // an operator would report as a bug.
        assertEquals("+1 416 555 0100", formatDialEntry("+14165550100"))
        assertEquals("416 555 0100", formatDialEntry("4165550100"))
    }

    @Test
    fun `a non-NANP number degrades to something readable rather than something wrong`() {
        // ⚠️ NOT how the UK groups its numbers, and that is an accepted cost: this is display only,
        // so the worst outcome is legibility. See formatDialEntry.
        assertEquals("+44 207 946 0958", formatDialEntry("+442079460958"))
    }

    @Test
    fun `a partial number groups as far as it goes`() {
        assertEquals("+1", formatDialEntry("+1"))
        assertEquals("416 5", formatDialEntry("4165"))
    }

    @Test
    fun `the operator's own separators are ignored rather than fought with`() {
        // ⚠️ GROUPING RUNS ON THE DIGITS, not the raw string. Grouping around somebody's own
        // brackets and dashes would produce two competing layouts in one field.
        assertEquals("416 555 0100", formatDialEntry("(416) 555-0100"))
    }

    @Test
    fun `no digits at all comes back untouched`() {
        // ⚠️ There is nothing to group, and inventing an empty layout would blank a field the
        // operator is still typing into.
        assertEquals("", formatDialEntry(""))
        assertEquals("+", formatDialEntry("+"))
        assertEquals("abc", formatDialEntry("abc"))
    }

    @Test
    fun `formatting never removes a digit`() {
        // ⛔ THE PROPERTY THAT MAKES THIS SAFE. Whatever the grouping does, the digits are the same
        // digits — so a formatting bug is cosmetic and can never dial a different number.
        for (raw in listOf("+14165550100", "(416) 555-0100", "011 44 20 7946 0958", "5")) {
            assertEquals(
                raw.filter { it.isDigit() },
                formatDialEntry(raw).filter { it.isDigit() },
            )
        }
    }

    // ── The duration ─────────────────────────────────────────────────────────

    @Test
    fun `a call under an hour reads as minutes and seconds`() {
        assertEquals("00:00", formatCallDuration(0))
        assertEquals("00:09", formatCallDuration(9))
        assertEquals("01:15", formatCallDuration(75))
        assertEquals("59:59", formatCallDuration(3599))
    }

    @Test
    fun `past an hour the minutes do not run past sixty`() {
        // ⛔ A FORMATTER THAT SHOWED `65:03` WOULD BE WRONG-LOOKING ON EXACTLY THE CALLS THAT COST
        // THE MOST, and one that showed `05:03` would be wrong. The platform permits calls up to
        // an hour and the token is minted for seventy minutes, so this is reachable rather than
        // theoretical.
        assertEquals("1:00:00", formatCallDuration(3600))
        assertEquals("1:05:03", formatCallDuration(3903))
    }

    @Test
    fun `a negative duration is clamped rather than rendered as nonsense`() {
        // ⚠️ Nothing should produce one, which is exactly why it is not worth a crash on a live
        // call screen.
        assertEquals("00:00", formatCallDuration(-5))
    }
}
