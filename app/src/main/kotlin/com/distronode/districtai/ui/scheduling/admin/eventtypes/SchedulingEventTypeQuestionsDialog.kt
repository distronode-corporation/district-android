package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.foundation.layout.Arrangement
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
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.ui.scheduling.admin.SchedulingChoiceRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingToggleRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.isWorking

/**
 * The booking questions, the form that edits one, or the question about deleting one; after
 * district-ios's `SchedulingEventTypeQuestionsSheet`. One of the three is drawn at a time.
 *
 * ⛔ THE LIST HAS NO SAVE: every add, edit, move and delete is its own request. Its confirm button
 * is "Add question".
 */
@Composable
fun SchedulingEventTypeQuestionsDialog(
    dialog: EventTypeDialog.Questions,
    questions: EventTypeQuestionsFlow,
    onClose: () -> Unit,
) {
    val editing = dialog.editing
    val deleting = dialog.confirmingDelete
    when {
        editing != null -> QuestionEditor(dialog, editing, questions)
        deleting != null -> SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_event_types_question_delete_title),
            body = stringResource(R.string.scheduling_admin_event_types_question_delete_body),
            confirmLabel = stringResource(R.string.scheduling_admin_event_types_question_delete_confirm),
            cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
            onConfirm = @DontMemoize { questions.delete() },
            onCancel = @DontMemoize { questions.confirmDelete(null) },
            tag = SCHEDULING_EVENT_TYPE_QUESTION_DELETE_DIALOG_TAG,
            confirmTag = SCHEDULING_EVENT_TYPE_QUESTION_DELETE_CONFIRM_TAG,
        )
        else -> QuestionList(dialog, questions, onClose)
    }
}

@Composable
private fun QuestionList(dialog: EventTypeDialog.Questions, questions: EventTypeQuestionsFlow, onClose: () -> Unit) {
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_event_types_questions_title),
        subtitle = null,
        confirmLabel = stringResource(R.string.scheduling_admin_event_types_question_add),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { questions.beginEdit(null) },
        onCancel = onClose,
        tag = SCHEDULING_EVENT_TYPE_QUESTIONS_DIALOG_TAG,
        confirmTag = SCHEDULING_EVENT_TYPE_QUESTION_ADD_TAG,
    ) {
        val load = dialog.load
        if (load == SectionState.Loading) {
            SchedulingSectionLoading(SCHEDULING_EVENT_TYPE_QUESTIONS_DIALOG_LOADING_TAG, rows = 1)
        } else {
            // ⚠️ A failed re-read after a write keeps the last list and says so above it.
            if (load is SectionState.Failed) {
                SchedulingFailureLine(load.failure, onRetry = @DontMemoize { questions.load() })
            }
            if (dialog.questions.isEmpty()) {
                SchedulingHint(stringResource(R.string.scheduling_admin_event_types_questions_empty))
            }
            dialog.questions.forEach { QuestionRow(it, questions, enabled = !dialog.state.isWorking) }
        }
    }
}

@Composable
private fun QuestionRow(question: SchedulingQuestion, questions: EventTypeQuestionsFlow, enabled: Boolean) {
    Column(modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_QUESTION_ROW_TAG-${question.id}")) {
        Text(
            text = question.label,
            style = MaterialTheme.typography.titleSmall,
            color = DistrictTheme.colors.foreground,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SchedulingHint(
                text = schedulingWireLabel(question.type, SCHEDULING_QUESTION_TYPES),
                modifier = Modifier.weight(1f),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_event_types_edit),
                onClick = @DontMemoize { questions.beginEdit(question) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_QUESTION_EDIT_TAG-${question.id}"),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_event_types_question_delete),
                onClick = @DontMemoize { questions.confirmDelete(question) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_QUESTION_DELETE_TAG-${question.id}"),
            )
        }
    }
}

/** One question's form. ⚠️ Reordering is its Position field: lower numbers are asked first. */
@Composable
private fun QuestionEditor(
    dialog: EventTypeDialog.Questions,
    form: SchedulingQuestionForm,
    questions: EventTypeQuestionsFlow,
) {
    val adding = form.id == null
    val enabled = !dialog.state.isWorking
    SchedulingWriteDialog(
        title = stringResource(
            if (adding) {
                R.string.scheduling_admin_event_types_question_add
            } else {
                R.string.scheduling_admin_event_types_question_edit_title
            },
        ),
        subtitle = null,
        confirmLabel = stringResource(
            if (adding) {
                R.string.scheduling_admin_event_types_question_add
            } else {
                R.string.scheduling_admin_event_types_question_save
            },
        ),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { questions.submit() },
        onCancel = @DontMemoize { questions.cancelEdit() },
        tag = SCHEDULING_EVENT_TYPE_QUESTION_EDITOR_TAG,
        confirmTag = SCHEDULING_EVENT_TYPE_QUESTION_SAVE_TAG,
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_event_types_question_label_label),
            value = form.label,
            onValueChange = @DontMemoize { value -> questions.editForm { it.copy(label = value) } },
            tag = SCHEDULING_EVENT_TYPE_QUESTION_LABEL_TAG,
            enabled = enabled,
        )
        SchedulingChoiceRow(
            options = SCHEDULING_QUESTION_TYPES.map { (wire, label) -> wire to stringResource(label) },
            selected = form.type,
            onSelect = @DontMemoize { type -> questions.editForm { it.copy(type = type) } },
            tagPrefix = SCHEDULING_EVENT_TYPE_QUESTION_TYPE_TAG,
            enabled = enabled,
            label = stringResource(R.string.scheduling_admin_event_types_question_type_label),
        )
        SchedulingToggleRow(
            label = stringResource(R.string.scheduling_admin_event_types_question_required_toggle),
            checked = form.required,
            onCheckedChange = @DontMemoize { on -> questions.editForm { it.copy(required = on) } },
            tag = SCHEDULING_EVENT_TYPE_QUESTION_REQUIRED_TAG,
            enabled = enabled,
        )
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_event_types_question_position_label),
            value = form.position,
            onValueChange = @DontMemoize { value -> questions.editForm { it.copy(position = value) } },
            tag = SCHEDULING_EVENT_TYPE_QUESTION_POSITION_TAG,
            enabled = enabled,
            hint = stringResource(R.string.scheduling_admin_event_types_question_position_hint),
            kind = SchedulingFieldKind.NUMBER,
        )
        if (form.isSelect) Options(form, questions, enabled)
        SchedulingRejection(dialog.validation?.let { stringResource(it) })
    }
}

