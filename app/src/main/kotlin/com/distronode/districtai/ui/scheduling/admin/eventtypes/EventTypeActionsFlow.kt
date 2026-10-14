package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingEventTypeChanges
import com.distronode.districtai.core.data.deleteEventType
import com.distronode.districtai.core.data.patchEventType
import com.distronode.districtai.core.data.sendEventTypeTestEmail
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The things done TO an event type rather than IN it; ported from district-ios's
 * `SchedulingEventTypeActionsModel`.
 *
 * ⛔ SEPARATE FROM THE EDITOR BECAUSE NONE OF THEM READS THE FORM: the test email sends the STORED
 * wording, so offering it beside unsaved edits would show somebody the old template.
 *
 * ⛔ ARCHIVE AND DELETE ARE BOTH OFFERED AND ONLY DELETE ASKS FIRST. Archiving is a patch that hides
 * the event type and is undone from the same dialog; deleting removes the row and its booking page.
 */
class EventTypeActionsFlow(
    private val host: EventTypeDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
    private val onDeleted: () -> Unit,
) {
    private val kind = EventTypeDialog.Actions::class

    fun open(row: SchedulingEventType) = host.show(EventTypeDialog.Actions(row))

    /** Turn the booking page on or off. ⚠️ An absent flag reads as on, the web's `is_active !== false`. */
    fun toggleActive() {
        val dialog = idle() ?: return
        val next = dialog.eventType.isActive == false
        val done = if (next) {
            R.string.scheduling_admin_event_types_turned_on
        } else {
            R.string.scheduling_admin_event_types_turned_off
        }
        patch(dialog, SchedulingEventTypeChanges(isActive = next), done)
    }

    fun toggleArchived() {
        val dialog = idle() ?: return
        val next = dialog.eventType.archived != true
        val done = if (next) {
            R.string.scheduling_admin_event_types_archived_done
        } else {
            R.string.scheduling_admin_event_types_restored
        }
        patch(dialog, SchedulingEventTypeChanges(archived = next), done)
    }

    /** Ask, or stop asking, whether to delete. */
    fun askDelete(asking: Boolean) {
        val dialog = idle() ?: return
        host.show(dialog.copy(confirmingDelete = asking))
    }

    /** ⛔ The one op here that removes something; it runs on the confirmation's button. */
    fun delete() {
        val dialog = idle() ?: return
        host.show(dialog.copy(confirmingDelete = false, state = WriteState.Working))
        scope.launch {
            when (val result = admin.deleteEventType(workspaceId, dialog.slug)) {
                is SchedulingAdminResult.Ok -> {
                    host.update(kind, dialog.slug) { null }
                    onDeleted()
                }
                is SchedulingAdminResult.Failed ->
                    host.update(kind, dialog.slug) { it.copy(state = result.toWriteState()) }
            }
        }
    }

    /**
     * Send one of the four templates to the calling member.
     *
     * ⚠️ THE ADDRESS COMES BACK IN THE ANSWER: the fork chooses it, and it is often not the one somebody
     * expects. `sent: false` is a request that worked and a mail that did not, so it is a sentence in
     * the result line rather than a failure offering Retry.
     */
    fun sendTestEmail(type: String) {
        val dialog = idle() ?: return
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val result = admin.sendEventTypeTestEmail(workspaceId, dialog.slug, type)
            host.update(kind, dialog.slug) {
                it.copy(
                    state = when (result) {
                        is SchedulingAdminResult.Ok -> WriteState.Done(
                            if (result.value.sent) {
                                UiText.Resource(R.string.scheduling_admin_event_types_test_email_sent, result.value.to)
                            } else {
                                UiText.Resource(R.string.scheduling_admin_event_types_test_email_not_sent)
                            },
                        )
                        is SchedulingAdminResult.Failed -> result.toWriteState()
                    },
                )
            }
        }
    }

    private fun patch(dialog: EventTypeDialog.Actions, changes: SchedulingEventTypeChanges, @StringRes done: Int) {
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val result = admin.patchEventType(workspaceId, dialog.slug, changes)
            host.update(kind, dialog.slug) {
                when (result) {
                    is SchedulingAdminResult.Ok ->
                        it.copy(eventType = result.value, state = WriteState.Done(UiText.Resource(done)))
                    is SchedulingAdminResult.Failed -> it.copy(state = result.toWriteState())
                }
            }
            if (result is SchedulingAdminResult.Ok) onChanged()
        }
    }

    /** The open actions dialog, unless a write is on its way. */
    private fun idle(): EventTypeDialog.Actions? =
        (host.current as? EventTypeDialog.Actions)?.takeUnless { it.state.isWorking }
}
