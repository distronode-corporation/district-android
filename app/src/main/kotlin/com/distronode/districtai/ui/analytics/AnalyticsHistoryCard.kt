package com.distronode.districtai.ui.analytics

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.UsageData

/**
 * The metered-usage trend, the web console's three-month view.
 *
 * ⛔ A THIRD FILE IN THIS PACKAGE RATHER THAN A THIRD SECTION OF `AnalyticsCards.kt`, for the
 * reason that file was split out of `AnalyticsScreen.kt`: detekt caps a file at 11 functions and
 * `AnalyticsCards.kt` already carries seven. The seam is the same one — the shell decides WHAT is
 * shown, the card files decide how each one looks.
 *
 * ⛔ EVERY FIGURE HERE IS NULLABLE AND AN ABSENT ONE RENDERS A DASH, NEVER A ZERO. An unmetered
 * metric has no key on the wire while one measured at zero is present as `0` — see `UsageData` —
 * and these sit under billing-shaped labels, which is exactly where a fabricated zero gets
 * believed. [sumMetered] carries that distinction through the addition and [normalizedAmounts]
 * keeps it out of the geometry.
 *
 * ⛔ DRAWN WITH `Canvas`, NO NEW DEPENDENCY. The same call the trend and funnel charts make: a
 * `Box` with `fillMaxWidth(fraction)` has an opinion about a zero-length bar, and a zero-length
 * bar is the ordinary case here — a month whose only metered row was a phone-number count.
 */
@Composable
internal fun UsageHistoryCard(months: List<UsageData>) {
    // ⚠️ Computed once for the whole card, not per row: the bars are scaled against EACH OTHER,
    // so the maximum is a property of the list and a per-row derivation would draw every month
    // full-width.
    val minutes = months.map { sumMetered(it.callMinutesOutbound, it.callMinutesInbound) }
    val widths = normalizedAmounts(minutes)

    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = ANALYTICS_HISTORY_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.analytics_history_title))

        // ⛔ AN EMPTY LIST IS "NOTHING HAS EVER BEEN METERED", NOT A FAULT AND NOT A ROW OF ZEROS.
        // The server appends only months that had rows, so a workspace that has never been metered
        // answers `[]` on a 200 — see UsageHistoryCardState.Ready.
        if (months.isEmpty()) {
            Text(
                text = stringResource(R.string.analytics_history_none),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = ANALYTICS_HISTORY_EMPTY_DESCRIPTION },
            )
            return@DistrictCard
        }

        // ⚠️ IN THE ORDER RECEIVED — NEWEST FIRST — AND NOT RE-SORTED. The server documents the
        // ordering; re-deriving it from the `YYYY-MM` string would work by accident today and
        // would be the thing depended on tomorrow.
        months.forEachIndexed { index, month ->
            HistoryMonthRow(month = month, minutes = minutes[index], barWidth = widths[index])
        }

        Text(
            text = stringResource(R.string.analytics_history_caption),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

/**
 * One month: its label, its metered call minutes as a bar, and its message count.
 *
 * ⚠️ [barWidth] IS PASSED IN RATHER THAN DERIVED. The scale is the whole list's maximum, which
 * this row cannot see — see the ⚠️ in [UsageHistoryCard].
 */
@Composable
private fun HistoryMonthRow(month: UsageData, minutes: Double?, barWidth: Float) {
    val track = DistrictTheme.colors.muted
    val accent = DistrictTheme.colors.district
    // ⚠️ EVERY MESSAGING CHANNEL, summed the same way the minutes are: a month with SMS metered
    // and WhatsApp absent totals the SMS, and a month with none of them metered totals to null.
    val messages = sumMetered(
        month.smsOutbound,
        month.smsInbound,
        month.mmsOutbound,
        month.whatsappOutbound,
        month.whatsappInbound,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DistrictTheme.spacing.tight)
            .semantics { contentDescription = historyMonthDescription(month.month) },
    ) {
        Text(
            // ⛔ FROM THE `YYYY-MM` KEY BY LOOKUP, never by parsing it into a date — see
            // [monthLabel]. An unrecognised key renders verbatim rather than as a guess.
            text = monthLabel(month.month),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
        )
        MeteredRow(labelRes = R.string.analytics_history_minutes, amount = minutes)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(HISTORY_BAR_HEIGHT)
                .padding(top = DistrictTheme.spacing.hairline),
        ) {
            drawRect(color = track, size = size)
            drawRect(color = accent, size = Size(size.width * barWidth, size.height))
        }
        MeteredRow(labelRes = R.string.analytics_history_messages, amount = messages)
    }
}

/**
 * One labelled metered amount.
 *
 * ⛔ NULL RENDERS A DASH. "We do not meter this for you" and "we metered it and it was zero" are
 * different facts, and only one of them may be printed as `0` beside a billing label.
 */
@Composable
private fun MeteredRow(@StringRes labelRes: Int, amount: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = DistrictTheme.spacing.hairline),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = amount?.let { formatUsageAmount(it) }
                ?: stringResource(R.string.analytics_history_absent),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.foreground,
        )
    }
}

/**
 * A stable per-month test handle, derived in one place so the card and its test cannot drift.
 *
 * ⚠️ Keyed on the RAW `YYYY-MM` value rather than the rendered label: the label is display copy
 * and would take the handle with it if it were ever reworded.
 */
internal fun historyMonthDescription(month: String): String = "district-analytics-history-$month"

/** ⚠️ Matches the funnel's bar height so the two cards read as one chart language. */
private val HISTORY_BAR_HEIGHT: Dp = 10.dp
