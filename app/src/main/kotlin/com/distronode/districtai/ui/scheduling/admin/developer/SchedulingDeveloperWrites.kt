package com.distronode.districtai.ui.scheduling.admin.developer

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.createApiKey
import com.distronode.districtai.core.data.createWebhook
import com.distronode.districtai.core.data.updateWebhook
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A secret the scheduler answered exactly once: a new API key, or a new webhook's signing secret.
 *
 * ⛔ HELD ONLY WHILE ITS DIALOG IS OPEN, AND NEVER PRINTED. Closing the dialog drops the dialog
 * state that holds it; nothing writes it to disk, a log or a snippet. [toString] leaves the value
 * out so a state dump cannot carry it, and equality is identity because nothing compares two.
 *
 * @property label the key's name as the scheduler echoed it, or the webhook's URL.
 */
class SchedulingRevealedSecret(val label: String, val value: String) {
    override fun toString(): String = "SchedulingRevealedSecret(label=$label)"
}

/**
 * The events and fields form, shared by Add and Edit; district-ios's `SchedulingWebhookDraftC`.
 *
 * ⚠️ SETS, BECAUSE A TICK BOX IS A MEMBERSHIP QUESTION; the order is restored on the way out by
 * filtering the canonical lists, so the stored arrays match every other surface.
 */
data class SchedulingWebhookDraft(
    val url: String,
    val events: Set<SchedulingWebhookEvent>,
    val fields: Set<String>,
) {
    val orderedEvents: List<SchedulingWebhookEvent> get() = SchedulingWebhookEvent.entries.filter { it in events }
    val orderedFields: List<String> get() = SchedulingWebhookFields.ALL.filter { it in fields }

    companion object {
        /** A new webhook: no URL, no events, every field but the attendee's own. */
        fun blank(): SchedulingWebhookDraft = SchedulingWebhookDraft("", emptySet(), SchedulingWebhookFields.DEFAULTS)

        /**
         * An existing row, as Edit opens it.
         *
         * ⛔ AN EVENT NAME THIS BUILD DOES NOT KNOW IS DROPPED FROM THE TICKS (the patch would be
         * refused with `unknown event`), and the dialog names it rather than losing it quietly.
         * ⚠️ An absent `fields` is the fork's default set, not an empty selection.
         */
        fun from(webhook: SchedulingWebhook): SchedulingWebhookDraft = SchedulingWebhookDraft(
            url = webhook.url,
            events = SchedulingWebhookEvent.entries.filter { it.wire in webhook.events }.toSet(),
            fields = webhook.fields?.toSet() ?: SchedulingWebhookFields.DEFAULTS,
        )
    }
}

/** The developer dialog that is open, if any. */
sealed interface DeveloperDialog {
    val state: WriteState

    /**
     * Name a key, mint it, and show it once.
     *
     * @property minted the key, from the moment it is minted until the dialog closes.
     */
    data class CreateKey(
        val name: String = "",
        val nameMissing: Boolean = false,
        override val state: WriteState = WriteState.Idle,
        val minted: SchedulingRevealedSecret? = null,
        val copied: Boolean = false,
    ) : DeveloperDialog

    /**
     * Add a webhook ([editing] null) or change one's events and fields.
     *
     * ⛔ THE URL IS A FIELD ON ADD AND A FACT ON EDIT: `webhooks.patch` takes only events and
     * fields, and a box that silently kept the old value would let a member believe deliveries
     * had moved.
     */
    data class Webhook(
        val editing: SchedulingWebhook?,
        val draft: SchedulingWebhookDraft,
        val urlIssue: Int? = null,
        val eventsMissing: Boolean = false,
        override val state: WriteState = WriteState.Idle,
        val secret: SchedulingRevealedSecret? = null,
    ) : DeveloperDialog {
        /** Event names the row carries that this build cannot offer or send back. */
        val droppedEvents: List<String>
            get() = editing?.events.orEmpty().filter { name ->
                SchedulingWebhookEvent.entries.none { it.wire == name }
            }

        /** ⚠️ An added webhook cannot be added twice from the same dialog. */
        val canSubmit: Boolean get() = !(editing == null && state is WriteState.Done)
    }
}

