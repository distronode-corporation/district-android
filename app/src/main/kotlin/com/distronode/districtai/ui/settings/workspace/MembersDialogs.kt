package com.distronode.districtai.ui.settings.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.ASSIGNABLE_MEMBER_ROLES
import com.distronode.districtai.core.model.DEFAULT_MEMBER_ROLE
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.toWire

/**
 * The three membership dialogs.
 *
 * ⛔ A SEPARATE FILE FROM `MembersScreen` FOR THE REASON `ContractFixtures` WAS EXTRACTED IN THE
 * CONTRACT TESTS: detekt caps a file's top-level function count, and the healthy answer is another
 * file rather than a raised threshold. They are also genuinely separable — nothing here reads
 * [MembersUiState.list].
 */

/**
 * Add one member.
 *
 * ⛔ THE CONFIRM BUTTON IS NOT DISABLED FOR AN INVALID ADDRESS, WHICH IS THE OPPOSITE CALL FROM
 * THE KNOWLEDGE ADD FORM AND IS DELIBERATE. There, the two required fields are visibly empty and a
 * disabled button reads as "you have not finished". Here the field looks complete and is merely
 * malformed — a button that silently refuses to work tells the operator nothing about WHY, and the
 * likely cause (a typo in an address) is exactly what they need pointed at. So the press is
 * accepted, the ViewModel rejects it, and [MembersUiState.addRejected] puts a reason on screen.
 *
 * ⚠️ THE ROLE PICKER DEFAULTS TO `client`, WHICH IS THE SERVER'S OWN DEFAULT AND NOT THE CAUTIOUS
 * ONE. Worth knowing: an operator who never touches the picker grants the ordinary tenant role,
 * not read-only.
 */
@Composable
internal fun AddMemberDialog(
    state: MembersUiState,
    onEditEmail: (String) -> Unit,
    onEditRole: (WorkspaceRole) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.members_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                SettingsTextField(
                    value = state.draftEmail,
                    label = stringResource(R.string.members_add_email),
                    description = MEMBERS_ADD_EMAIL_DESCRIPTION,
                    enabled = !state.busy,
                    singleLine = true,
                    onValueChange = onEditEmail,
                )
                RolePicker(
                    selected = state.draftRole,
                    enabled = !state.busy,
                    descriptionFor = ::memberAddRoleDescription,
                    onSelect = onEditRole,
                )
                if (state.addRejected) {
                    Text(
                        text = stringResource(R.string.members_add_rejected),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier.semantics {
                            contentDescription = MEMBERS_ADD_REJECTED_DESCRIPTION
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.busy,
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_ADD_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.members_add_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_ADD_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.members_cancel))
            }
        },
    )
}

/**
 * Remove one member.
 *
 * ⛔ CONFIRMED BECAUSE IT IS IMMEDIATE AND IS NOT AN UNDO. The next request that person makes
 * resolves no role in this workspace — every call, contact and conversation here goes with it —
 * and re-adding them writes a NEW row rather than restoring the old one, so the join date and the
 * roster order change too.
 *
 * ⚠️ THE BODY NAMES THE ADDRESS. "Are you sure?" on a list of similar-looking addresses is exactly
 * the dialog people dismiss without reading.
 */
@Composable
internal fun RemoveMemberDialog(email: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.members_remove_confirm_title)) },
        text = { Text(stringResource(R.string.members_remove_confirm_body, email)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_REMOVE_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.members_remove))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_REMOVE_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.members_cancel))
            }
        },
    )
}

/**
 * Change one member's role.
 *
 * ⚠️ SEEDED FROM THE ROW'S CURRENT ROLE WHEN THIS BUILD RECOGNISES IT, and from the server's own
 * default when it does not. `role` is free text, so a value written by a server-side replace can be
 * one this enum has never heard of; the picker cannot show it, and the honest fallback is the
 * default rather than silently pre-selecting `agency`.
 *
 * ⛔ CONFIRMING MAY STILL BE REFUSED. Demoting the last agency member is a 409 the server decides
 * inside a transaction, so this dialog cannot know in advance — the roster on screen may be stale
 * in exactly the way that matters. The refusal is surfaced as its own message afterwards.
 */
