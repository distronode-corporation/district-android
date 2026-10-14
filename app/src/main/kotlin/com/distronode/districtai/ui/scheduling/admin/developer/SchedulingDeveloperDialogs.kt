package com.distronode.districtai.ui.scheduling.admin.developer

import android.content.ClipData
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.ui.scheduling.admin.SchedulingConfirmDialog
import com.distronode.districtai.ui.scheduling.admin.SchedulingFieldKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingRejection
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusBadge
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingTextField
import com.distronode.districtai.ui.scheduling.admin.SchedulingToggleRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingWriteDialog
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue
import kotlinx.coroutines.launch

/** Whichever developer dialog [writes] has open. */
@Composable
fun SchedulingDeveloperDialogs(writes: SchedulingDeveloperWrites) {
    val dialog by writes.dialog.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copy: (String, String) -> Unit = @DontMemoize { label, value ->
        scope.launch { clipboard.setClipEntry(ClipEntry(schedulingSensitiveClip(label, value))) }
    }
    when (val open = dialog) {
        is DeveloperDialog.CreateKey -> {
            val minted = open.minted
            if (minted == null) {
                CreateKeyDialog(open, writes)
            } else {
                val clipLabel = stringResource(R.string.scheduling_admin_developer_clip_key)
                SecretDialog(
                    title = stringResource(R.string.scheduling_admin_developer_key_reveal_title, minted.label),
                    note = null,
                    secret = minted.value,
                    copyLabel = if (open.copied) {
                        stringResource(R.string.scheduling_admin_developer_key_copied)
                    } else {
                        stringResource(R.string.scheduling_admin_developer_key_copy_action)
                    },
                    onCopy = @DontMemoize {
                        copy(clipLabel, minted.value)
                        writes.keys.markCopied()
                    },
                    onDone = @DontMemoize { writes.close() },
                )
            }
        }
        is DeveloperDialog.Webhook -> {
            val secret = open.secret
            if (secret == null) {
                WebhookDialog(open, writes)
            } else {
                val clipLabel = stringResource(R.string.scheduling_admin_developer_clip_secret)
                SecretDialog(
                    title = stringResource(R.string.scheduling_admin_developer_webhook_secret_title),
                    note = stringResource(R.string.scheduling_admin_developer_webhook_secret_note, secret.label),
                    secret = secret.value,
                    copyLabel = stringResource(R.string.scheduling_admin_developer_webhook_secret_copy),
                    onCopy = @DontMemoize { copy(clipLabel, secret.value) },
                    onDone = @DontMemoize { writes.close() },
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun CreateKeyDialog(dialog: DeveloperDialog.CreateKey, writes: SchedulingDeveloperWrites) {
    SchedulingWriteDialog(
        title = stringResource(R.string.scheduling_admin_developer_key_create),
        subtitle = stringResource(R.string.scheduling_admin_developer_key_name_hint),
        confirmLabel = stringResource(R.string.scheduling_admin_developer_key_create),
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { writes.keys.submit() },
        onCancel = @DontMemoize { writes.close() },
        tag = DEVELOPER_KEY_DIALOG_TAG,
        confirmTag = DEVELOPER_KEY_SUBMIT_TAG,
        onRetry = @DontMemoize { writes.keys.submit() },
    ) {
        SchedulingTextField(
            label = stringResource(R.string.scheduling_admin_developer_key_name_label),
            value = dialog.name,
            onValueChange = @DontMemoize { value -> writes.keys.editName(value) },
            tag = DEVELOPER_KEY_NAME_TAG,
            enabled = !dialog.state.isWorking,
            error = if (dialog.nameMissing) {
                stringResource(R.string.scheduling_admin_developer_key_name_missing)
            } else {
                null
            },
        )
    }
}

/**
 * Add or edit one webhook: a URL (a fact on Edit), the events to send and the fields each
 * delivery carries; district-ios's `SchedulingWebhookEditorSheet`.
 *
 * ⚠️ THE FIELDS THAT CARRY A PERSON'S DETAILS ARE BADGED, and the attendee's own open unticked on
 * a new webhook (see [SchedulingWebhookFields.DEFAULTS]).
 */
@Composable
private fun WebhookDialog(dialog: DeveloperDialog.Webhook, writes: SchedulingDeveloperWrites) {
    val editing = dialog.editing
    val working = dialog.state.isWorking
    SchedulingWriteDialog(
        title = if (editing == null) {
            stringResource(R.string.scheduling_admin_developer_webhook_add_action)
        } else {
            stringResource(R.string.scheduling_admin_developer_webhook_edit_title)
        },
        subtitle = null,
        confirmLabel = if (editing == null) {
            stringResource(R.string.scheduling_admin_developer_webhook_add_action)
        } else {
            stringResource(R.string.scheduling_admin_developer_webhook_save_action)
        },
        cancelLabel = stringResource(R.string.scheduling_admin_common_cancel),
        state = dialog.state,
        onConfirm = @DontMemoize { writes.webhooks.submit() },
        onCancel = @DontMemoize { writes.close() },
        tag = DEVELOPER_WEBHOOK_DIALOG_TAG,
        confirmTag = DEVELOPER_WEBHOOK_SUBMIT_TAG,
        confirmEnabled = dialog.canSubmit,
        onRetry = @DontMemoize { writes.webhooks.submit() },
    ) {
        if (editing == null) {
            SchedulingTextField(
                label = stringResource(R.string.scheduling_admin_developer_webhook_url_label),
                value = dialog.draft.url,
                onValueChange = @DontMemoize { value -> writes.webhooks.editUrl(value) },
                tag = DEVELOPER_WEBHOOK_URL_TAG,
                enabled = !working,
                hint = stringResource(R.string.scheduling_admin_developer_webhook_url_hint),
                error = dialog.urlIssue?.let { stringResource(it) },
                kind = SchedulingFieldKind.URL,
            )
        } else {
            SchedulingReadOnlyRow(stringResource(R.string.scheduling_admin_developer_webhook_url_label), editing.url)
            SchedulingHint(stringResource(R.string.scheduling_admin_developer_webhook_url_fixed))
        }
        if (dialog.droppedEvents.isNotEmpty()) {
            Text(
                text = stringResource(
                    R.string.scheduling_admin_developer_webhook_dropped_events,
                    dialog.droppedEvents.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.warning,
                modifier = Modifier.testTag(DEVELOPER_WEBHOOK_DROPPED_TAG),
            )
        }
        WebhookChoices(dialog, writes)
    }
}

/**
 * The event and field ticks, each named by its wire value as the receiving code knows it.
 *
 * ⚠️ Only the offered events have a tick; one the row already holds beyond them stays in the draft
 * and is sent back unchanged (see [SchedulingWebhookDraft.from]).
 */
@Composable
private fun WebhookChoices(dialog: DeveloperDialog.Webhook, writes: SchedulingDeveloperWrites) {
    val working = dialog.state.isWorking
    SchedulingReadOnlyRow(
        stringResource(R.string.scheduling_admin_developer_webhook_events_label),
        stringResource(R.string.scheduling_admin_developer_webhook_events_hint),
    )
    SchedulingWebhookEvent.OFFERED.forEach { event ->
        SchedulingToggleRow(
            label = schedulingUnknownValue(event.wire),
            checked = event in dialog.draft.events,
            onCheckedChange = @DontMemoize { writes.webhooks.toggleEvent(event, it) },
            tag = "$DEVELOPER_WEBHOOK_EVENT_TAG-${event.wire}",
            enabled = !working,
        )
    }
    if (dialog.eventsMissing) {
        SchedulingRejection(stringResource(R.string.scheduling_admin_developer_webhook_events_missing))
    }
    SchedulingReadOnlyRow(
        stringResource(R.string.scheduling_admin_developer_webhook_fields_label),
        stringResource(R.string.scheduling_admin_developer_webhook_fields_hint),
    )
    SchedulingWebhookFields.ALL.forEach { field ->
        SchedulingToggleRow(
            label = schedulingUnknownValue(field),
            checked = field in dialog.draft.fields,
            onCheckedChange = @DontMemoize { writes.webhooks.toggleField(field, it) },
            tag = "$DEVELOPER_WEBHOOK_FIELD_TAG-$field",
            enabled = !working,
        )
        if (field in SchedulingWebhookFields.PERSONAL_DATA) {
            SchedulingStatusBadge(
                stringResource(R.string.scheduling_admin_developer_webhook_personal_data),
                SchedulingStatusKind.ERROR,
            )
        }
    }
}

/**
 * The one and only sight of a secret.
 *
 * ⛔ NOTHING HERE LOGS, PERSISTS OR SHARES IT. Copy puts it on the clipboard (marked sensitive, so
 * the system's clipboard preview hides it), because that is the point of the dialog; there is no
 * share sheet. Done, Back or a tap outside closes the dialog, which drops it.
 */
@Composable
private fun SecretDialog(
    title: String,
    note: String?,
    secret: String,
    copyLabel: String,
    onCopy: () -> Unit,
    onDone: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        modifier = Modifier.testTag(DEVELOPER_SECRET_DIALOG_TAG),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                Text(
                    stringResource(R.string.scheduling_admin_developer_key_reveal_warning),
                    style = MaterialTheme.typography.bodyMedium,
                )
                note?.let { SchedulingHint(it) }
                SelectionContainer {
                    Text(
                        text = secret,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DistrictTheme.colors.muted, RoundedCornerShape(DistrictTheme.spacing.tight))
                            .padding(DistrictTheme.spacing.tight)
                            .testTag(DEVELOPER_SECRET_VALUE_TAG),
                    )
                }
            }
        },
        confirmButton = {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_developer_done),
                onClick = onDone,
                modifier = Modifier.testTag(DEVELOPER_SECRET_DONE_TAG),
            )
        },
        dismissButton = {
            DistrictButton(
                text = copyLabel,
                onClick = onCopy,
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(DEVELOPER_SECRET_COPY_TAG),
            )
        },
    )
}

