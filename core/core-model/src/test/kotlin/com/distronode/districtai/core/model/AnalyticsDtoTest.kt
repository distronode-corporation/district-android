package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The analytics and usage DTOs as VALUES: their defaults, their equality, and the two decisions
 * encoded in their nullability.
 *
 * ⛔ EQUALITY IS NOT INCIDENTAL HERE. `AnalyticsViewModel` publishes its state through a
 * `MutableStateFlow`, which DE-DUPLICATES by `equals` — so a report that compared equal when it
 * should not would silently drop a state emission and leave the previous window's figures on
 * screen. Every DTO on that path is a data class for exactly that reason, and this is what pins
 * the field-by-field comparison generated for it.
 *
 * ⚠️ SEPARATE FROM `ContractFixtureTest`, which asks a different question. That file asks whether
 * the SERVER'S output decodes; this one asks whether these types behave sanely as Kotlin values,
 * including for shapes the current server never emits.
 */
class AnalyticsDtoTest {

    /** Matches the production parser: lenient about extra keys, so a new server field degrades. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ── Defaults ─────────────────────────────────────────────────────────────

    @Test
    fun `an empty body decodes into a complete report, which is why the envelope is checked`() {
        // ⛔ THE REASON `rejectedEnvelope` EXISTS ON THIS PATH. Every field has a default, so `{}`
        // parses into a well-formed report reading zero calls, zero conversions and a flat trend —
        // indistinguishable, on screen, from a genuinely quiet week. The defaults are correct
        // individually (the API omits keys rather than sending nulls) and dangerous together, and
        // `success` defaulting to FALSE is what makes the combination detectable.
        val report = json.decodeFromString<AnalyticsResponse>("{}")

        assertFalse("the envelope must not affirm success by default", report.success)
        assertEquals(0, report.metrics.totalCalls)
        assertEquals(0, report.metrics.activeAgents)
        assertTrue(report.engagementTrends.isEmpty())
        assertTrue(report.funnelData.isEmpty())
        assertTrue(report.sentimentDistribution.isEmpty())
        assertEquals(AnalyticsMetrics(), report.metrics)
        assertEquals(CallVolumeDelta(), report.callVolumeDelta)
    }

    @Test
    fun `an empty usage body defaults to a NULL month rather than a zeroed one`() {
        // ⛔ Deliberately unlike every other DTO in this module, which defaults its payload to a
        // constructed empty value. Here that would erase the distinction the type exists to carry:
        // "nothing metered" and "no response" must not both become a row of zeros.
        val single = json.decodeFromString<UsageResponse>("{}")
        assertFalse(single.success)
        assertNull(single.usage)

        val history = json.decodeFromString<UsageHistoryResponse>("{}")
        assertFalse(history.success)
        assertTrue(history.usage.isEmpty())
    }

    @Test
    fun `a usage row defaults every metric to null, never to zero`() {
        val usage = UsageData()

        assertEquals("", usage.month)
        assertNull(usage.provider)
        assertNull(usage.smsOutbound)
        assertNull(usage.smsInbound)
        assertNull(usage.mmsOutbound)
        assertNull(usage.whatsappOutbound)
        assertNull(usage.whatsappInbound)
        assertNull(usage.callMinutesOutbound)
        assertNull(usage.callMinutesInbound)
        assertNull(usage.numberCount)
        assertNull(usage.videoMinutes)
        assertNull(usage.lastUpdated)
    }

    // ── The delta's two independent fields ───────────────────────────────────

    @Test
    fun `isNew is decided by the percentage, not by the direction or the counts`() {
        // ⛔ THE FOUR COMBINATIONS THAT ACTUALLY OCCUR, and they show why neither field may be
        // inferred from the other. A first-ever call is `up` with NO percentage; a dead-quiet new
        // workspace is `flat` with no percentage; an unchanged established workspace is `flat`
        // WITH a percentage of zero — and rendering that last one as "New" would be wrong.
        assertTrue(CallVolumeDelta(current = 0, prior = 0, pct = null, direction = DIRECTION_FLAT).isNew)
        assertTrue(CallVolumeDelta(current = 7, prior = 0, pct = null, direction = DIRECTION_UP).isNew)
        assertFalse(CallVolumeDelta(current = 40, prior = 40, pct = 0, direction = DIRECTION_FLAT).isNew)
        assertFalse(CallVolumeDelta(current = 32, prior = 40, pct = -20, direction = DIRECTION_DOWN).isNew)
    }

    @Test
    fun `the direction constants are the server's exact wire words`() {
        // ⚠️ Plain Strings rather than an enum because the server has none, and these are compared
        // verbatim on the screen. A typo would silently take the `else` arm and render "no change"
        // for a real rise.
        assertEquals("up", DIRECTION_UP)
        assertEquals("down", DIRECTION_DOWN)
        assertEquals("flat", DIRECTION_FLAT)
        assertEquals(DIRECTION_FLAT, CallVolumeDelta().direction)
    }

    // ── The range enum ───────────────────────────────────────────────────────

    @Test
    fun `every range carries the wire value the server matches, and no other`() {
        // ⛔ AN UNRECOGNISED timeRange IS NOT AN ERROR SERVER-SIDE — it falls back to 7d and
        // answers 200. So a wrong value here serves a week of data under a "90 days" heading with
        // nothing reporting a problem, which no response-shape test could ever catch.
        assertEquals(
            listOf("7d", "30d", "90d"),
            AnalyticsRange.entries.map { it.wire },
        )
        assertEquals(3, AnalyticsRange.entries.size)
    }

    // ── Equality, which the state flow de-duplicates on ──────────────────────

    @Test
    fun `two reports differing in ANY field compare unequal`() {
        // ⛔ A FALSE EQUALITY DROPS A STATE EMISSION. MutableStateFlow does not re-emit a value
        // equal to the one it holds, so a report that compared equal to the previous window's
        // would leave the OLD figures on screen under the NEW heading.
        val base = AnalyticsResponse(
            success = true,
            metrics = AnalyticsMetrics(totalCalls = 48, avgDuration = 120, conversionRate = 38),
            callVolumeDelta = CallVolumeDelta(48, 40, 20, DIRECTION_UP),
            engagementTrends = listOf(EngagementPoint("Aug 15", "2026-08-15", 11, 120)),
            funnelData = listOf(FunnelStage("Total Dials", 48)),
            sentimentDistribution = listOf(SentimentSlice("Positive Sentiment", 21, "#10b981")),
        )

        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())

        assertNotEquals(base, base.copy(success = false))
        assertNotEquals(base, base.copy(metrics = base.metrics.copy(totalCalls = 49)))
        assertNotEquals(base, base.copy(callVolumeDelta = base.callVolumeDelta.copy(pct = null)))
        assertNotEquals(base, base.copy(engagementTrends = emptyList()))
        assertNotEquals(base, base.copy(funnelData = emptyList()))
        assertNotEquals(base, base.copy(sentimentDistribution = emptyList()))
        // The report names its own contents, which is what makes a failed assertion readable.
        assertTrue(base.toString().contains("48"))
    }

    @Test
    fun `metrics compare on every field, so a changed figure is never de-duplicated`() {
        val base = AnalyticsMetrics(
            totalCalls = 48,
            avgDuration = 120,
            conversionRate = 38,
            abandonedCalls = 4,
            missedCalls = 9,
            activeAgents = 0,
        )

        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(totalCalls = 0))
        assertNotEquals(base, base.copy(avgDuration = 0))
        assertNotEquals(base, base.copy(conversionRate = 0))
        assertNotEquals(base, base.copy(abandonedCalls = 0))
        assertNotEquals(base, base.copy(missedCalls = 0))
        assertNotEquals(base, base.copy(activeAgents = 1))
        assertEquals(base.hashCode(), base.copy().hashCode())
    }

    @Test
    fun `a delta compares on all four fields`() {
        val base = CallVolumeDelta(current = 48, prior = 40, pct = 20, direction = DIRECTION_UP)

        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(current = 0))
        assertNotEquals(base, base.copy(prior = 0))
        // ⛔ The one that matters most: null and zero must NOT compare equal. "New" and "no change"
        // are different sentences.
        assertNotEquals(base, base.copy(pct = null))
        assertNotEquals(base.copy(pct = 0), base.copy(pct = null))
        assertNotEquals(base, base.copy(direction = DIRECTION_FLAT))
        assertEquals(base.hashCode(), base.copy().hashCode())
    }

    @Test
    fun `a trend point compares its display label and its ISO date separately`() {
        // ⚠️ TWO DATE FIELDS THAT ARE NOT DERIVABLE FROM EACH OTHER — one is a localized label with
        // no year, the other a UTC calendar date. Comparing on only one would let a timezone
        // change in the operator's profile go unnoticed by the state flow.
        val base = EngagementPoint(date = "Aug 15", isoDate = "2026-08-15", calls = 11, avgDuration = 120)

        assertEquals(base, base.copy())
        assertNotEquals(base, base.copy(date = "15 Aug"))
        assertNotEquals(base, base.copy(isoDate = "2026-08-16"))
        assertNotEquals(base, base.copy(calls = 0))
        assertNotEquals(base, base.copy(avgDuration = 0))
        assertEquals(base.hashCode(), base.copy().hashCode())
    }

    @Test
    fun `a funnel stage and a sentiment slice compare on every field`() {
        val stage = FunnelStage(name = "Total Dials", count = 48)
        assertEquals(stage, stage.copy())
        assertNotEquals(stage, stage.copy(name = "Connected Calls"))
        assertNotEquals(stage, stage.copy(count = 35))
        assertEquals(stage.hashCode(), stage.copy().hashCode())

        val slice = SentimentSlice(name = "Positive Sentiment", value = 21, color = "#10b981")
        assertEquals(slice, slice.copy())
        assertNotEquals(slice, slice.copy(name = "Neutral Sentiment"))
        assertNotEquals(slice, slice.copy(value = 16))
        // ⚠️ Colour is part of the identity even though it is presentational: a server that
        // re-themed the bands must not be de-duplicated away by the state flow.
        assertNotEquals(slice, slice.copy(color = "#f59e0b"))
        assertEquals(slice.hashCode(), slice.copy().hashCode())
    }

    @Test
    fun `a usage row compares on every metric, so a corrected figure always redraws`() {
        // ⛔ THESE ARE BILLING FIGURES. A metering correction that arrived and compared equal would
        // leave the previous number on screen with no indication anything had changed.
        val base = UsageData(
            month = "2026-08",
            provider = "twilio",
            smsOutbound = 412.0,
            smsInbound = 87.0,
            mmsOutbound = 6.0,
            whatsappOutbound = 0.0,
            whatsappInbound = 1.0,
            callMinutesOutbound = 318.5,
            callMinutesInbound = 1204.25,
            numberCount = 3.0,
            videoMinutes = 42.0,
            lastUpdated = "2026-08-15T14:30:00.000Z",
        )

        assertEquals(base, base.copy())
        assertEquals(base.hashCode(), base.copy().hashCode())
        assertNotEquals(base, base.copy(month = "2026-07"))
        assertNotEquals(base, base.copy(provider = null))
        assertNotEquals(base, base.copy(smsOutbound = 413.0))
        assertNotEquals(base, base.copy(smsInbound = null))
        assertNotEquals(base, base.copy(mmsOutbound = null))
        // ⛔ ZERO AND ABSENT MUST NOT COMPARE EQUAL. "metered at zero" and "never metered" are
        // different facts and the screen shows a row for one and not the other.
        assertNotEquals(base, base.copy(whatsappOutbound = null))
        assertNotEquals(base.copy(whatsappOutbound = 0.0), base.copy(whatsappOutbound = null))
        assertNotEquals(base, base.copy(whatsappInbound = null))
        assertNotEquals(base, base.copy(callMinutesOutbound = 318.75))
        assertNotEquals(base, base.copy(callMinutesInbound = null))
        assertNotEquals(base, base.copy(numberCount = 4.0))
        assertNotEquals(base, base.copy(videoMinutes = null))
        assertNotEquals(base, base.copy(lastUpdated = null))
    }

    @Test
    fun `the two usage envelopes compare on their payloads`() {
        val month = UsageData(month = "2026-08", smsOutbound = 412.0)

        val single = UsageResponse(success = true, usage = month)
        assertEquals(single, single.copy())
        assertEquals(single.hashCode(), single.copy().hashCode())
        assertNotEquals(single, single.copy(success = false))
        // ⛔ A populated month must never compare equal to an empty one.
        assertNotEquals(single, single.copy(usage = null))

        val history = UsageHistoryResponse(success = true, usage = listOf(month))
        assertEquals(history, history.copy())
        assertEquals(history.hashCode(), history.copy().hashCode())
        assertNotEquals(history, history.copy(success = false))
        assertNotEquals(history, history.copy(usage = emptyList()))
        assertTrue(history.toString().contains("2026-08"))
    }

    @Test
    fun `a report is not equal to an unrelated value`() {
        // ⚠️ The `other is` arm of every generated equals, which nothing above reaches.
        assertFalse(AnalyticsResponse().equals("not a report"))
        assertFalse(AnalyticsMetrics().equals(0))
        // ⚠️ NOT a null comparison. detekt refuses `.equals(null)` and the compiler warns that
        // `!= null` on a non-null type is always true — and it would reach the same generated
        // `other !is X` branch an unrelated type already covers, so it proves nothing extra.
        assertFalse(CallVolumeDelta().equals(AnalyticsMetrics()))
        assertFalse(EngagementPoint().equals(FunnelStage()))
        assertFalse(FunnelStage().equals(SentimentSlice()))
        assertFalse(SentimentSlice().equals(""))
        assertFalse(UsageData().equals(UsageResponse()))
        assertFalse(UsageResponse().equals(UsageHistoryResponse()))
        assertFalse(UsageHistoryResponse().equals(emptyList<UsageData>()))
    }
}
