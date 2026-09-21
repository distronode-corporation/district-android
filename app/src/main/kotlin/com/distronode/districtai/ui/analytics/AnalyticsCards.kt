package com.distronode.districtai.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.MetricCard
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.DIRECTION_DOWN
import com.distronode.districtai.core.model.DIRECTION_UP
import com.distronode.districtai.core.model.EngagementPoint
import com.distronode.districtai.core.model.FunnelStage
import com.distronode.districtai.core.model.SentimentSlice
import com.distronode.districtai.core.model.UsageData
import kotlin.math.abs

/**
 * The individual cards the analytics screen stacks.
 *
 * ⛔ A SEPARATE FILE FROM `AnalyticsScreen.kt` BECAUSE detekt CAPS A FILE AT 11 FUNCTIONS AND THE
 * COMBINED SCREEN LANDED AT 16. The split is along the seam that was already there: the shell
 * (scaffold, chip selector, loading and failure states) decides WHAT is shown, and these decide how
 * one card looks. They are `internal` rather than `private` only because they now cross a file
 * boundary.
 *
 * ⛔ EVERY CHART IS DRAWN WITH `Canvas`/`DrawScope`, AND THE ARITHMETIC LIVES IN
 * `AnalyticsChartMath.kt` RATHER THAN HERE. The obvious alternative — a `Box` with
 * `fillMaxWidth(fraction)` — has an opinion about a zero-length bar, and a zero-length bar is the
 * common case: a workspace with no calls produces an all-zero series, and the sentiment breakdown
 * always carries its three bands even when every one is zero. Drawing rectangles makes a zero
 * simply a rect of zero width, and keeping the scaling in plain functions is what lets a unit test
 * reach the division that would otherwise only be reachable by rendering.
 */

@Composable
internal fun MetricTiles(report: AnalyticsResponse) {
    val metrics = report.metrics
    Column(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_METRICS_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            MetricCard(
                label = stringResource(R.string.analytics_metric_total_calls),
                value = metrics.totalCalls.toString(),
                caption = stringResource(R.string.analytics_metric_total_calls_caption),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = stringResource(R.string.analytics_metric_avg_duration),
                // ⛔ COMPLETED-ONLY, and the caption says so. See AnalyticsMetrics.
                value = formatDurationSeconds(metrics.avgDuration),
                caption = stringResource(R.string.analytics_metric_avg_duration_caption),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            MetricCard(
                label = stringResource(R.string.analytics_metric_conversion),
                // ⚠️ ALREADY A PERCENTAGE. Multiplying by 100 here is the obvious mistake and
                // produces a plausible four-digit number.
                value = stringResource(R.string.analytics_percent, metrics.conversionRate),
                caption = stringResource(R.string.analytics_metric_conversion_caption),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                label = stringResource(R.string.analytics_metric_missed),
                value = metrics.missedCalls.toString(),
                caption = stringResource(R.string.analytics_metric_missed_caption),
                modifier = Modifier.weight(1f),
            )
        }
        MetricCard(
            label = stringResource(R.string.analytics_metric_abandoned),
            value = metrics.abandonedCalls.toString(),
            caption = stringResource(R.string.analytics_metric_abandoned_caption),
        )
        // ⛔ NO "ACTIVE AGENTS" TILE. The server hardcodes that field to zero — there is no
        // presence signal in this product — so a tile would read "0" forever and look like a
        // measurement. Absent is honest; see AnalyticsMetrics.activeAgents.
    }
}

/**
 * The prior-period comparison.
 *
 * ⛔ A NULL `pct` RENDERS "New", NOT "0%". There is no prior period to compare against, so no
 * percentage exists — and a zero would tell a brand-new customer their call volume was flat during
 * their first week on the platform.
 */
