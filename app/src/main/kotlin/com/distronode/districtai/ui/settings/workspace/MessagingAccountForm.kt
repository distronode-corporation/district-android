package com.distronode.districtai.ui.settings.workspace

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.distronode.districtai.core.model.MESSAGING_CREDENTIAL_SOURCES
import com.distronode.districtai.core.model.MESSAGING_PROVIDERS
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_SINCH
import com.distronode.districtai.core.model.MESSAGING_PROVIDER_TELNYX
import com.distronode.districtai.core.model.MESSAGING_SOURCE_BYOK
import com.distronode.districtai.ui.resolve

/**
 * The add/edit carrier-account form.
 *
 * ⛔ ITS OWN FILE FOR THE REASON `MembersDialogs` IS ONE: detekt caps a file's top-level function
 * count, and the healthy answer is another file rather than a raised threshold.
 *
 * ⛔ THE SENTENCE "Leave blank to keep the saved value" IS THE LOAD-BEARING PIECE OF COPY ON THIS
 * SCREEN, AND IT IS WHY THE FORM CAN EXIST AT ALL. The old ⛔ on `MessagingApi` refused an edit form
 * on the grounds that a redacted read forces an operator to retype a live carrier secret on a phone
 * keyboard. That was wrong, and it was wrong because of exactly this: `buildEncryptedProviderConfig`
 * reuses the STORED ciphertext when an incoming secret is blank, so the ordinary edit types no
 * credential. Removing or weakening that line turns the objection back into a true one.
 *
 * ⛔ THE ONE CASE WHERE A BLANK IS NOT "KEEP" IS CALLED OUT SEPARATELY. Changing the carrier on an
 * existing account discards the stored secrets (the route only reuses them when the provider is
 * unchanged), so the form demands every field and says why.
 */
@Composable
internal fun MessagingAccountDialog(
    state: MessagingUiState,
    draft: MessagingDraft,
    onEditDraft: (MessagingDraft?) -> Unit,
    onTest: () -> Unit,
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
                    if (draft.isCreate) R.string.messaging_add_title else R.string.messaging_edit_title,
                ),
            )
        },
        text = {
            // ⚠️ SCROLLABLE. Sinch alone contributes five credential boxes on top of the label, the
            // numbers box, two pickers and the default toggle; on a phone in landscape the confirm
            // button would otherwise be off-screen with no way to reach it.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                SettingsTextField(
                    value = draft.label,
                    label = stringResource(R.string.messaging_field_label),
                    description = MESSAGING_LABEL_DESCRIPTION,
                    enabled = !state.busy,
                    singleLine = true,
                    onValueChange = { onEditDraft(draft.copy(label = it)) },
                )
                ProviderPicker(draft, !state.busy, onEditDraft)
                CredentialSourcePicker(draft, !state.busy, onEditDraft)
                CredentialFields(state, draft, onEditDraft, onTest)
                NumbersField(state, draft, onEditDraft)
                MakeDefaultToggle(state, draft, onEditDraft)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // ⛔ DISABLED ONLY FOR AN INCOMPLETE CREDENTIAL SET, never for a blank one on an
                // ordinary edit — a blank there is the CORRECT input. See the ⛔ on the file.
                enabled = state.canEditNow && draft.canSave,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_SAVE_DESCRIPTION
                },
            ) {
                Text(
                    stringResource(
                        if (state.accountSave.busy) R.string.messaging_saving else R.string.messaging_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.messaging_cancel))
            }
        },
    )
}

/**
 * Which carrier.
 *
 * ⛔ CHANGING IT ON AN EXISTING ACCOUNT IS A CREDENTIAL RESET, AND THE WARNING SAYS SO. The route
 * only carries the stored secrets forward when the provider is unchanged, so after a switch a blank
 * box stores nothing — an account that saves cleanly and then fails on its first send, with nothing
 * pointing back here.
 *
 * ⚠️ THE SECRET MAP IS CLEARED ON A SWITCH rather than carried across. Twilio's `accountSid` and
 * Telnyx's `apiKey` are different fields, but a half-typed Sinch key left in the map would be sent
 * as an unrecognised plaintext identifier and stored in the clear.
 */
@Composable
private fun ProviderPicker(
    draft: MessagingDraft,
    enabled: Boolean,
    onEditDraft: (MessagingDraft?) -> Unit,
) {
    Eyebrow(stringResource(R.string.messaging_provider_section))
    MESSAGING_PROVIDERS.forEach { provider ->
        DistrictListRow(
            title = stringResource(providerLabel(provider)),
            trailing = {
                RadioButton(
                    selected = provider == draft.provider,
                    onClick = {
                        onEditDraft(draft.copy(provider = provider, secrets = emptyMap()))
                    },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription = messagingProviderDescription(provider)
                    },
                )
            },
        )
    }
    if (draft.secretsRequired && !draft.isCreate) {
        Text(
            text = stringResource(R.string.messaging_provider_switch_warning),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
            modifier = Modifier.semantics {
                contentDescription = MESSAGING_PROVIDER_SWITCH_DESCRIPTION
            },
        )
    }
}

