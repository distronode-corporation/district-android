package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.ManagedAccount
import com.distronode.districtai.core.model.MessagingAccount

/**
 * Which carrier accounts this workspace sends through, and — for agency and client — how to change
 * them.
 *
 * Two facts shape the editing UI:
 *
 *   - The read is redacted, and the redaction is what makes an in-app form safe: a blank
 *     credential box means "keep the saved value", so editing a label or a phone number list types
 *     no secret at all. The form says that in as many words.
 *   - A delete releases the phone-number claims that stop another tenant sending as this one. It
 *     is the one irreversible action here, so it is confirmed with wording that names what is
 *     released rather than with "are you sure".
 *
 * ⛔ A VIEWER SEES A READ-ONLY SCREEN. Every write on this route excludes `viewer`, so not one
 * control is drawn for them and a caption says why; the settings hub is open to viewers, so this
 * is a real audience. `MessagingScreenTest` asserts the ABSENCE of every editable handle in that
 * case; the absence is the feature.
 *
 * ⛔ THE PLATFORM'S ACCOUNT IS DRAWN AS ITS OWN SECTION, NOT AS A ROW IN THE LIST, AND IT HAS NO
 * CONTROLS. It has no id and is not a valid sender identity — `resolveSendingContext` rejects a
 * `from` that no account in `accounts` owns — so listing it alongside the others would present a
 * pickable sender whose every send fails, and offering "make default" on it would 404.
 */
@Composable
fun MessagingScreen(
    state: MessagingUiState,
    /** ⚠️ `null` opens a create, an id opens an edit — see `MessagingViewModel.startEditing`. */
    onStartEditing: (String?) -> Unit,
    /** ⚠️ `null` closes the form — see `MessagingViewModel.editDraft`. */
    onEditDraft: (MessagingDraft?) -> Unit,
    onSaveAccount: () -> Unit,
    onTestCredentials: () -> Unit,
    onSetDefault: (String) -> Unit,
    onSetChannelDefault: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onEditCreatorCell: (String) -> Unit,
    onSaveCreatorCell: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ `remember`, not `rememberSaveable`: a `MessagingAccount` is not Parcelable, and a delete
    // dialog that outlived a rotation holding a stale row would confirm a release against whichever
    // account the reloaded list had moved into that position. Dismissing on rotation is the safe
    // direction — the same call `MembersScreen` makes for the same reason.
    var pendingDelete by remember { mutableStateOf<MessagingAccount?>(null) }
    var pendingChannel by remember { mutableStateOf<String?>(null) }

    pendingDelete?.let { account ->
        DeleteAccountDialog(
            account = account,
            onConfirm = {
                pendingDelete = null
                onDelete(account.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    pendingChannel?.let { channel ->
        ChannelDefaultDialog(
            channel = channel,
            accounts = state.accounts,
            onConfirm = { accountId ->
                pendingChannel = null
                onSetChannelDefault(channel, accountId)
            },
            onDismiss = { pendingChannel = null },
        )
    }

    state.draft?.let { draft ->
        MessagingAccountDialog(
            state = state,
            draft = draft,
            onEditDraft = onEditDraft,
            onTest = onTestCredentials,
            onConfirm = onSaveAccount,
            onDismiss = { onEditDraft(null) },
        )
    }

    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = MESSAGING_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.messaging_title), onBack = onBack)
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            MessagingContent(
                state = state,
                onRetry = onRetry,
                onStartEditing = onStartEditing,
                onSetDefault = onSetDefault,
                onRequestDelete = { pendingDelete = it },
                onRequestChannel = { pendingChannel = it },
                onEditCreatorCell = onEditCreatorCell,
                onSaveCreatorCell = onSaveCreatorCell,
            )
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * The accounts, the platform account, the channel defaults, and — for a mutating role — the forms.
 *
 * ⚠️ THE CAPTION IS NOW THE VIEWER'S BRANCH RATHER THAN EVERYONE'S. It used to say where a change
 * happens because no change happened here; saying that to someone looking at an Edit button would
 * be false.
 */
@Composable
private fun MessagingContent(
    state: MessagingUiState,
    onRetry: () -> Unit,
    onStartEditing: (String?) -> Unit,
    onSetDefault: (String) -> Unit,
    onRequestDelete: (MessagingAccount) -> Unit,
    onRequestChannel: (String) -> Unit,
    onEditCreatorCell: (String) -> Unit,
    onSaveCreatorCell: () -> Unit,
) {
    ContentContainer {
        AccountsSection(state, onRetry, onStartEditing, onSetDefault, onRequestDelete)
    }
    if (state.canEdit) {
        ContentContainer { AddAccountSection(state) { onStartEditing(null) } }
    }
    state.messaging?.managedAccount?.let { managed ->
        ContentContainer { ManagedSection(managed) }
    }
    ContentContainer { ChannelDefaultsSection(state, onRequestChannel) }
    if (state.canEdit) {
        ContentContainer { CreatorCellSection(state, onEditCreatorCell, onSaveCreatorCell) }
    } else {
        ContentContainer {
            Text(
                text = stringResource(R.string.messaging_read_only),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(DistrictTheme.spacing.gutter)
                    .semantics { contentDescription = MESSAGING_READ_ONLY_DESCRIPTION },
            )
        }
    }
}

/**
 * The workspace's own carrier accounts.
 *
 * ⚠️ THE DEFAULT IS MARKED ON THE ROW rather than stated separately, because "which of these
 * actually sends" is the question and a sentence underneath a list does not answer it at a glance.
 *
 * ⚠️ "Make default" IS ABSENT ON THE ROW THAT ALREADY IS ONE, rather than disabled. The badge is
 * already there; a greyed-out button beside it adds a control that can never do anything.
 */
@Composable
private fun AccountsSection(
    state: MessagingUiState,
    onRetry: () -> Unit,
    onStartEditing: (String?) -> Unit,
    onSetDefault: (String) -> Unit,
    onRequestDelete: (MessagingAccount) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.messaging_section_accounts),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
            SaveNotice(state = state.defaultSave, description = MESSAGING_DEFAULT_NOTICE_DESCRIPTION)
            SaveNotice(state = state.deleteSave, description = MESSAGING_DELETE_NOTICE_DESCRIPTION)
        }
        when (val load = state.load) {
            MessagingLoadState.Loading -> ConfigSkeleton()
            is MessagingLoadState.Failed ->
                ConfigLoadFailure(failure = load.failure, onRetry = onRetry)
            is MessagingLoadState.Ready ->
                if (load.messaging.accounts.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.messaging_empty_title),
                        body = stringResource(R.string.messaging_empty_body),
                        modifier = Modifier.semantics {
                            contentDescription = MESSAGING_EMPTY_DESCRIPTION
                        },
                    )
                } else {
                    load.messaging.accounts.forEachIndexed { index, account ->
                        if (index > 0) DistrictRowDivider()
                        AccountRow(
                            account = account,
                            isDefault = account.id == load.messaging.defaultAccountId,
                            state = state,
                            onStartEditing = onStartEditing,
                            onSetDefault = onSetDefault,
                            onRequestDelete = onRequestDelete,
                        )
                    }
                }
        }
    }
}

