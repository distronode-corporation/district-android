package com.distronode.districtai.ui.settings.workspace

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
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.KB_MODE_INTERNAL
import com.distronode.districtai.core.model.KB_MODE_LINKED
import com.distronode.districtai.core.model.KnowledgeDocument

/**
 * What the agent can answer from, and where the answer is composed.
 *
 * ⛔ THE MODE IS A DATA-RESIDENCY CONTROL AND THE `linked` OPTION IS CONFIRMED. Choosing it starts
 * sending this workspace's questions to Atlassian to be answered, which is the route's own framing
 * and is not a display preference. The confirmation names the third party rather than asking "are
 * you sure".
 *
 * ⛔ AND THE SELECTOR IS WITHHELD WHEN THE MODE READ FAILED. Rendering `internal` as selected
 * because nothing came back would be a false claim about where a customer's questions go — and the
 * radio the operator then leaves alone would look like a choice they made.
 *
 * ⛔ A VIEWER SEES THE DOCUMENTS AND THE MODE AND NOT ONE CONTROL. Both reads admit `viewer`
 * server-side and all three writes exclude one. The mode is
 * rendered as a STATED VALUE rather than as a disabled radio group — a greyed-out control still
 * invites a tap and still reads as something the operator could have changed, and on a
 * data-residency setting that is the wrong impression to leave.
 *
 * ⚠️ NO WHOLESALE-REPLACE SAVE ON THIS SCREEN, so unlike the two array editors an add form may
 * exist while the list is still loading. Documents are created and deleted by id.
 */
@Composable
fun KnowledgeScreen(
    state: KnowledgeUiState,
    onEditTitle: (String) -> Unit,
    onEditContent: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onSelectMode: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<KnowledgeDocument?>(null) }
    var pendingLinkedMode by remember { mutableStateOf(false) }

    pendingDelete?.let { document ->
        DeleteDocumentDialog(
            title = document.title.orEmpty(),
            onConfirm = {
                pendingDelete = null
                onDelete(document.id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (pendingLinkedMode) {
        LinkedModeDialog(
            onConfirm = {
                pendingLinkedMode = false
                onSelectMode(KB_MODE_LINKED)
            },
            onDismiss = { pendingLinkedMode = false },
        )
    }

    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = KNOWLEDGE_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(title = stringResource(R.string.knowledge_title), onBack = onBack)
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            ContentContainer {
                KnowledgeModeSection(state) { mode ->
                    if (state.isResidencyChange(mode)) pendingLinkedMode = true else onSelectMode(mode)
                }
            }
            if (state.canWrite) {
                ContentContainer { KnowledgeAddSection(state, onEditTitle, onEditContent, onAdd) }
            }
            ContentContainer {
                KnowledgeDocuments(state, onRetry) { document -> pendingDelete = document }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * Where the answer is composed.
 *
 * ⛔ THE `linked` HELP TEXT SAYS THE QUESTION LEAVES, IN THOSE WORDS. It is the whole difference
 * between the two options, and a description that only mentioned "wider coverage" would sell the
 * feature while hiding the trade the customer is agreeing to.
 */
@Composable
private fun KnowledgeModeSection(state: KnowledgeUiState, onSelectMode: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.knowledge_section_mode),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        // ⛔ A VIEWER GETS THE ANSWER WITHOUT THE CONTROL. Rendering the radio group disabled would
        // put five tappable-looking nodes in the tree for a role that can never use them; stating
        // the stored mode answers the only question a viewer has here.
        if (!state.canWrite && !state.modeUnavailable) {
            val (label, help) = modeStrings(state.mode.orEmpty())
            DistrictListRow(
                title = stringResource(label),
                subtitle = stringResource(help),
                modifier = Modifier.semantics {
                    contentDescription = KNOWLEDGE_MODE_READ_ONLY_DESCRIPTION
                },
            )
            return
        }
        if (state.modeUnavailable) {
            Text(
                text = stringResource(R.string.knowledge_mode_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(horizontal = DistrictTheme.spacing.gutter)
                    .semantics { contentDescription = KNOWLEDGE_MODE_UNAVAILABLE_DESCRIPTION },
            )
            return
        }
        state.modeOptions.forEach { option ->
            val (label, help) = modeStrings(option)
            DistrictListRow(
                title = stringResource(label),
                subtitle = stringResource(help),
                trailing = {
                    RadioButton(
                        selected = state.mode == option,
                        onClick = { onSelectMode(option) },
                        enabled = state.canChangeMode,
                        modifier = Modifier.semantics {
                            contentDescription = knowledgeModeDescription(option)
                        },
                    )
                },
            )
        }
        Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
            SaveNotice(state = state.modeSave, description = KNOWLEDGE_MODE_NOTICE_DESCRIPTION)
        }
    }
}

/**
 * Paste a document.
 *
 * ⛔ THE BUTTON IS DISABLED FOR THE WHOLE ROUND TRIP, and that is a spending control rather than a
 * loading affordance: each tap buys one embedding run over every chunk the content produced.
 */
@Composable
private fun KnowledgeAddSection(
    state: KnowledgeUiState,
    onEditTitle: (String) -> Unit,
    onEditContent: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.knowledge_section_add))
        SettingsTextField(
            value = state.draftTitle,
            label = stringResource(R.string.knowledge_doc_title),
            description = KNOWLEDGE_TITLE_DESCRIPTION,
            enabled = !state.busy,
            singleLine = true,
            onValueChange = onEditTitle,
        )
        SettingsTextField(
            value = state.draftContent,
            label = stringResource(R.string.knowledge_doc_content),
            description = KNOWLEDGE_CONTENT_DESCRIPTION,
            enabled = !state.busy,
            singleLine = false,
            onValueChange = onEditContent,
        )
        if (state.addRejected) {
            Text(
                text = stringResource(R.string.knowledge_add_rejected),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.semantics {
                    contentDescription = KNOWLEDGE_ADD_REJECTED_DESCRIPTION
                },
            )
        }
        SaveNotice(state = state.addSave, description = KNOWLEDGE_ADD_NOTICE_DESCRIPTION)
        DistrictButton(
            text = stringResource(
                if (state.addSave.busy) R.string.knowledge_adding else R.string.knowledge_add,
            ),
            onClick = onAdd,
            enabled = state.canAdd,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = KNOWLEDGE_ADD_DESCRIPTION },
        )
    }
}

