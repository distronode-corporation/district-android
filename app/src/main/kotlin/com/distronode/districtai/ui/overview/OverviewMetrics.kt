package com.distronode.districtai.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.data.Overview
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.MetricCard

/**
 * The four headline numbers, as a 2×2 grid of [MetricCard]s.
 *
 * ⚠️ WHAT CHANGED, AND WHY IT WAS WORTH CHANGING. These were `Card`s whose label used
 * `labelSmall` at Material's default weight — which rendered as bold small text and read as a
 * HEADING competing with the number beneath it. `MetricCard` uses the mono eyebrow for the label,
 * which demotes it to an annotation so the value is unambiguously the subject.
 *
 * ⚠️ Still a hand-built 2×2 rather than a `LazyVerticalGrid`. Four items, never more, never
 * fewer: a lazy grid nested inside the screen's `LazyColumn` needs an explicit height (nested
 * scrollables in the same axis are a runtime crash), and giving it one hardcodes the very
 * measurement the grid was supposed to compute.
 */
@Composable
internal fun MetricTiles(overview: Overview) {
    val gap = DistrictTheme.spacing.row
    // ⚠️ The gutter is applied here rather than taken as a `modifier`: the overview is the one
    // caller, and it always passed exactly this padding.
    Column(
        modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Tile(
                label = stringResource(R.string.overview_tile_total_calls),
                value = overview.metrics.totalCalls.toString(),
                caption = stringResource(R.string.overview_tile_total_calls_caption),
            )
            Tile(
                label = stringResource(R.string.overview_tile_weekly_calls),
                value = overview.metrics.callsThisWeek.toString(),
                caption = stringResource(R.string.overview_tile_weekly_calls_caption),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            Tile(
                label = stringResource(R.string.overview_tile_contacts),
                value = overview.metrics.totalContacts.toString(),
                caption = stringResource(R.string.overview_tile_contacts_caption),
            )
            Tile(
                label = stringResource(R.string.overview_tile_avg_duration),
                // ⛔ THE SERVER'S LABEL, NOT A LOCAL FORMAT. Two duration formats ship in this
                // product and disagree on the same input: this tile omits a zero minutes component
                // ("45s") while a call row always emits one ("0m 45s"). Formatting
                // metrics.avgDuration here would make the app disagree with the browser on every
                // sub-minute average.
                value = overview.avgDurationLabel,
                caption = stringResource(R.string.overview_tile_avg_duration_caption),
            )
        }
    }
}

/**
 * ⚠️ The label and value are paired into ONE content description. Read separately a screen
 * reader announces two unrelated texts — "Total Calls Routed" then "412" — with no stated
 * relationship. Pairing makes the tile announce itself as a fact.
 */
@Composable
private fun RowScope.Tile(
    label: String,
    value: String,
    caption: String,
) {
    // ⚠️ A `RowScope` extension that weights itself, rather than a `modifier` parameter: every tile
    // sits in one of the two rows and every caller passed exactly this weight.
    MetricCard(
        label = label,
        value = value,
        caption = caption,
        modifier = Modifier.weight(1f).semantics { contentDescription = "$label, $value" },
    )
}
