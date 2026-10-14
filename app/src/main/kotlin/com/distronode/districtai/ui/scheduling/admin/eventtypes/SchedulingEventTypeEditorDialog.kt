package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingToggleRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.isWorking

/** Whichever event-type dialog [writes] has open. */
@Composable
fun SchedulingEventTypeDialogs(dialog: EventTypeDialog?, writes: SchedulingEventTypeWrites) {
    val close: () -> Unit = @DontMemoize { writes.close() }
    when (dialog) {
        is EventTypeDialog.Editor -> SchedulingEventTypeEditorDialog(dialog, writes.editor, close)
        is EventTypeDialog.Actions -> SchedulingEventTypeActionsDialog(dialog, writes.actions, close)
        is EventTypeDialog.Hosts -> SchedulingEventTypeHostsDialog(dialog, writes.hosts, close)
        is EventTypeDialog.Questions -> SchedulingEventTypeQuestionsDialog(dialog, writes.questions, close)
        null -> Unit
    }
}

/**
 * Create an event type, or edit the open one, after district-ios's `SchedulingEventTypeEditorSheet`.
 *
 * ⛔ THE CREATE FORM IS THREE FIELDS AND THE EDIT FORM IS THE FULL ONE: `eventTypes.create` refuses
 * most of what `eventTypes.patch` takes. ⚠️ The slug is shown on an edit and never editable: it is
 * the public address, and the patch cannot change it.
 */
@Composable
fun SchedulingEventTypeEditorDialog(
    dialog: EventTypeDialog.Editor,
    editor: EventTypeEditorFlow,
    onClose: () -> Unit,
) {
    val slug = dialog.slug
    val creating = slug == null
    val enabled = !dialog.state.isWorking
    SchedulingWriteDialog(
        title = stringResource(
            if (creating) {
                R.string.scheduling_admin_event_types_create_title
            } else {
                R.string.scheduling_admin_event_types_edit_title
            },
        ),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_common_save),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { editor.save() },
        onCancel = onClose,
        tag = SCHEDULING_EVENT_TYPE_EDITOR_TAG,
        confirmTag = SCHEDULING_EVENT_TYPE_EDITOR_SAVE_TAG,
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_event_types_name_label),
            value = dialog.form.name,
            onValueChange = @DontMemoize { value -> editor.edit { it.copy(name = value) } },
            tag = SCHEDULING_EVENT_TYPE_EDITOR_NAME_TAG,
            enabled = enabled,
        )
        if (slug == null) {
            NumberField(
                R.string.scheduling_admin_event_types_duration_label,
                R.string.scheduling_admin_event_types_duration_hint,
                dialog.form.duration,
                SCHEDULING_EVENT_TYPE_EDITOR_DURATION_TAG,
                enabled,
            ) @DontMemoize { value -> editor.edit { it.copy(duration = value) } }
            LocationChoice(dialog, editor, enabled)
        } else {
            EditFields(dialog, slug, editor, enabled)
        }
        // ⚠️ The client's refusal (nothing was sent) and the write's own failure are separate lines.
        SchedulingRejection(dialog.validation?.let { stringResource(it) })
    }
}

@Composable
private fun EditFields(dialog: EventTypeDialog.Editor, slug: String, editor: EventTypeEditorFlow, enabled: Boolean) {
    val form = dialog.form
    SchedulingReadOnlyRow(
        label = stringResource(R.string.scheduling_admin_event_types_booking_page_label),
        value = slug,
    )
    SchedulingHint(stringResource(R.string.scheduling_admin_event_types_booking_page_hint))
    SchedulingTextField(
        label = stringResource(R.string.scheduling_admin_event_types_description_label),
        value = form.description,
        onValueChange = @DontMemoize { value -> editor.edit { it.copy(description = value) } },
        tag = SCHEDULING_EVENT_TYPE_EDITOR_DESCRIPTION_TAG,
        enabled = enabled,
        hint = stringResource(R.string.scheduling_admin_event_types_description_hint),
        kind = SchedulingFieldKind.MULTILINE,
    )
    NumberField(
        R.string.scheduling_admin_event_types_duration_label,
        R.string.scheduling_admin_event_types_duration_hint,
        form.duration,
        SCHEDULING_EVENT_TYPE_EDITOR_DURATION_TAG,
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(duration = value) } }
    NumberField(
        R.string.scheduling_admin_event_types_interval_label,
        R.string.scheduling_admin_event_types_interval_hint,
        form.interval,
        SCHEDULING_EVENT_TYPE_EDITOR_INTERVAL_TAG,
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(interval = value) } }
    LocationChoice(dialog, editor, enabled)
    LocationValueField(form, editor, enabled)
    Toggles(form, editor, enabled)
    Limits(form, editor, enabled)
}

/** ⚠️ The web's four, plus the stored type when it is not one of them (labelled as sent). */
@Composable
private fun LocationChoice(dialog: EventTypeDialog.Editor, editor: EventTypeEditorFlow, enabled: Boolean) {
    SchedulingChoiceRow(
        options = SchedulingLocationType.choices(dialog.storedLocation).map { it to schedulingLocationLabel(it) },
        selected = dialog.form.location,
        onSelect = @DontMemoize { value -> editor.edit { it.copy(location = value) } },
        tagPrefix = SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_TAG,
        enabled = enabled,
        label = stringResource(R.string.scheduling_admin_event_types_location_label),
    )
}

