package com.distronode.districtai.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.WHISPER_NOT_APPLIED_TAKEN_OVER
import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.ui.resolve

/*
 * The writer's half of a Live calls card: the note to the receptionist, its receipt, and the
 * take-over control. A file of its own for detekt's per-file function ceiling.
 */

/** The note box and the take-over control: a writer, on a call the receptionist is on. */
@Composable
internal fun WriterControls(callId: String, view: LiveCallsView, actions: LiveCallsActions) {
    val takenOver = view.supervision.takenOver
    if (takenOver) {
        DistrictButton(
            text = stringResource(R.string.live_calls_hand_back),
            onClick = actions.onLeave,
            modifier = Modifier.testTag(LIVE_CALLS_HAND_BACK_TAG),
        )
        return
    }
    WhisperControl(callId, view.whispers[callId] ?: WhisperBox(), actions)
    if (view.prompt.confirmingTakeover == callId) {
        TakeOverConfirmation(actions)
    } else {
        DistrictButton(
            text = stringResource(R.string.live_calls_take_over),
            onClick = @DontMemoize { actions.onAskTakeOver(callId) },
            variant = ButtonVariant.Secondary,
            enabled = !view.supervision.busy,
            modifier = Modifier.testTag(LIVE_CALLS_TAKE_OVER_TAG),
        )
    }
}

@Composable
private fun WhisperControl(callId: String, box: WhisperBox, actions: LiveCallsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        OutlinedTextField(
            value = box.draft,
            onValueChange = @DontMemoize { actions.onDraft(callId, it) },
            label = { Text(stringResource(R.string.live_calls_whisper_label)) },
            placeholder = { Text(stringResource(R.string.live_calls_whisper_placeholder)) },
            colors = districtFieldColors(),
            modifier = Modifier.fillMaxWidth().testTag(LIVE_CALLS_WHISPER_FIELD_TAG),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.live_calls_whisper_send),
                onClick = @DontMemoize { actions.onSend(callId) },
                size = ButtonSize.Sm,
                enabled = !box.sending && box.draft.isNotBlank(),
                modifier = Modifier.testTag(LIVE_CALLS_WHISPER_SEND_TAG),
            )
        }
        box.error?.let { Failure(it.resolve(), Modifier.testTag(LIVE_CALLS_WHISPER_ERROR_TAG)) }
        box.receipt?.let { Note(receiptText(it), Modifier.testTag(LIVE_CALLS_RECEIPT_TAG)) }
    }
}

/** ⚠️ `reason` is the agent's wire vocabulary, mapped to a sentence and never shown raw. */
@Composable
private fun receiptText(receipt: WhisperAppliedData): String {
    val words = receipt.firstWords?.takeIf { it.isNotBlank() }
    return when {
        receipt.applied && words != null -> stringResource(R.string.live_calls_receipt_applied_words, words)
        receipt.applied -> stringResource(R.string.live_calls_receipt_applied)
        receipt.reason == WHISPER_NOT_APPLIED_TAKEN_OVER -> stringResource(R.string.live_calls_receipt_taken_over)
        else -> stringResource(R.string.live_calls_receipt_transfer)
    }
}

/** ⛔ In the page, never a system dialog, and it says what the caller will hear. */
@Composable
private fun TakeOverConfirmation(actions: LiveCallsActions) {
    Column(
        modifier = Modifier.testTag(LIVE_CALLS_CONFIRM_TAG),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Text(
            text = stringResource(R.string.live_calls_take_over_confirm_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Note(stringResource(R.string.live_calls_take_over_confirm_body))
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.live_calls_take_over_confirm),
                onClick = actions.onConfirmTakeOver,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag(LIVE_CALLS_CONFIRM_YES_TAG),
            )
            DistrictButton(
                text = stringResource(R.string.live_calls_take_over_cancel),
                onClick = actions.onCancelTakeOver,
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Sm,
            )
        }
    }
}

@Composable
internal fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = modifier,
    )
}

@Composable
internal fun Failure(text: String, modifier: Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.destructive,
        modifier = modifier.fillMaxWidth(),
    )
}
