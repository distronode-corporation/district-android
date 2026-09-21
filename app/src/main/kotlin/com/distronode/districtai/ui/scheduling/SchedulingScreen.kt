package com.distronode.districtai.ui.scheduling

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
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
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * The workspace's booking page: what state its scheduling tenancy is in, the one button that
 * provisions one, and the hand-off into the scheduler's own admin.
 *
 * ⛔ THERE IS NO BOOKING EDITOR IN THIS APP AND THERE MUST NOT BE ONE. Event types, availability,
 * questions and branding are edited in the scheduler's own admin, served from the workspace's
 * public host, exactly as the web dashboard does it — the web card links out for the same reason.
 * Rebuilding any of that here would be a second editor over the same rows with no server surface
 * of its own to talk to.
 *
 * ⛔ AND NOTHING ON THIS SCREEN OFFERS A PURCHASE PATH. A workspace the feature does not admit gets
 * one sentence and no control at all: Google Play's Payments policy is the same shape as the App
 * Store's 3.1.3(b) here, and a "contact sales" link out of a paid product's own settings is that
 * offer wearing a different hat. The marketplace's read-only hand-off exists for the same reason.
 *
 * ⛔ THE ENABLE BUTTON IS GATED ON THE SERVER'S OWN `canManage`, NEVER ON A ROLE THIS DESTINATION
 * CARRIES — which is why the route has no `{role}` segment, unlike almost every other
 * workspace-scoped screen here. See [offersEnable].
 *
 * ⚠️ EVERY STATE GETS ITS OWN SENTENCE. A workspace with no tenancy row, a provision that failed
 * and a read that never landed are three different answers and none of them is "there is nothing
 * here"; see [SchedulingPresentation] and [FailureText].
 */
@Composable
fun SchedulingScreen(
    state: SchedulingUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEnable: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenScheduler: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = SCHEDULING_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.scheduling_title), onBack = onBack)
        },
    ) { inset ->
        ContentContainer(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.section),
            ) {
                // ⚠️ ABOVE THE CARD RATHER THAN INSIDE IT, so a refused provision keeps its
                // sentence even when the re-read that follows replaces the card with a failure of
                // its own.
                state.notice?.let { SchedulingNotice(it.resolve(), onDismissNotice) }

                when (val screen = state.screen) {
                    SchedulingScreenState.Loading -> LoadingCard()
                    // ⛔ A FAILURE, NEVER AN EMPTY CARD. `tenant == null` is a legitimate answer on
                    // this surface, so the two would otherwise look identical.
                    is SchedulingScreenState.Failed -> SchedulingFailure(screen.failure, onRetry)
                    is SchedulingScreenState.Ready -> SchedulingCard(
                        status = screen.status,
                        busy = state.busy,
                        openingDashboard = state.openingDashboard,
                        openingConsole = state.openingConsole,
                        consoleRetired = state.consoleRetired,
                        onEnable = onEnable,
                        onOpenDashboard = onOpenDashboard,
                        onOpenScheduler = onOpenScheduler,
                        onRefresh = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Column(
        modifier = Modifier.semantics { contentDescription = SCHEDULING_LOADING_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
    }
}

/** A dismissible sentence above the card. Mirrors the devices screen's notice. */
@Composable
private fun SchedulingNotice(message: String, onDismiss: () -> Unit) {
    DistrictCard(
        modifier = Modifier.semantics { contentDescription = SCHEDULING_NOTICE_DESCRIPTION },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
        DistrictButton(
            text = stringResource(R.string.scheduling_dismiss),
            onClick = onDismiss,
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier
                .padding(top = DistrictTheme.spacing.tight)
                .semantics { contentDescription = SCHEDULING_DISMISS_DESCRIPTION },
        )
    }
}

@Composable
private fun SchedulingFailure(failure: FailureText, onRetry: () -> Unit) {
    DistrictCard(
        modifier = Modifier.semantics { contentDescription = SCHEDULING_FAILURE_DESCRIPTION },
    ) {
        Eyebrow(stringResource(R.string.scheduling_failed))
        Text(
            text = failure.message.resolve(),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
        )
        // ⚠️ Only when retrying could work. A signed-out failure repeats identically, and a button
        // that cannot help is worse than none.
        if (failure.retryable) {
            DistrictButton(
                text = stringResource(R.string.overview_retry),
                onClick = onRetry,
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = SCHEDULING_RETRY_DESCRIPTION },
            )
        }
    }
}

private const val SKELETON_ROWS = 3

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val SCHEDULING_ROOT_DESCRIPTION: String = "district-scheduling-root"
const val SCHEDULING_LOADING_DESCRIPTION: String = "district-scheduling-loading"
const val SCHEDULING_CARD_DESCRIPTION: String = "district-scheduling-card"
const val SCHEDULING_BADGE_DESCRIPTION: String = "district-scheduling-badge"
const val SCHEDULING_HOST_DESCRIPTION: String = "district-scheduling-host"
const val SCHEDULING_LINK_DESCRIPTION: String = "district-scheduling-link"
const val SCHEDULING_NO_LINK_DESCRIPTION: String = "district-scheduling-no-link"
const val SCHEDULING_REASON_DESCRIPTION: String = "district-scheduling-reason"

/** The PRIMARY hand-off: a signed-in browser onto the dashboard's own scheduling pages. */
const val SCHEDULING_DASHBOARD_DESCRIPTION: String = "district-scheduling-dashboard"

/** The SECONDARY hand-off, into the scheduler fork's `/admin/` console. Withdrawn on a 410. */
const val SCHEDULING_OPEN_DESCRIPTION: String = "district-scheduling-open"

const val SCHEDULING_ENABLE_DESCRIPTION: String = "district-scheduling-enable"
const val SCHEDULING_REFRESH_DESCRIPTION: String = "district-scheduling-refresh"
const val SCHEDULING_NOTICE_DESCRIPTION: String = "district-scheduling-notice"
const val SCHEDULING_DISMISS_DESCRIPTION: String = "district-scheduling-dismiss"
const val SCHEDULING_FAILURE_DESCRIPTION: String = "district-scheduling-failure"
const val SCHEDULING_RETRY_DESCRIPTION: String = "district-scheduling-retry"

@Preview(showBackground = true)
@Composable
private fun SchedulingScreenPreview() {
    DistrictTheme {
        SchedulingScreen(
            state = SchedulingUiState(
                screen = SchedulingScreenState.Ready(
                    SchedulingStatusResponse(
                        eligible = true,
                        canManage = true,
                        tenant = SchedulingTenant(
                            status = "ready",
                            publicHost = "acme-book.distronode.com",
                            region = "us",
                            lastReadyAt = "2026-09-06T11:20:00.000Z",
                            hasCredentials = true,
                            bookingUrl = "https://acme-book.distronode.com/book/phone-consultation",
                        ),
                    ),
                ),
            ),
            onBack = {},
            onRetry = {},
            onEnable = {},
            onOpenDashboard = {},
            onOpenScheduler = {},
            onDismissNotice = {},
        )
    }
}
