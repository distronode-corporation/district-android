package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.createEventTypeQuestion
import com.distronode.districtai.core.data.deleteEventTypeQuestion
import com.distronode.districtai.core.data.eventTypeQuestions
import com.distronode.districtai.core.data.patchEventTypeQuestion
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The booking questions: list, add, edit, reorder, delete; ported from district-ios's
 * `SchedulingEventTypeQuestionsModel`.
 *
 * ⛔ EVERY CHANGE IS ITS OWN REQUEST AND THERE IS NO SAVE FOR THE LIST, the web's arrangement: a
 * batched save would have to decide what to do when the third of five is refused.
 *
 * ⛔ REORDERING IS A PATCH OF ONE QUESTION'S `position`, NOT A RENUMBERING, as on the web: positions
 * may collide and may have gaps, and the list is read in `position` order.
 *
 * ⚠️ THE LIST IS RE-READ AFTER EVERY WRITE, and a failed re-read is not a failed write: the change
 * landed, and saying otherwise is what makes somebody add a question twice.
 */
class EventTypeQuestionsFlow(
    private val host: EventTypeDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: () -> Unit,
) {
    private val kind = EventTypeDialog.Questions::class

    fun open(slug: String) {
        host.show(EventTypeDialog.Questions(slug))
        load()
    }

    fun load() {
        val dialog = host.current as? EventTypeDialog.Questions ?: return
        host.show(dialog.copy(load = SectionState.Loading))
        scope.launch { reread(dialog.slug, notify = false) }
    }

    /** Open the one-question form: [question] to edit it, null to add one at the end. */
    fun beginEdit(question: SchedulingQuestion?) {
        val dialog = idle() ?: return
        val form = question?.let(SchedulingQuestionForm::editing)
            ?: SchedulingQuestionForm.adding(dialog.questions.size)
        host.show(dialog.copy(editing = form, validation = null, state = WriteState.Idle))
    }

    fun editForm(change: (SchedulingQuestionForm) -> SchedulingQuestionForm) {
        val dialog = idle() ?: return
        val form = dialog.editing ?: return
        host.show(dialog.copy(editing = change(form), state = clearedFailure(dialog.state)))
    }

    /** Back to the list without saving. */
    fun cancelEdit() {
        val dialog = idle() ?: return
        host.show(dialog.copy(editing = null, validation = null, state = WriteState.Idle))
    }

    /** Ask whether to delete [question]; null stops asking. */
    fun confirmDelete(question: SchedulingQuestion?) {
        val dialog = idle() ?: return
        host.show(dialog.copy(confirmingDelete = question))
    }

    fun submit() {
        val dialog = idle() ?: return
        val form = dialog.editing ?: return
        val id = form.id
        val write: (suspend () -> SchedulingAdminResult<*>)? = if (id == null) {
            form.draft()?.let { draft -> suspend { admin.createEventTypeQuestion(workspaceId, dialog.slug, draft) } }
        } else {
            form.changes()?.let { changes ->
                suspend { admin.patchEventTypeQuestion(workspaceId, dialog.slug, id, changes) }
            }
        }
        if (write == null) {
            host.show(dialog.copy(validation = form.error))
            return
        }
        val done = if (id == null) {
            R.string.scheduling_admin_event_types_question_added
        } else {
            R.string.scheduling_admin_event_types_question_saved
        }
        host.show(dialog.copy(validation = null, state = WriteState.Working))
        scope.launch {
            when (val result = write()) {
                is SchedulingAdminResult.Ok -> {
                    host.update(kind, dialog.slug) {
                        it.copy(editing = null, state = WriteState.Done(UiText.Resource(done)))
                    }
                    reread(dialog.slug, notify = true)
                }
                is SchedulingAdminResult.Failed ->
                    host.update(kind, dialog.slug) { it.copy(state = result.toWriteState()) }
            }
        }
    }

    /** ⛔ Runs on the confirmation's button, for the question it asked about. */
    fun delete() {
        val dialog = idle() ?: return
        val question = dialog.confirmingDelete ?: return
        host.show(dialog.copy(confirmingDelete = null, state = WriteState.Working))
        scope.launch {
            when (val result = admin.deleteEventTypeQuestion(workspaceId, dialog.slug, question.id)) {
                is SchedulingAdminResult.Ok -> {
                    val deleted = UiText.Resource(R.string.scheduling_admin_event_types_question_deleted)
                    host.update(kind, dialog.slug) { it.copy(state = WriteState.Done(deleted)) }
                    reread(dialog.slug, notify = true)
                }
                is SchedulingAdminResult.Failed ->
                    host.update(kind, dialog.slug) { it.copy(state = result.toWriteState()) }
            }
        }
    }

    /** ⚠️ Sorted by `position`, the order the booker sees; the op promises none. */
    private suspend fun reread(slug: String, notify: Boolean) {
        when (val result = admin.eventTypeQuestions(workspaceId, slug)) {
            is SchedulingAdminResult.Ok -> {
                host.update(kind, slug) {
                    it.copy(
                        load = SectionState.Ready(Unit),
                        questions = result.value.sortedBy(SchedulingQuestion::position),
                    )
                }
                if (notify) onSaved()
            }
            is SchedulingAdminResult.Failed -> host.update(kind, slug) {
                it.copy(load = SectionState.Failed(result.error.toFailureText()))
            }
        }
    }

    /** The open questions dialog, unless a write is on its way. */
    private fun idle(): EventTypeDialog.Questions? =
        (host.current as? EventTypeDialog.Questions)?.takeUnless { it.state.isWorking }
}
