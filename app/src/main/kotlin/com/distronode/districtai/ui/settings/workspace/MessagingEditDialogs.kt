package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.MESSAGING_CHANNELS
import com.distronode.districtai.core.model.MessagingAccount

/**
 * The consequential half of messaging editing: the delete confirmation, the channel picker and the
 * owner's mobile number.
 *
 * ⛔ ITS OWN FILE FOR THE REASON `MembersDialogs` IS ONE — detekt caps a file's top-level function
 * count — and because these three are genuinely separable from the account form: none of them reads
 * [MessagingDraft].
 */

/**
 * Remove one carrier account.
 *
 * ⛔ THE BODY NAMES WHAT IS RELEASED, AND THAT IS THE WHOLE POINT OF THIS DIALOG. Deleting an
 * account drops the hub `PhoneNumberIndex` rows for every number only it held — the rows that route
 * an inbound call or SMS to this workspace and that four sibling routes check ownership against.
 * Once released, ANOTHER TENANT CAN CLAIM THE NUMBER: the carrier probe that guards a first claim
 * will agree with them, because whoever still owns it at the carrier is not us. That is not
 * something "Are you sure?" conveys, and it is the objection the old read-only decision was built
 * on — kept, and answered in words, rather than by hiding the action.
 *
 * ⚠️ THE COUNT COMES FROM THE READ, so it is what this client last saw rather than what the server
 * will actually free. It is the honest number to show and it is the right order of magnitude; the
 * sentence says "released", not "exactly these".
 */
@Composable
internal fun DeleteAccountDialog(
    account: MessagingAccount,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.messaging_delete_confirm_title)) },
        text = {
            Text(
                stringResource(
                    R.string.messaging_delete_confirm_body,
                    account.label.orEmpty().ifBlank { account.id },
                    account.phoneNumbers.size,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_DELETE_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.messaging_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_DELETE_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.messaging_cancel))
            }
        },
    )
}

/**
 * Choose which account sends one channel.
 *
 * ⛔ ONLY REAL ACCOUNTS ARE OFFERED. The platform's managed account is deliberately absent: it has
 * no id, `resolveSendingContext` rejects a `from` no entry in `accounts` owns, and
 * `handleSetChannelDefault` answers 404 for an id the accounts array does not contain. Offering it
 * would be a pickable option that fails twice over.
 *
 * ⚠️ NO "CLEAR" OPTION, BECAUSE THE ROUTE HAS NONE. `channelDefaults` is merged one key at a time
 * and there is no delete branch, so an override set here can be re-pointed but not removed. Better
 * to be missing a control than to offer one that silently does nothing.
 */
@Composable
internal fun ChannelDefaultDialog(
    channel: String,
    accounts: List<MessagingAccount>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(channel) { mutableStateOf(accounts.firstOrNull()?.id) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.messaging_channel_title, channel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                accounts.forEach { account ->
                    DistrictListRow(
                        title = account.label.orEmpty().ifBlank { account.id },
                        subtitle = account.provider,
                        trailing = {
                            RadioButton(
                                selected = account.id == selected,
                                onClick = { selected = account.id },
                                modifier = Modifier.semantics {
                                    contentDescription = messagingChannelOptionDescription(account.id)
                                },
                            )
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_CHANNEL_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.messaging_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_CHANNEL_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.messaging_cancel))
            }
        },
    )
}

/**
 * The three channels an override can be set for.
 *
 * ⛔ EXACTLY THE ROUTE'S OWN LIST. `handleSetChannelDefault` validates against
 * `["sms","voice","whatsapp"]` and answers a 400 naming anything else, so a channel that arrived on
 * the READ but is not one of these renders in the list above and is not offered here.
 *
 * ⚠️ HIDDEN WHEN THERE IS NO ACCOUNT TO POINT AT. Every option would fail the route's "Account not
 * found" check, and a picker with nothing in it is a dead end rather than a feature.
 */
@Composable
internal fun ChannelPickerLauncher(state: MessagingUiState, onRequestChannel: (String) -> Unit) {
    if (state.accounts.isEmpty()) return
    Column(
        modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        MESSAGING_CHANNELS.forEach { channel ->
            DistrictButton(
                text = stringResource(R.string.messaging_channel_title, channel),
                onClick = { onRequestChannel(channel) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                enabled = state.canEditNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = messagingChannelSetDescription(channel) },
            )
        }
    }
}

/**
 * The workspace owner's mobile number.
 *
 * ⛔ NEVER PRE-FILLED, AND THE HELP TEXT SAYS SO. Nothing this client can read returns the stored
 * value — it is not on the messaging GET and the `meta` write echoes only `{success:true}` — so a
 * field seeded from what we know could only be blank, which is exactly the shape that saves a blank
 * over a real number. Same reasoning, and the same remedy, as the workspace rename.
 *
 * ⚠️ ITS OWN ACTION RATHER THAN A FIELD ON THE ACCOUNT FORM. A workspace with no carrier account has
 * no upsert to attach it to, and that is the workspace most likely to be setting it.
 */
@Composable
internal fun CreatorCellSection(
    state: MessagingUiState,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.messaging_section_creator_cell))
        Text(
            text = stringResource(R.string.messaging_creator_cell_help),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.semantics {
                contentDescription = MESSAGING_CREATOR_CELL_HELP_DESCRIPTION
            },
        )
        SettingsTextField(
            value = state.creatorCellDraft,
            label = stringResource(R.string.messaging_creator_cell_label),
            description = MESSAGING_CREATOR_CELL_DESCRIPTION,
            enabled = !state.busy,
            singleLine = true,
            onValueChange = onEdit,
        )
        SaveNotice(state = state.metaSave, description = MESSAGING_META_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(R.string.messaging_creator_cell_save),
            onClick = onSave,
            enabled = state.canSaveCreatorCell,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = MESSAGING_CREATOR_CELL_SAVE_DESCRIPTION },
        )
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MESSAGING_DELETE_CONFIRM_DESCRIPTION: String = "district-messaging-delete-confirm"
const val MESSAGING_DELETE_CANCEL_DESCRIPTION: String = "district-messaging-delete-cancel"
const val MESSAGING_CHANNEL_CONFIRM_DESCRIPTION: String = "district-messaging-channel-confirm"
const val MESSAGING_CHANNEL_CANCEL_DESCRIPTION: String = "district-messaging-channel-cancel"
const val MESSAGING_CREATOR_CELL_DESCRIPTION: String = "district-messaging-creator-cell"
const val MESSAGING_CREATOR_CELL_HELP_DESCRIPTION: String = "district-messaging-creator-cell-help"
const val MESSAGING_CREATOR_CELL_SAVE_DESCRIPTION: String =
    "district-messaging-creator-cell-save"
const val MESSAGING_META_NOTICE_DESCRIPTION: String = "district-messaging-meta-notice"
