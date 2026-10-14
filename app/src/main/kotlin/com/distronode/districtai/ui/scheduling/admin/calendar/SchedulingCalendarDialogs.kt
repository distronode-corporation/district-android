package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingToggleRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.isWorking

/** Whichever calendar dialog [writes] has open. */
@Composable
fun SchedulingCalendarDialogs(dialog: CalendarDialog?, writes: SchedulingCalendarWrites) {
    when (dialog) {
        is CalendarDialog.Caldav -> CaldavDialog(dialog, writes.caldav, @DontMemoize { writes.close() })
        is CalendarDialog.Picker -> PickerDialog(dialog, writes.picker, @DontMemoize { writes.close() })
        // ⚠️ `else` is no dialog: an exhaustive dispatch in a composable leaves an unreachable branch.
        else -> Unit
    }
}

/**
 * "Connect a CalDAV calendar": where it lives, the server address when that needs one, the
 * username and the app password.
 *
 * ⛔ THE PASSWORD BOX IS A MASKED FIELD, NEVER OFFERED TO THE KEYBOARD'S SUGGESTIONS, and its test
 * handle names the box, never the value.
 */
@Composable
private fun CaldavDialog(dialog: CalendarDialog.Caldav, flow: CaldavConnectFlow, onClose: () -> Unit) {
    val form = dialog.form
    val enabled = !dialog.state.isWorking
    fun errorFor(field: CaldavField): Int? = form.fieldMessage?.takeIf { form.erroredField == field }
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_calendar_caldav_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_calendar_caldav_connect_action),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { flow.submit() },
        onCancel = onClose,
        tag = SCHEDULING_CALDAV_DIALOG_TAG,
        confirmTag = SCHEDULING_CALDAV_CONFIRM_TAG,
        onRetry = @DontMemoize { flow.submit() },
    ) {
        SchedulingChoiceRow(
            options = CaldavPreset.entries.map { it to stringResource(it.label) },
            selected = form.preset,
            onSelect = @DontMemoize { preset -> flow.edit { it.copy(preset = preset) } },
            tagPrefix = SCHEDULING_CALDAV_PRESET_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_calendar_caldav_preset_label),
        )
        SchedulingHint(stringResource(form.preset.note))
        if (form.preset.needsServerUrl) {
            SchedulingTextField(
                label = stringResource(R.string.scheduling_admin_calendar_caldav_server_url_label),
                value = form.serverUrl,
                onValueChange = @DontMemoize { value -> flow.edit { it.copy(serverUrl = value) } },
                tag = SCHEDULING_CALDAV_SERVER_TAG,
                enabled = enabled,
                hint = stringResource(R.string.scheduling_admin_calendar_caldav_server_url_hint),
                error = errorFor(CaldavField.SERVER_URL)?.let { stringResource(it) },
                kind = SchedulingFieldKind.URL,
            )
        }
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_calendar_caldav_username_label),
            value = form.username,
            onValueChange = @DontMemoize { value -> flow.edit { it.copy(username = value) } },
            tag = SCHEDULING_CALDAV_USERNAME_TAG,
            enabled = enabled,
            error = errorFor(CaldavField.USERNAME)?.let { stringResource(it) },
        )
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_calendar_caldav_password_label),
            value = form.appPassword,
            onValueChange = @DontMemoize { value -> flow.edit { it.copy(appPassword = value) } },
            tag = SCHEDULING_CALDAV_PASSWORD_TAG,
            enabled = enabled,
            hint = stringResource(R.string.scheduling_admin_calendar_caldav_password_hint),
            error = errorFor(CaldavField.APP_PASSWORD)?.let { stringResource(it) },
            kind = SchedulingFieldKind.PASSWORD,
        )
    }
}

/**
 * "Calendars in <account>": which are checked for conflicts, and which receives new bookings.
 *
 * ⛔ THE WHOLE LIST GOES BACK ON SAVE, and the destination is a choice with no "none" option,
 * because no call clears a destination.
 */