/**
 * The three removals' prompts, each naming what goes and what stops working, with the reference's
 * wording. ⛔ Only the row a removal is asking about has a prompt up.
 */
@Composable
fun SchedulingDeveloperRemovalDialogs(removals: SchedulingDeveloperRemovals) {
    val key by removals.keyRevoke.state.collectAsState()
    val app by removals.appRevoke.state.collectAsState()
    val hook by removals.webhookDelete.state.collectAsState()
    val cancel = stringResource(R.string.scheduling_admin_common_cancel)
    key.pending?.let {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_developer_key_revoke_prompt, it.name),
            body = stringResource(R.string.scheduling_admin_developer_key_revoke_confirm),
            confirmLabel = stringResource(R.string.scheduling_admin_developer_key_revoke_action),
            cancelLabel = cancel,
            onConfirm = @DontMemoize { removals.keyRevoke.confirm() },
            onCancel = @DontMemoize { removals.keyRevoke.cancel() },
            tag = DEVELOPER_REMOVE_DIALOG_TAG,
            confirmTag = DEVELOPER_REMOVE_CONFIRM_TAG,
        )
    }
    app.pending?.let {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_developer_app_revoke_prompt, it.clientName),
            body = stringResource(R.string.scheduling_admin_developer_app_revoke_confirm),
            confirmLabel = stringResource(R.string.scheduling_admin_developer_app_revoke_action),
            cancelLabel = cancel,
            onConfirm = @DontMemoize { removals.appRevoke.confirm() },
            onCancel = @DontMemoize { removals.appRevoke.cancel() },
            tag = DEVELOPER_REMOVE_DIALOG_TAG,
            confirmTag = DEVELOPER_REMOVE_CONFIRM_TAG,
        )
    }
    hook.pending?.let {
        SchedulingConfirmDialog(
            title = stringResource(R.string.scheduling_admin_developer_webhook_delete_prompt),
            body = stringResource(R.string.scheduling_admin_developer_webhook_delete_confirm),
            confirmLabel = stringResource(R.string.scheduling_admin_developer_webhook_delete_action),
            cancelLabel = cancel,
            onConfirm = @DontMemoize { removals.webhookDelete.confirm() },
            onCancel = @DontMemoize { removals.webhookDelete.cancel() },
            tag = DEVELOPER_REMOVE_DIALOG_TAG,
            confirmTag = DEVELOPER_REMOVE_CONFIRM_TAG,
        )
    }
}