/**
 * Create an API key and add or edit a webhook; ported from district-ios's
 * `SchedulingAPIKeyCreateModel` and `SchedulingWebhookEditorModel`.
 *
 * ⛔ ONE PRESS IS ONE REQUEST: a second press mints a second credential of which only one is ever
 * shown, so a submit while one is on its way is ignored and the dialog cannot close under it.
 *
 * @param onChanged told after every successful write, so the tab behind re-reads. ⚠️ The list is
 *   re-read rather than appended to: a create answers a different shape, one carrying a secret.
 */
class SchedulingDeveloperWrites(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    onChanged: () -> Unit,
) {
    private val host = DeveloperDialogHost()
    val dialog: StateFlow<DeveloperDialog?> = host.dialog

    val keys = SchedulingApiKeyCreateFlow(host, scope, admin, workspaceId, onChanged)
    val webhooks = SchedulingWebhookEditorFlow(host, scope, admin, workspaceId, onChanged)

    /** Close whatever is open, dropping any secret it showed. Ignored while a write is on its way. */
    fun close() = host.close()
}

/** The one open developer dialog. */
class DeveloperDialogHost {
    private val _dialog = MutableStateFlow<DeveloperDialog?>(null)
    val dialog: StateFlow<DeveloperDialog?> = _dialog.asStateFlow()

    val current: DeveloperDialog? get() = _dialog.value

    fun show(dialog: DeveloperDialog) {
        _dialog.value = dialog
    }

    fun close() {
        if (_dialog.value?.state?.isWorking == true) return
        _dialog.value = null
    }

    /** Change the open dialog if it is still a [D]. */
    inline fun <reified D : DeveloperDialog> update(change: (D) -> DeveloperDialog) {
        val open = current as? D ?: return
        show(change(open))
    }
}

/** Mint one API key and show its plaintext exactly once. */
class SchedulingApiKeyCreateFlow(
    private val host: DeveloperDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    fun open() = host.show(DeveloperDialog.CreateKey())

    fun editName(value: String) {
        val dialog = host.current as? DeveloperDialog.CreateKey ?: return
        host.show(dialog.copy(name = value, nameMissing = false, state = idleAfterFailure(dialog.state)))
    }

    /** ⚠️ The name is trimmed, and a blank one is refused here, the one check the schema and web make. */
    fun submit() {
        val dialog = host.current as? DeveloperDialog.CreateKey ?: return
        if (dialog.state.isWorking || dialog.minted != null) return
        val name = dialog.name.trim()
        if (name.isEmpty()) {
            host.show(dialog.copy(nameMissing = true))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            when (val result = admin.createApiKey(workspaceId, name)) {
                is SchedulingAdminResult.Ok -> {
                    val minted = SchedulingRevealedSecret(result.value.name, result.value.key)
                    host.update<DeveloperDialog.CreateKey> { DeveloperDialog.CreateKey(minted = minted) }
                    onChanged()
                }
                is SchedulingAdminResult.Failed -> host.update<DeveloperDialog.CreateKey> {
                    it.copy(state = WriteState.Failed(result.error.toFailureText()))
                }
            }
        }
    }

    /** The copy button's label changes; the clipboard write itself is the screen's. */
    fun markCopied() {
        val dialog = host.current as? DeveloperDialog.CreateKey ?: return
        host.show(dialog.copy(copied = true))
    }
}