/** ⛔ Drawn only where the value means something: the scheduler generates the join link itself otherwise. */
@Composable
private fun LocationValueField(form: SchedulingEventTypeForm, editor: EventTypeEditorFlow, enabled: Boolean) {
    form.locationField?.let { field ->
        SchedulingTextField(
            label = stringResource(field),
            value = form.locationValue,
            onValueChange = @DontMemoize { value -> editor.edit { it.copy(locationValue = value) } },
            tag = SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_VALUE_TAG,
            enabled = enabled,
        )
    }
}

@Composable
private fun Toggles(form: SchedulingEventTypeForm, editor: EventTypeEditorFlow, enabled: Boolean) {
    SchedulingToggleRow(
        label = stringResource(R.string.scheduling_admin_event_types_active_toggle),
        checked = form.isActive,
        onCheckedChange = @DontMemoize { on -> editor.edit { it.copy(isActive = on) } },
        tag = SCHEDULING_EVENT_TYPE_EDITOR_ACTIVE_TAG,
        enabled = enabled,
    )
    SchedulingToggleRow(
        label = stringResource(R.string.scheduling_admin_event_types_listed_toggle),
        checked = form.isPublic,
        onCheckedChange = @DontMemoize { on -> editor.edit { it.copy(isPublic = on) } },
        tag = SCHEDULING_EVENT_TYPE_EDITOR_LISTED_TAG,
        enabled = enabled,
    )
    SchedulingToggleRow(
        label = stringResource(R.string.scheduling_admin_event_types_show_taken_toggle),
        checked = form.showTakenSlots,
        onCheckedChange = @DontMemoize { on -> editor.edit { it.copy(showTakenSlots = on) } },
        tag = SCHEDULING_EVENT_TYPE_EDITOR_TAKEN_TAG,
        enabled = enabled,
    )
}

@Composable
private fun Limits(form: SchedulingEventTypeForm, editor: EventTypeEditorFlow, enabled: Boolean) {
    NumberField(
        R.string.scheduling_admin_event_types_buffer_before_label,
        R.string.scheduling_admin_event_types_buffer_before_hint,
        form.bufferBefore,
        "$SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG-buffer-before",
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(bufferBefore = value) } }
    NumberField(
        R.string.scheduling_admin_event_types_buffer_after_label,
        R.string.scheduling_admin_event_types_buffer_after_hint,
        form.bufferAfter,
        "$SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG-buffer-after",
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(bufferAfter = value) } }
    NumberField(
        R.string.scheduling_admin_event_types_min_notice_label,
        R.string.scheduling_admin_event_types_min_notice_hint,
        form.minNotice,
        "$SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG-notice",
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(minNotice = value) } }
    NumberField(
        R.string.scheduling_admin_event_types_max_future_label,
        R.string.scheduling_admin_event_types_no_limit_hint,
        form.maxFutureDays,
        "$SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG-future",
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(maxFutureDays = value) } }
    NumberField(
        R.string.scheduling_admin_event_types_max_active_label,
        R.string.scheduling_admin_event_types_no_limit_hint,
        form.maxActiveBookings,
        "$SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG-active",
        enabled,
    ) @DontMemoize { value -> editor.edit { it.copy(maxActiveBookings = value) } }
}

@Composable
private fun NumberField(
    @StringRes label: Int,
    @StringRes hint: Int,
    value: String,
    tag: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    SchedulingTextField(
        label = stringResource(label),
        value = value,
        onValueChange = onChange,
        tag = tag,
        enabled = enabled,
        hint = stringResource(hint),
        kind = SchedulingFieldKind.NUMBER,
    )
}

const val SCHEDULING_EVENT_TYPE_EDITOR_TAG: String = "district-scheduling-event-type-editor"
const val SCHEDULING_EVENT_TYPE_EDITOR_SAVE_TAG: String = "district-scheduling-event-type-editor-save"
const val SCHEDULING_EVENT_TYPE_EDITOR_NAME_TAG: String = "district-scheduling-event-type-editor-name"
const val SCHEDULING_EVENT_TYPE_EDITOR_DESCRIPTION_TAG: String = "district-scheduling-event-type-editor-description"
const val SCHEDULING_EVENT_TYPE_EDITOR_DURATION_TAG: String = "district-scheduling-event-type-editor-duration"
const val SCHEDULING_EVENT_TYPE_EDITOR_INTERVAL_TAG: String = "district-scheduling-event-type-editor-interval"
const val SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_TAG: String = "district-scheduling-event-type-editor-location"
const val SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_VALUE_TAG: String =
    "district-scheduling-event-type-editor-location-value"
const val SCHEDULING_EVENT_TYPE_EDITOR_ACTIVE_TAG: String = "district-scheduling-event-type-editor-active"
const val SCHEDULING_EVENT_TYPE_EDITOR_LISTED_TAG: String = "district-scheduling-event-type-editor-listed"
const val SCHEDULING_EVENT_TYPE_EDITOR_TAKEN_TAG: String = "district-scheduling-event-type-editor-taken"
const val SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG: String = "district-scheduling-event-type-editor-limit"
