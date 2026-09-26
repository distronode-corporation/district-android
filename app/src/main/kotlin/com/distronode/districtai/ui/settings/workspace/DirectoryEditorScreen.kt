package com.distronode.districtai.ui.settings.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.DirectoryField

/**
 * The list of humans the voice agent will transfer a live caller to.
 *
 * ⛔ THE SAVE IS A REPLACEMENT AND THE CONFIRMATION SAYS SO WITH A NUMBER. `PATCH
 * workspace/directory` sets the stored array to exactly what it receives, so the honest question
 * before that tap is not "save?" but "replace the directory with these N entries?". The count is in
 * the wording because it is the one thing an operator can check against what they meant.
 *
 * ⛔ AND AN EMPTY SAVE GETS DIFFERENT WORDING, NOT A DIFFERENT NUMBER. "Replace with 0 entries" is
 * arithmetic; "this removes every transfer target, so the agent will have no one to put a caller
 * through to" is the consequence. They are separate strings so neither can be softened into the
 * other.
 *
 * ⛔ A FAILED LOAD RENDERS RETRY AND NOTHING ELSE, and so does a stored value this client cannot
 * carry losslessly — see [DirectoryEditorUiState.unmodellable]. `DirectoryEditorScreenTest` asserts
 * the absence of every handle in [DIRECTORY_MUTATING_DESCRIPTIONS] in both states.
 */
@Composable
fun DirectoryEditorScreen(
    state: DirectoryEditorUiState,
    onEditNew: (DirectoryField, String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int, DirectoryField, String) -> Unit,
    onRemove: (Int) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmingExit by remember { mutableStateOf(false) }
    var confirmingSave by remember { mutableStateOf(false) }

    BackHandler(enabled = state.hasUnsavedChanges) { confirmingExit = true }

    if (confirmingExit) {
        UnsavedChangesDialog(
            onDiscard = {
                confirmingExit = false
                onBack()
            },
            onDismiss = { confirmingExit = false },
        )
    }

    if (confirmingSave) {
        ReplaceDirectoryDialog(
            count = state.entries.size,
            emptying = state.savingEmptiesDirectory,
            onConfirm = {
                confirmingSave = false
                onSave()
            },
            onDismiss = { confirmingSave = false },
        )
    }

    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = DIRECTORY_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.directory_title),
                onBack = { if (state.hasUnsavedChanges) confirmingExit = true else onBack() },
            )
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            when (val load = state.load) {
                ConfigState.Loading -> ContentContainer { ConfigSkeleton() }
                is ConfigState.LoadFailed -> ContentContainer {
                    ConfigLoadFailure(failure = load.failure, onRetry = onRetry)
                }
                is ConfigState.Ready ->
                    if (state.unmodellable) {
                        ContentContainer {
                            NotEditableNotice(
                                title = stringResource(R.string.directory_unmodellable_title),
                                body = stringResource(R.string.directory_unmodellable_body),
                                description = DIRECTORY_UNMODELLABLE_DESCRIPTION,
                            )
                        }
                    } else {
                        ContentContainer { DirectoryAddRow(state, onEditNew, onAdd) }
                        ContentContainer { DirectoryList(state, onEdit, onRemove) }
                        ContentContainer {
                            DirectorySaveSection(state) { confirmingSave = true }
                        }
                    }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * The pending row.
 *
 * ⚠️ THE SAME TWO REQUIRED FIELDS AS THE WEB FORM AND NO MORE VALIDATION THAN IT HAS. A blank name
 * or number is refused with a message; a number in any format at all is accepted, because the
 * server stores what it is given and real directories carry extensions and national formats.
 */
@Composable
private fun DirectoryAddRow(
    state: DirectoryEditorUiState,
    onEditNew: (DirectoryField, String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.directory_add_section))
        SettingsTextField(
            value = state.newName,
            label = stringResource(R.string.directory_name),
            description = DIRECTORY_NEW_NAME_DESCRIPTION,
            enabled = !state.save.busy,
            // ⚠️ Single-line: both fields are short identifiers, not prose.
            singleLine = true,
            onValueChange = { onEditNew(DirectoryField.NAME, it) },
        )
        SettingsTextField(
            value = state.newPhoneNumber,
            label = stringResource(R.string.directory_phone),
            description = DIRECTORY_NEW_PHONE_DESCRIPTION,
            enabled = !state.save.busy,
            singleLine = true,
            onValueChange = { onEditNew(DirectoryField.PHONE_NUMBER, it) },
        )
        if (state.addRejected) {
            Text(
                text = stringResource(R.string.directory_add_rejected),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.semantics {
                    contentDescription = DIRECTORY_ADD_REJECTED_DESCRIPTION
                },
            )
        }
        DistrictButton(
            text = stringResource(R.string.directory_add),
            onClick = onAdd,
            variant = ButtonVariant.Ghost,
            enabled = state.canAdd,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = DIRECTORY_ADD_DESCRIPTION },
        )
    }
}