@Composable
internal fun DeltaCard(report: AnalyticsResponse) {
    val delta = report.callVolumeDelta
    // ⚠️ Copied to a local: `pct` is a val on a class from another module, which Kotlin will not
    // smart-cast across the null check below.
    val pct = delta.pct

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_DELTA_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.analytics_delta_title))
        Text(
            text = stringResource(R.string.analytics_delta_counts, delta.current, delta.prior),
            style = DistrictTheme.text.metric,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        Text(
            text = when {
                pct == null -> stringResource(R.string.analytics_delta_new)
                // ⚠️ The magnitude, with the ARROW carrying the sign. The server sends a negative
                // percentage for a fall, and "▼ -20%" reads as a double negative.
                delta.direction == DIRECTION_UP ->
                    stringResource(R.string.analytics_delta_up, abs(pct))
                delta.direction == DIRECTION_DOWN ->
                    stringResource(R.string.analytics_delta_down, abs(pct))
                else -> stringResource(R.string.analytics_delta_flat)
            },
            style = MaterialTheme.typography.bodySmall,
            color = when {
                pct == null -> DistrictTheme.colors.mutedForeground
                delta.direction == DIRECTION_UP -> DistrictTheme.colors.success
                delta.direction == DIRECTION_DOWN -> DistrictTheme.colors.destructive
                else -> DistrictTheme.colors.mutedForeground
            },
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
    }
}

/**
 * The trend series as vertical bars.
 *
 * ⚠️ THE AXIS LABELS ARE RENDERED VERBATIM AND NEVER PARSED. `EngagementPoint.date` is a display
 * string the SERVER localized to the operator's timezone; it carries no year and shifts with the
 * reader. `isoDate` is the machine-readable one and is used for nothing here except ordering,
 * which the server has already applied.
 *
 * ⚠️ AN ALL-ZERO SERIES IS LABELLED RATHER THAN LEFT BLANK. The series is never empty — the server
 * emits one point per bucket regardless — so a workspace with no calls draws a flat floor, and an
 * unlabelled flat chart reads as a broken renderer rather than as "no calls".
 */
@Composable
internal fun TrendCard(points: List<EngagementPoint>) {
    val heights = normalizedHeights(points.map { it.calls })
    val track = DistrictTheme.colors.muted
    val accent = DistrictTheme.colors.district

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_TREND_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.analytics_trend_title))

        if (points.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT)
                    .padding(top = DistrictTheme.spacing.tight),
            ) {
                val slots = barSlots(points.size, size.width, BAR_GAP.toPx())
                slots.forEachIndexed { index, slot ->
                    drawRect(
                        color = track,
                        topLeft = Offset(slot.left, 0f),
                        size = Size(slot.width, size.height),
                    )
                    val filled = size.height * heights[index]
                    drawRect(
                        color = accent,
                        topLeft = Offset(slot.left, size.height - filled),
                        size = Size(slot.width, filled),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.hairline),
            ) {
                Text(
                    text = stringResource(
                        R.string.analytics_trend_axis,
                        points.first().date,
                        points.last().date,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )
            }
        }

        if (points.none { it.calls > 0 }) {
            Text(
                text = stringResource(R.string.analytics_trend_empty),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = ANALYTICS_TREND_EMPTY_DESCRIPTION },
            )
        }
    }
}

/** The dial → connect → lead funnel, as horizontal bars scaled to the widest stage. */
@Composable
internal fun FunnelCard(stages: List<FunnelStage>) {
    val widths = normalizedHeights(stages.map { it.count })
    val track = DistrictTheme.colors.muted
    val accent = DistrictTheme.colors.district

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_FUNNEL_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.analytics_funnel_title))
        stages.forEachIndexed { index, stage ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stage.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.foreground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stage.count.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.mutedForeground,
                )
            }
            Canvas(modifier = Modifier.fillMaxWidth().height(BAR_HEIGHT)) {
                drawRect(color = track, size = size)
                drawRect(
                    color = accent,
                    size = Size(size.width * widths[index], size.height),
                )
            }
        }
    }
}