/**
 * Whose carrier account is billed.
 *
 * ⛔ `managed` IS OFFERED WITHOUT BEING PRE-VALIDATED, AND THAT IS DELIBERATE. Nothing this client
 * can read says whether the workspace is entitled to the platform's shared carrier credentials;
 * `isEntitledToManagedCredentials` is a server-side check that answers 403 with a sentence naming
 * support. Guessing here would either hide a legitimate option or promise one that does not exist,
 * so the option is shown and the refusal is surfaced verbatim.
 */
@Composable
private fun CredentialSourcePicker(
    draft: MessagingDraft,
    enabled: Boolean,
    onEditDraft: (MessagingDraft?) -> Unit,
) {
    Eyebrow(stringResource(R.string.messaging_source_section))
    MESSAGING_CREDENTIAL_SOURCES.forEach { source ->
        val byok = source == MESSAGING_SOURCE_BYOK
        DistrictListRow(
            title = stringResource(
                if (byok) R.string.messaging_source_byok else R.string.messaging_source_managed,
            ),
            subtitle = stringResource(
                if (byok) {
                    R.string.messaging_source_byok_help
                } else {
                    R.string.messaging_source_managed_help
                },
            ),
            trailing = {
                RadioButton(
                    selected = source == draft.credentialSource,
                    onClick = { onEditDraft(draft.copy(credentialSource = source)) },
                    enabled = enabled,
                    modifier = Modifier.semantics {
                        contentDescription = messagingSourceDescription(source)
                    },
                )
            },
        )
    }
}

/**
 * The provider's credential boxes, and the probe.
 *
 * ⛔ EVERY BOX STARTS EMPTY AND NOTHING PRE-FILLS ONE, because there is nothing to pre-fill from:
 * the GET projects five keys per account and not one of them is a credential. That is the redaction
 * working, and the caption underneath is what turns it from a limitation into a contract.
 *
 * ⛔ THE TEST BUTTON IS OFFERED ONLY WHEN EVERY FIELD IS TYPED. `messaging/test` reads PLAINTEXT,
 * UNSAVED credentials out of the body, so on an ordinary edit there is nothing to test with —
 * sending blanks would report the carrier's 401 as though the SAVED keys were broken, which on this
 * screen is the belief that leads someone to retype a live key they never needed to touch.
 */
@Composable
private fun CredentialFields(
    state: MessagingUiState,
    draft: MessagingDraft,
    onEditDraft: (MessagingDraft?) -> Unit,
    onTest: () -> Unit,
) {
    val fields = com.distronode.districtai.core.model.messagingCredentialFields(draft.provider)
    if (fields.isEmpty()) return
    Eyebrow(stringResource(R.string.messaging_section_credentials))
    if (!draft.secretsRequired) {
        Text(
            text = stringResource(R.string.messaging_secret_keep),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.semantics { contentDescription = MESSAGING_SECRET_KEEP_DESCRIPTION },
        )
    }
    fields.forEach { field ->
        SettingsTextField(
            value = draft.secrets[field.key].orEmpty(),
            label = stringResource(credentialLabel(field.key)),
            description = messagingCredentialDescription(field.key),
            enabled = !state.busy,
            singleLine = true,
            onValueChange = {
                onEditDraft(draft.copy(secrets = draft.secrets + (field.key to it)))
            },
        )
    }
    TestConnection(state, draft, onTest)
}

