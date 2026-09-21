package com.distronode.districtai.ui.contacts

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
 * The contacts list's non-list states.
 *
 * ⚠️ Split out of ContactsScreen.kt to stay under detekt's 11-function-per-file threshold, which is
 * the better trade than raising it: the screen file is now the list, the row and the create flow.
 */

/** ⚠️ Skeleton rows in the shape of the list, for the same reason as the call log's. */
@Composable
internal fun ContactsLoading() {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .padding(DistrictTheme.spacing.gutter)
                .semantics { contentDescription = CONTACTS_LOADING_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            repeat(SKELETON_ROWS) { SkeletonBlock(height = SKELETON_ROW_HEIGHT) }
        }
    }
}

/**
 * ⚠️ Reachable only once refresh SUCCEEDED, so it genuinely means "no contacts". The create action
 * lives INSIDE the empty state rather than only on the floating button, because an empty CRM is
 * exactly where the first contact gets made and pointing at a control elsewhere is a dead end.
 */
@Composable
internal fun ContactsEmpty(canMutate: Boolean, onCreate: () -> Unit) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = CONTACTS_EMPTY_DESCRIPTION },
            verticalArrangement = Arrangement.Center,
        ) {
            EmptyState(
                title = stringResource(R.string.contacts_empty_title),
                body = stringResource(R.string.contacts_empty),
                action = if (canMutate) {
                    {
                        DistrictButton(
                            text = stringResource(R.string.contacts_create_action),
                            onClick = onCreate,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
internal fun ContactsFailure(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    ContentContainer(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DistrictTheme.spacing.section)
                .semantics { contentDescription = CONTACTS_FAILURE_DESCRIPTION },
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
            ContactsFailureAction(
                failure = failure,
                onRetry = onRetry,
                onSignIn = onSignIn,
                modifier = Modifier.padding(top = DistrictTheme.spacing.section),
            )
        }
    }
}

@Composable
internal fun ContactsAppending() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            color = DistrictTheme.colors.district,
            modifier = Modifier.semantics { contentDescription = CONTACTS_APPENDING_DESCRIPTION },
        )
    }
}

@Composable
internal fun ContactsAppendFailure(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(DistrictTheme.spacing.gutter)
            .semantics { contentDescription = CONTACTS_APPEND_FAILURE_DESCRIPTION },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
        )
        ContactsFailureAction(
            failure = failure,
            onRetry = onRetry,
            onSignIn = onSignIn,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
    }
}

@Composable
private fun ContactsFailureAction(
    failure: FailureText,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        // A dead session cannot be retried; the only way forward is signing in.
        failure.signedOutCause != null -> DistrictButton(
            text = stringResource(R.string.overview_sign_in_again),
            onClick = onSignIn,
            modifier = modifier,
        )
        // ⚠️ Only offered when retrying could work. A contract mismatch or a role refusal produces
        // the identical failure every time, and a button that cannot succeed is worse than none.
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