/** Every stored row, editable in place. */
@Composable
private fun DirectoryList(
    state: DirectoryEditorUiState,
    onEdit: (Int, DirectoryField, String) -> Unit,
    onRemove: (Int) -> Unit,
) {
    if (state.entries.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.directory_empty_title),
            body = stringResource(R.string.directory_empty_body),
            modifier = Modifier.semantics { contentDescription = DIRECTORY_EMPTY_DESCRIPTION },
        )
        return
    }
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        state.entries.forEachIndexed { index, entry ->
            if (index > 0) DistrictRowDivider()
            Column(
                modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                SettingsTextField(
                    value = entry.value(DirectoryField.NAME),
                    label = stringResource(R.string.directory_name),
                    description = directoryFieldDescription(index, DirectoryField.NAME),
                    enabled = !state.save.busy,
                    singleLine = true,
                    onValueChange = { onEdit(index, DirectoryField.NAME, it) },
                )
                SettingsTextField(
                    value = entry.value(DirectoryField.PHONE_NUMBER),
                    label = stringResource(R.string.directory_phone),
                    description = directoryFieldDescription(index, DirectoryField.PHONE_NUMBER),
                    enabled = !state.save.busy,
                    singleLine = true,
                    onValueChange = { onEdit(index, DirectoryField.PHONE_NUMBER, it) },
                )
                DistrictButton(
                    text = stringResource(R.string.directory_remove),
                    onClick = { onRemove(index) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = !state.save.busy,
                    modifier = Modifier.semantics {
                        contentDescription = directoryRemoveDescription(index)
                    },
                )
            }
        }
    }
}

/**
 * The warning, the banner and the save.
 *
 * ⚠️ THE INCOMPLETE-ROW WARNING IS A WARNING AND NOT A BLOCK. A half-filled entry is legal
 * server-side (`name` and `phoneNumber` are both `.nullish()`) and already exists in stored data,
 * so refusing to save one would mean refusing to save a directory the web wrote.
 */
@Composable
private fun DirectorySaveSection(state: DirectoryEditorUiState, onRequestSave: () -> Unit) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        if (state.incompleteCount > 0) {
            Text(
                text = pluralStringResource(
                    R.plurals.directory_incomplete_warning,
                    state.incompleteCount,
                    state.incompleteCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = DIRECTORY_INCOMPLETE_DESCRIPTION
                },
            )
        }
        SaveNotice(state = state.save, description = DIRECTORY_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(
                if (state.save.busy) {
                    R.string.workspace_settings_saving
                } else {
                    R.string.directory_save
                },
            ),
            onClick = onRequestSave,
            enabled = state.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = DIRECTORY_SAVE_DESCRIPTION },
        )
    }
}

/**
 * ⛔ THE COUNT IS IN THE WORDING, AND THE EMPTY CASE HAS ITS OWN. See the ⛔ on the screen: a
 * replacement is not a save, and a replacement with nothing is not a small replacement.
 */
@Composable
private fun ReplaceDirectoryDialog(
    count: Int,
    emptying: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = {
            Text(
                stringResource(
                    if (emptying) {
                        R.string.directory_confirm_empty_title
                    } else {
                        R.string.directory_confirm_title
                    },
                ),
            )
        },
        text = {
            Text(
                if (emptying) {
                    stringResource(R.string.directory_confirm_empty_body)
                } else {
                    pluralStringResource(R.plurals.directory_confirm_body, count, count)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = DIRECTORY_CONFIRM_DESCRIPTION
                },
            ) {
                Text(
                    stringResource(
                        if (emptying) {
                            R.string.directory_confirm_empty_action
                        } else {
                            R.string.directory_confirm_action
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = DIRECTORY_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.workspace_settings_keep_editing))
            }
        },
    )
}

/** Per-row handles, derived in one place so a row and its test cannot drift. */
internal fun directoryFieldDescription(index: Int, field: DirectoryField): String =
    "district-directory-$index-${field.key}"

internal fun directoryRemoveDescription(index: Int): String = "district-directory-remove-$index"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val DIRECTORY_ROOT_DESCRIPTION: String = "district-directory-root"
const val DIRECTORY_NEW_NAME_DESCRIPTION: String = "district-directory-new-name"
const val DIRECTORY_NEW_PHONE_DESCRIPTION: String = "district-directory-new-phone"
const val DIRECTORY_ADD_DESCRIPTION: String = "district-directory-add"
const val DIRECTORY_ADD_REJECTED_DESCRIPTION: String = "district-directory-add-rejected"
const val DIRECTORY_SAVE_DESCRIPTION: String = "district-directory-save"
const val DIRECTORY_NOTICE_DESCRIPTION: String = "district-directory-notice"
const val DIRECTORY_EMPTY_DESCRIPTION: String = "district-directory-empty"
const val DIRECTORY_INCOMPLETE_DESCRIPTION: String = "district-directory-incomplete"
const val DIRECTORY_UNMODELLABLE_DESCRIPTION: String = "district-directory-unmodellable"
const val DIRECTORY_CONFIRM_DESCRIPTION: String = "district-directory-confirm"
const val DIRECTORY_CANCEL_DESCRIPTION: String = "district-directory-cancel"

/**
 * ⛔ EVERY MUTATING HANDLE ON THIS SCREEN THAT IS NOT PER-ROW. The screen test asserts all of these
 * are ABSENT when the config did not load and when the stored array cannot be modelled — the two
 * states in which a save would be a deletion built from nothing. A control added and not listed
 * here silently drops out of that assertion.
 */
val DIRECTORY_MUTATING_DESCRIPTIONS: List<String> = listOf(
    DIRECTORY_NEW_NAME_DESCRIPTION,
    DIRECTORY_NEW_PHONE_DESCRIPTION,
    DIRECTORY_ADD_DESCRIPTION,
    DIRECTORY_SAVE_DESCRIPTION,
)
