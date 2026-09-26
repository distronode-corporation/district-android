package com.distronode.districtai.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * The call log's non-list states: loading, empty, failed, and the two footer states.
 *
 * ⚠️ SPLIT OUT OF CallLogScreen.kt to stay under detekt's 11-function-per-file threshold. Raising
 * the threshold was the alternative and it is the wrong one — the screen file is now the list and
 * the row, which is what anyone opening it is looking for.
 */

/**
 * ⚠️ SKELETON ROWS, NOT A CENTRED SPINNER. The list that is arriving has a known shape — avatar,
 * two lines, trailing badge — so drawing that shape says what is loading and stops the layout
 * jumping when it lands. A spinner in the middle of a blank screen conveys only "wait".
 */
@Composable
internal fun FullScreenLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = CALL_LOG_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_ROW_HEIGHT) }
        }
    }
}

/**
 * ⚠️ Only reachable once refresh has SUCCEEDED, so this genuinely means "no calls" rather than "we
 * could not load them". That ordering is what makes an [EmptyState] honest here.
 */
@Composable
internal fun EmptyLog() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = CALL_LOG_EMPTY_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                title = stringResource(R.string.overview_recent_empty_title),
                body = stringResource(R.string.overview_recent_empty),
            )
        }
    }
}

@Composable
internal fun FullScreenFailure(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = CALL_LOG_FAILURE_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = failure.message.resolve(),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                textAlign = TextAlign.Center,
            )
            failure.degradedRegions.takeIf { it.isNotEmpty() }?.let { regions ->
                Text(
                    text = stringResource(
                        R.string.overview_degraded_regions,
                        regions.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
                )
            }
            FailureAction(
                failure = failure,
                onRetry = onRetry,
                onSignIn = onSignIn,
                modifier = Modifier.padding(top = DistrictTheme.spacing.section),
            )
        }
    }
}

/**
 * ⚠️ A spinner is correct HERE, unlike the full-screen case: the footer is a small strip below rows
 * the user is already reading, so there is no shape to stand in for and nothing to stop jumping.
 */
@Composable
internal fun AppendLoading() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = DistrictTheme.colors.district,
            modifier = Modifier.semantics { contentDescription = CALL_LOG_APPENDING_DESCRIPTION },
        )
    }
}

@Composable
internal fun AppendFailure(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(DistrictTheme.spacing.gutter)
            .semantics { contentDescription = CALL_LOG_APPEND_FAILURE_DESCRIPTION },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        FailureAction(
            failure = failure,
            onRetry = onRetry,
            onSignIn = onSignIn,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

@Composable
private fun FailureAction(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier,
) {
    when {
        // A dead session cannot be retried; the only way forward is signing in.
        failure.signedOutCause != null -> DistrictButton(
            text = stringResource(R.string.overview_sign_in_again),
            onClick = onSignIn,
            modifier = modifier,
        )
        // ⚠️ Only offered when retrying could actually work. A contract mismatch or a role refusal
        // produces the identical failure on every attempt, and a button that cannot succeed is
        // worse than no button.
        failure.retryable -> DistrictButton(
            text = stringResource(R.string.overview_retry),
            onClick = onRetry,
            modifier = modifier,
        )
        else -> Unit
    }
}

private const val SKELETON_ROWS = 8
private val SKELETON_ROW_HEIGHT = 56.dp