/** One carrier account, with its controls when the role admits them. */
@Composable
private fun AccountRow(
    account: MessagingAccount,
    isDefault: Boolean,
    state: MessagingUiState,
    onStartEditing: (String?) -> Unit,
    onSetDefault: (String) -> Unit,
    onRequestDelete: (MessagingAccount) -> Unit,
) {
    DistrictListRow(
        title = account.label.orEmpty().ifBlank { account.provider.orEmpty() },
        subtitle = stringResource(
            R.string.messaging_account_subtitle,
            account.provider ?: stringResource(R.string.messaging_provider_unknown),
            account.credentialSource ?: stringResource(R.string.messaging_provider_unknown),
            account.phoneNumbers.size,
        ),
        trailing = {
            Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                if (isDefault) {
                    Text(
                        text = stringResource(R.string.messaging_default_badge),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.district,
                    )
                }
                if (state.canEdit) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                        if (!isDefault) {
                            DistrictButton(
                                text = stringResource(R.string.messaging_make_default_action),
                                onClick = { onSetDefault(account.id) },
                                variant = ButtonVariant.Ghost,
                                size = ButtonSize.Sm,
                                enabled = !state.busy,
                                modifier = Modifier.semantics {
                                    contentDescription = messagingDefaultDescription(account.id)
                                },
                            )
                        }
                        DistrictButton(
                            text = stringResource(R.string.messaging_edit),
                            onClick = { onStartEditing(account.id) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            enabled = !state.busy,
                            modifier = Modifier.semantics {
                                contentDescription = messagingEditDescription(account.id)
                            },
                        )
                        DistrictButton(
                            text = stringResource(R.string.messaging_delete),
                            onClick = { onRequestDelete(account) },
                            variant = ButtonVariant.Ghost,
                            size = ButtonSize.Sm,
                            enabled = !state.busy,
                            modifier = Modifier.semantics {
                                contentDescription = messagingRemoveDescription(account.id)
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.semantics {
            contentDescription = messagingAccountDescription(account.id)
        },
    )
}

/**
 * The entry point to the add form.
 *
 * ⚠️ ITS OWN SECTION RATHER THAN A ROW IN THE LIST, so it is still reachable when the list is empty
 * — which is the workspace most likely to need it — and so the account save's banner has somewhere
 * to live that is not attached to a particular row.
 */
@Composable
private fun AddAccountSection(state: MessagingUiState, onStartCreate: () -> Unit) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        SaveNotice(state = state.accountSave, description = MESSAGING_ACCOUNT_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(R.string.messaging_add),
            onClick = onStartCreate,
            enabled = state.canEditNow,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = MESSAGING_ADD_DESCRIPTION },
        )
    }
}

/**
 * Numbers Distronode bought on this workspace's behalf.
 *
 * ⛔ ITS OWN SECTION WITH NO ID, NO DEFAULT BADGE AND NO CONTROLS. See the ⛔ on the screen: it is
 * not a sender identity the API will accept, so every write that takes an `accountId` would 404 on
 * it.
 */
@Composable
private fun ManagedSection(managed: ManagedAccount) {
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.messaging_section_managed),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        DistrictListRow(
            title = managed.provider ?: stringResource(R.string.messaging_provider_unknown),
            subtitle = stringResource(
                R.string.messaging_managed_subtitle,
                managed.phoneNumbers.size,
            ),
            modifier = Modifier.semantics {
                contentDescription = MESSAGING_MANAGED_DESCRIPTION
            },
        )
    }
}