/**
 * Sentiment as one proportional bar plus a legend.
 *
 * ⛔ THE COLOURS COME FROM THE SERVER AS OPAQUE HEX STRINGS AND ARE PARSED LENIENTLY HERE, falling
 * back to the brand accent. Parsing them in the DTO would let a malformed shade fail the entire
 * analytics response — every metric, every trend point — over a presentational detail.
 */
@Composable
internal fun SentimentCard(slices: List<SentimentSlice>) {
    val shares = proportions(slices.map { it.value })
    val fallback = DistrictTheme.colors.district
    val track = DistrictTheme.colors.muted
    val colors = slices.map { slice -> parseHexArgb(slice.color)?.let { Color(it) } ?: fallback }

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_SENTIMENT_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.analytics_sentiment_title))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .padding(top = DistrictTheme.spacing.tight),
        ) {
            drawRect(color = track, size = size)
            var offset = 0f
            shares.forEachIndexed { index, share ->
                val width = size.width * share
                drawRect(
                    color = colors[index],
                    topLeft = Offset(offset, 0f),
                    size = Size(width, size.height),
                )
                offset += width
            }
        }
        slices.forEach { slice ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.hairline),
            ) {
                Text(
                    text = slice.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = slice.value.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.foreground,
                )
            }
        }
        if (slices.none { it.value > 0 }) {
            Text(
                text = stringResource(R.string.analytics_sentiment_empty),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
        }
    }
}

/**
 * This month's metered usage.
 *
 * ⛔ A NULL [usage] RENDERS A SENTENCE, NEVER ZEROS. `usage: null` means the month has no metering
 * rows at all — which is not the claim "you sent nothing". A zero beside a billing label reads as
 * a measurement, and it is the kind of wrong number that becomes a support ticket.
 */
@Composable
internal fun UsageCard(usage: UsageData?) {
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_USAGE_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.analytics_usage_title))
        if (usage == null) {
            Text(
                text = stringResource(R.string.analytics_usage_none),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = ANALYTICS_USAGE_EMPTY_DESCRIPTION },
            )
            return@DistrictCard
        }

        Text(
            text = stringResource(R.string.analytics_usage_month, usage.month),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
        // ⚠️ ONLY THE METRICS THAT ARE PRESENT. An absent metric has no key in the response,
        // which is a different fact from a metric measured at zero — see UsageData — so a null
        // row is omitted while a zero row is shown.
        usageRows(usage).forEach { (labelRes, amount) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.hairline),
            ) {
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatUsageAmount(amount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = DistrictTheme.colors.foreground,
                )
            }
        }
    }
}

/**
 * The metrics this month actually carries, in a fixed display order.
 *
 * ⚠️ NOT a composable and not `@StringRes`-annotated on the pair, so it stays a plain list a test
 * can assert on without a Context.
 */
private fun usageRows(usage: UsageData): List<Pair<Int, Double>> = listOfNotNull(
    usage.smsOutbound?.let { R.string.analytics_usage_sms_outbound to it },
    usage.smsInbound?.let { R.string.analytics_usage_sms_inbound to it },
    usage.mmsOutbound?.let { R.string.analytics_usage_mms_outbound to it },
    usage.whatsappOutbound?.let { R.string.analytics_usage_whatsapp_outbound to it },
    usage.whatsappInbound?.let { R.string.analytics_usage_whatsapp_inbound to it },
    usage.callMinutesOutbound?.let { R.string.analytics_usage_call_minutes_outbound to it },
    usage.callMinutesInbound?.let { R.string.analytics_usage_call_minutes_inbound to it },
    usage.numberCount?.let { R.string.analytics_usage_numbers to it },
    usage.videoMinutes?.let { R.string.analytics_usage_video_minutes to it },
)

/** The trend chart's plot height. Also the skeleton's, so the layout does not jump on arrival. */
internal val CHART_HEIGHT: Dp = 140.dp

private val BAR_HEIGHT: Dp = 10.dp

/** Between trend bars. ⚠️ Consumed from the available width — see [barSlots]. */
private val BAR_GAP: Dp = 4.dp
