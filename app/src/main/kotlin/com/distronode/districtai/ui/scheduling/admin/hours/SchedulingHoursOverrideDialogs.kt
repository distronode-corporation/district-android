package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.ui.scheduling.admin.RemovalState
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingRemoval
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Add one override: a day or days unavailable, or one day of custom hours. The Android shape of
 * district-ios's add sheet in `SchedulingAvailabilityOverridesSheet`.
 *
 * ⚠️ THE DATES ARE PICKED FROM A CALENDAR, NOT TYPED AS district-ios TYPES THEM: an override is often
 * weeks ahead (a holiday), which a day-at-a-time stepper makes a chore, and a picked date cannot be
 * malformed. The web uses a date picker too. ⚠️ The confirm button is off once the override is added,
 * so a second press cannot add it twice; an edit makes it live again for the next one.
 *
 * @param today the member's today, where the calendar opens when no date is chosen yet.
 */
@Composable
fun SchedulingOverrideAddDialog(
    add: SchedulingOverrideAddState,
    writes: SchedulingHoursOverrideWrites,
    today: LocalDate,
) {
    var picking by remember { mutableStateOf<OverrideDateField?>(null) }
    val form = add.form
    val enabled = !add.state.isWorking
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_hours_override_add_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_hours_override_add),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = add.state,
        onConfirm = @DontMemoize { writes.submit() },
        onCancel = @DontMemoize { writes.close() },
        tag = SCHEDULING_OVERRIDE_ADD_DIALOG_TAG,
        confirmTag = SCHEDULING_OVERRIDE_ADD_CONFIRM_TAG,
        confirmEnabled = add.state !is WriteState.Done,
    ) {
        SchedulingChoiceRow(
            options = SchedulingOverrideReason.entries.map { it to stringResource(it.label) },
            selected = form.reason,
            onSelect = @DontMemoize { reason -> writes.edit { it.copy(reason = reason) } },
            tagPrefix = SCHEDULING_OVERRIDE_KIND_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_hours_kind_label),
        )
        DateRow(
            label = stringResource(
                if (form.isCustomHours) {
                    R.string.scheduling_admin_hours_date_label
                } else {
                    R.string.scheduling_admin_hours_first_day_label
                },
            ),
            date = form.date,
            tag = SCHEDULING_OVERRIDE_DATE_TAG,
            enabled = enabled,
            onPick = @DontMemoize { picking = OverrideDateField.FIRST },
        )
        if (form.isCustomHours) {
            CustomHours(form, writes, enabled)
        } else {
            DateRow(
                label = stringResource(R.string.scheduling_admin_hours_last_day_label),
                date = form.endDate,
                tag = SCHEDULING_OVERRIDE_END_DATE_TAG,
                enabled = enabled,
                onPick = @DontMemoize { picking = OverrideDateField.LAST },
                hint = stringResource(R.string.scheduling_admin_hours_last_day_hint),
                onClear = (@DontMemoize { writes.edit { it.copy(endDate = null) } }).takeIf { form.endDate != null },
            )
        }
        SchedulingRejection(add.rejection?.let { stringResource(it) })
    }
    picking?.let { field ->
        val initial = when (field) {
            OverrideDateField.FIRST -> form.date
            OverrideDateField.LAST -> form.endDate ?: form.date
        }
        DatePickDialog(
            initial = initial ?: today,
            onPick = @DontMemoize { picked ->
                writes.edit {
                    when (field) {
                        OverrideDateField.FIRST -> it.copy(date = picked)
                        OverrideDateField.LAST -> it.copy(endDate = picked)
                    }
                }
                picking = null
            },
            onDismiss = @DontMemoize { picking = null },
        )
    }
}

/**
 * "Delete this override?" for the row waiting for an answer, if any.
 *
 * ⚠️ ONE DIALOG, TWO SENTENCES, as in district-ios: a range says how many days go back to the weekly
 * hours, because that is the number somebody is agreeing to.
 */
@Composable
fun SchedulingOverrideDeleteDialog(
    removal: RemovalState<SchedulingOverrideRow>,
    flow: SchedulingRemoval<SchedulingOverrideRow>,
) {
    val row = removal.pending ?: return
    SchedulingConfirmDialog(
        title = stringResource(R.string.scheduling_admin_hours_override_delete_title),
        body = if (row.days > 1) {
            pluralStringResource(R.plurals.scheduling_admin_hours_override_delete_group_body, row.days, row.days)
        } else {
            stringResource(R.string.scheduling_admin_hours_override_delete_single_body)
        },
        confirmLabel = stringResource(R.string.scheduling_admin_hours_override_delete_confirm),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        onConfirm = @DontMemoize { flow.confirm() },
        onCancel = @DontMemoize { flow.cancel() },
        tag = SCHEDULING_OVERRIDE_DELETE_DIALOG_TAG,
        confirmTag = SCHEDULING_OVERRIDE_DELETE_CONFIRM_TAG,
    )
}

