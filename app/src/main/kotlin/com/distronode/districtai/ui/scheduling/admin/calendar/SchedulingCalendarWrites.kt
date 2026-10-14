package com.distronode.districtai.ui.scheduling.admin.calendar

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.connectionCalendars
import com.distronode.districtai.core.data.setConnectionCalendars
import com.distronode.districtai.core.data.setDestinationConnection
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import com.distronode.districtai.ui.scheduling.admin.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The calendar dialog that is open, if any. */
sealed interface CalendarDialog {
    /** Which opening this is, so a late answer cannot land in a dialog opened after it. */
    val key: Long
    val state: WriteState

    /** Connect a CalDAV account. See [CaldavForm] for what is never kept. */
    data class Caldav(
        override val key: Long,
        val form: CaldavForm = CaldavForm(),
        override val state: WriteState = WriteState.Idle,
    ) : CalendarDialog

    /**
     * Which calendars of one connection are checked for conflicts, and which receives bookings.
     *
     * @property rows ⚠️ an EMPTY list is a real answer (an account with no calendars), not a failure.
     */
    data class Picker(
        override val key: Long,
        val connection: SchedulingCalendarConnection,
        val rows: SectionState<List<SchedulingCalendarSelection>> = SectionState.Loading,
        override val state: WriteState = WriteState.Idle,
    ) : CalendarDialog {
        /**
         * ⛔ ONLY WRITABLE CALENDARS MAY RECEIVE BOOKINGS, and `writable == null` counts as writable:
         * the flag is absent on a row the fork did not describe, and reading absence as "read only"
         * would hide a primary calendar.
         */
        val destinationChoices: List<SchedulingCalendarSelection>
            get() = rows.valueOrNull.orEmpty().filter { it.writable != false }

        val destinationId: String? get() = rows.valueOrNull?.firstOrNull { it.isDestination == true }?.id
    }
}

/** The one open calendar dialog, shared by CalDAV and the picker. */
class CalendarDialogHost {
    private val _dialog = MutableStateFlow<CalendarDialog?>(null)
    val dialog: StateFlow<CalendarDialog?> = _dialog.asStateFlow()

    val current: CalendarDialog? get() = _dialog.value

    private var opened = 0L

    /** A key no dialog opened before has. */
    fun nextKey(): Long = ++opened

    fun show(dialog: CalendarDialog) {
        _dialog.value = dialog
    }

    /** ⛔ Ignored while a write is on its way: the dialog cannot be closed under it. */
    fun close() {
        if (_dialog.value?.state?.isWorking == true) return
        _dialog.value = null
    }

    /** Change the open dialog only if it is still a [D] from the opening [key]. */
    inline fun <reified D : CalendarDialog> update(key: Long, change: (D) -> CalendarDialog) {
        val open = current as? D ?: return
        if (open.key != key) return
        show(change(open))
    }
}

/**
 * CalDAV and the calendar picker, ported from district-ios's `SchedulingCaldavConnectModel` and
 * `SchedulingCalendarPickerModel`.
 *
 * @param onChanged told after every successful write, so the screen re-reads.
 */
class SchedulingCalendarWrites(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    onChanged: () -> Unit,
) {
    private val host = CalendarDialogHost()
    val dialog: StateFlow<CalendarDialog?> = host.dialog

    val caldav = CaldavConnectFlow(host, scope, admin, workspaceId, onChanged)
    val picker = CalendarPickerFlow(host, scope, admin, workspaceId, onChanged)

    /** Close whatever is open. Ignored while a write is on its way. */
    fun close() = host.close()
}

/**
 * Choose which calendars are checked and which receives bookings, for one connection.
 *
 * ⛔ THE PUT IS A REPLACE, NOT A PATCH: a calendar left out is a calendar turned off. So the whole
 * list is read, edited in place and sent back, never an array built from the one row that was
 * touched. ⛔ And the four flags stay optional: a row nobody touched keeps its nulls, because absent
 * and `false` are different instructions to the fork.
 *
 * ⚠️ TWO WRITES ON SAVE. The PUT stores the selection; `calendar.connections.destination` then
 * moves the account-level destination to this account, belt and braces on the one choice whose
 * silent failure is "I chose it and bookings still go somewhere else".
 */
class CalendarPickerFlow(
    private val host: CalendarDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    /** ⛔ Takes the connection, not its id: the ops are addressed by provider and account too. */
    fun open(connection: SchedulingCalendarConnection) {
        host.show(CalendarDialog.Picker(host.nextKey(), connection))
        load()
    }

    fun load() {
        val dialog = host.current as? CalendarDialog.Picker ?: return
        if (dialog.state.isWorking) return
        host.show(dialog.copy(rows = SectionState.Loading))
        scope.launch {
            val connection = dialog.connection
            val rows = admin.connectionCalendars(
                workspaceId,
                connection.id,
                connection.provider,
                connection.accountEmail,
            )
            host.update<CalendarDialog.Picker>(dialog.key) { it.copy(rows = rows.toSectionState()) }
        }
    }

    /** ⚠️ An explicit boolean onto the one row; any number of calendars may be checked. */
    fun setCheckConflicts(id: String, on: Boolean) = edit { row ->
        if (row.id == id) row.copy(checkConflicts = on) else row
    }

    /**
     * ⛔ A MOVE, NOT A TOGGLE: every row is set explicitly, true on one and false on the rest, and
     * nothing offers to clear it, because no call clears a destination.
     */
    fun chooseDestination(id: String) = edit { row -> row.copy(isDestination = row.id == id) }

    private fun edit(change: (SchedulingCalendarSelection) -> SchedulingCalendarSelection) {
        val dialog = host.current as? CalendarDialog.Picker ?: return
        val rows = dialog.rows.valueOrNull ?: return
        if (dialog.state.isWorking) return
        val state = if (dialog.state is WriteState.Failed) WriteState.Idle else dialog.state
        host.show(dialog.copy(rows = SectionState.Ready(rows.map(change)), state = state))
    }

    fun save() {
        val dialog = host.current as? CalendarDialog.Picker ?: return
        val rows = dialog.rows.valueOrNull ?: return
        if (dialog.state.isWorking) return
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val outcome = send(dialog.connection, rows)
            host.update<CalendarDialog.Picker>(dialog.key) { it.copy(state = outcome) }
            if (outcome is WriteState.Done) onChanged()
        }
    }

    private suspend fun send(
        connection: SchedulingCalendarConnection,
        rows: List<SchedulingCalendarSelection>,
    ): WriteState {
        val put = admin.setConnectionCalendars(
            workspaceId,
            connection.id,
            connection.provider,
            rows,
            connection.accountEmail,
        )
        if (put is SchedulingAdminResult.Failed) return WriteState.Failed(put.error.toFailureText())
        if (rows.any { it.isDestination == true }) {
            val moved = admin.setDestinationConnection(
                workspaceId,
                connection.id,
                connection.provider,
                connection.accountEmail,
            )
            if (moved is SchedulingAdminResult.Failed) return WriteState.Failed(moved.error.toFailureText())
        }
        return WriteState.Done(UiText.Resource(R.string.scheduling_admin_calendar_calendars_saved))
    }
}
