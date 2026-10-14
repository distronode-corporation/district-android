package com.distronode.districtai.ui.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.ui.resolve
import java.util.UUID

/**
 * The Report dialog, with its own [ReportViewModel].
 *
 * ⛔ THE VIEWMODEL IS KEYED BY A KEY MINTED WHEN THE DIALOG OPENS, and that key is the request's
 * idempotency key: a retry in the same dialog reuses it, and a new dialog gets a new one.
 * `rememberSaveable` keeps it across rotation, so a rotated dialog is the same report.
 */
@Composable
fun ReportDialogHost(
    target: ReportTarget,
    workspaceId: String,
    support: SupportRepository,
    onDismiss: () -> Unit,
) {
    val key = rememberSaveable { UUID.randomUUID().toString() }
    val viewModel: ReportViewModel = viewModel(
        key = "report-$key",
        factory = ReportViewModel.factory(support, workspaceId, key),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportDialog(target = target, state = state, onSubmit = viewModel::submit, onDismiss = onDismiss)
}

/**
 * The flag: one optional note, one Report button, one confirmation.
 *
 * ⛔ IT DOES NOT CLOSE ITSELF ON SUCCESS: the reviewer must SEE the flag accepted, and a dialog that
 * vanished would look the same as one that failed. The confirmation is the content; Done closes it.
 *
 * ⚠️ THE NOTE IS CLAMPED TO [ContentReport.NOTE_LIMIT] AS IT IS TYPED, so an over-long note is
 * refused at the keyboard rather than 400-ing after the server claimed the key.
 */
@Composable
fun ReportDialog(
    target: ReportTarget,
    state: ReportState,
    onSubmit: (subject: String, message: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var note by rememberSaveable { mutableStateOf("") }
    val sending = state == ReportState.Sending
    val words = reportWords()
    AlertDialog(
        onDismissRequest = onDismiss,
        // ⚠️ A REPORT ON ITS WAY IS NOT CLOSED BY A TAP OUTSIDE OR BACK: its outcome must be seen.
        properties = DialogProperties(dismissOnBackPress = !sending, dismissOnClickOutside = !sending),
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        title = { Text(headline(target)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(REPORT_ROOT_TAG),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                when (state) {
                    is ReportState.Done -> Text(
                        text = stringResource(state.confirmation),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(REPORT_CONFIRMATION_TAG),
                    )
                    else -> {
                        if (state is ReportState.Failed) {
                            Text(
                                text = state.failure.message.resolve(),
                                style = MaterialTheme.typography.bodySmall,
                                color = DistrictTheme.colors.destructive,
                                modifier = Modifier.testTag(REPORT_FAILURE_TAG),
                            )
                        }
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it.take(ContentReport.NOTE_LIMIT) },
                            label = { Text(stringResource(R.string.report_note_label)) },
                            placeholder = { Text(stringResource(R.string.report_note_prompt)) },
                            enabled = !sending,
                            minLines = NOTE_MIN_LINES,
                            maxLines = NOTE_MAX_LINES,
                            colors = districtFieldColors(),
                            modifier = Modifier.fillMaxWidth().testTag(REPORT_NOTE_TAG),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (state is ReportState.Done) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(REPORT_DONE_TAG)) {
                    Text(stringResource(R.string.report_done))
                }
            } else {
                TextButton(
                    onClick = { onSubmit(words.subject, ContentReport.message(target, note, words)) },
                    enabled = !sending,
                    modifier = Modifier.testTag(REPORT_SUBMIT_TAG),
                ) {
                    Text(stringResource(if (sending) R.string.report_sending else R.string.report_submit))
                }
            }
        },
        dismissButton = {
            if (state !is ReportState.Done) {
                TextButton(onClick = onDismiss, enabled = !sending, modifier = Modifier.testTag(REPORT_CANCEL_TAG)) {
                    Text(stringResource(R.string.report_cancel))
                }
            }
        },
    )
}

@Composable
private fun headline(target: ReportTarget): String {
    val resource = ContentReport.headline(target)
    val name = (target as? ReportTarget.Conversation)?.name
    return if (name.isNullOrBlank()) stringResource(resource) else stringResource(resource, name)
}

/**
 * The desk's English, from the catalog's `fixed` entries.
 *
 * ⚠️ THE REFERENCE LINES ARE FORMATTED WHEN THE MESSAGE IS BUILT, through [LocalResources], because
 * their id is only known then.
 */
@Composable
private fun reportWords(): ReportWords {
    val resources = LocalResources.current
    return ReportWords(
        subject = stringResource(R.string.report_subject),
        preamble = stringResource(R.string.report_preamble),
        noNote = stringResource(R.string.report_no_note),
    ) { reference ->
        when (reference) {
            is ReportReference.Call -> resources.getString(R.string.report_reference_call, reference.callId)
            is ReportReference.Contact -> resources.getString(R.string.report_reference_contact, reference.contactId)
            ReportReference.Conversation -> resources.getString(R.string.report_reference_conversation)
            ReportReference.HqAnswer -> resources.getString(R.string.report_reference_hq_answer)
        }
    }
}

private const val NOTE_MIN_LINES = 3
private const val NOTE_MAX_LINES = 6

/** Stable handles for tests. */
const val REPORT_ROOT_TAG: String = "district-report-root"
const val REPORT_NOTE_TAG: String = "district-report-note"
const val REPORT_SUBMIT_TAG: String = "district-report-submit"
const val REPORT_CANCEL_TAG: String = "district-report-cancel"
const val REPORT_DONE_TAG: String = "district-report-done"
const val REPORT_FAILURE_TAG: String = "district-report-failure"
const val REPORT_CONFIRMATION_TAG: String = "district-report-confirmation"