/** ⛔ Drawn only for a select: a list on any other type is one nothing will ever show. */
@Composable
private fun Options(form: SchedulingQuestionForm, questions: EventTypeQuestionsFlow, enabled: Boolean) {
    Eyebrow(stringResource(R.string.scheduling_admin_event_types_question_options_label))
    SchedulingHint(stringResource(R.string.scheduling_admin_event_types_question_options_hint))
    form.options.forEachIndexed { index, option ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = option,
                style = MaterialTheme.typography.bodyMedium,
                color = DistrictTheme.colors.foreground,
                modifier = Modifier.weight(1f),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_event_types_remove),
                onClick = @DontMemoize { questions.editForm { it.withoutOption(option) } },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = enabled,
                modifier = Modifier.testTag("$SCHEDULING_EVENT_TYPE_QUESTION_OPTION_REMOVE_TAG-$index"),
            )
        }
    }
    SchedulingTextField(
        label = stringResource(R.string.scheduling_admin_event_types_question_option_new),
        value = form.optionDraft,
        onValueChange = @DontMemoize { value -> questions.editForm { it.copy(optionDraft = value) } },
        tag = SCHEDULING_EVENT_TYPE_QUESTION_OPTION_TAG,
        enabled = enabled,
    )
    DistrictButton(
        text = stringResource(R.string.scheduling_admin_event_types_question_add_option),
        onClick = @DontMemoize { questions.editForm { it.withOption() } },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Sm,
        enabled = enabled,
        modifier = Modifier.testTag(SCHEDULING_EVENT_TYPE_QUESTION_ADD_OPTION_TAG),
    )
}

const val SCHEDULING_EVENT_TYPE_QUESTIONS_DIALOG_TAG: String = "district-scheduling-event-type-questions-dialog"
const val SCHEDULING_EVENT_TYPE_QUESTIONS_DIALOG_LOADING_TAG: String =
    "district-scheduling-event-type-questions-dialog-loading"
const val SCHEDULING_EVENT_TYPE_QUESTION_ADD_TAG: String = "district-scheduling-event-type-question-add"
const val SCHEDULING_EVENT_TYPE_QUESTION_ROW_TAG: String = "district-scheduling-event-type-question"
const val SCHEDULING_EVENT_TYPE_QUESTION_EDIT_TAG: String = "district-scheduling-event-type-question-edit"
const val SCHEDULING_EVENT_TYPE_QUESTION_DELETE_TAG: String = "district-scheduling-event-type-question-delete"
const val SCHEDULING_EVENT_TYPE_QUESTION_DELETE_DIALOG_TAG: String =
    "district-scheduling-event-type-question-delete-dialog"
const val SCHEDULING_EVENT_TYPE_QUESTION_DELETE_CONFIRM_TAG: String =
    "district-scheduling-event-type-question-delete-confirm"
const val SCHEDULING_EVENT_TYPE_QUESTION_EDITOR_TAG: String = "district-scheduling-event-type-question-editor"
const val SCHEDULING_EVENT_TYPE_QUESTION_SAVE_TAG: String = "district-scheduling-event-type-question-save"
const val SCHEDULING_EVENT_TYPE_QUESTION_LABEL_TAG: String = "district-scheduling-event-type-question-label"
const val SCHEDULING_EVENT_TYPE_QUESTION_TYPE_TAG: String = "district-scheduling-event-type-question-type"
const val SCHEDULING_EVENT_TYPE_QUESTION_REQUIRED_TAG: String = "district-scheduling-event-type-question-required"
const val SCHEDULING_EVENT_TYPE_QUESTION_POSITION_TAG: String = "district-scheduling-event-type-question-position"
const val SCHEDULING_EVENT_TYPE_QUESTION_OPTION_TAG: String = "district-scheduling-event-type-question-option"
const val SCHEDULING_EVENT_TYPE_QUESTION_ADD_OPTION_TAG: String = "district-scheduling-event-type-question-add-option"
const val SCHEDULING_EVENT_TYPE_QUESTION_OPTION_REMOVE_TAG: String =
    "district-scheduling-event-type-question-option-remove"
