package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.RemovalState
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingNoticeLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingProviderLabel

/** The calendar section, wired to its ViewModel. */
@Composable
fun SchedulingCalendarRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingCalendarViewModel = viewModel(
        key = "scheduling-calendar-${deps.workspaceId}",
        factory = SchedulingCalendarViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val removal by viewModel.disconnect.state.collectAsStateWithLifecycle()
    val dialog by viewModel.writes.dialog.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    // ⚠️ The browser leg tells the app nothing, so coming back is what triggers the re-read.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    SchedulingCalendarScreen(state, removal, CalendarRouteActions(viewModel, deps))
    SchedulingCalendarDialogs(dialog, viewModel.writes)
    removal.pending?.let { pending ->
        val disconnect = viewModel.disconnect
        SchedulingDisconnectDialog(
            connection = pending,
            onConfirm = @DontMemoize { disconnect.confirm() },
            onCancel = @DontMemoize { disconnect.cancel() },
        )
    }
}

/** The screen's requests, sent to the ViewModel. */
private class CalendarRouteActions(
    private val viewModel: SchedulingCalendarViewModel,
    private val deps: SchedulingAdminDeps,
) : SchedulingCalendarActions {
    override fun back() = deps.onBack()
    override fun refresh() = viewModel.load()
    override fun connect(provider: String) = viewModel.connect(provider, deps.openUrl)
    override fun dismissConnectFailure() = viewModel.dismissConnectFailure()
    override fun caldav() = viewModel.writes.caldav.open()
    override fun choose(connection: SchedulingCalendarConnection) = viewModel.writes.picker.open(connection)
    override fun disconnect(connection: SchedulingCalendarConnection) = viewModel.disconnect.ask(connection)
    override fun dismissDisconnectFailure() = viewModel.disconnect.dismissFailure()
}

/**
 * The calendar accounts bookings are written to and checked against, ported from district-ios's
 * `SchedulingCalendarView`.
 *
 * ⛔ EVERY CONTROL HERE IS DRAWN FOR EVERY MEMBER: the `calendar.*` writes are the member's own.
 */
@Composable
fun SchedulingCalendarScreen(
    state: SchedulingCalendarUiState,
    removal: RemovalState<SchedulingCalendarConnection>,
    actions: SchedulingCalendarActions,
) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.CALENDAR.title),
        rootTag = SCHEDULING_CALENDAR_ROOT_TAG,
        onBack = @DontMemoize { actions.back() },
        onRefresh = @DontMemoize { actions.refresh() },
    ) {
        when (val status = state.status) {
            is SectionState.Failed -> SchedulingSectionFailure(
                failure = status.failure,
                onRetry = @DontMemoize { actions.refresh() },
                tag = SCHEDULING_CALENDAR_FAILURE_TAG,
            )
            is SectionState.Ready -> {
                status.value.connections.forEach { connection ->
                    ConnectionCard(connection, state.calendars[connection.id], removal.busy, actions)
                }
                ConnectCard(status.value, state, removal, actions)
            }
            // ⚠️ `else` IS LOADING: an exhaustive sealed `when` here leaves a branch no test reaches.
            else -> SchedulingSectionLoading(SCHEDULING_CALENDAR_LOADING_TAG)
        }
    }
}

@Composable
private fun ConnectionCard(
    connection: SchedulingCalendarConnection,
    calendars: List<SchedulingCalendarSelection>?,
    removing: Boolean,
    actions: SchedulingCalendarActions,
) {
    SchedulingCard(
        eyebrow = schedulingProviderLabel(connection.provider),
        modifier = Modifier.testTag("$SCHEDULING_CALENDAR_CONNECTION_TAG-${connection.id}"),
    ) {
        SchedulingReadOnlyRow(stringResource(R.string.scheduling_admin_calendar_account), connection.accountEmail)
        SchedulingReadOnlyRow(
            stringResource(R.string.scheduling_admin_calendar_conflicts),
            conflictSummary(connection, calendars),
        )
        SchedulingReadOnlyRow(
            stringResource(R.string.scheduling_admin_calendar_destination),
            destinationSummary(connection, calendars),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_calendar_calendars_open),
                onClick = @DontMemoize { actions.choose(connection) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$SCHEDULING_CALENDAR_CHOOSE_TAG-${connection.id}"),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_calendar_disconnect_action),
                onClick = @DontMemoize { actions.disconnect(connection) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = !removing,
                modifier = Modifier.testTag("$SCHEDULING_CALENDAR_DISCONNECT_TAG-${connection.id}"),
            )
        }
    }
}

/**
 * ⛔ THE OAuth LEG LEAVES THE APP AND COMES BACK THROUGH A RE-READ, while CalDAV is a form that
 * answers in-band and is offered whether or not the instance has OAuth providers configured.
 */