/**
 * A clipboard entry for a secret.
 *
 * ⚠️ MARKED SENSITIVE (`android.content.extra.IS_SENSITIVE`, the spelled-out key so it works below
 * API 33 too), so Android 13 and later do not show it in the clipboard preview.
 */
internal fun schedulingSensitiveClip(label: String, value: String): ClipData =
    ClipData.newPlainText(label, value).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }

private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

const val DEVELOPER_KEY_DIALOG_TAG: String = "district-scheduling-developer-key-dialog"
const val DEVELOPER_KEY_SUBMIT_TAG: String = "district-scheduling-developer-key-submit"
const val DEVELOPER_KEY_NAME_TAG: String = "district-scheduling-developer-key-name"
const val DEVELOPER_WEBHOOK_DIALOG_TAG: String = "district-scheduling-developer-webhook-dialog"
const val DEVELOPER_WEBHOOK_SUBMIT_TAG: String = "district-scheduling-developer-webhook-submit"
const val DEVELOPER_WEBHOOK_URL_TAG: String = "district-scheduling-developer-webhook-url"
const val DEVELOPER_WEBHOOK_DROPPED_TAG: String = "district-scheduling-developer-webhook-dropped"
const val DEVELOPER_WEBHOOK_EVENT_TAG: String = "district-scheduling-developer-webhook-event"
const val DEVELOPER_WEBHOOK_FIELD_TAG: String = "district-scheduling-developer-webhook-field"
const val DEVELOPER_SECRET_DIALOG_TAG: String = "district-scheduling-developer-secret"
const val DEVELOPER_SECRET_VALUE_TAG: String = "district-scheduling-developer-secret-value"
const val DEVELOPER_SECRET_COPY_TAG: String = "district-scheduling-developer-secret-copy"
const val DEVELOPER_SECRET_DONE_TAG: String = "district-scheduling-developer-secret-done"
const val DEVELOPER_REMOVE_DIALOG_TAG: String = "district-scheduling-developer-remove"
const val DEVELOPER_REMOVE_CONFIRM_TAG: String = "district-scheduling-developer-remove-confirm"