@Composable
private fun PickerDialog(dialog: CalendarDialog.Picker, flow: CalendarPickerFlow, onClose: () -> Unit) {
    val enabled = !dialog.state.isWorking
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_calendar_calendars_title, dialog.connection.accountEmail),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_calendar_calendars_save_action),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { flow.save() },
        onCancel = onClose,
        tag = SCHEDULING_CALENDAR_PICKER_DIALOG_TAG,
        confirmTag = SCHEDULING_CALENDAR_PICKER_CONFIRM_TAG,
        confirmEnabled = dialog.rows is SectionState.Ready,
        onRetry = @DontMemoize { flow.save() },
    ) {
        when (val rows = dialog.rows) {
            is SectionState.Failed -> SchedulingSectionFailure(
                failure = rows.failure,
                onRetry = @DontMemoize { flow.load() },
                tag = SCHEDULING_CALENDAR_PICKER_FAILURE_TAG,
            )
            is SectionState.Ready -> {
                SchedulingHint(stringResource(R.string.scheduling_admin_calendar_calendars_conflicts_hint))
                rows.value.forEach { row ->
                    SchedulingToggleRow(
                        label = row.name,
                        checked = row.checkConflicts == true,
                        onCheckedChange = @DontMemoize { flow.setCheckConflicts(row.id, it) },
                        tag = "$SCHEDULING_CALENDAR_PICKER_CONFLICT_TAG-${row.id}",
                        enabled = enabled,
                    )
                }
                SchedulingChoiceRow(
                    options = dialog.destinationChoices.map { it.id to it.name },
                    selected = dialog.destinationId,
                    onSelect = @DontMemoize { flow.chooseDestination(it) },
                    tagPrefix = SCHEDULING_CALENDAR_PICKER_DESTINATION_TAG,
                    enabled = enabled,
                    label = stringResource(R.string.scheduling_admin_calendar_destination),
                )
                SchedulingHint(stringResource(R.string.scheduling_admin_calendar_calendars_destination_hint))
            }
            // ⚠️ `else` IS LOADING: an exhaustive sealed `when` here leaves a branch no test reaches.
            else -> SchedulingSectionLoading(SCHEDULING_CALENDAR_PICKER_LOADING_TAG, rows = 1)
        }
    }
}

/**
 * Unlink one account. ⚠️ The keep button reads "Keep it connected" rather than "Cancel", because two
 * neutral-sounding words beside "Disconnect" is where a mis-tap comes from.
 */
@Composable
fun SchedulingDisconnectDialog(
    connection: SchedulingCalendarConnection,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    SchedulingConfirmDialog(
        title = stringResource(R.string.scheduling_admin_calendar_disconnect_prompt, connection.accountEmail),
        body = stringResource(R.string.scheduling_admin_calendar_disconnect_confirm),
        confirmLabel = stringResource(R.string.scheduling_admin_calendar_disconnect_action),
        cancelLabel = stringResource(R.string.scheduling_admin_calendar_disconnect_keep),
        onConfirm = onConfirm,
        onCancel = onCancel,
        tag = SCHEDULING_CALENDAR_DISCONNECT_DIALOG_TAG,
        confirmTag = SCHEDULING_CALENDAR_DISCONNECT_CONFIRM_TAG,
    )
}

const val SCHEDULING_CALDAV_DIALOG_TAG: String = "district-scheduling-caldav"
const val SCHEDULING_CALDAV_CONFIRM_TAG: String = "district-scheduling-caldav-confirm"
const val SCHEDULING_CALDAV_PRESET_TAG: String = "district-scheduling-caldav-preset"
const val SCHEDULING_CALDAV_SERVER_TAG: String = "district-scheduling-caldav-server"
const val SCHEDULING_CALDAV_USERNAME_TAG: String = "district-scheduling-caldav-username"
const val SCHEDULING_CALDAV_PASSWORD_TAG: String = "district-scheduling-caldav-password"
const val SCHEDULING_CALENDAR_PICKER_DIALOG_TAG: String = "district-scheduling-calendar-picker"
const val SCHEDULING_CALENDAR_PICKER_CONFIRM_TAG: String = "district-scheduling-calendar-picker-confirm"
const val SCHEDULING_CALENDAR_PICKER_LOADING_TAG: String = "district-scheduling-calendar-picker-loading"
const val SCHEDULING_CALENDAR_PICKER_FAILURE_TAG: String = "district-scheduling-calendar-picker-failure"
const val SCHEDULING_CALENDAR_PICKER_CONFLICT_TAG: String = "district-scheduling-calendar-picker-conflict"
const val SCHEDULING_CALENDAR_PICKER_DESTINATION_TAG: String = "district-scheduling-calendar-picker-destination"
const val SCHEDULING_CALENDAR_DISCONNECT_DIALOG_TAG: String = "district-scheduling-calendar-disconnect-dialog"
const val SCHEDULING_CALENDAR_DISCONNECT_CONFIRM_TAG: String = "district-scheduling-calendar-disconnect-confirm"
