package com.distronode.districtai.ui.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * Telephony analytics, this month's metered usage, and the recent-months trend.
 *
 * ⛔ THE THREE CARD AREAS FAIL INDEPENDENTLY. Analytics, usage and usage history are separate
 * server reads, so a failure of one is rendered inside its own card with the others' figures still
 * on screen. The whole-screen [AnalyticsUiState.Failed] is reached only when EVERY read failed and
 * there is genuinely nothing left to preserve.
 *
 * ⚠️ THE CARDS THEMSELVES LIVE IN `AnalyticsCards.kt` and `AnalyticsHistoryCard.kt`, and the
 * charts' arithmetic in `AnalyticsChartMath.kt`. This file owns the shell: the scaffold, the window
 * selector, and the three top-level states.
 *
 * ⚠️ A `verticalScroll` COLUMN RATHER THAN A LazyColumn, unlike the overview and the call log.
 * The content is a fixed, bounded set of cards — there is no list here to virtualise — and a
 * LazyColumn would only add item-scope ceremony around static children.
 */
@Composable
fun AnalyticsScreen(
    state: AnalyticsUiState,
    onSelectRange: (AnalyticsRange) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = ANALYTICS_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.analytics_title), onBack = onBack)
        },
    ) { inset ->
        when (state) {
            AnalyticsUiState.Loading -> LoadingState(inset)
            is AnalyticsUiState.Failed -> TotalFailureState(inset, state.failure, onRetry)
            is AnalyticsUiState.Content -> ContentState(state, inset, onSelectRange, onRetry)
        }
    }
}

/**
 * ⚠️ SKELETON BLOCKS SHAPED LIKE THE CONTENT, matching the overview. A spinner says only "wait";
 * a chip row, four tiles and a chart-sized block say what is arriving, and the layout does not
 * jump when it does.
 */
@Composable
private fun LoadingState(inset: Modifier) {
    ContentContainer(modifier = inset.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = ANALYTICS_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            SkeletonBlock(modifier = Modifier.fillMaxWidth(SKELETON_CHIPS_FRACTION))
            repeat(SKELETON_TILE_ROWS) { SkeletonBlock(height = SKELETON_TILE_HEIGHT) }
            SkeletonBlock(height = CHART_HEIGHT)
        }
    }
}

/** ⛔ Reached only when BOTH reads failed — see [AnalyticsUiState.Failed]. */
@Composable
private fun TotalFailureState(inset: Modifier, failure: FailureText, onRetry: () -> Unit) {
    ContentContainer(modifier = inset.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = ANALYTICS_FAILED_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = failure.message.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.mutedForeground,
            )
            if (failure.retryable) {
                DistrictButton(
                    text = stringResource(R.string.overview_retry),
                    onClick = onRetry,
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.section)
                        .semantics { contentDescription = ANALYTICS_RETRY_DESCRIPTION },
                )
            }
        }
    }
}

@Composable
private fun ContentState(
    state: AnalyticsUiState.Content,
    inset: Modifier,
    onSelectRange: (AnalyticsRange) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = inset
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        ContentContainer {
            RangeChips(selected = state.range, onSelectRange = onSelectRange)
        }

        if (state.refreshing) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = ANALYTICS_REFRESHING_DESCRIPTION },
                color = DistrictTheme.colors.district,
                trackColor = DistrictTheme.colors.muted,
            )
        }

        when (val analytics = state.analytics) {
            is AnalyticsCardState.Ready -> AnalyticsSections(analytics.report)
            is AnalyticsCardState.Failed -> ContentContainer {
                CardFailure(
                    title = stringResource(R.string.analytics_failed),
                    failure = analytics.failure,
                    description = ANALYTICS_ANALYTICS_FAILURE_DESCRIPTION,
                    onRetry = onRetry,
                )
            }
        }

        ContentContainer {
            when (val usage = state.usage) {
                is UsageCardState.Ready -> UsageCard(usage.usage)
                is UsageCardState.Failed -> CardFailure(
                    title = stringResource(R.string.analytics_usage_failed),
                    failure = usage.failure,
                    description = ANALYTICS_USAGE_FAILURE_DESCRIPTION,
                    onRetry = onRetry,
                )
            }
        }

        // ⛔ BESIDE THE CURRENT MONTH, NOT INSTEAD OF IT, AND WITH ITS OWN FAILURE. The two are
        // separate requests against the same route (`history=true` is the only difference), so a
        // history read that failed must not take this month's figures off the screen.
        ContentContainer {
            when (val history = state.history) {
                is UsageHistoryCardState.Ready -> UsageHistoryCard(history.months)
                is UsageHistoryCardState.Failed -> CardFailure(
                    title = stringResource(R.string.analytics_history_failed),
                    failure = history.failure,
                    description = ANALYTICS_HISTORY_FAILURE_DESCRIPTION,
                    onRetry = onRetry,
                )
            }
        }

        // Bottom breathing room; the scroll container clips a modifier padding.
        Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
    }
}

