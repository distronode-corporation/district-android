package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingQuestionChanges
import com.distronode.districtai.core.data.SchedulingQuestionDraft
import com.distronode.districtai.core.model.SchedulingHost
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.core.model.SchedulingUser

/**
 * One row of the hosts list while it is being edited, after district-ios's `SchedulingHostRow`.
 *
 * ⛔ [userId] IS THE SCHEDULER'S USER ID, NOT A DISTRICT MEMBER ID. ⚠️ [priority] is a string for
 * [SchedulingEventTypeForm]'s reason: 0 is legal, so a field bound to an Int cannot tell "cleared it
 * to retype" from "meant zero".
 */
data class SchedulingHostRow(
    val userId: String,
    val name: String,
    val email: String,
    val role: String,
    val priority: String,
) {
    companion object {
        fun from(host: SchedulingHost): SchedulingHostRow =
            SchedulingHostRow(host.userId, host.name, host.email, host.role, host.priority.toString())

        /** A newly added host: required, priority 0, as the reference adds one. */
        fun added(user: SchedulingUser): SchedulingHostRow =
            SchedulingHostRow(user.id, user.name, user.email, role = ROLE_REQUIRED, priority = "0")

        const val ROLE_REQUIRED: String = "required"
    }
}

/**
 * One booking question as the form holds it, after district-ios's `SchedulingQuestionForm`.
 *
 * ⚠️ [id] NULL MEANS CREATE. The two ops take the same fields; only the question's own id decides
 * which one runs. [optionDraft] is the text in the "Add an option" field, not part of the question.
 */
data class SchedulingQuestionForm(
    val id: String? = null,
    val label: String = "",
    val type: String = TYPE_TEXT,
    val options: List<String> = emptyList(),
    val required: Boolean = false,
    val position: String = "0",
    val optionDraft: String = "",
) {
    val trimmedLabel: String get() = label.trim()

    val isSelect: Boolean get() = type == TYPE_SELECT

    /**
     * ⛔ SENT ONLY FOR A `select`, WHICH IS THE WEB'S RULE: a question that was a select and became a
     * text KEEPS its options server-side, because null is "leave alone" on the patch.
     */
    val wireOptions: List<String>? get() = if (isSelect) options else null

    @get:StringRes
    val error: Int?
        get() = when {
            trimmedLabel.isEmpty() -> R.string.scheduling_admin_event_types_question_label_missing
            isSelect && options.isEmpty() -> R.string.scheduling_admin_event_types_question_needs_option
            SchedulingEventTypeForm.whole(position, atLeast = 0) == null ->
                R.string.scheduling_admin_event_types_question_position_invalid
            else -> null
        }

    /** The typed option added to the list. ⚠️ A blank one or a duplicate is dropped in silence, as on the web. */
    fun withOption(): SchedulingQuestionForm {
        val option = optionDraft.trim()
        val keep = option.isEmpty() || option in options
        return copy(options = if (keep) options else options + option, optionDraft = "")
    }

    fun withoutOption(option: String): SchedulingQuestionForm = copy(options = options - option)

    /** The create body, or null when [error] is not. */
    fun draft(): SchedulingQuestionDraft? {
        val at = SchedulingEventTypeForm.whole(position, atLeast = 0) ?: return null
        if (error != null) return null
        return SchedulingQuestionDraft(trimmedLabel, type, required, options = wireOptions, position = at)
    }

    /** The patch body, or null when [error] is not. */
    fun changes(): SchedulingQuestionChanges? = draft()?.let {
        SchedulingQuestionChanges(it.label, it.type, it.options, it.required, it.position)
    }

    companion object {
        const val TYPE_TEXT: String = "text"
        const val TYPE_SELECT: String = "select"

        /**
         * A new question. ⚠️ IT OPENS AT THE END OF THE LIST, which is what "Add question" means; a
         * literal 0 would insert it at the top.
         */
        fun adding(count: Int): SchedulingQuestionForm = SchedulingQuestionForm(position = count.toString())

        fun editing(question: SchedulingQuestion): SchedulingQuestionForm = SchedulingQuestionForm(
            id = question.id,
            label = question.label,
            type = question.type,
            options = question.options.orEmpty(),
            required = question.required,
            position = question.position.toString(),
        )
    }
}