/** The probe's button and its result. See the ⛔ on [CredentialFields] for when it is offered. */
@Composable
private fun TestConnection(state: MessagingUiState, draft: MessagingDraft, onTest: () -> Unit) {
    if (!draft.canTest) {
        Text(
            text = stringResource(R.string.messaging_test_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.semantics {
                contentDescription = MESSAGING_TEST_UNAVAILABLE_DESCRIPTION
            },
        )
        return
    }
    DistrictButton(
        text = stringResource(
            if (state.test == MessagingTestState.Running) {
                R.string.messaging_testing
            } else {
                R.string.messaging_test
            },
        ),
        onClick = onTest,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Sm,
        enabled = !state.busy,
        modifier = Modifier.semantics { contentDescription = MESSAGING_TEST_DESCRIPTION },
    )
    TestResult(state.test)
}

/**
 * ⛔ A REJECTION AND AN UNREACHABLE CARRIER READ DIFFERENTLY AND ARE COLOURED DIFFERENTLY. "The
 * carrier says these keys are wrong" is about what was typed; "we could not ask" is about the
 * network, and drawing the second in the destructive colour would tell someone their working
 * credentials are broken.
 */
@Composable
private fun TestResult(test: MessagingTestState) {
    val text: String
    val tone = when (test) {
        is MessagingTestState.Passed -> {
            text = test.detail?.let { stringResource(R.string.messaging_test_passed_detail, it) }
                ?: stringResource(R.string.messaging_test_passed)
            DistrictTheme.colors.foreground
        }
        is MessagingTestState.Rejected -> {
            text = test.message
            DistrictTheme.colors.destructive
        }
        is MessagingTestState.Unreachable -> {
            text = test.failure.message.resolve()
            DistrictTheme.colors.mutedForeground
        }
        MessagingTestState.Idle, MessagingTestState.Running -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = tone,
        modifier = Modifier.semantics { contentDescription = MESSAGING_TEST_RESULT_DESCRIPTION },
    )
}

/**
 * The phone-number list.
 *
 * ⛔ A KEYSTROKE IS WHAT MAKES THIS PART OF THE SAVE. The box is pre-filled from the read so the
 * operator can see what is stored, but `numbersEdited` starts false and the list is omitted from
 * the request until it flips. Sending it unconditionally would rewrite the account's numbers from
 * whatever this client happened to render on every "change the label" save — and an accidentally
 * cleared box would remove them all and release their claims.
 */
@Composable
private fun NumbersField(
    state: MessagingUiState,
    draft: MessagingDraft,
    onEditDraft: (MessagingDraft?) -> Unit,
) {
    SettingsTextField(
        value = draft.phoneNumbers,
        label = stringResource(R.string.messaging_field_numbers),
        description = MESSAGING_NUMBERS_DESCRIPTION,
        enabled = !state.busy,
        singleLine = false,
        onValueChange = { onEditDraft(draft.copy(phoneNumbers = it, numbersEdited = true)) },
    )
    Text(
        text = stringResource(R.string.messaging_numbers_help),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier.semantics { contentDescription = MESSAGING_NUMBERS_HELP_DESCRIPTION },
    )
}

/**
 * ⚠️ SENT ONLY WHEN TICKED. `makeDefault: false` and an absent key are the same thing to the route
 * (`if (makeDefault || !defaultAccountId)`), so the draft omits it rather than asserting a negative
 * — and a workspace's FIRST account becomes the default whether or not this is ticked.
 */
@Composable
private fun MakeDefaultToggle(
    state: MessagingUiState,
    draft: MessagingDraft,
    onEditDraft: (MessagingDraft?) -> Unit,
) {
    DistrictListRow(
        title = stringResource(R.string.messaging_make_default),
        trailing = {
            Checkbox(
                checked = draft.makeDefault,
                onCheckedChange = { onEditDraft(draft.copy(makeDefault = it)) },
                enabled = !state.busy,
                modifier = Modifier.semantics {
                    contentDescription = MESSAGING_MAKE_DEFAULT_DESCRIPTION
                },
            )
        },
    )
}

/** ⚠️ The three the route accepts. An unrecognised one cannot be reached from the picker. */
@StringRes
private fun providerLabel(provider: String): Int = when (provider) {
    MESSAGING_PROVIDER_SINCH -> R.string.messaging_provider_sinch
    MESSAGING_PROVIDER_TELNYX -> R.string.messaging_provider_telnyx
    else -> R.string.messaging_provider_twilio
}

/**
 * The display name for one credential field.
 *
 * ⚠️ KEYED ON THE ROUTE'S OWN FIELD NAME so a key added to `messagingCredentialFields` without a
 * label renders as its wire name rather than crashing. Falling back to the API key label would be
 * worse than showing `applicationKey` verbatim.
 */
@StringRes
internal fun credentialLabel(key: String): Int = when (key) {
    "accountSid" -> R.string.messaging_field_account_sid
    "authToken" -> R.string.messaging_field_auth_token
    "projectId" -> R.string.messaging_field_project_id
    "keyId" -> R.string.messaging_field_key_id
    "keySecret" -> R.string.messaging_field_key_secret
    "applicationKey" -> R.string.messaging_field_application_key
    "applicationSecret" -> R.string.messaging_field_application_secret
    else -> R.string.messaging_field_api_key
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MESSAGING_LABEL_DESCRIPTION: String = "district-messaging-label"
const val MESSAGING_NUMBERS_DESCRIPTION: String = "district-messaging-numbers"
const val MESSAGING_NUMBERS_HELP_DESCRIPTION: String = "district-messaging-numbers-help"
const val MESSAGING_MAKE_DEFAULT_DESCRIPTION: String = "district-messaging-make-default"
const val MESSAGING_SECRET_KEEP_DESCRIPTION: String = "district-messaging-secret-keep"
const val MESSAGING_PROVIDER_SWITCH_DESCRIPTION: String = "district-messaging-provider-switch"
const val MESSAGING_TEST_DESCRIPTION: String = "district-messaging-test"
const val MESSAGING_TEST_RESULT_DESCRIPTION: String = "district-messaging-test-result"
const val MESSAGING_TEST_UNAVAILABLE_DESCRIPTION: String = "district-messaging-test-unavailable"
const val MESSAGING_SAVE_DESCRIPTION: String = "district-messaging-save"
const val MESSAGING_CANCEL_DESCRIPTION: String = "district-messaging-cancel"
