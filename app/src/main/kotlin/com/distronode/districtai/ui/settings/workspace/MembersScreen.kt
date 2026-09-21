package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole

/**
 * Who belongs to this workspace, and what it is called.
 *
 * ⛔ THREE ROLES SEE THREE DIFFERENT SCREENS, AND THE ROSTER IS COMMON TO ALL OF THEM. A viewer
 * sees the list and nothing else; a client sees the list and the rename field; an agency operator
 * sees the list, the rename field, and the add/change/remove controls. That is the server's own
 * split — the read admits all three, `rename` admits agency and client, and the membership writes
 * admit agency alone — and it is why the screen takes two independent flags rather than one
 * `canMutate`.
 *
 * ⛔ THE TWO 409 REFUSALS ARE SHOWN AS THEIR OWN SENTENCES. "Already a member" is something the
 * operator can act on; "every workspace needs at least one agency member" is not a mistake at all,
 * and wording it as a validation error would send someone looking for a typo. Neither offers a
 * retry.
 *
 * ⛔ AND REMOVING SOMEONE IS CONFIRMED, BECAUSE IT IS IMMEDIATE AND NOT AN UNDO. The next request
 * that person makes resolves no role in this workspace; re-adding them creates a new row rather
 * than restoring the old one.
 *
 * ⚠️ THE RENAME FIELD ASKS FOR A NEW NAME AND DOES NOT PREFILL. Nothing this client can call
 * returns the workspace's current name, so a "current value" here could only be a blank — which is
 * how a form saves over a real value. Once a rename has landed, the confirmed name is shown from
 * the server's own echo. See the ⛔ on [MembersUiState].
 */
@Composable
fun MembersScreen(
    state: MembersUiState,
    onEditEmail: (String) -> Unit,
    onEditRole: (WorkspaceRole) -> Unit,
    onAdd: () -> Unit,
    onChangeRole: (String, WorkspaceRole) -> Unit,
    onRemove: (String) -> Unit,
    onEditName: (String) -> Unit,
    onRename: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ `rememberSaveable` for the dialog flag and plain `remember` for the pending rows. The flag
    // is a Boolean and survives a rotation for free; a `WorkspaceMember` is not `Parcelable`, and a
    // dialog that outlived a rotation with a stale row would confirm a removal against whoever the
    // list had moved into that position. Dismissing on rotation is the safe direction.
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<WorkspaceMember?>(null) }
    var pendingRole by remember { mutableStateOf<WorkspaceMember?>(null) }

    if (showAdd) {
        AddMemberDialog(
            state = state,
            onEditEmail = onEditEmail,
            onEditRole = onEditRole,
            onConfirm = {
                showAdd = false
                onAdd()
            },
            onDismiss = { showAdd = false },
        )
    }

    pendingRemove?.let { member ->
        RemoveMemberDialog(
            email = member.email,
            onConfirm = {
                pendingRemove = null
                onRemove(member.email)
            },
            onDismiss = { pendingRemove = null },
        )
    }

    pendingRole?.let { member ->
        RoleChangeDialog(
            member = member,
            onConfirm = { role ->
                pendingRole = null
                onChangeRole(member.email, role)
            },
            onDismiss = { pendingRole = null },
        )
    }

    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = MEMBERS_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.members_title), onBack = onBack)
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            ContentContainer {
                MembersRoster(
                    state = state,
                    onRetry = onRetry,
                    onRequestRole = { pendingRole = it },
                    onRequestRemove = { pendingRemove = it },
                )
            }
            if (state.canManage) {
                ContentContainer { MembersAddSection(state) { showAdd = true } }
            }
            if (state.canRename) {
                ContentContainer { MembersRenameSection(state, onEditName, onRename) }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * The roster.
 *
 * ⛔ RENDERED FOR EVERY ROLE, INCLUDING `viewer`, AND THAT IS DELIBERATE RATHER THAN AN OVERSIGHT
 * IN THE GATING. The route admits all three because a viewer who cannot see who else is in the
 * workspace cannot tell who to ask for help. Only the per-row controls are gated.
 *
 * ⚠️ OLDEST FIRST — the server's `createdAt asc`, so the founding member is at the top and the list
 * grows at the bottom rather than reshuffling as people join.
 */
@Composable
private fun MembersRoster(
    state: MembersUiState,
    onRetry: () -> Unit,
    onRequestRole: (WorkspaceMember) -> Unit,
    onRequestRemove: (WorkspaceMember) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.members_section_roster),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
            SaveNotice(state = state.roleSave, description = MEMBERS_ROLE_NOTICE_DESCRIPTION)
            SaveNotice(state = state.removeSave, description = MEMBERS_REMOVE_NOTICE_DESCRIPTION)
        }
        when (val list = state.list) {
            MembersListState.Loading -> ConfigSkeleton()
            is MembersListState.Failed -> ConfigLoadFailure(failure = list.failure, onRetry = onRetry)
            is MembersListState.Ready ->
                if (list.members.isEmpty()) {
                    // ⚠️ Should be unreachable — the caller had to be a member to read this at all
                    // — so it is worded as "we could not find anyone" rather than as an invitation
                    // to add the first person.
                    EmptyState(
                        title = stringResource(R.string.members_empty_title),
                        body = stringResource(R.string.members_empty_body),
                        modifier = Modifier.semantics {
                            contentDescription = MEMBERS_EMPTY_DESCRIPTION
                        },
                    )
                } else {
                    list.members.forEachIndexed { index, member ->
                        if (index > 0) DistrictRowDivider()
                        MemberRow(member, state, onRequestRole, onRequestRemove)
                    }
                }
        }
    }
}

