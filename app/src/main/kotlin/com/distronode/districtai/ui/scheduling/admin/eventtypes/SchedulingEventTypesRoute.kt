package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminRoutes
import com.distronode.districtai.ui.scheduling.admin.SchedulingEmptyState
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusBadge
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMinutes

/**
 * The event types section, wired to its ViewModel.
 *
 * ⚠️ IT READS ON EVERY APPEARANCE (the `LaunchedEffect`), not once in the ViewModel's `init`: coming
 * back from an event type that was renamed, turned off or deleted shows the list as it now is.
 */
@Composable
fun SchedulingEventTypesRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingEventTypesViewModel = viewModel(
        key = "scheduling-event-types-${deps.workspaceId}",
        factory = SchedulingEventTypesViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.writes.dialog.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingEventTypesScreen(
        state = state,
        onBack = deps.onBack,
        onRefresh = @DontMemoize { viewModel.load() },
        onOpen = @DontMemoize { deps.navigate(SchedulingAdminRoutes.eventType(deps.workspaceId, it.slug)) },
        onCreate = @DontMemoize { viewModel.create() },
    )
    SchedulingEventTypeDialogs(dialog, viewModel.writes)
}

/**
 * Every event type, one page a customer can book per row, ported from district-ios's
 * `SchedulingEventTypesView`.
 *
 * ⛔ CREATE IS DRAWN ONLY FOR A MEMBER WHO MAY MANAGE AND ONLY OVER A LIST IN HAND (see
 * [SchedulingEventTypesViewModel.create]). A row opens the event type's own screen.
 */
@Composable
fun SchedulingEventTypesScreen(
    state: SchedulingEventTypesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (SchedulingEventType) -> Unit,
    onCreate: () -> Unit,
) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.EVENT_TYPES.title),
        rootTag = SCHEDULING_EVENT_TYPES_ROOT_TAG,
        onBack = onBack,
        onRefresh = onRefresh,
    ) {
        // ⚠️ Loading is the `else` arm: an exhaustive sealed `when` in a composable leaves a branch no
        // test can reach.
        when (val list = state.list) {
            is SectionState.Failed ->
                SchedulingSectionFailure(list.failure, onRefresh, SCHEDULING_EVENT_TYPES_FAILURE_TAG)
            is SectionState.Ready -> {
                if (state.access.canManage) {
                    DistrictButton(
                        text = stringResource(R.string.scheduling_admin_event_types_create_title),
                        onClick = onCreate,
                        modifier = Modifier.testTag(SCHEDULING_EVENT_TYPES_CREATE_TAG),
                    )
                }
                if (list.value.isEmpty()) {
                    SchedulingEmptyState(
                        title = stringResource(R.string.scheduling_admin_event_types_empty_title),
                        body = stringResource(R.string.scheduling_admin_event_types_empty_body),
                        tag = SCHEDULING_EVENT_TYPES_EMPTY_TAG,
                    )
                } else {
                    EventTypeRows(list.value, state.access.publicHost, onOpen)
                }
            }
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_EVENT_TYPES_LOADING_TAG)
        }
    }
}

/**
 * ⚠️ THE BOOKING PAGE LINE IS "Not public" WHEREVER NO CUSTOMER CAN REACH THE PAGE (see
 * [schedulingBookingPage]); the reference computes it and leaves it off the row, this draws it.
 */
@Composable
private fun EventTypeRows(
    rows: List<SchedulingEventType>,
    publicHost: String?,
    onOpen: (SchedulingEventType) -> Unit,
) {
    DistrictCard {
        rows.forEachIndexed { index, item ->
            if (index > 0) DistrictRowDivider()
            val status = schedulingEventTypeState(item)
            DistrictListRow(
                title = item.name,
                subtitle = stringResource(
                    R.string.scheduling_admin_event_types_subtitle,
                    schedulingMinutes(item.durationMinutes),
                    schedulingLocationLabel(item.locationType),
                ),
                onClick = @DontMemoize { onOpen(item) },
                trailing = { SchedulingStatusBadge(status.label, status.kind) },
                modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPES_ROW_TAG-${item.slug}"),
            )
            SchedulingHint(
                text = schedulingBookingPage(item, publicHost)
                    ?: stringResource(R.string.scheduling_admin_event_types_not_public),
                // Room under the booking page's address before the row's divider (visual QA AB17).
                modifier = Modifier
                    .padding(
                        start = DistrictTheme.spacing.gutter,
                        end = DistrictTheme.spacing.gutter,
                        bottom = DistrictTheme.spacing.row,
                    )
                    .testTag("$SCHEDULING_EVENT_TYPES_PAGE_TAG-${item.slug}"),
            )
        }
    }
}

const val SCHEDULING_EVENT_TYPES_ROOT_TAG: String = "district-scheduling-event-types"
const val SCHEDULING_EVENT_TYPES_LOADING_TAG: String = "district-scheduling-event-types-loading"
const val SCHEDULING_EVENT_TYPES_FAILURE_TAG: String = "district-scheduling-event-types-failure"
const val SCHEDULING_EVENT_TYPES_EMPTY_TAG: String = "district-scheduling-event-types-empty"
const val SCHEDULING_EVENT_TYPES_CREATE_TAG: String = "district-scheduling-event-types-create"
const val SCHEDULING_EVENT_TYPES_ROW_TAG: String = "district-scheduling-event-types-row"
const val SCHEDULING_EVENT_TYPES_PAGE_TAG: String = "district-scheduling-event-types-page"
