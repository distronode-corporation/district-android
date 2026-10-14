package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.annotation.StringRes
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * The event-type dialog that is open, if any, with everything it shows. One at a time, the Android
 * shape of district-ios's four sheets (editor, actions, hosts, questions).
 *
 * @property slug the event type the dialog is about; null only for a create.
 */
sealed interface EventTypeDialog {
    val slug: String?
    val state: WriteState

    /**
     * Create (no [slug]) or edit. ⚠️ [storedLocation] is the type AS LOADED, so a stored location
     * outside the offered four stays a choice after somebody picks another one.
     *
     * @property takenSlugs the slugs on screen, which a new slug is made unique against.
     * @property validation the client's refusal: nothing was sent, unlike a failed [state].
     */
    data class Editor(
        override val slug: String?,
        val form: SchedulingEventTypeForm,
        val storedLocation: String? = null,
        val takenSlugs: List<String> = emptyList(),
        @get:StringRes val validation: Int? = null,
        override val state: WriteState = WriteState.Idle,
    ) : EventTypeDialog

    /**
     * What is done TO an event type: on or off, archive or restore, a test email, delete.
     * ⚠️ [eventType] is replaced by every patch's answer, so the labels say what the server last said.
     */
    data class Actions(
        val eventType: SchedulingEventType,
        val confirmingDelete: Boolean = false,
        override val state: WriteState = WriteState.Idle,
    ) : EventTypeDialog {
        override val slug: String get() = eventType.slug
    }

    /**
     * Who can be booked, and how the next booking reaches them.
     *
     * @property canAddHosts false when `users.list` was refused (it is admin-only at the fork): the
     * list is still editable, with no add control and no error.
     */
    data class Hosts(
        override val slug: String,
        val routingMode: String,
        val rotationStrategy: String,
        val load: SectionState<Unit> = SectionState.Loading,
        val rows: List<SchedulingHostRow> = emptyList(),
        val candidates: List<SchedulingUser> = emptyList(),
        val canAddHosts: Boolean = false,
        @get:StringRes val validation: Int? = null,
        override val state: WriteState = WriteState.Idle,
    ) : EventTypeDialog

    /**
     * The booking questions, with the one being edited ([editing]) or asked about ([confirmingDelete]).
     * ⚠️ [load] can fail after a write landed; [questions] is then the last list read, kept.
     */
    data class Questions(
        override val slug: String,
        val load: SectionState<Unit> = SectionState.Loading,
        val questions: List<SchedulingQuestion> = emptyList(),
        val editing: SchedulingQuestionForm? = null,
        val confirmingDelete: SchedulingQuestion? = null,
        @get:StringRes val validation: Int? = null,
        override val state: WriteState = WriteState.Idle,
    ) : EventTypeDialog
}

/** The one open event-type dialog, shared by the four flows. */
class EventTypeDialogHost {
    private val _dialog = MutableStateFlow<EventTypeDialog?>(null)
    val dialog: StateFlow<EventTypeDialog?> = _dialog.asStateFlow()

    val current: EventTypeDialog? get() = _dialog.value

    fun show(dialog: EventTypeDialog?) {
        _dialog.value = dialog
    }

    /** Close whatever is open. ⛔ Ignored while a write is on its way: one press is one request. */
    fun close() {
        if (current?.state?.isWorking == true) return
        show(null)
    }

    /**
     * Change the open dialog if it is still a [type] about [slug]; a null answer closes it. ⚠️ A
     * closed dialog, or one reopened about another event type, is left alone, so a late answer
     * cannot land in the wrong one. (A class argument rather than a reified inline function, so the
     * check is one body that a test reaches, not a copy at every call site.)
     */
    fun <D : EventTypeDialog> update(type: KClass<D>, slug: String?, change: (D) -> EventTypeDialog?) {
        val open = type.safeCast(current) ?: return
        if (open.slug != slug) return
        show(change(open))
    }
}

/**
 * The event types' writes, shared by the list (create) and one event type's screen (the rest);
 * ported from district-ios's `SchedulingEventType{Editor,Actions,Hosts,Questions}Model`.
 *
 * ⛔ EVERY SUBMIT IS ONE REQUEST: a submit while one is on its way is ignored, the dialog cannot be
 * closed under it, and nothing retries on its own.
 *
 * @param onChanged told after a write landed, so the screen behind re-reads.
 * @param onDeleted told after the event type itself was deleted, so its screen goes back.
 */
class SchedulingEventTypeWrites(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    onChanged: () -> Unit,
    onDeleted: () -> Unit,
) {
    private val host = EventTypeDialogHost()
    val dialog: StateFlow<EventTypeDialog?> = host.dialog

    val editor = EventTypeEditorFlow(host, scope, admin, workspaceId, onChanged)
    val actions = EventTypeActionsFlow(host, scope, admin, workspaceId, onChanged, onDeleted)
    val hosts = EventTypeHostsFlow(host, scope, admin, workspaceId, onChanged)
    val questions = EventTypeQuestionsFlow(host, scope, admin, workspaceId, onChanged)

    /** Close whatever is open. Ignored while a write is on its way. */
    fun close() = host.close()
}

/** A failed write as the state its dialog shows. */
internal fun SchedulingAdminResult.Failed.toWriteState(): WriteState = WriteState.Failed(error.toFailureText())

/** Editing after a failure clears it, so the next press is a fresh attempt. */
internal fun clearedFailure(state: WriteState): WriteState = if (state is WriteState.Failed) WriteState.Idle else state
