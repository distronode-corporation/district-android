package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * Telephony analytics for one workspace over one time window.
 *
 * ⛔ EVERY NUMBER HERE IS DERIVED SERVER-SIDE, IN SQL, OVER THE FULL WINDOW — and the client must
 * not recompute any of it. `avgDuration` divides by COMPLETED calls while `totalCalls` counts all
 * of them, `conversionRate` is already a rounded percentage, and `neutral` sentiment is a
 * REMAINDER that is never queried. A client that re-derived any of those from the other fields
 * would disagree with the web console on the same data, which is the failure mode this shape
 * exists to prevent: two surfaces quoting different numbers to the same operator.
 */
@Serializable
data class AnalyticsResponse(
    val success: Boolean = false,
    val metrics: AnalyticsMetrics = AnalyticsMetrics(),
    val callVolumeDelta: CallVolumeDelta = CallVolumeDelta(),
    /**
     * The trend series, OLDEST FIRST.
     *
     * ⚠️ Never empty for a well-formed response: the server seeds one point per bucket and fills
     * from the query, so a workspace with no calls at all gets a series of ZEROS rather than an
     * absent one. That is the degenerate input the chart has to survive — see
     * `com.distronode.districtai.ui.analytics` — and it is why "no data" cannot be detected by
     * checking for emptiness.
     */
    val engagementTrends: List<EngagementPoint> = emptyList(),
    val funnelData: List<FunnelStage> = emptyList(),
    val sentimentDistribution: List<SentimentSlice> = emptyList(),
)

/**
 * The headline tiles.
 *
 * ⚠️ [avgDuration] IS SECONDS ACROSS *COMPLETED* SESSIONS, not across every call. A failed or
 * abandoned call with a non-zero duration is deliberately excluded from both the numerator and
 * the denominator, so `avgDuration * totalCalls` is not talk time and must never be presented as
 * such.
 *
 * ⚠️ [conversionRate] is ALREADY A PERCENTAGE (0..100), already rounded. Multiplying by 100 is
 * the obvious mistake and produces a plausible-looking four-digit number.
 *
 * ⛔ [activeAgents] IS HARDCODED TO ZERO SERVER-SIDE. There is no live-agent presence signal in
 * this product, so it is a placeholder rather than a measurement. Do not build a tile on it: it
 * would read "0 agents online" forever, which is worse than an absent tile because it looks like
 * a fact.
 */
@Serializable
data class AnalyticsMetrics(
    val totalCalls: Int = 0,
    val avgDuration: Int = 0,
    val conversionRate: Int = 0,
    val abandonedCalls: Int = 0,
    val missedCalls: Int = 0,
    val activeAgents: Int = 0,
)

/**
 * Call volume against the immediately preceding window of the same length.
 *
 * ⛔ [pct] IS NULLABLE AND NULL MEANS "New", NOT ZERO. A workspace with no prior period has no
 * baseline, so there is no percentage to state — the server sends null rather than inventing a
 * divide-by-zero or a fake 0%. Rendering null as "0%" tells a brand-new customer their call
 * volume is flat when in fact this is their first week. `district-analytics-new-workspace.json`
 * exists precisely to keep this field nullable.
 *
 * ⚠️ [direction] IS INDEPENDENT OF [pct] AND MUST NOT BE INFERRED FROM IT. Zero against zero is
 * `flat` with a null percentage; a first-ever call is `up` with a null percentage. The arrow and
 * the number answer different questions.
 */
@Serializable
data class CallVolumeDelta(
    val current: Int = 0,
    val prior: Int = 0,
    val pct: Int? = null,
    /** `up`, `down` or `flat`. A plain String: the server has no enum here and neither is added. */
    val direction: String = DIRECTION_FLAT,
) {
    /** True when there is no prior baseline, so the delta reads "New" rather than a percentage. */
    val isNew: Boolean get() = pct == null
}

const val DIRECTION_UP: String = "up"
const val DIRECTION_DOWN: String = "down"
const val DIRECTION_FLAT: String = "flat"

/**
 * One bucket of the trend series.
 *
 * ⛔ [date] AND [isoDate] ARE NOT INTERCHANGEABLE, AND ONLY ONE OF THEM IS MACHINE-READABLE.
 * [date] is a DISPLAY string the server localized to the OPERATOR'S timezone ("Aug 15") — it
 * carries no year and shifts with the reader, so parsing it is not merely fragile but wrong.
 * Render it verbatim. [isoDate] is the same bucket's UTC calendar date ("2026-08-15") and is the
 * only field that may be sorted, diffed or re-bucketed.
 *
 * ⚠️ THE BUCKET IS NOT ALWAYS A DAY. 7d and 30d windows bucket daily; a 90d window buckets
 * WEEKLY, and for a weekly bucket both fields name its END. So the series length is a property of
 * the window, not a constant, and consecutive `isoDate` values are not necessarily one day apart.
 *
 * ⚠️ [calls] counts ALL calls in the bucket (the bars sum to `metrics.totalCalls`), while
 * [avgDuration] averages COMPLETED ones only — so a bucket can legitimately have traffic and a
 * zero average.
 */
@Serializable
data class EngagementPoint(
    val date: String = "",
    val isoDate: String = "",
    val calls: Int = 0,
    val avgDuration: Int = 0,
)

/** One stage of the dial → connect → lead funnel. Ordered widest first by the server. */
@Serializable
data class FunnelStage(
    val name: String = "",
    val count: Int = 0,
)

/**
 * One sentiment band.
 *
 * ⛔ [color] IS A SERVER-CHOSEN HEX STRING CARRIED AS AN OPAQUE String, NOT PARSED HERE. Nothing
 * server-side constrains it to `#rrggbb`, and a DTO that decoded it into a colour type would
 * fail the WHOLE response — every metric, every trend point — over a presentational detail. It is
 * parsed leniently at render time instead, falling back to a theme token, so a malformed colour
 * costs a shade rather than the screen.
 *
 * ⚠️ The three slices are always present even when every value is zero, so a proportional bar has
 * to handle a total of zero rather than an absent list.
 */
@Serializable
data class SentimentSlice(
    val name: String = "",
    val value: Int = 0,
    val color: String = "",
)

/**
 * The selectable analytics window.
 *
 * ⛔ [wire] IS THE CONTRACT, NOT THE ENUM NAME. The server matches `7d`/`30d`/`90d` exactly and
 * falls back to 7d for ANYTHING it does not recognise — silently, with a 200. So a typo here does
 * not fail: it quietly serves a week's data under a "90 days" heading, which is the one outcome
 * that cannot be spotted by looking at the screen.
 */
enum class AnalyticsRange(val wire: String) {
    SEVEN_DAYS("7d"),
    THIRTY_DAYS("30d"),

    /** ⚠️ Buckets WEEKLY server-side — see [EngagementPoint]. Roughly 13 points, not 90. */
    NINETY_DAYS("90d"),
}
