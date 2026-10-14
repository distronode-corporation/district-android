package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.ui.resolve

/**
 * One number's actions (iOS `NumberActionsSheet`): reapply routing, and release.
 *
 * ⛔ A MANAGED LINE OFFERS NOTHING: it is held on Distronode's carrier account, the workspace's to
 * use and not to release or reconfigure. A role that may not write is told why instead.
 *
 * ⛔ RELEASE IS CONFIRMED FIRST ([NumberConfirmation.Release]): it is irreversible.
 */
@Composable
fun NumberActionsDialog(
    number: ListedNumber,
    canProvision: Boolean,
    write: NumberWriteState,
    onConfigure: () -> Unit,
    onRelease: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        containerColor = DistrictTheme.colors.card,
        title = { Text(number.phoneNumber) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().testTag(NUMBER_ACTIONS_TAG),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                Muted(stringResource(R.string.marketplace_number_meta, number.provider, number.status))
                NumberWriteNotice(write)
                when {
                    number.managed -> Muted(stringResource(R.string.numbers_managed_no_release))
                    !canProvision -> Muted(stringResource(R.string.numbers_viewer_cannot_provision))
                    else -> {
                        DistrictButton(
                            text = stringResource(R.string.numbers_configure_action),
                            onClick = onConfigure,
                            variant = ButtonVariant.Secondary,
                            enabled = write.isArmed,
                            modifier = Modifier.fillMaxWidth().testTag(NUMBER_CONFIGURE_TAG),
                        )
                        Muted(stringResource(R.string.numbers_configure_caption))
                        DistrictButton(
                            text = stringResource(R.string.numbers_release_action),
                            onClick = onRelease,
                            variant = ButtonVariant.Danger,
                            enabled = write.isArmed,
                            modifier = Modifier.fillMaxWidth().testTag(NUMBER_RELEASE_TAG),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag(NUMBER_ACTIONS_CLOSE_TAG)) {
                Text(stringResource(R.string.numbers_close))
            }
        },
    )
}

/**
 * The confirmation of a billed, filed or irreversible write (iOS `NumberConfirmationHost`).
 *
 * ⚠️ IT CLOSES AS THE WRITE STARTS (the ViewModel clears it), and the outcome shows where the
 * control was: on the number's actions, the lookup card, or the tab's notice. A write that is not
 * armed is never offered a confirmation in the first place.
 */
@Composable
fun NumberConfirmDialog(confirmation: NumberConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val words = confirmationWords(confirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        title = { Text(words.title) },
        text = { Muted(words.body, Modifier.testTag(NUMBER_CONFIRM_TAG)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(NUMBER_CONFIRM_ACTION_TAG)) {
                Text(words.action)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(NUMBER_CONFIRM_CANCEL_TAG)) {
                Text(stringResource(R.string.numbers_cancel))
            }
        },
    )
}

/** A confirmation's title, body and action. */
private data class ConfirmationWords(val title: String, val body: String, val action: String)

@Composable
private fun confirmationWords(confirmation: NumberConfirmation): ConfirmationWords = when (confirmation) {
    is NumberConfirmation.Release -> ConfirmationWords(
        stringResource(R.string.numbers_release_confirm_title),
        stringResource(R.string.numbers_release_confirm_body, confirmation.phoneNumber),
        stringResource(R.string.numbers_release_confirm_action),
    )
    is NumberConfirmation.SubmitRegistration -> ConfirmationWords(
        stringResource(R.string.numbers_submit_confirm_title),
        stringResource(R.string.numbers_submit_confirm_body, confirmation.country),
        stringResource(R.string.numbers_submit_confirm_action),
    )
    NumberConfirmation.A2P -> ConfirmationWords(
        stringResource(R.string.numbers_a2p_confirm_title),
        stringResource(R.string.numbers_a2p_confirm_body),
        stringResource(R.string.numbers_a2p_confirm_action),
    )
    NumberConfirmation.TollFree -> ConfirmationWords(
        stringResource(R.string.numbers_tfv_confirm_title),
        stringResource(R.string.numbers_tfv_confirm_body),
        stringResource(R.string.numbers_tfv_confirm_action),
    )
    NumberConfirmation.Lookup -> ConfirmationWords(
        stringResource(R.string.numbers_lookup_confirm_title),
        stringResource(R.string.numbers_lookup_confirm_body),
        stringResource(R.string.numbers_lookup_confirm_action),
    )
}

/**
 * What the last write of a family said: done in the foreground colour, failed in the destructive
 * one, and, after a failure that may have gone through, that it is not offered again.
 */
@Composable
fun NumberWriteNotice(state: NumberWriteState, modifier: Modifier = Modifier) {
    val (text, failed) = when (state) {
        NumberWriteState.Idle, NumberWriteState.Running -> return
        is NumberWriteState.Done -> (listOf(state.notice.resolve()) + state.details).joinToString(" ") to false
        is NumberWriteState.Failed -> state.failure.message.resolve() to true
    }
    Column(
        modifier = modifier.fillMaxWidth().testTag(NUMBER_NOTICE_TAG),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) DistrictTheme.colors.destructive else DistrictTheme.colors.foreground,
        )
        if (state.refusedRepeat) Muted(stringResource(R.string.numbers_write_unrepeatable))
    }
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = modifier,
    )
}

/** Stable handles for tests. */
const val NUMBER_ACTIONS_TAG: String = "district-number-actions"
const val NUMBER_ACTIONS_CLOSE_TAG: String = "district-number-actions-close"
const val NUMBER_CONFIGURE_TAG: String = "district-number-configure"
const val NUMBER_RELEASE_TAG: String = "district-number-release"
const val NUMBER_CONFIRM_TAG: String = "district-number-confirm"
const val NUMBER_CONFIRM_ACTION_TAG: String = "district-number-confirm-action"
const val NUMBER_CONFIRM_CANCEL_TAG: String = "district-number-confirm-cancel"
const val NUMBER_NOTICE_TAG: String = "district-number-notice"