/**
 * One member.
 *
 * ⛔ NO "THIS IS YOU" HIGHLIGHT, AND ITS ABSENCE IS A FACT ABOUT THE CLIENT RATHER THAN A DESIGN
 * CHOICE. Nothing this app holds is the signed-in address: no response DTO carries it (the
 * workspace list, the overview and the device list were all checked), and the token store keeps
 * opaque credentials rather than claims. Marking a row without that would mean guessing, and
 * guessing wrong on this screen means labelling a colleague as the operator themselves.
 *
 * ⚠️ THE ROLE IS SHOWN VERBATIM WHEN IT IS ONE THIS BUILD DOES NOT KNOW. `role` is a free-text
 * column and older rows written by a server-side replace can be capitalised differently or hold a
 * value that is not in this enum; rendering it as itself is honest, whereas mapping it to a guess
 * would tell an operator someone has privileges they may not have.
 */
@Composable
private fun MemberRow(
    member: WorkspaceMember,
    state: MembersUiState,
    onRequestRole: (WorkspaceMember) -> Unit,
    onRequestRemove: (WorkspaceMember) -> Unit,
) {
    val parsed = WorkspaceRole.fromWire(member.role)
    DistrictListRow(
        title = member.email,
        subtitle = if (parsed != null) {
            stringResource(roleLabelFor(parsed))
        } else {
            stringResource(R.string.members_role_unknown, member.role.orEmpty())
        },
        trailing = {
            if (state.canManage) {
                Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                    DistrictButton(
                        text = stringResource(R.string.members_change_role),
                        onClick = { onRequestRole(member) },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        enabled = !state.busy,
                        modifier = Modifier.semantics {
                            contentDescription = memberRoleDescription(member.email)
                        },
                    )
                    DistrictButton(
                        text = stringResource(R.string.members_remove),
                        onClick = { onRequestRemove(member) },
                        variant = ButtonVariant.Ghost,
                        size = ButtonSize.Sm,
                        enabled = !state.busy,
                        modifier = Modifier.semantics {
                            contentDescription = memberRemoveDescription(member.email)
                        },
                    )
                }
            }
        },
        modifier = Modifier.semantics { contentDescription = memberRowDescription(member.email) },
    )
}

/**
 * The entry point to the add dialog, and the one place this screen states what adding does NOT do.
 *
 * ⛔ NO INVITATION IS SENT AND NO ACCOUNT IS CREATED. The route's header is explicit that
 * provisioning is a separate feature; without saying so here, an operator adds an address and
 * waits for an email that is never coming.
 */
@Composable
private fun MembersAddSection(state: MembersUiState, onOpen: () -> Unit) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.members_section_add))
        Text(
            text = stringResource(R.string.members_add_no_invite),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.semantics { contentDescription = MEMBERS_NO_INVITE_DESCRIPTION },
        )
        SaveNotice(state = state.addSave, description = MEMBERS_ADD_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(R.string.members_add),
            onClick = onOpen,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = MEMBERS_ADD_OPEN_DESCRIPTION },
        )
    }
}

/**
 * Rename the workspace.
 *
 * ⛔ THE FIELD IS EMPTY AND ASKS FOR A NEW NAME. See the ⛔ on [MembersUiState]: there is no read
 * on this client that returns the current name, and a field prefilled from nothing is how a save
 * writes a blank over a real value. [MembersUiState.storedName] is shown only once the server has
 * confirmed one, which is the only name this client can vouch for.
 *
 * ⚠️ OFFERED TO `client` AS WELL AS `agency`, unlike everything above it — the rename route's guard
 * is genuinely wider than the membership routes'.
 */
@Composable
private fun MembersRenameSection(
    state: MembersUiState,
    onEditName: (String) -> Unit,
    onRename: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.members_section_rename))
        state.storedName?.let { name ->
            Text(
                text = stringResource(R.string.members_rename_current, name),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_RENAME_CURRENT_DESCRIPTION
                },
            )
        }
        SettingsTextField(
            value = state.renameDraft,
            label = stringResource(R.string.members_rename_label),
            description = MEMBERS_RENAME_FIELD_DESCRIPTION,
            enabled = !state.busy && state.loaded,
            singleLine = true,
            onValueChange = onEditName,
        )
        SaveNotice(state = state.renameSave, description = MEMBERS_RENAME_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(R.string.members_rename_action),
            onClick = onRename,
            enabled = state.canRenameNow,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = MEMBERS_RENAME_DESCRIPTION },
        )
    }
}

/** Per-row handles, derived in one place so a row and its test cannot drift. */
internal fun memberRowDescription(email: String): String = "district-member-row-$email"

internal fun memberRoleDescription(email: String): String = "district-member-role-$email"

internal fun memberRemoveDescription(email: String): String = "district-member-remove-$email"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MEMBERS_ROOT_DESCRIPTION: String = "district-members-root"
const val MEMBERS_EMPTY_DESCRIPTION: String = "district-members-empty"
const val MEMBERS_ADD_OPEN_DESCRIPTION: String = "district-members-add-open"
const val MEMBERS_NO_INVITE_DESCRIPTION: String = "district-members-no-invite"
const val MEMBERS_ADD_NOTICE_DESCRIPTION: String = "district-members-add-notice"
const val MEMBERS_ROLE_NOTICE_DESCRIPTION: String = "district-members-role-notice"
const val MEMBERS_REMOVE_NOTICE_DESCRIPTION: String = "district-members-remove-notice"
const val MEMBERS_RENAME_FIELD_DESCRIPTION: String = "district-members-rename-field"
const val MEMBERS_RENAME_DESCRIPTION: String = "district-members-rename"
const val MEMBERS_RENAME_NOTICE_DESCRIPTION: String = "district-members-rename-notice"
const val MEMBERS_RENAME_CURRENT_DESCRIPTION: String = "district-members-rename-current"