/** The uploaded documents, newest first. */
@Composable
private fun KnowledgeDocuments(
    state: KnowledgeUiState,
    onRetry: () -> Unit,
    onRequestDelete: (KnowledgeDocument) -> Unit,
) {
    Column(
        modifier = Modifier.padding(vertical = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.knowledge_section_documents),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )
        Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
            SaveNotice(state = state.deleteSave, description = KNOWLEDGE_DELETE_NOTICE_DESCRIPTION)
        }
        when (val list = state.list) {
            KnowledgeListState.Loading -> ConfigSkeleton()
            is KnowledgeListState.Failed -> ConfigLoadFailure(
                failure = list.failure,
                onRetry = onRetry,
            )
            is KnowledgeListState.Ready ->
                if (list.documents.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.knowledge_empty_title),
                        body = stringResource(R.string.knowledge_empty_body),
                        modifier = Modifier.semantics {
                            contentDescription = KNOWLEDGE_EMPTY_DESCRIPTION
                        },
                    )
                } else {
                    list.documents.forEachIndexed { index, document ->
                        if (index > 0) DistrictRowDivider()
                        DocumentRow(
                            document = document,
                            // ⚠️ Two conditions, not one: the role decides whether the control
                            // EXISTS, `busy` decides whether it is live during a round trip.
                            canDelete = state.canWrite,
                            enabled = !state.busy,
                            onRequestDelete = onRequestDelete,
                        )
                    }
                }
        }
    }
}

/**
 * One document.
 *
 * ⚠️ THE STATUS IS SHOWN VERBATIM. The column is a plain string — the ingest route writes `ready`
 * today and older rows carry other values — so an unrecognised status renders as itself rather than
 * being mapped to a guess.
 */
