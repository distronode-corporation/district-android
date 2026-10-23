package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.schedulingWeekday

/**
 * The weekly hours editor: seven days, Monday first, each with as many windows as the member wants.
 * The Android shape of district-ios's `SchedulingAvailabilityRulesSheet`.
 *
 * ⚠️ THE TIMES ARE TEXT FIELDS, NOT A TIME PICKER, as in the reference: they are wall-clock times in
 * the member's scheduler zone, and a picker bound to an instant would have to invent a day and a zone
 * to hold one. They show and read the language's own spelling (« 9 h 30 ») and are stored as `HH:mm`
 * ([SchedulingClockField]).
 */
@Composable
fun SchedulingHoursRulesDialog(editor: SchedulingRulesEditorState, actions: SchedulingHoursRulesEditor) {
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_hours_edit_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_common_save),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = editor.state,
        onConfirm = @DontMemoize { actions.save() },
        onCancel = @DontMemoize { actions.close() },
        tag = SCHEDULING_HOURS_RULES_DIALOG_TAG,
        confirmTag = SCHEDULING_HOURS_RULES_SAVE_TAG,
        confirmEnabled = editor.canSave,
    ) {
        val load = editor.load
        when (load) {
            is SectionState.Failed ->
                SchedulingSectionFailure(
                    load.failure,
                    @DontMemoize { actions.load() },
                    SCHEDULING_HOURS_RULES_FAILURE_TAG,
                )
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_HOURS_RULES_LOADING_TAG, rows = 1)
            is SectionState.Ready -> {
                val errors = editor.errors
                editor.week.forEachIndexed { day, ranges ->
                    DayEditor(day, ranges, errors[day], actions, enabled = !editor.state.isWorking)
                }
            }
        }
    }
}

@Composable
private fun DayEditor(
    day: Int,
    ranges: List<SchedulingHoursDraft>,
    errors: List<SchedulingRangeError?>,
    actions: SchedulingHoursRulesEditor,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier.testTag("$SCHEDULING_HOURS_RULES_DAY_TAG-$day"),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Text(
            text = schedulingWeekday(day),
            style = MaterialTheme.typography.titleSmall,
            color = DistrictTheme.colors.foreground,
        )
        if (ranges.isEmpty()) SchedulingHint(stringResource(R.string.scheduling_admin_hours_day_not_bookable))
        ranges.forEachIndexed { index, range -> RangeEditor(day, index, range, errors[index], actions, enabled) }
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_hours_add_range),
                onClick = @DontMemoize { actions.addRange(day) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_HOURS_RULES_ADD_TAG-$day"),
            )
            if (day in SCHEDULING_WEEKDAY_ROWS && ranges.isNotEmpty()) {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_hours_copy_to_weekdays),
                    onClick = @DontMemoize { actions.copyToWeekdays(day) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = enabled,
                    modifier = Modifier.testTag("$SCHEDULING_HOURS_RULES_COPY_TAG-$day"),
                )
            }
        }
    }
}

/** One window: start, end, Remove, and why it is refused. Tags carry the day and the position. */
@Composable
private fun RangeEditor(
    day: Int,
    index: Int,
    range: SchedulingHoursDraft,
    error: SchedulingRangeError?,
    actions: SchedulingHoursRulesEditor,
    enabled: Boolean,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                SchedulingClockField(
                    label = stringResource(R.string.scheduling_admin_hours_start_label),
                    wire = range.start,
                    onWireChange = @DontMemoize { actions.setTimes(day, range.key, it, range.end) },
                    tag = "$SCHEDULING_HOURS_RULES_START_TAG-$day-$index",
                    enabled = enabled,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                SchedulingClockField(
                    label = stringResource(R.string.scheduling_admin_hours_end_label),
                    wire = range.end,
                    onWireChange = @DontMemoize { actions.setTimes(day, range.key, range.start, it) },
                    tag = "$SCHEDULING_HOURS_RULES_END_TAG-$day-$index",
                    enabled = enabled,
                )
            }
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_hours_remove_range),
                onClick = @DontMemoize { actions.removeRange(day, range.key) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_HOURS_RULES_REMOVE_TAG-$day-$index"),
            )
        }
        SchedulingRejection(error?.let { stringResource(it.message) })
    }
}

const val SCHEDULING_HOURS_RULES_DIALOG_TAG: String = "district-scheduling-hours-rules"
const val SCHEDULING_HOURS_RULES_SAVE_TAG: String = "district-scheduling-hours-rules-save"
const val SCHEDULING_HOURS_RULES_LOADING_TAG: String = "district-scheduling-hours-rules-loading"
const val SCHEDULING_HOURS_RULES_FAILURE_TAG: String = "district-scheduling-hours-rules-failure"
const val SCHEDULING_HOURS_RULES_DAY_TAG: String = "district-scheduling-hours-rules-day"
const val SCHEDULING_HOURS_RULES_ADD_TAG: String = "district-scheduling-hours-rules-add"
const val SCHEDULING_HOURS_RULES_COPY_TAG: String = "district-scheduling-hours-rules-copy"
const val SCHEDULING_HOURS_RULES_START_TAG: String = "district-scheduling-hours-rules-start"
const val SCHEDULING_HOURS_RULES_END_TAG: String = "district-scheduling-hours-rules-end"
const val SCHEDULING_HOURS_RULES_REMOVE_TAG: String = "district-scheduling-hours-rules-remove"
