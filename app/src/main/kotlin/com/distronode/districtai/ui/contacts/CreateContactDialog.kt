package com.distronode.districtai.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.ui.resolve

/**
 * Add a contact.
 *
 * ⛔ REQUIRES A NAME PLUS EITHER A PHONE OR AN EMAIL — not both. Contacts became email-first, and the
 * database deliberately allows any number of phone-less rows per workspace, so demanding a number here
 * would refuse legitimate input. (Note `contacts/bulk-create` DISAGREES and requires a phone per row,
 * silently counting an email-only row as invalid; that inconsistency is server-side and is not
 * smoothed over.)
 *
 * ⚠️ Only ever shown for a role the server would admit — every contacts mutation excludes `viewer`.
 */
@Composable
fun CreateContactDialog(
    state: CreateContactUiState,
    onCreate: (name: String, phoneNumber: String, email: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // ⛔ rememberSaveable, NOT remember. THIS DIALOG IS THE ONLY PLACE IN THE APP THAT LOSES USER
    // INPUT. A plain `remember` is discarded when the activity is recreated, and `MainActivity`
    // declares no `android:configChanges` — so a rotation, a dark-mode switch, a font-size change or
    // a keyboard appearing on some OEM builds threw away a typed name, phone and email with no
    // warning and no way to recover them.
    var name by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }

    val saving = state is CreateContactUiState.Saving
    // A name is mandatory; one contact method is mandatory; which one is the user's choice.
    val hasContactMethod = phone.isNotBlank() || email.isNotBlank()
    val canSubmit = !saving && name.isNotBlank() && hasContactMethod

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        // ⚠️ The card token, not Material's default dialog surface. A dialog left on the Material
        // baseline is a lighter, differently-tinted panel floating over a correctly-themed screen —
        // the mismatch is more obvious than being slightly wrong everywhere.
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.contact_create)) },
        text = {
            Column(modifier = Modifier.semantics { contentDescription = CONTACT_CREATE_DESCRIPTION }) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Eyebrow(stringResource(R.string.contact_create_name)) },
                    colors = districtFieldColors(),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = CONTACT_CREATE_NAME_DESCRIPTION },
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Eyebrow(stringResource(R.string.contact_create_phone)) },
                    colors = districtFieldColors(),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics { contentDescription = CONTACT_CREATE_PHONE_DESCRIPTION },
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Eyebrow(stringResource(R.string.contact_create_email)) },
                    colors = districtFieldColors(),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = DistrictTheme.spacing.tight)
                        .semantics { contentDescription = CONTACT_CREATE_EMAIL_DESCRIPTION },
                )

                // Stated as guidance rather than an error, since it is the normal starting state.
                if (name.isNotBlank() && !hasContactMethod) {
                    Text(
                        text = stringResource(R.string.contact_create_needs_one),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.mutedForeground,
                        modifier = Modifier
                            .padding(top = DistrictTheme.spacing.tight)
                            .semantics { contentDescription = CONTACT_CREATE_HINT_DESCRIPTION },
                    )
                }

                // ⚠️ A 409 lands here as "already exists" rather than as a server fault — the database
                // enforces one contact per phone and per lowercased email per workspace.
                (state as? CreateContactUiState.Failed)?.let { failed ->
                    Text(
                        text = failed.failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier
                            .padding(top = DistrictTheme.spacing.tight)
                            .semantics { contentDescription = CONTACT_CREATE_FAILURE_DESCRIPTION },
                    )
                }
            }
        },
        confirmButton = {
            DistrictButton(
                text = stringResource(
                    if (saving) R.string.contact_create_saving else R.string.contact_create_save,
                ),
                onClick = { onCreate(name, phone, email) },
                enabled = canSubmit,
                modifier = Modifier.semantics { contentDescription = CONTACT_CREATE_SUBMIT_DESCRIPTION },
            )
        },
        dismissButton = {
            DistrictButton(
                text = stringResource(R.string.contact_detail_cancel),
                onClick = onDismiss,
                variant = ButtonVariant.Ghost,
                enabled = !saving,
            )
        },
    )
}

/** Stable handles for tests. */
const val CONTACT_CREATE_DESCRIPTION: String = "district-contact-create"
const val CONTACT_CREATE_NAME_DESCRIPTION: String = "district-contact-create-name"
const val CONTACT_CREATE_PHONE_DESCRIPTION: String = "district-contact-create-phone"
const val CONTACT_CREATE_EMAIL_DESCRIPTION: String = "district-contact-create-email"
const val CONTACT_CREATE_HINT_DESCRIPTION: String = "district-contact-create-hint"
const val CONTACT_CREATE_FAILURE_DESCRIPTION: String = "district-contact-create-failure"
const val CONTACT_CREATE_SUBMIT_DESCRIPTION: String = "district-contact-create-submit"
