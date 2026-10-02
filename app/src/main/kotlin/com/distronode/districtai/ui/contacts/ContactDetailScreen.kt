package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.ui.CenteredState
import com.distronode.districtai.ui.FailureState
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.resolve

/**
 * One contact in full, with the two mutations a detail screen offers.
 *
 * ⚠️ EVERY MUTATING CONTROL IS GATED ON [canMutate], which is false for a `viewer`. All three contacts
 * mutations exclude that role server-side, so offering the controls would guarantee a 403 the user can
 * do nothing about. The gate is an affordance, not a security boundary — the server still enforces.
 */
@Composable
fun ContactDetailScreen(
    state: ContactDetailUiState,
    canMutate: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismissMutationFailure: () -> Unit,
    /**
     * ⛔ SPENDS MONEY. One tap is one external crawl plus one LLM synthesis, and the endpoint is
     * not idempotent — see `DgiApi`. The control that calls this is hidden unless the contact is
     * genuinely enrichable, and disabled while anything else is in flight.
     */
    onEnrich: () -> Unit,
    /** ⛔ Destroys the dossier (not the contact) and is not recoverable. Confirmed in-screen. */
    onClearIntel: () -> Unit,
    /** A READ: re-check a dossier whose poll stopped on a failure. Never re-sends the enrich. */
    onCheckDossierAgain: () -> Unit,
) {
    // ⚠️ NO `modifier` PARAMETER: the one caller (the nav graph) never sized or placed this screen.
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_ROOT_DESCRIPTION },
        topBar = {
            // ⚠️ The back affordance moves INTO the app bar. It used to exist only inside the
            // FAILURE state, so a contact that loaded successfully offered no visible way back.
            DistrictTopBar(title = stringResource(R.string.contact_detail_title), onBack = onBack)
        },
    ) { inset ->
        // ⛔ THE INSET WRAPS *EVERY* STATE, NOT JUST THE LOADED ONE. See the same note in
        // CallLogScreen: passing it only to the populated branch left loading and failure rendering
        // UNDERNEATH the 56dp app bar.
        Box(modifier = inset.fillMaxSize()) {
            when (state) {
                ContactDetailUiState.Loading -> CenteredState(CONTACT_DETAIL_LOADING_DESCRIPTION) {
                    CircularProgressIndicator()
                }

                is ContactDetailUiState.Failed -> FailureState(
                    failure = state.failure,
                    onRetry = onRetry,
                    onSignIn = null,
                    description = CONTACT_DETAIL_FAILURE_DESCRIPTION,
                )

                is ContactDetailUiState.Content -> Content(
                    state = state,
                    canMutate = canMutate,
                    onRename = onRename,
                    onDelete = onDelete,
                    onDismissMutationFailure = onDismissMutationFailure,
                    onEnrich = onEnrich,
                    onClearIntel = onClearIntel,
                    onCheckDossierAgain = onCheckDossierAgain,
                )
            }
        }
    }
}

@Composable
private fun Content(
    state: ContactDetailUiState.Content,
    canMutate: Boolean,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismissMutationFailure: () -> Unit,
    onEnrich: () -> Unit,
    onClearIntel: () -> Unit,
    onCheckDossierAgain: () -> Unit,
) {
    val contact = state.contact
    var renaming by remember(contact.id) { mutableStateOf(false) }
    var confirmingDelete by remember(contact.id) { mutableStateOf(false) }
    var confirmingClear by remember(contact.id) { mutableStateOf(false) }

    ContentContainer(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        contentPadding = PaddingValues(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
    ) {
        Identity(contact)
        Attributes(contact)
        DossierSection(
            contact = contact,
            canMutate = canMutate,
            // ⚠️ ONE `saving` FLAG FOR EVERY MUTATION ON THE SCREEN, deliberately. A rename, a
            // delete, an enrich and a clear all reload the same contact, so allowing a second
            // one while the first is in flight would race two writes against one re-read.
            busy = state.saving,
            onEnrich = onEnrich,
            onClearIntel = { confirmingClear = true },
        )

        state.pollFailure?.let { PollFailure(it, onCheckDossierAgain) }

        state.mutationFailure?.let { MutationFailure(it, onDismissMutationFailure) }

        if (canMutate) {
            MutationControls(
                contact = contact,
                saving = state.saving,
                renaming = renaming,
                onStartRename = { renaming = true },
                onRename = {
                    renaming = false
                    onRename(it)
                },
                onCancelRename = { renaming = false },
                onRequestDelete = { confirmingDelete = true },
            )
        } else {
            Text(
                text = stringResource(R.string.contacts_read_only),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    contentDescription = CONTACT_DETAIL_READ_ONLY_DESCRIPTION
                },
            )
        }
    }

    if (confirmingDelete) {
        // ⛔ CONFIRMED, because a delete is irreversible and a mis-tap in a list costs a customer record.
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            text = { Text(stringResource(R.string.contact_detail_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = CONTACT_DETAIL_DELETE_CONFIRM_DESCRIPTION
                    },
                ) {
                    Text(stringResource(R.string.contact_detail_delete_confirmed))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.contact_detail_cancel))
                }
            },
        )
    }

    if (confirmingClear) {
        ClearIntelDialog(
            onConfirm = {
                confirmingClear = false
                onClearIntel()
            },
            onDismiss = { confirmingClear = false },
        )
    }
}

