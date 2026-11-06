package com.distronode.districtai.ui.inbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictFilterChip
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.ReplyTarget
import com.distronode.districtai.core.model.WhatsAppTemplate
import com.distronode.districtai.core.model.jsTrim
import com.distronode.districtai.l10n.inboxChannelLabel
import com.distronode.districtai.ui.resolve

/**
 * The composer's channel picker and WhatsApp template sheet, handed in by the nav graph.
 *
 * ⚠️ A STATE AND ITS CALLBACKS IN ONE VALUE, for the reason [ComposerHandlers] gives: both come from
 * the same [ReplyChannels], so a screen cannot draw one channel while sending on another.
 */
data class ReplyChannelHandlers(
    val state: ReplyChannelState,
    val onChoose: (ReplyTarget) -> Unit,
    val onOpenTemplates: () -> Unit,
    val onDismissTemplates: () -> Unit,
    val onChooseTemplate: (WhatsAppTemplate) -> Unit,
    val onTemplateValue: (Int, String) -> Unit,
    val onSendTemplate: () -> Unit,
)

/** [state] with this controller's callbacks. */
fun ReplyChannels.handlers(state: ReplyChannelState): ReplyChannelHandlers = ReplyChannelHandlers(
    state = state,
    onChoose = ::choose,
    onOpenTemplates = ::openTemplates,
    onDismissTemplates = ::dismissTemplates,
    onChooseTemplate = ::chooseTemplate,
    onTemplateValue = ::setTemplateValue,
    onSendTemplate = ::sendTemplate,
)

/**
 * The channel chips, labelled with the channel's name, only when there is a choice to make.
 *
 * ⚠️ A TAP WHILE A SEND IS IN FLIGHT IS REFUSED BY [ReplyChannels.choose], not by disabling the
 * chips: a chip greyed out mid-send would read as "this channel is gone".
 */
@Composable
internal fun ReplyChannelPicker(channels: ReplyChannelHandlers) {
    val state = channels.state
    if (state.choices.size < 2) return
    ContentContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .testTag(THREAD_CHANNELS_DESCRIPTION),
        ) {
            Eyebrow(stringResource(R.string.thread_reply_channel))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
                modifier = Modifier.selectableGroup(),
            ) {
                state.choices.forEachIndexed { index, choice ->
                    DistrictFilterChip(
                        label = inboxChannelLabel(choice.channel),
                        selected = choice == state.selected,
                        onClick = { channels.onChoose(choice) },
                        modifier = Modifier
                            .testTag(THREAD_CHANNEL_CHIP_DESCRIPTION + index)
                            .oneOfMany(),
                    )
                }
            }
        }
    }
}

/**
 * Why the composer cannot send free text as it stands, and what it may send: attachments on a
 * channel that cannot carry them, WhatsApp's closed window (with the way forward, a template), and
 * Messenger's or Instagram's window (closed, or open for a human agent's reply only).
 */
@Composable
internal fun ComposerNotices(state: ThreadUiState.Content, channels: ReplyChannelHandlers?) {
    val via = channels?.state ?: return
    if (state.attachments.isNotEmpty() && !via.attachmentsAllowed) {
        Notice(stringResource(R.string.thread_attachments_sms_only), THREAD_ATTACHMENTS_SMS_ONLY_DESCRIPTION)
    }
    MetaWindowNotice(via.meta)
    if (via.whatsApp is WhatsAppWindowState.Closed) {
        ContentContainer {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DistrictTheme.spacing.gutter)
                    .testTag(THREAD_WINDOW_CLOSED_DESCRIPTION),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                Text(
                    text = stringResource(R.string.thread_whatsapp_window_closed),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                DistrictButton(
                    text = stringResource(R.string.thread_whatsapp_template_label),
                    onClick = channels.onOpenTemplates,
                    size = ButtonSize.Sm,
                    modifier = Modifier.testTag(THREAD_TEMPLATE_OPEN_DESCRIPTION),
                )
            }
        }
    }
    if (via.sheet.open) TemplateSheet(channels)
}

/**
 * The web composer's two Meta lines, in its words: a badge for the human agent window, a warning
 * when closed. ⚠️ No countdown, as the web shows none: `until` is not drawn.
 *
 * ⚠️ BOTH ARE LIVE REGIONS: they arrive after the window is read (or a send is refused), below
 * where TalkBack's focus is, so without one a screen reader user is never told the window shut.
 */