@Composable
private fun ConnectCard(
    status: SchedulingCalendarStatus,
    state: SchedulingCalendarUiState,
    removal: RemovalState<SchedulingCalendarConnection>,
    actions: SchedulingCalendarActions,
) {
    SchedulingCard(stringResource(R.string.scheduling_admin_calendar_connect_eyebrow)) {
        SchedulingHint(connectSentence(status))
        state.zoom?.takeIf { it.configured }?.let { zoom ->
            SchedulingReadOnlyRow(
                label = schedulingProviderLabel(ZOOM),
                value = stringResource(
                    if (zoom.connected) {
                        R.string.scheduling_admin_calendar_connected
                    } else {
                        R.string.scheduling_admin_calendar_not_connected
                    },
                ),
            )
        }
        ProviderButtons(status, state.connecting, actions)
        state.connectFailure?.let {
            SchedulingFailureLine(it, onDismiss = @DontMemoize { actions.dismissConnectFailure() })
        }
        DistrictButton(
            text = stringResource(R.string.scheduling_admin_calendar_caldav_title),
            onClick = @DontMemoize { actions.caldav() },
            variant = ButtonVariant.Secondary,
            modifier = Modifier.testTag(SCHEDULING_CALENDAR_CALDAV_TAG),
        )
        removal.failure?.let {
            SchedulingFailureLine(it, onDismiss = @DontMemoize { actions.dismissDisconnectFailure() })
        }
        // ⛔ DRAWN FROM THE ROW THAT WAS REMOVED, NOT FROM THE RE-READ: a member left with no
        // destination has new bookings written nowhere, and the re-read has nothing left to ask.
        if (removal.removed?.isDestination == true) {
            SchedulingNoticeLine(UiText.Resource(R.string.scheduling_admin_calendar_disconnected_last_destination))
        }
    }
}

/**
 * ⚠️ AN INSTANCE THAT IS CONFIGURED BUT OFFERS NEITHER OAuth PROVIDER GETS ONE SENTENCE AND NO
 * BUTTON: a control that could only fail is worse than none. An unconfigured instance has already
 * said so in [connectSentence], so it is not said twice.
 */
@Composable
private fun ProviderButtons(
    status: SchedulingCalendarStatus,
    connecting: Boolean,
    actions: SchedulingCalendarActions,
) {
    val providers = SchedulingCalendarViewModel.offeredProviders(status)
    if (providers.isEmpty()) {
        if (status.configured) SchedulingHint(stringResource(R.string.scheduling_admin_calendar_not_configured))
        return
    }
    SchedulingHint(stringResource(R.string.scheduling_admin_calendar_connect_hand_off_note))
    providers.forEach { provider ->
        DistrictButton(
            text = stringResource(
                R.string.scheduling_admin_calendar_connect_provider,
                schedulingProviderLabel(provider),
            ),
            onClick = @DontMemoize { actions.connect(provider) },
            variant = ButtonVariant.Secondary,
            enabled = !connecting,
            modifier = Modifier.testTag("$SCHEDULING_CALENDAR_CONNECT_TAG-$provider"),
        )
    }
}

/** ⚠️ "Not set up on this scheduler" is not a fault: it is a different fact from "none connected yet". */
@Composable
private fun connectSentence(status: SchedulingCalendarStatus): String = stringResource(
    when {
        !status.configured -> R.string.scheduling_admin_calendar_not_configured
        status.connections.isEmpty() -> R.string.scheduling_admin_calendar_none_connected
        else -> R.string.scheduling_admin_calendar_connect_another
    },
)

/**
 * What is checked for conflicts on one connection, after district-core-swift's
 * `SchedulingCalendarFormat.conflictSummary`.
 *
 * ⛔ THREE ANSWERS: no calendar read means the connection's own flag ("Checked" or "Not checked"),
 * a read with nothing checked means "None", and otherwise the checked calendars' names.
 */
@Composable
internal fun conflictSummary(
    connection: SchedulingCalendarConnection,
    calendars: List<SchedulingCalendarSelection>?,
): String {
    if (calendars == null) {
        return stringResource(
            if (connection.checkConflicts) {
                R.string.scheduling_admin_calendar_conflicts_checked
            } else {
                R.string.scheduling_admin_calendar_conflicts_not_checked
            },
        )
    }
    val names = calendars.filter { it.checkConflicts == true }.map { it.name }
    return if (names.isEmpty()) {
        stringResource(R.string.scheduling_admin_calendar_conflicts_none)
    } else {
        names.joinToString()
    }
}

/**
 * Where bookings are written. ⚠️ "This account" is not "No calendar in this account": the first
 * means the fork writes somewhere in this account without being told which calendar.
 */
@Composable
internal fun destinationSummary(
    connection: SchedulingCalendarConnection,
    calendars: List<SchedulingCalendarSelection>?,
): String = calendars?.firstOrNull { it.isDestination == true }?.name ?: stringResource(
    if (connection.isDestination) {
        R.string.scheduling_admin_calendar_destination_this_account
    } else {
        R.string.scheduling_admin_calendar_destination_none
    },
)

private const val ZOOM = "zoom"

const val SCHEDULING_CALENDAR_ROOT_TAG: String = "district-scheduling-calendar"
const val SCHEDULING_CALENDAR_LOADING_TAG: String = "district-scheduling-calendar-loading"
const val SCHEDULING_CALENDAR_FAILURE_TAG: String = "district-scheduling-calendar-failure"
const val SCHEDULING_CALENDAR_CONNECTION_TAG: String = "district-scheduling-calendar-connection"
const val SCHEDULING_CALENDAR_CHOOSE_TAG: String = "district-scheduling-calendar-choose"
const val SCHEDULING_CALENDAR_DISCONNECT_TAG: String = "district-scheduling-calendar-disconnect"
const val SCHEDULING_CALENDAR_CONNECT_TAG: String = "district-scheduling-calendar-connect"
const val SCHEDULING_CALENDAR_CALDAV_TAG: String = "district-scheduling-calendar-caldav"
