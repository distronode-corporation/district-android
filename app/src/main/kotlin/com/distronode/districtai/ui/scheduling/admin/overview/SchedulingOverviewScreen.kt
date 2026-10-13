package com.distronode.districtai.ui.scheduling.admin.overview

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusBadge
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingBookingStatus
import com.distronode.districtai.ui.scheduling.admin.schedulingBookingUrl
import com.distronode.districtai.ui.scheduling.admin.schedulingProviderLabel
import com.distronode.districtai.ui.scheduling.admin.valueOrNull
import java.time.Instant

/** The overview section, wired to its ViewModel. */
@Composable
fun SchedulingOverviewRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingOverviewViewModel = viewModel(
        key = "scheduling-overview-${deps.workspaceId}",
        factory = SchedulingOverviewViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingOverviewScreen(state = state, onBack = deps.onBack, onRefresh = viewModel::load)
}

/**
 * The booking desk and what is coming up, ported from district-ios's `SchedulingOverviewView`.
 *
 * ⚠️ EACH DESK ROW HAS ITS OWN LOADING AND FAILURE: the five reads are independent, and a calendar
 * read that failed must not hide working hours that loaded.
 */
@Composable
fun SchedulingOverviewScreen(
    state: SchedulingOverviewUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    now: Instant = Instant.now(),
) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.OVERVIEW.title),
        rootTag = SCHEDULING_OVERVIEW_ROOT_TAG,
        onBack = onBack,
        onRefresh = onRefresh,
    ) {
        SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_overview_desk_eyebrow)) {
            Text(
                text = stringResource(R.string.scheduling_admin_common_times_in, state.timezone),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
            BookingPageRow(state)
            DistrictRowDivider()
            CalendarRow(state)
            DistrictRowDivider()
            HoursRow(state)
            DistrictRowDivider()
            EventTypesRow(state)
            DistrictRowDivider()
            NextBookingRow(state, now)
        }
        ComingUp(state, now)
    }
}

@Composable
private fun BookingPageRow(state: SchedulingOverviewUiState) {
    DeskRow(R.string.scheduling_admin_overview_desk_booking_page, state.eventTypes, "booking-page") { items ->
        val host = state.publicHost
        val bookable = bookableEventTypes(items)
        when {
            host == null -> Hint(stringResource(R.string.scheduling_admin_overview_desk_no_host))
            bookable.isEmpty() -> Hint(stringResource(R.string.scheduling_admin_overview_desk_no_event_types))
            else -> bookable.forEach { Value(schedulingBookingUrl(host, it.slug)) }
        }
    }
}

@Composable
private fun CalendarRow(state: SchedulingOverviewUiState) {
    DeskRow(R.string.scheduling_admin_overview_desk_calendar, state.calendar, "calendar") { status ->
        if (status.connections.isEmpty()) {
            Hint(stringResource(R.string.scheduling_admin_overview_desk_no_calendar))
        } else {
            status.connections.forEach { connection ->
                Value(
                    stringResource(
                        R.string.scheduling_admin_overview_calendar_line,
                        schedulingProviderLabel(connection.provider),
                        connection.accountEmail,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HoursRow(state: SchedulingOverviewUiState) {
    DeskRow(R.string.scheduling_admin_overview_desk_hours, state.rules, "hours") { rules ->
        val summary = workingHoursSummary(rules)
        if (summary == null) Hint(stringResource(R.string.scheduling_admin_overview_desk_no_hours)) else Value(summary)
    }
}

@Composable
private fun EventTypesRow(state: SchedulingOverviewUiState) {
    DeskRow(R.string.scheduling_admin_overview_desk_event_types, state.eventTypes, "event-types") { items ->
        val summary = eventTypesSummary(items)
        if (summary == null) {
            Hint(stringResource(R.string.scheduling_admin_overview_desk_no_event_types))
        } else {
            Value(summary)
            val names = items.filter { it.isActive != false }.map { it.name }
            if (names.isNotEmpty()) Hint(names.joinToString(", "))
        }
    }
}

@Composable
private fun NextBookingRow(state: SchedulingOverviewUiState, now: Instant) {
    DeskRow(R.string.scheduling_admin_overview_desk_next_booking, state.bookings, "next-booking") { bookings ->
        val next = bookings.firstOrNull()
        if (next == null) {
            Hint(stringResource(R.string.scheduling_admin_overview_desk_no_bookings))
        } else {
            Value(
                stringResource(
                    R.string.scheduling_admin_overview_booking_line,
                    bookingWhen(next.startAt, state.timezone, now)
                        ?: stringResource(R.string.scheduling_admin_common_unknown_time),
                    bookingWho(next),
                    bookingEventTypeName(next, state.eventTypes.valueOrNull.orEmpty()),
                ),
            )
        }
    }
}

@Composable
private fun ComingUp(state: SchedulingOverviewUiState, now: Instant) {
    SchedulingCard(
        eyebrow = stringResource(R.string.scheduling_admin_overview_coming_up_eyebrow),
        modifier = Modifier.testTag(SCHEDULING_OVERVIEW_COMING_UP_TAG),
    ) {
        when (val bookings = state.bookings) {
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_OVERVIEW_LOADING_TAG)
            is SectionState.Failed -> Failure(bookings.failure.message.resolve())
            is SectionState.Ready -> if (bookings.value.isEmpty()) {
                Hint(stringResource(R.string.scheduling_admin_overview_coming_up_empty))
            } else {
                val types = state.eventTypes.valueOrNull.orEmpty()
                bookings.value.forEachIndexed { index, booking ->
                    if (index > 0) DistrictRowDivider()
                    val status = schedulingBookingStatus(booking.status)
                    DistrictListRow(
                        title = bookingWhen(booking.startAt, state.timezone, now)
                            ?: stringResource(R.string.scheduling_admin_common_unknown_time),
                        subtitle = stringResource(
                            R.string.scheduling_admin_overview_subtitle,
                            bookingWho(booking),
                            bookingEventTypeName(booking, types),
                        ),
                        trailing = { SchedulingStatusBadge(status.label, status.kind) },
                        modifier = Modifier.testTag("$SCHEDULING_OVERVIEW_ROW_TAG-${booking.id}"),
                    )
                }
            }
        }
    }
}

/** A desk row: its label, then a skeleton, the failure, or [content]. */
@Composable
private fun <T> DeskRow(@StringRes title: Int, state: SectionState<T>, tag: String, content: @Composable (T) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().testTag("$SCHEDULING_OVERVIEW_DESK_TAG-$tag")) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelMedium,
            color = DistrictTheme.colors.mutedForeground,
        )
        when (state) {
            SectionState.Loading -> SkeletonBlock()
            is SectionState.Failed -> Failure(state.failure.message.resolve())
            is SectionState.Ready -> content(state.value)
        }
    }
}

@Composable
private fun Value(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = DistrictTheme.colors.foreground)
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = DistrictTheme.colors.mutedForeground)
}

@Composable
private fun Failure(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = DistrictTheme.colors.destructive)
}

const val SCHEDULING_OVERVIEW_ROOT_TAG: String = "district-scheduling-overview"
const val SCHEDULING_OVERVIEW_DESK_TAG: String = "district-scheduling-overview-desk"
const val SCHEDULING_OVERVIEW_COMING_UP_TAG: String = "district-scheduling-overview-coming-up"
const val SCHEDULING_OVERVIEW_LOADING_TAG: String = "district-scheduling-overview-loading"
const val SCHEDULING_OVERVIEW_ROW_TAG: String = "district-scheduling-overview-row"
