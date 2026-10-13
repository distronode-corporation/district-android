package com.distronode.districtai.ui.scheduling.admin.bookings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminRoutes
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingEmptyState
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusBadge
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.overview.bookingEventTypeName
import com.distronode.districtai.ui.scheduling.admin.overview.bookingWho
import com.distronode.districtai.ui.scheduling.admin.schedulingBookingStatus
import com.distronode.districtai.ui.scheduling.admin.schedulingDateTime

/** The bookings section, wired to its ViewModel. */
@Composable
fun SchedulingBookingsRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingBookingsViewModel = viewModel(
        key = "scheduling-bookings-${deps.workspaceId}",
        factory = SchedulingBookingsViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.writes.dialog.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingBookingsScreen(
        state = state,
        actions = SchedulingBookingsActions(
            onBack = deps.onBack,
            onRefresh = viewModel::load,
            onSelectView = viewModel::selectView,
            onLoadMore = viewModel::loadMore,
            onOpen = { deps.navigate(SchedulingAdminRoutes.booking(deps.workspaceId, it.id)) },
            isActionable = viewModel::isActionable,
            onCancel = viewModel.writes.cancel::open,
            onReschedule = viewModel.writes.reschedule::open,
            onReassign = viewModel.writes.reassign::open,
        ),
    )
    SchedulingBookingDialogs(dialog, viewModel.writes, state.timezone)
}

/** Everything the bookings screen can ask for. */
class SchedulingBookingsActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onSelectView: (SchedulingBookingView) -> Unit,
    val onLoadMore: () -> Unit,
    val onOpen: (SchedulingBooking) -> Unit,
    val isActionable: (SchedulingBooking) -> Boolean,
    val onCancel: (SchedulingBooking) -> Unit,
    val onReschedule: (SchedulingBooking) -> Unit,
    val onReassign: (SchedulingBooking) -> Unit,
)

/**
 * Every booking, filtered by view, a page at a time, ported from district-ios's
 * `SchedulingBookingsView`.
 *
 * ⛔ THE WRITE CONTROLS ARE DRAWN ONLY FOR A MEMBER WHO MAY MANAGE AND ONLY ON A BOOKING THAT IS
 * CONFIRMED AND NOT YET OVER; Reschedule needs an event type, and Reassign needs the scheduler's own
 * admin bit. A control the server would refuse is not drawn.
 */
@Composable
fun SchedulingBookingsScreen(state: SchedulingBookingsUiState, actions: SchedulingBookingsActions) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.BOOKINGS.title),
        rootTag = SCHEDULING_BOOKINGS_ROOT_TAG,
        onBack = actions.onBack,
        onRefresh = actions.onRefresh,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            Text(
                text = stringResource(R.string.scheduling_admin_common_times_in, state.timezone),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
            SchedulingChoiceRow(
                options = SchedulingBookingView.entries.map { it to stringResource(it.label) },
                selected = state.view,
                onSelect = actions.onSelectView,
                tagPrefix = SCHEDULING_BOOKINGS_VIEW_TAG,
            )
        }
        when (val list = state.list) {
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_BOOKINGS_LOADING_TAG)
            is SectionState.Failed ->
                SchedulingSectionFailure(list.failure, actions.onRefresh, SCHEDULING_BOOKINGS_FAILURE_TAG)
            is SectionState.Ready -> if (state.rows.isEmpty()) {
                SchedulingEmptyState(
                    title = stringResource(R.string.scheduling_admin_bookings_empty_title),
                    body = stringResource(state.view.empty),
                    tag = SCHEDULING_BOOKINGS_EMPTY_TAG,
                )
            } else {
                BookingRows(state, actions)
            }
        }
    }
}

@Composable
private fun BookingRows(state: SchedulingBookingsUiState, actions: SchedulingBookingsActions) {
    DistrictCard {
        state.rows.forEachIndexed { index, booking ->
            if (index > 0) DistrictRowDivider()
            val status = schedulingBookingStatus(booking.status)
            DistrictListRow(
                title = schedulingDateTime(booking.startAt, state.timezone),
                subtitle = stringResource(
                    R.string.scheduling_admin_bookings_subtitle,
                    bookingWho(booking),
                    bookingEventTypeName(booking, state.eventTypes),
                    booking.hostName ?: stringResource(R.string.scheduling_admin_common_none),
                ),
                onClick = { actions.onOpen(booking) },
                trailing = { SchedulingStatusBadge(status.label, status.kind) },
                modifier = Modifier.testTag("$SCHEDULING_BOOKINGS_ROW_TAG-${booking.id}"),
            )
            if (state.canManage && actions.isActionable(booking)) BookingActions(booking, state, actions)
        }
    }
    state.appendFailure?.let { SchedulingFailureLine(it) }
    if (!state.isEnd) {
        DistrictButton(
            text = if (state.loadingMore) {
                stringResource(R.string.scheduling_admin_common_loading_more)
            } else {
                stringResource(R.string.scheduling_admin_common_load_more)
            },
            onClick = actions.onLoadMore,
            variant = ButtonVariant.Secondary,
            enabled = !state.loadingMore,
            modifier = Modifier.testTag(SCHEDULING_BOOKINGS_MORE_TAG),
        )
    }
}

@Composable
private fun BookingActions(
    booking: SchedulingBooking,
    state: SchedulingBookingsUiState,
    actions: SchedulingBookingsActions,
) {
    Row(
        modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        val id = booking.id
        ActionButton(R.string.scheduling_admin_bookings_cancel_confirm, "$SCHEDULING_BOOKINGS_CANCEL_TAG-$id") {
            actions.onCancel(booking)
        }
        if (booking.eventTypeSlug != null) {
            val tag = "$SCHEDULING_BOOKINGS_RESCHEDULE_TAG-$id"
            ActionButton(R.string.scheduling_admin_bookings_reschedule_action, tag) {
                actions.onReschedule(booking)
            }
        }
        if (state.schedulerIsAdmin) {
            ActionButton(R.string.scheduling_admin_bookings_reassign_action, "$SCHEDULING_BOOKINGS_REASSIGN_TAG-$id") {
                actions.onReassign(booking)
            }
        }
    }
}

@Composable
private fun ActionButton(@StringRes label: Int, tag: String, onClick: () -> Unit) {
    DistrictButton(
        text = stringResource(label),
        onClick = onClick,
        variant = ButtonVariant.Ghost,
        size = ButtonSize.Sm,
        modifier = Modifier.testTag(tag),
    )
}

const val SCHEDULING_BOOKINGS_ROOT_TAG: String = "district-scheduling-bookings"
const val SCHEDULING_BOOKINGS_VIEW_TAG: String = "district-scheduling-bookings-view"
const val SCHEDULING_BOOKINGS_LOADING_TAG: String = "district-scheduling-bookings-loading"
const val SCHEDULING_BOOKINGS_FAILURE_TAG: String = "district-scheduling-bookings-failure"
const val SCHEDULING_BOOKINGS_EMPTY_TAG: String = "district-scheduling-bookings-empty"
const val SCHEDULING_BOOKINGS_ROW_TAG: String = "district-scheduling-bookings-row"
const val SCHEDULING_BOOKINGS_MORE_TAG: String = "district-scheduling-bookings-more"
const val SCHEDULING_BOOKINGS_CANCEL_TAG: String = "district-scheduling-bookings-cancel"
const val SCHEDULING_BOOKINGS_RESCHEDULE_TAG: String = "district-scheduling-bookings-reschedule"
const val SCHEDULING_BOOKINGS_REASSIGN_TAG: String = "district-scheduling-bookings-reassign"
