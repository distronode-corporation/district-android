package com.distronode.districtai.l10n

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.l10n.DistrictFormat.NBSP
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.analytics.formatDurationSeconds
import com.distronode.districtai.ui.analytics.formatUsageAmount
import com.distronode.districtai.ui.analytics.monthLabel
import com.distronode.districtai.ui.billing.formatCents
import com.distronode.districtai.ui.billing.formatUnixSeconds
import com.distronode.districtai.ui.workflows.formatRunTimestamp
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Server-built and app-built readings: unchanged in English, rebuilt in French.
 *
 * ⛔ EVERY ENGLISH CASE HERE IS TODAY'S ON-SCREEN STRING, not the formatter's English. Where the two
 * differ (an ungrouped price, a tile's "2m 0s", the server's 24-hour "Aug 15, 14:30") the English
 * assertion is the proof that W1 left it alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ReadingsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val en = AppLanguage.EN
    private val fr = AppLanguage.FR
    private val utc: ZoneId = ZoneOffset.UTC

    private fun assertRendered(vararg cases: Pair<String, @Composable () -> String>) {
        val actual = arrayOfNulls<String>(cases.size)
        composeRule.setContent { cases.forEachIndexed { i, (_, block) -> actual[i] = block() } }
        composeRule.waitForIdle()
        cases.forEachIndexed { i, (expected, _) -> assertEquals("case $i", expected, actual[i]) }
    }

    @Test
    fun `a call row's time and duration are the server's in English and rebuilt in French`() {
        val created = "2026-08-15T14:30:00.000Z"
        assertRendered(
            "Aug 15, 14:30" to { callTimeText("Aug 15, 14:30", created, en, utc) },
            "15 août, 14 h 30" to { callTimeText("Aug 15, 14:30", created, fr, utc) },
            "Aug 15, 14:30" to { callTimeText("Aug 15, 14:30", "not an instant", fr, utc) },
            "Aug 15, 14:30" to { callTimeText("Aug 15, 14:30", null, fr, utc) },
            "Aug 15, 14:30" to { callTimeText("Aug 15, 14:30", created) },
            "0m 5s" to { callDurationText("0m 5s", 5, en) },
            "5${NBSP}s" to { callDurationText("0m 5s", 5, fr) },
            "0m 5s" to { callDurationText("0m 5s", null, fr) },
            "0m 5s" to { callDurationText("0m 5s", 5) },
        )
    }

    @Test
    fun `a chart day and a count are the app's English and the French reading`() {
        assertRendered(
            "Aug 9" to { chartDateText("Aug 9", "2026-08-09", en) },
            "9 août" to { chartDateText("Aug 9", "2026-08-09", fr) },
            "Aug 9" to { chartDateText("Aug 9", "", fr) },
            "Aug 9" to { chartDateText("Aug 9", "2026-08-09") },
            "1204" to { countText(1204, en) },
            "1${NBSP}204" to { countText(1204, fr) },
            "1204" to { countText(1204) },
        )
    }

    @Test
    fun `money, amounts, durations and months keep today's English and read French in French`() {
        assertEquals("1204.25", formatCents(120_425))
        assertEquals("-0.50", formatCents(-50))
        assertEquals("1${NBSP}204,25", formatCents(120_425, fr))
        assertEquals("1522.75", formatUsageAmount(1522.75))
        assertEquals("1${NBSP}522,75", formatUsageAmount(1522.75, fr))
        assertEquals("2m 0s", formatDurationSeconds(120))
        assertEquals("2${NBSP}min", formatDurationSeconds(120, fr))
        assertEquals("Aug 2026", monthLabel("2026-08"))
        assertEquals("août 2026", monthLabel("2026-08", fr))
        assertEquals("2026-13", monthLabel("2026-13", fr))
    }

    @Test
    fun `billing dates and workflow run times read French in French`() {
        val seconds = Instant.parse("2026-08-01T12:00:00Z").epochSecond
        assertEquals("Aug 1, 2026", formatUnixSeconds(seconds, utc, Locale.US))
        assertEquals("1er août 2026", formatUnixSeconds(seconds, utc, Locale.US, fr))
        assertEquals(
            "21 sept. 2026, 15 h 04",
            formatRunTimestamp("2026-09-21T15:04:00Z", utc, Locale.US, fr),
        )
        assertEquals("not a time", formatRunTimestamp("not a time", utc, Locale.US, fr))
    }

    @Test
    fun `the French readings that are not in the format vectors`() {
        assertEquals("Aug 2026", DistrictFormat.monthYear(8, 2026, en))
        assertEquals("déc. 2026", DistrictFormat.monthYear(12, 2026, fr))
        val at = Instant.parse("2026-08-15T14:30:00Z")
        assertEquals("15 août, 14 h 30", DistrictFormat.monthDayTime(at, utc, fr))
        assertEquals("Aug 15, 10:30 a.m.", DistrictFormat.monthDayTime(at, ZoneId.of("America/New_York"), en))
        assertEquals("Aug 15, 14:30", DistrictFormat.monthDayTime(at, utc, en))
    }

    @Test
    fun `stamps read in both languages, and months and clock times keep today's English`() {
        val iso = "2026-08-15T14:30:00.000Z"
        val toronto = ZoneId.of("America/Toronto")
        assertRendered(
            // ⛔ English is formatted too (visual QA AA3): never the raw "2026-08-15T14:30:00.000Z".
            "Aug 15, 14:30" to { stampText(iso, en, utc) },
            "Aug 15, 10:30 a.m." to { stampText(iso, en, toronto) },
            "15 août, 14 h 30" to { stampText(iso, fr, utc) },
            "yesterday" to { stampText("yesterday", fr, utc) },
            "yesterday" to { stampText("yesterday") },
            "Aug 15, 2026, 14:30" to { stampDateTimeText(iso, en, utc) },
            "15 août 2026, 14 h 30" to { stampDateTimeText(iso, fr, utc) },
            "soon" to { stampDateTimeText("soon", fr, utc) },
            "soon" to { stampDateTimeText("soon") },
            "Aug 2026" to { yearMonthText("2026-08", en) },
            "2026-13" to { yearMonthText("2026-13", en) },
            "août 2026" to { yearMonthText("2026-08", fr) },
            "2026-13" to { yearMonthText("2026-13", fr) },
            "2026-00" to { yearMonthText("2026-00", fr) },
            "August" to { yearMonthText("August", fr) },
            "Aug 2026" to { yearMonthText("2026-08") },
            "10:30" to { clockText("10:30", en) },
            "10 h 30" to { clockText("10:30", fr) },
            "15 h" to { clockText("15:00", fr) },
            "9 h" to { clockText("9:00", fr) },
            "24:00" to { clockText("24:00", fr) },
            "9:75" to { clockText("9:75", fr) },
            "noon" to { clockText("noon", fr) },
            "10:30" to { clockText("10:30") },
        )
        assertEquals("15 h 04", DistrictFormat.frenchClock(15, 4))
        assertEquals("9 h", DistrictFormat.frenchClock(9, 0))
    }
}
