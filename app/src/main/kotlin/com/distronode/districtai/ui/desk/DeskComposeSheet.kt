package com.distronode.districtai.ui.desk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.resolve

/**
 * Raise a ticket on a customer's behalf.
 *
 * ⚠️ AN `AlertDialog` RATHER THAN A `ModalBottomSheet`, matching every other form in this app. The
 * sheet APIs are still `ExperimentalMaterial3Api` on this BOM and nothing else here opts in; a
 * dialog needs no opt-in and is what `AddMemberDialog` and the messaging editors already use.
 *
 * ⛔ THE THREE REQUESTER BOXES MAY ALL BE LEFT BLANK AND THE TICKET IS STILL VALID. The route needs
 * only a subject and a message. What must NOT happen is a blank box reaching the wire as `""`:
 * `requesterEmail: ""` fails the route's `.email()` and takes the whole object down with "A subject
 * and a description are required" — naming two fields the operator did fill in. `DeskRepository`
 * trims them to null, which is why this form hands over whatever is in its boxes without checking.
 *
 * ⛔ THE HELP LINE SAYS WHOSE WORDS THE MESSAGE BECOMES, and that is a product fact rather than
 * decoration: the server records the CUSTOMER as the author even when an operator types it, because
 * it is the customer's problem. An operator who thought they were writing as the team would word it
 * the other way round.
 */
@Composable
fun DeskComposeSheet(
    state: DeskComposeState,
    onSubject: (String) -> Unit,
    onMessage: (String) -> Unit,
    onRequesterName: (String) -> Unit,
    onRequesterEmail: (String) -> Unit,
    onRequesterPhone: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(stringResource(R.string.desk_compose_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .semantics { contentDescription = DESK_COMPOSE_ROOT_DESCRIPTION },
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                Text(
                    text = stringResource(R.string.desk_compose_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = DistrictTheme.colors.mutedForeground,
                )

                state.failure?.let { failure ->
                    Text(
                        text = failure.message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DistrictTheme.colors.destructive,
                        modifier = Modifier.semantics {
                            contentDescription = DESK_COMPOSE_FAILURE_DESCRIPTION
                        },
                    )
                }

                Field(
                    value = state.subject,
                    onChange = onSubject,
                    labelId = R.string.desk_compose_subject,
                    enabled = !state.submitting,
                    description = DESK_COMPOSE_SUBJECT_DESCRIPTION,
                )
                Field(
                    value = state.message,
                    onChange = onMessage,
                    labelId = R.string.desk_compose_message,
                    enabled = !state.submitting,
                    description = DESK_COMPOSE_MESSAGE_DESCRIPTION,
                )
                Field(
                    value = state.requesterName,
                    onChange = onRequesterName,
                    labelId = R.string.desk_compose_requester_name,
                    enabled = !state.submitting,
                    description = DESK_COMPOSE_NAME_DESCRIPTION,
                )
                Field(
                    value = state.requesterEmail,
                    onChange = onRequesterEmail,
                    labelId = R.string.desk_compose_requester_email,
                    enabled = !state.submitting,
                    description = DESK_COMPOSE_EMAIL_DESCRIPTION,
                )
                Field(
                    value = state.requesterPhone,
                    onChange = onRequesterPhone,
                    labelId = R.string.desk_compose_requester_phone,
                    enabled = !state.submitting,
                    description = DESK_COMPOSE_PHONE_DESCRIPTION,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                // ⚠️ Disabled WHILE SUBMITTING as well as when incomplete. The idempotency key makes
                // a double tap harmless server-side; this makes it invisible.
                enabled = state.submittable,
                modifier = Modifier.semantics {
                    contentDescription = DESK_COMPOSE_SUBMIT_DESCRIPTION
                },
            ) {
                Text(
                    stringResource(
                        if (state.submitting) {
                            R.string.desk_compose_submitting
                        } else {
                            R.string.desk_compose_submit
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
                    contentDescription = DESK_COMPOSE_CANCEL_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.desk_compose_cancel))
            }
        },
    )
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    labelId: Int,
    enabled: Boolean,
    description: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(labelId)) },
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    )
}

const val DESK_COMPOSE_ROOT_DESCRIPTION: String = "district-desk-compose-root"
const val DESK_COMPOSE_SUBJECT_DESCRIPTION: String = "district-desk-compose-subject"
const val DESK_COMPOSE_MESSAGE_DESCRIPTION: String = "district-desk-compose-message"
const val DESK_COMPOSE_NAME_DESCRIPTION: String = "district-desk-compose-name"
const val DESK_COMPOSE_EMAIL_DESCRIPTION: String = "district-desk-compose-email"
const val DESK_COMPOSE_PHONE_DESCRIPTION: String = "district-desk-compose-phone"
const val DESK_COMPOSE_SUBMIT_DESCRIPTION: String = "district-desk-compose-submit"
const val DESK_COMPOSE_CANCEL_DESCRIPTION: String = "district-desk-compose-cancel"
const val DESK_COMPOSE_FAILURE_DESCRIPTION: String = "district-desk-compose-failure"
