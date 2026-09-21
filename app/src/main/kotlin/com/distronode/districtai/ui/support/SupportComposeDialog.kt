package com.distronode.districtai.ui.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.ui.resolve

/**
 * Raise a request with Distronode.
 *
 * ⛔ THE PAYLOAD IS EXACTLY `kind`, `subject`, `message` AND THE IDEMPOTENCY KEY, AND NOTHING MAY BE
 * ADDED TO IT. This is backed by a real Atlassian service desk where `requestFieldValues` may carry
 * only the fields the REQUEST TYPE exposes on its portal form, and an unknown field is a hard 400
 * rather than an ignored key — the failure that once cost every ticket the platform tried to file.
 * So no field may be added to this form because it looks available, and in particular nothing that
 * identifies the requester: the server derives that from the session.
 *
 * ⛔ THE KIND IS A CLOSED VOCABULARY AND IS PICKED, NEVER TYPED. The route maps it onto a Jira
 * request type id precisely so a caller cannot file into an arbitrary type whose portal form we do
 * not populate.
 */
@Composable
fun SupportComposeDialog(
    state: SupportComposeState,
    onKind: (SupportRequestKind) -> Unit,
    onSubject: (String) -> Unit,
    onMessage: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.support_new)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = SUPPORT_COMPOSE_ROOT_DESCRIPTION },
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                Text(
                    text = stringResource(R.string.support_kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
                ) {
                    SupportRequestKind.entries.forEach { kind ->
                        DistrictButton(
                            text = stringResource(kindLabel(kind)),
                            onClick = { onKind(kind) },
                            variant = if (state.kind == kind) {
                                ButtonVariant.Primary
                            } else {
                                ButtonVariant.Ghost
                            },
                            enabled = !state.submitting,
                            modifier = Modifier.semantics {
                                contentDescription = "$SUPPORT_COMPOSE_KIND_DESCRIPTION-${kind.wire}"
                            },
                        )
                    }
                }

                // ⚠️ THE SERVER'S OWN SENTENCE, VERBATIM. A 429 names the remedy (reply on an
                // existing request) and a 503 names the public form as a PATH rather than a host,
                // because Canada's canonical host is distronode.ca. Replacing either with a generic
                // message would drop the one useful thing in it.
                state.failure?.let { failure ->
                    Text(
                        text = failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier.semantics {
                            contentDescription = SUPPORT_COMPOSE_FAILURE_DESCRIPTION
                        },
                    )
                }

                OutlinedTextField(
                    value = state.subject,
                    onValueChange = onSubject,
                    label = { Text(stringResource(R.string.support_compose_subject)) },
                    enabled = !state.submitting,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = SUPPORT_COMPOSE_SUBJECT_DESCRIPTION },
                )
                OutlinedTextField(
                    value = state.message,
                    onValueChange = onMessage,
                    label = { Text(stringResource(R.string.support_compose_message)) },
                    enabled = !state.submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = SUPPORT_COMPOSE_MESSAGE_DESCRIPTION },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = state.submittable,
                modifier = Modifier.semantics {
                    contentDescription = SUPPORT_COMPOSE_SUBMIT_DESCRIPTION
                },
            ) {
                Text(
                    stringResource(
                        if (state.submitting) {
                            R.string.support_compose_submitting
                        } else {
                            R.string.support_compose_submit
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.submitting,
                modifier = Modifier.semantics {
                    contentDescription = SUPPORT_COMPOSE_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.support_compose_cancel))
            }
        },
    )
}

internal fun kindLabel(kind: SupportRequestKind): Int = when (kind) {
    SupportRequestKind.PROBLEM -> R.string.support_kind_problem
    SupportRequestKind.QUESTION -> R.string.support_kind_question
    SupportRequestKind.SUGGESTION -> R.string.support_kind_suggestion
}

const val SUPPORT_COMPOSE_ROOT_DESCRIPTION: String = "district-support-compose-root"
const val SUPPORT_COMPOSE_KIND_DESCRIPTION: String = "district-support-compose-kind"
const val SUPPORT_COMPOSE_SUBJECT_DESCRIPTION: String = "district-support-compose-subject"
const val SUPPORT_COMPOSE_MESSAGE_DESCRIPTION: String = "district-support-compose-message"
const val SUPPORT_COMPOSE_SUBMIT_DESCRIPTION: String = "district-support-compose-submit"
const val SUPPORT_COMPOSE_CANCEL_DESCRIPTION: String = "district-support-compose-cancel"
const val SUPPORT_COMPOSE_FAILURE_DESCRIPTION: String = "district-support-compose-failure"
