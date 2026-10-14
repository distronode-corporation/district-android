package com.distronode.districtai.ui.scheduling.admin.eventtypes

import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.createEventType
import com.distronode.districtai.core.data.patchEventType
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Create an event type, or edit one; ported from district-ios's `SchedulingEventTypeEditorModel`.
 *
 * ⛔ ONE FLOW FOR BOTH, AND THE MODE DECIDES WHICH OP AND WHICH FIELDS. `eventTypes.create` refuses
 * most of what `eventTypes.patch` takes, so a create draws three fields and an edit the full form.
 *
 * ⚠️ THE SAVE BUTTON IS LIVE WHILE THE FORM IS INVALID and the refusal is shown on the press, as the
 * reference and the web do. A save that landed closes the dialog and tells the screen behind.
 */
class EventTypeEditorFlow(
    private val host: EventTypeDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onSaved: () -> Unit,
) {
    private val kind = EventTypeDialog.Editor::class

    /** A new event type, made unique against [takenSlugs], the slugs on screen. */
    fun openCreate(takenSlugs: List<String>) =
        host.show(EventTypeDialog.Editor(slug = null, form = SchedulingEventTypeForm(), takenSlugs = takenSlugs))

    /** ⛔ Opened only from a loaded row: the patch sends every field, so it must start from what is stored. */
    fun openEdit(row: SchedulingEventType) = host.show(
        EventTypeDialog.Editor(
            slug = row.slug,
            form = SchedulingEventTypeForm.from(row),
            storedLocation = row.locationType,
        ),
    )

    fun edit(change: (SchedulingEventTypeForm) -> SchedulingEventTypeForm) {
        val dialog = host.current as? EventTypeDialog.Editor ?: return
        if (dialog.state.isWorking) return
        host.show(dialog.copy(form = change(dialog.form), state = clearedFailure(dialog.state)))
    }

    /**
     * ⚠️ THE SLUG OF A CREATE IS DERIVED FROM THE NAME HERE, AT THE PRESS, and the operator never types
     * one (see [SchedulingEventTypeSlug]). An edit addresses the row by the slug it was opened with.
     */
    fun save() {
        val dialog = host.current as? EventTypeDialog.Editor ?: return
        if (dialog.state.isWorking) return
        val slug = dialog.slug
        val form = dialog.form
        val write: (suspend () -> SchedulingAdminResult<SchedulingEventType>)? = if (slug == null) {
            form.createDraft(SchedulingEventTypeSlug.unique(form.trimmedName, dialog.takenSlugs))
                ?.let { draft -> suspend { admin.createEventType(workspaceId, draft) } }
        } else {
            form.changes()?.let { changes -> suspend { admin.patchEventType(workspaceId, slug, changes) } }
        }
        if (write == null) {
            host.show(dialog.copy(validation = if (slug == null) form.createError else form.editError))
            return
        }
        host.show(dialog.copy(validation = null, state = WriteState.Working))
        scope.launch {
            when (val result = write()) {
                is SchedulingAdminResult.Ok -> {
                    host.update(kind, slug) { null }
                    onSaved()
                }
                is SchedulingAdminResult.Failed ->
                    host.update(kind, slug) { it.copy(state = result.toWriteState()) }
            }
        }
    }
}