@Composable
private fun MetaWindowNotice(meta: MetaWindowState) {
    when (meta) {
        MetaWindowState.CLOSED ->
            Notice(stringResource(R.string.thread_meta_window_closed), THREAD_META_WINDOW_CLOSED_DESCRIPTION)
        MetaWindowState.HUMAN_AGENT -> ContentContainer {
            DistrictBadge(
                text = stringResource(R.string.thread_meta_human_agent),
                tone = Tone.Info,
                modifier = Modifier
                    .padding(horizontal = DistrictTheme.spacing.gutter)
                    .testTag(THREAD_META_HUMAN_AGENT_DESCRIPTION)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        else -> Unit
    }
}

@Composable
private fun Notice(text: String, tag: String) {
    ContentContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .testTag(tag)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * Pick an approved template, fill one field per `{{n}}`, read the preview, send.
 *
 * ⛔ SEND TEMPLATE IS DISABLED UNTIL EVERY VARIABLE IS FILLED AND WHILE ONE IS ON ITS WAY (billable).
 */
@Composable
private fun TemplateSheet(channels: ReplyChannelHandlers) {
    val state = channels.state
    val sheet = state.sheet
    AlertDialog(
        onDismissRequest = channels.onDismissTemplates,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.thread_whatsapp_template_label)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .testTag(THREAD_TEMPLATE_SHEET_DESCRIPTION),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                TemplateChoices(state.whatsApp, sheet, channels.onChooseTemplate)
                sheet.chosen?.let { TemplateFields(sheet, channels.onTemplateValue) }
                sheet.failure?.let { failure ->
                    Text(
                        text = failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier
                            .testTag(THREAD_TEMPLATE_FAILURE_DESCRIPTION)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            DistrictButton(
                text = stringResource(
                    if (sheet.sending) R.string.thread_sending else R.string.thread_whatsapp_template_send,
                ),
                onClick = channels.onSendTemplate,
                enabled = sheet.complete && !sheet.sending,
                modifier = Modifier.testTag(THREAD_TEMPLATE_SEND_DESCRIPTION),
            )
        },
        dismissButton = {
            DistrictButton(
                text = stringResource(R.string.inbox_compose_cancel),
                onClick = channels.onDismissTemplates,
                variant = ButtonVariant.Ghost,
                enabled = !sheet.sending,
            )
        },
    )
}

@Composable
private fun TemplateChoices(
    window: WhatsAppWindowState,
    sheet: TemplateSheetState,
    onChoose: (WhatsAppTemplate) -> Unit,
) {
    val templates = (window as? WhatsAppWindowState.Closed)?.templates
    val ready = (templates as? WhatsAppTemplatesState.Ready)?.templates.orEmpty()
    val message = when {
        templates == WhatsAppTemplatesState.Loading -> R.string.thread_whatsapp_templates_loading
        templates == WhatsAppTemplatesState.Failed -> R.string.thread_whatsapp_templates_failed
        ready.isEmpty() -> R.string.thread_whatsapp_templates_none
        else -> null
    }
    if (message != null) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .testTag(THREAD_TEMPLATE_STATUS_DESCRIPTION)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        return
    }
    Eyebrow(stringResource(R.string.thread_whatsapp_template_placeholder))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        modifier = Modifier.selectableGroup(),
    ) {
        ready.forEachIndexed { index, template ->
            DistrictFilterChip(
                label = stringResource(R.string.thread_whatsapp_template_option, template.name, template.language),
                selected = template == sheet.chosen,
                onClick = { onChoose(template) },
                modifier = Modifier
                    .testTag(THREAD_TEMPLATE_OPTION_DESCRIPTION + index)
                    .oneOfMany(),
            )
        }
    }
}

/** One field per `{{n}}`, then the text as the customer will read it. */
@Composable
private fun TemplateFields(sheet: TemplateSheetState, onValue: (Int, String) -> Unit) {
    sheet.values.forEachIndexed { index, value ->
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(index, it) },
            label = {
                Eyebrow(stringResource(R.string.thread_whatsapp_template_variable, "{{${index + 1}}}"))
            },
            // ⚠️ JavaScript's trim, as Send's `complete` measures: Kotlin's isBlank disagrees on
            // U+FEFF, which left Send off with no hint saying why.
            supportingText = if (jsTrim(value).isEmpty()) {
                { Text(stringResource(R.string.thread_whatsapp_template_variable_required)) }
            } else {
                null
            },
            enabled = !sheet.sending,
            singleLine = true,
            colors = districtFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(THREAD_TEMPLATE_VARIABLE_DESCRIPTION + index),
        )
    }
    Eyebrow(stringResource(R.string.thread_whatsapp_template_preview))
    Text(
        text = sheet.preview,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(THREAD_TEMPLATE_PREVIEW_DESCRIPTION),
    )
}

/**
 * A chip that is one choice of several (a channel, a template): TalkBack reads a radio button.
 *
 * ⚠️ A filter chip reads as a checkbox ("checked"), which promises that several can be on at once.
 */
private fun Modifier.oneOfMany(): Modifier = semantics { role = Role.RadioButton }

/** Stable handles for tests. Those ending in `-` take the item's index. */
const val THREAD_CHANNELS_DESCRIPTION: String = "district-thread-channels"
const val THREAD_CHANNEL_CHIP_DESCRIPTION: String = "district-thread-channel-"
const val THREAD_ATTACHMENTS_SMS_ONLY_DESCRIPTION: String = "district-thread-attachments-sms-only"
const val THREAD_WINDOW_CLOSED_DESCRIPTION: String = "district-thread-window-closed"
const val THREAD_TEMPLATE_OPEN_DESCRIPTION: String = "district-thread-template-open"
const val THREAD_META_WINDOW_CLOSED_DESCRIPTION: String = "district-thread-meta-window-closed"
const val THREAD_META_HUMAN_AGENT_DESCRIPTION: String = "district-thread-meta-human-agent"
const val THREAD_TEMPLATE_SHEET_DESCRIPTION: String = "district-thread-template-sheet"
const val THREAD_TEMPLATE_STATUS_DESCRIPTION: String = "district-thread-template-status"
const val THREAD_TEMPLATE_OPTION_DESCRIPTION: String = "district-thread-template-option-"
const val THREAD_TEMPLATE_VARIABLE_DESCRIPTION: String = "district-thread-template-variable-"
const val THREAD_TEMPLATE_PREVIEW_DESCRIPTION: String = "district-thread-template-preview"
const val THREAD_TEMPLATE_SEND_DESCRIPTION: String = "district-thread-template-send"
const val THREAD_TEMPLATE_FAILURE_DESCRIPTION: String = "district-thread-template-failure"