/**
 * The window selector.
 *
 * ⚠️ THE SELECTED CHIP IS A FILLED BUTTON RATHER THAN A BORDERED ONE, because this app's design
 * system has no chip component and inventing one for a single screen is how a second, drifting set
 * of primitives starts. Primary-versus-Secondary is already the system's "this one is active".
 */
@Composable
private fun RangeChips(selected: AnalyticsRange, onSelectRange: (AnalyticsRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        RangeChip(AnalyticsRange.SEVEN_DAYS, selected, R.string.analytics_range_7d, onSelectRange)
        RangeChip(AnalyticsRange.THIRTY_DAYS, selected, R.string.analytics_range_30d, onSelectRange)
        RangeChip(AnalyticsRange.NINETY_DAYS, selected, R.string.analytics_range_90d, onSelectRange)
    }
}

@Composable
private fun RangeChip(
    range: AnalyticsRange,
    selected: AnalyticsRange,
    labelRes: Int,
    onSelectRange: (AnalyticsRange) -> Unit,
) {
    DistrictButton(
        text = stringResource(labelRes),
        onClick = { onSelectRange(range) },
        variant = if (range == selected) ButtonVariant.Primary else ButtonVariant.Secondary,
        size = ButtonSize.Sm,
        modifier = Modifier.semantics { contentDescription = rangeDescription(range) },
    )
}

/** Stable per-range test handles, derived in one place so the screen and its test cannot drift. */
internal fun rangeDescription(range: AnalyticsRange): String = "district-analytics-range-${range.wire}"

@Composable
private fun AnalyticsSections(report: AnalyticsResponse) {
    ContentContainer { MetricTiles(report) }
    ContentContainer { DeltaCard(report) }
    ContentContainer { TrendCard(report.engagementTrends) }
    ContentContainer { FunnelCard(report.funnelData) }
    ContentContainer { SentimentCard(report.sentimentDistribution) }
}

/**
 * One card area's own failure.
 *
 * ⚠️ A CARD, NOT A WHOLE-SCREEN STATE. The other half of this screen may have loaded fine, and
 * replacing everything with one message would discard a correct answer already on screen.
 */
@Composable
private fun CardFailure(
    title: String,
    failure: FailureText,
    description: String,
    onRetry: () -> Unit,
) {
    DistrictCard(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = description },
    ) {
        Eyebrow(title)
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        // ⚠️ Offered only when retrying could work. Contract drift and a role refusal produce the
        // identical failure every time.
        if (failure.retryable) {
            DistrictButton(
                text = stringResource(R.string.overview_retry),
                onClick = onRetry,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
        }
    }
}

private val SKELETON_TILE_HEIGHT: Dp = 72.dp
private const val SKELETON_TILE_ROWS = 3
private const val SKELETON_CHIPS_FRACTION = 0.7f

/**
 * Stable handles for tests.
 *
 * ⚠️ Constants rather than literals duplicated in the test: a renamed description in one place and
 * not the other produces a test that silently matches nothing.
 */
const val ANALYTICS_ROOT_DESCRIPTION: String = "district-analytics-root"
const val ANALYTICS_LOADING_DESCRIPTION: String = "district-analytics-loading"
const val ANALYTICS_FAILED_DESCRIPTION: String = "district-analytics-failed"
const val ANALYTICS_RETRY_DESCRIPTION: String = "district-analytics-retry"
const val ANALYTICS_REFRESHING_DESCRIPTION: String = "district-analytics-refreshing"
const val ANALYTICS_METRICS_DESCRIPTION: String = "district-analytics-metrics"
const val ANALYTICS_DELTA_DESCRIPTION: String = "district-analytics-delta"
const val ANALYTICS_TREND_DESCRIPTION: String = "district-analytics-trend"
const val ANALYTICS_TREND_EMPTY_DESCRIPTION: String = "district-analytics-trend-empty"
const val ANALYTICS_FUNNEL_DESCRIPTION: String = "district-analytics-funnel"
const val ANALYTICS_SENTIMENT_DESCRIPTION: String = "district-analytics-sentiment"
const val ANALYTICS_ANALYTICS_FAILURE_DESCRIPTION: String = "district-analytics-report-failure"
const val ANALYTICS_USAGE_DESCRIPTION: String = "district-analytics-usage"
const val ANALYTICS_USAGE_EMPTY_DESCRIPTION: String = "district-analytics-usage-empty"
const val ANALYTICS_USAGE_FAILURE_DESCRIPTION: String = "district-analytics-usage-failure"
const val ANALYTICS_HISTORY_DESCRIPTION: String = "district-analytics-history"
const val ANALYTICS_HISTORY_EMPTY_DESCRIPTION: String = "district-analytics-history-empty"
const val ANALYTICS_HISTORY_FAILURE_DESCRIPTION: String = "district-analytics-history-failure"