/** Add a webhook, or change an existing one's events and fields. */
class SchedulingWebhookEditorFlow(
    private val host: DeveloperDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    fun openCreate() = host.show(DeveloperDialog.Webhook(editing = null, draft = SchedulingWebhookDraft.blank()))

    fun openEdit(webhook: SchedulingWebhook) =
        host.show(DeveloperDialog.Webhook(editing = webhook, draft = SchedulingWebhookDraft.from(webhook)))

    fun editUrl(value: String) = edit { it.copy(draft = it.draft.copy(url = value), urlIssue = null) }

    fun toggleEvent(event: SchedulingWebhookEvent, on: Boolean) = edit {
        val events = if (on) it.draft.events + event else it.draft.events - event
        it.copy(draft = it.draft.copy(events = events), eventsMissing = false)
    }

    fun toggleField(field: String, on: Boolean) = edit {
        val fields = if (on) it.draft.fields + field else it.draft.fields - field
        it.copy(draft = it.draft.copy(fields = fields))
    }

    /**
     * ⚠️ THE URL IS CHECKED ONLY ON ADD: on Edit it is not sent, and a stored row this build would
     * now refuse is still a working webhook whose events somebody may want to narrow.
     */
    fun submit() {
        val dialog = host.current as? DeveloperDialog.Webhook ?: return
        // ⚠️ A shown secret means the webhook was added, which [canSubmit] already refuses.
        if (dialog.state.isWorking || !dialog.canSubmit) return
        val urlIssue = if (dialog.editing == null) schedulingWebhookUrlIssue(dialog.draft.url) else null
        val eventsMissing = dialog.draft.events.isEmpty()
        if (urlIssue != null || eventsMissing) {
            host.show(dialog.copy(urlIssue = urlIssue, eventsMissing = eventsMissing))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val editing = dialog.editing
            if (editing == null) create(dialog.draft) else patch(editing.id, dialog.draft)
        }
    }

    private suspend fun create(draft: SchedulingWebhookDraft) {
        val url = draft.url.trim()
        when (val result = admin.createWebhook(workspaceId, url, draft.orderedEvents, draft.orderedFields)) {
            is SchedulingAdminResult.Ok -> {
                // ⚠️ `secret` is optional on the wire: a create that minted none still made the
                // webhook, and the dialog says so instead of showing an empty secret.
                val secret = result.value.secret?.let { SchedulingRevealedSecret(result.value.url, it) }
                host.update<DeveloperDialog.Webhook> {
                    it.copy(state = done(R.string.scheduling_admin_developer_webhook_added), secret = secret)
                }
                onChanged()
            }
            is SchedulingAdminResult.Failed -> failed(result)
        }
    }

    /** ⚠️ The patch answers 204 with no body, so nothing is merged locally: the tab re-reads. */
    private suspend fun patch(webhookId: String, draft: SchedulingWebhookDraft) {
        val events = draft.orderedEvents.map { it.wire }
        when (val result = admin.updateWebhook(workspaceId, webhookId, events, draft.orderedFields)) {
            is SchedulingAdminResult.Ok -> {
                host.update<DeveloperDialog.Webhook> {
                    it.copy(state = done(R.string.scheduling_admin_developer_webhook_updated))
                }
                onChanged()
            }
            is SchedulingAdminResult.Failed -> failed(result)
        }
    }

    private fun failed(result: SchedulingAdminResult.Failed) = host.update<DeveloperDialog.Webhook> {
        it.copy(state = WriteState.Failed(result.error.toFailureText()))
    }

    private inline fun edit(change: (DeveloperDialog.Webhook) -> DeveloperDialog.Webhook) {
        val dialog = host.current as? DeveloperDialog.Webhook ?: return
        if (dialog.state.isWorking) return
        val changed = change(dialog)
        host.show(changed.copy(state = idleAfterFailure(changed.state)))
    }
}

private fun done(id: Int): WriteState = WriteState.Done(UiText.Resource(id))

/** Editing after a failure clears it, so the next press is a fresh attempt. */
private fun idleAfterFailure(state: WriteState): WriteState = if (state is WriteState.Failed) WriteState.Idle else state