@Composable
private fun Identity(contact: Contact) {
    Text(
        text = contact.displayName ?: stringResource(R.string.contacts_unnamed),
        style = MaterialTheme.typography.headlineSmall,
    )
    // ⚠️ Email-first: show whichever identifiers exist rather than assuming a phone number. A
    // phone-less contact is legal, and any number of them coexist in one workspace.
    contact.phoneNumber?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    contact.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (contact.phoneNumber == null && contact.email == null) {
        Text(
            text = stringResource(R.string.contacts_no_contact_details),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * ⚠️ `company` IS DELIBERATELY NOT HERE ANY MORE. It is written by the DGI pipeline, not typed by
 * the operator, and `clear-intel` nulls it alongside `intelligence` — so it belongs in the
 * dossier section, where clearing it is not a surprise. See [DossierSection].
 */
@Composable
private fun Attributes(contact: Contact) {
    contact.latestContextSummary?.let {
        LabelledCard(stringResource(R.string.contact_detail_context), it)
    }
    contact.budget?.let { LabelledCard(stringResource(R.string.contact_detail_budget), it) }
    contact.timeline?.let { LabelledCard(stringResource(R.string.contact_detail_timeline), it) }
    contact.website?.let { LabelledCard(stringResource(R.string.contact_detail_website), it) }
}

/**
 * ⚠️ Shown ALONGSIDE the contact, not instead of it: a failed rename does not invalidate the data
 * already on screen, and blanking it would lose what the user was reading.
 */
@Composable
private fun MutationFailure(failure: FailureText, onDismiss: () -> Unit) {
    DistrictCard {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            Text(
                text = failure.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.semantics {
                    contentDescription = CONTACT_DETAIL_MUTATION_FAILURE_DESCRIPTION
                },
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.contact_detail_cancel))
            }
        }
    }
}

/**
 * ⚠️ THE DOSSIER POLL STOPPED ON A FAILED READ. Without this the badge would say "building" for
 * good, because the poll does not restart on its own (a dead session must not be hammered). The
 * retry is offered only when retrying could work, the same rule as the full-screen failure.
 */
@Composable
private fun PollFailure(failure: FailureText, onCheckAgain: () -> Unit) {
    DistrictCard {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            Text(
                text = stringResource(R.string.contact_detail_dossier_poll_failed, failure.message.resolve()),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.semantics {
                    contentDescription = CONTACT_DETAIL_POLL_FAILURE_DESCRIPTION
                },
            )
            if (failure.retryable) {
                TextButton(onClick = onCheckAgain) {
                    Text(stringResource(R.string.overview_retry))
                }
            }
        }
    }
}

/** Only ever composed for a role the server would admit — see the note on [ContactDetailScreen]. */
@Composable
private fun MutationControls(
    contact: Contact,
    saving: Boolean,
    renaming: Boolean,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    if (renaming) {
        RenameField(
            initial = contact.name,
            saving = saving,
            onSave = onRename,
            onCancel = onCancelRename,
        )
    } else {
        TextButton(
            onClick = onStartRename,
            // Disabled while a mutation is in flight, so a double tap cannot race two edits.
            enabled = !saving,
            modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_RENAME_DESCRIPTION },
        ) {
            Text(stringResource(R.string.contact_detail_rename))
        }
    }

    TextButton(
        onClick = onRequestDelete,
        enabled = !saving,
        modifier = Modifier.semantics { contentDescription = CONTACT_DETAIL_DELETE_DESCRIPTION },
    ) {
        Text(stringResource(R.string.contact_detail_delete))
    }
}

@Composable
private fun RenameField(
    initial: String,
    saving: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(stringResource(R.string.contact_detail_rename_label)) },
            singleLine = true,
            enabled = !saving,
            colors = districtFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = CONTACT_DETAIL_RENAME_FIELD_DESCRIPTION },
        )
        Column(modifier = Modifier.padding(top = DistrictTheme.spacing.tight)) {
            Button(
                // Disabled for a blank or unchanged value: neither is an edit, and the server would
                // reject a blank name as a validation error.
                enabled = !saving && value.isNotBlank() && value.trim() != initial,
                onClick = { onSave(value) },
                modifier = Modifier.semantics {
                    contentDescription = CONTACT_DETAIL_RENAME_SAVE_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.contact_detail_save))
            }
            TextButton(onClick = onCancel, enabled = !saving) {
                Text(stringResource(R.string.contact_detail_cancel))
            }
        }
    }
}

/** Stable handles for tests. */
const val CONTACT_DETAIL_ROOT_DESCRIPTION: String = "district-contact-detail-root"
const val CONTACT_DETAIL_LOADING_DESCRIPTION: String = "district-contact-detail-loading"
const val CONTACT_DETAIL_FAILURE_DESCRIPTION: String = "district-contact-detail-failure"
const val CONTACT_DETAIL_DOSSIER_DESCRIPTION: String = "district-contact-detail-dossier"
const val CONTACT_DETAIL_RENAME_DESCRIPTION: String = "district-contact-detail-rename"
const val CONTACT_DETAIL_RENAME_FIELD_DESCRIPTION: String = "district-contact-detail-rename-field"
const val CONTACT_DETAIL_RENAME_SAVE_DESCRIPTION: String = "district-contact-detail-rename-save"
const val CONTACT_DETAIL_DELETE_DESCRIPTION: String = "district-contact-detail-delete"
const val CONTACT_DETAIL_DELETE_CONFIRM_DESCRIPTION: String = "district-contact-detail-delete-confirm"
const val CONTACT_DETAIL_READ_ONLY_DESCRIPTION: String = "district-contact-detail-read-only"
const val CONTACT_DETAIL_MUTATION_FAILURE_DESCRIPTION: String = "district-contact-detail-mutation-failure"
const val CONTACT_DETAIL_POLL_FAILURE_DESCRIPTION: String = "district-contact-detail-poll-failure"