@Composable
private fun DocumentRow(
    document: KnowledgeDocument,
    canDelete: Boolean,
    enabled: Boolean,
    onRequestDelete: (KnowledgeDocument) -> Unit,
) {
    DistrictListRow(
        title = document.title.orEmpty().ifBlank { document.id },
        subtitle = stringResource(
            R.string.knowledge_doc_subtitle,
            document.status ?: stringResource(R.string.knowledge_status_unknown),
            document.chunkCount ?: 0,
        ),
        trailing = {
            if (canDelete) {
                DistrictButton(
                    text = stringResource(R.string.knowledge_delete),
                    onClick = { onRequestDelete(document) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription = knowledgeDeleteDescription(document.id)
                    },
                )
            }
        },
    )
}

/** ⛔ Not recoverable: the chunks cascade and the embeddings have to be bought again. */
@Composable
private fun DeleteDocumentDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.knowledge_delete_confirm_title)) },
        text = { Text(stringResource(R.string.knowledge_delete_confirm_body, title)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = KNOWLEDGE_DELETE_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.knowledge_delete_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = KNOWLEDGE_DELETE_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.workspace_settings_keep_editing))
            }
        },
    )
}

/** ⛔ NAMES THE THIRD PARTY. It is a residency change, and a vague "are you sure" would hide that. */
@Composable
private fun LinkedModeDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.knowledge_linked_confirm_title)) },
        text = { Text(stringResource(R.string.knowledge_linked_confirm_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = KNOWLEDGE_LINKED_CONFIRM_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.knowledge_linked_confirm_action))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = KNOWLEDGE_LINKED_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.workspace_settings_keep_editing))
            }
        },
    )
}

/**
 * The label and help text for one mode.
 *
 * ⚠️ ONE FUNCTION RETURNING BOTH, so a mode can never be labelled as one thing and explained as
 * another — and so this file stays under detekt's per-file function ceiling.
 */
private fun modeStrings(mode: String): Pair<Int, Int> = if (mode == KB_MODE_INTERNAL) {
    R.string.knowledge_mode_internal to R.string.knowledge_mode_internal_help
} else {
    R.string.knowledge_mode_linked to R.string.knowledge_mode_linked_help
}

/** Per-row handles, derived in one place so a row and its test cannot drift. */
internal fun knowledgeDeleteDescription(documentId: String): String =
    "district-knowledge-delete-$documentId"

internal fun knowledgeModeDescription(mode: String): String = "district-knowledge-mode-$mode"

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val KNOWLEDGE_ROOT_DESCRIPTION: String = "district-knowledge-root"
const val KNOWLEDGE_TITLE_DESCRIPTION: String = "district-knowledge-title"
const val KNOWLEDGE_CONTENT_DESCRIPTION: String = "district-knowledge-content"
const val KNOWLEDGE_ADD_DESCRIPTION: String = "district-knowledge-add"
const val KNOWLEDGE_ADD_REJECTED_DESCRIPTION: String = "district-knowledge-add-rejected"
const val KNOWLEDGE_ADD_NOTICE_DESCRIPTION: String = "district-knowledge-add-notice"
const val KNOWLEDGE_DELETE_NOTICE_DESCRIPTION: String = "district-knowledge-delete-notice"
const val KNOWLEDGE_MODE_NOTICE_DESCRIPTION: String = "district-knowledge-mode-notice"
const val KNOWLEDGE_MODE_UNAVAILABLE_DESCRIPTION: String = "district-knowledge-mode-unavailable"
const val KNOWLEDGE_MODE_READ_ONLY_DESCRIPTION: String = "district-knowledge-mode-read-only"
const val KNOWLEDGE_EMPTY_DESCRIPTION: String = "district-knowledge-empty"
const val KNOWLEDGE_DELETE_CONFIRM_DESCRIPTION: String = "district-knowledge-delete-confirm"
const val KNOWLEDGE_DELETE_CANCEL_DESCRIPTION: String = "district-knowledge-delete-cancel"
const val KNOWLEDGE_LINKED_CONFIRM_DESCRIPTION: String = "district-knowledge-linked-confirm"
const val KNOWLEDGE_LINKED_CANCEL_DESCRIPTION: String = "district-knowledge-linked-cancel"