/**
 * The date a calendar's selection names. ⚠️ The picker answers midnight UTC of the chosen day, so it
 * is read back in UTC, never in the device's zone; no selection (an emptied typed date) is none.
 */
fun schedulingPickedDate(millis: Long?): LocalDate? =
    millis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }

/** Which of the form's two dates the calendar is open for. */
private enum class OverrideDateField { FIRST, LAST }

@Composable
private fun DateRow(
    label: String,
    date: LocalDate?,
    tag: String,
    enabled: Boolean,
    onPick: () -> Unit,
    hint: String? = null,
    onClear: (() -> Unit)? = null,
) {
    Column {
        Eyebrow(label)
        Row(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = date?.let { schedulingDate(it) } ?: stringResource(R.string.scheduling_admin_common_not_set),
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.weight(1f).testTag("$tag-value"),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_hours_pick_date),
                onClick = onPick,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$tag-pick"),
            )
            onClear?.let {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_hours_clear_date),
                    onClick = it,
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = enabled,
                    modifier = Modifier.testTag("$tag-clear"),
                )
            }
        }
        hint?.let { SchedulingHint(it) }
    }
}

@Composable
private fun CustomHours(form: SchedulingOverrideForm, writes: SchedulingHoursOverrideWrites, enabled: Boolean) {
    Column {
        Eyebrow(stringResource(R.string.scheduling_admin_hours_hours_label))
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            Box(modifier = Modifier.weight(1f)) {
                SchedulingClockField(
                    label = stringResource(R.string.scheduling_admin_hours_start_label),
                    wire = form.start,
                    onWireChange = @DontMemoize { value -> writes.edit { it.copy(start = value) } },
                    tag = SCHEDULING_OVERRIDE_START_TAG,
                    enabled = enabled,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SchedulingClockField(
                    label = stringResource(R.string.scheduling_admin_hours_end_label),
                    wire = form.end,
                    onWireChange = @DontMemoize { value -> writes.edit { it.copy(end = value) } },
                    tag = SCHEDULING_OVERRIDE_END_TAG,
                    enabled = enabled,
                )
            }
        }
    }
}

/** The Material calendar in its own dialog, opening on [initial]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(initial: LocalDate, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SCHEDULING_OVERRIDE_PICKER_TAG),
        confirmButton = {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_hours_pick_date_confirm),
                onClick = @DontMemoize { onPick(schedulingPickedDate(state.selectedDateMillis)) },
                variant = ButtonVariant.Ghost,
                modifier = Modifier.testTag(SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG),
            )
        },
        dismissButton = {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_common_cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Ghost,
                modifier = Modifier.testTag(SCHEDULING_OVERRIDE_PICKER_CANCEL_TAG),
            )
        },
    ) {
        DatePicker(state = state)
    }
}

const val SCHEDULING_OVERRIDE_ADD_DIALOG_TAG: String = "district-scheduling-override-add"
const val SCHEDULING_OVERRIDE_ADD_CONFIRM_TAG: String = "district-scheduling-override-add-confirm"
const val SCHEDULING_OVERRIDE_KIND_TAG: String = "district-scheduling-override-kind"
const val SCHEDULING_OVERRIDE_DATE_TAG: String = "district-scheduling-override-date"
const val SCHEDULING_OVERRIDE_END_DATE_TAG: String = "district-scheduling-override-end-date"
const val SCHEDULING_OVERRIDE_START_TAG: String = "district-scheduling-override-start"
const val SCHEDULING_OVERRIDE_END_TAG: String = "district-scheduling-override-end"
const val SCHEDULING_OVERRIDE_PICKER_TAG: String = "district-scheduling-override-picker"
const val SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG: String = "district-scheduling-override-picker-confirm"
const val SCHEDULING_OVERRIDE_PICKER_CANCEL_TAG: String = "district-scheduling-override-picker-cancel"
const val SCHEDULING_OVERRIDE_DELETE_DIALOG_TAG: String = "district-scheduling-override-delete"
const val SCHEDULING_OVERRIDE_DELETE_CONFIRM_TAG: String = "district-scheduling-override-delete-confirm"