/**
 * Per-channel sender overrides.
 *
 * ⚠️ AN OPEN MAP: a channel added server-side renders here rather than being dropped. The SETTER
 * offers only the three the route validates, because sending a fourth is a 400 naming the value.
 *
 * ⚠️ DRAWN EVEN WHEN THE MAP IS EMPTY FOR AN EDITING ROLE, because "no overrides" is where the
 * control to add one has to live. A viewer with no overrides still sees nothing.
 */
@Composable
private fun ChannelDefaultsSection(state: MessagingUiState, onRequestChannel: (String) -> Unit) {
    val defaults = state.messaging?.channelDefaults.orEmpty()
    if (defaults.isEmpty() && !state.canEdit) return
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.messaging_section_channels),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
            SaveNotice(state = state.channelSave, description = MESSAGING_CHANNEL_NOTICE_DESCRIPTION)
        }
        defaults.entries.forEachIndexed { index, (channel, accountId) ->
            if (index > 0) DistrictRowDivider()
            DistrictListRow(
                title = channel,
                // ⚠️ The account's LABEL when it resolves, else the raw id. A channel default can
                // legitimately name an account that was deleted, and hiding that would make a
                // broken override look like no override.
                subtitle = state.accounts.firstOrNull { it.id == accountId }?.label ?: accountId,
                modifier = Modifier.semantics {
                    contentDescription = messagingChannelDescription(channel)
                },
            )
        }
        if (state.canEdit) {
            ChannelPickerLauncher(state = state, onRequestChannel = onRequestChannel)
        }
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MESSAGING_ROOT_DESCRIPTION: String = "district-messaging-root"
const val MESSAGING_EMPTY_DESCRIPTION: String = "district-messaging-empty"
const val MESSAGING_MANAGED_DESCRIPTION: String = "district-messaging-managed"
const val MESSAGING_READ_ONLY_DESCRIPTION: String = "district-messaging-read-only"
const val MESSAGING_ADD_DESCRIPTION: String = "district-messaging-add"
const val MESSAGING_ACCOUNT_NOTICE_DESCRIPTION: String = "district-messaging-account-notice"
const val MESSAGING_DEFAULT_NOTICE_DESCRIPTION: String = "district-messaging-default-notice"
const val MESSAGING_DELETE_NOTICE_DESCRIPTION: String = "district-messaging-delete-notice"
const val MESSAGING_CHANNEL_NOTICE_DESCRIPTION: String = "district-messaging-channel-notice"
