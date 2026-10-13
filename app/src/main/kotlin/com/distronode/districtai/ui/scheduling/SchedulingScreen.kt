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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.model.SchedulingTenantStatus.READY
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.InlineFailure
import com.distronode.districtai.ui.resolve

/**
 * The workspace's booking page: what state its scheduling tenancy is in, the one button that
 * provisions one, and the hand-off into the scheduler's own admin.
 *
 * ⚠️ THE NATIVE ADMIN SITS BEHIND THE "MANAGE" LIST. Once the tenancy is `ready`, the sections
 * district-ios has (overview, event types, working hours, bookings, calendar, team, settings and
 * developer) open as native screens; see `ui/scheduling/admin`. The web hand-offs stay for what
 * only a browser can do (a calendar's OAuth consent) and for anyone who prefers the dashboard.
 *
 * ⛔ NOTHING ON THIS SCREEN OFFERS A PURCHASE PATH. A workspace the feature does not admit gets
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
    onOpenSection: (SchedulingSection) -> Unit = {},
) {
    DistrictScaffold(
        modifier = Modifier.testTag(SCHEDULING_ROOT_DESCRIPTION),
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
                    is SchedulingScreenState.Failed -> InlineFailure(
                        title = stringResource(R.string.scheduling_failed),
                        failure = screen.failure,
                        onRetry = onRetry,
                        description = SCHEDULING_FAILURE_DESCRIPTION,
                        retryDescription = SCHEDULING_RETRY_DESCRIPTION,
                    )
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

                val live = (state.screen as? SchedulingScreenState.Ready)?.status?.tenant?.tenantStatus == READY
                if (live) SchedulingSections(onOpenSection)
            }
        }
    }
}

@Composable
private fun LoadingCard() {
    Column(
        modifier = Modifier.testTag(SCHEDULING_LOADING_DESCRIPTION),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
    }
}

/** A dismissible sentence above the card. Mirrors the devices screen's notice. */
@Composable
private fun SchedulingNotice(message: String, onDismiss: () -> Unit) {
    DistrictCard(
        modifier = Modifier.testTag(SCHEDULING_NOTICE_DESCRIPTION),
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
                .testTag(SCHEDULING_DISMISS_DESCRIPTION),
        )
    }
}

/**
 * The native admin's sections, drawn only for a `ready` tenancy: before that there is nothing for
 * them to read, and every one would answer "not set up yet".
 */
@Composable
private fun SchedulingSections(onOpenSection: (SchedulingSection) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        Eyebrow(stringResource(R.string.scheduling_admin_common_manage))
        DistrictCard(modifier = Modifier.testTag(SCHEDULING_SECTIONS_DESCRIPTION)) {
            SchedulingSection.entries.forEachIndexed { index, section ->
                if (index > 0) DistrictRowDivider()
                DistrictListRow(
                    title = stringResource(section.title),
                    subtitle = stringResource(section.subtitle),
                    onClick = { onOpenSection(section) },
                    modifier = Modifier.testTag("$SCHEDULING_SECTION_DESCRIPTION-${section.pathSegment}"),
                )
            }
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

/** The native admin's section list, and each row in it (suffixed with the section's path segment). */
const val SCHEDULING_SECTIONS_DESCRIPTION: String = "district-scheduling-sections"
const val SCHEDULING_SECTION_DESCRIPTION: String = "district-scheduling-section"

// ⚠️ INTERNAL RATHER THAN PRIVATE so `SchedulingScreenTest` can render it: a preview that stopped
// composing would break Android Studio's renderer without failing anything else.
@Preview(showBackground = true)
@Composable
internal fun SchedulingScreenPreview() {
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