@Composable
internal fun RoleChangeDialog(
    member: WorkspaceMember,
    onConfirm: (WorkspaceRole) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(member.email) {
        mutableStateOf(WorkspaceRole.fromWire(member.role) ?: DEFAULT_MEMBER_ROLE)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.members_role_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
                Text(
                    text = member.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )
                RolePicker(
                    selected = selected,
                    enabled = true,
                    descriptionFor = ::memberRoleOptionDescription,
                    onSelect = { selected = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_ROLE_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.members_role_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = MEMBERS_ROLE_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.members_cancel))
            }
        },
    )
}

/**
 * The three roles, with what each one may do.
 *
 * ⛔ THE HELP TEXT SAYS WHAT THE ROLE CAN DO, NOT WHAT IT IS CALLED. "Agency" and "Client" are
 * internal vocabulary that tells an operator nothing about whether the person they are adding can
 * change the AI's prompt or spend money; the subtitle is where that is answered.
 *
 * ⚠️ THE OPTIONS COME FROM [ASSIGNABLE_MEMBER_ROLES], which is derived from the enum rather than
 * listed again, so a role added to the model cannot be silently missing from the picker.
 */
@Composable
private fun RolePicker(
    selected: WorkspaceRole,
    enabled: Boolean,
    descriptionFor: (WorkspaceRole) -> String,
    onSelect: (WorkspaceRole) -> Unit,
) {
    ASSIGNABLE_MEMBER_ROLES.forEach { role ->
        DistrictListRow(
            title = stringResource(roleLabelFor(role)),
            subtitle = stringResource(roleHelpFor(role)),
            trailing = {
                RadioButton(
                    selected = role == selected,
                    onClick = { onSelect(role) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = descriptionFor(role) },
                )
            },
        )
    }
}

/** The display name for a role. */
@StringRes
internal fun roleLabelFor(role: WorkspaceRole): Int = when (role) {
    WorkspaceRole.AGENCY -> R.string.members_role_agency
    WorkspaceRole.CLIENT -> R.string.members_role_client
    WorkspaceRole.VIEWER -> R.string.members_role_viewer
}

/** ⚠️ What the role may DO. See the ⛔ on the picker. */
@StringRes
private fun roleHelpFor(role: WorkspaceRole): Int = when (role) {
    WorkspaceRole.AGENCY -> R.string.members_role_agency_help
    WorkspaceRole.CLIENT -> R.string.members_role_client_help
    WorkspaceRole.VIEWER -> R.string.members_role_viewer_help
}

/**
 * Per-option handles.
 *
 * ⚠️ TWO FAMILIES, NOT ONE, BECAUSE BOTH PICKERS CAN BE ON SCREEN IN THE SAME TEST RUN. A shared
 * handle would make `onNodeWithContentDescription` ambiguous the moment a test rendered the add
 * dialog and the role dialog in sequence without recreating the rule.
 */
internal fun memberAddRoleDescription(role: WorkspaceRole): String =
    "district-members-add-role-${role.toWire()}"

internal fun memberRoleOptionDescription(role: WorkspaceRole): String =
    "district-members-role-option-${role.toWire()}"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MEMBERS_ADD_EMAIL_DESCRIPTION: String = "district-members-add-email"
const val MEMBERS_ADD_REJECTED_DESCRIPTION: String = "district-members-add-rejected"
const val MEMBERS_ADD_CONFIRM_DESCRIPTION: String = "district-members-add-confirm"
const val MEMBERS_ADD_CANCEL_DESCRIPTION: String = "district-members-add-cancel"
const val MEMBERS_REMOVE_CONFIRM_DESCRIPTION: String = "district-members-remove-confirm"
const val MEMBERS_REMOVE_CANCEL_DESCRIPTION: String = "district-members-remove-cancel"
const val MEMBERS_ROLE_CONFIRM_DESCRIPTION: String = "district-members-role-confirm"
const val MEMBERS_ROLE_CANCEL_DESCRIPTION: String = "district-members-role-cancel"
