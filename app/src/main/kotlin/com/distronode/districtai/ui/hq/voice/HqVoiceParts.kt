// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.hq.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.HqResolutionStatus

/** One change HQ proposed by voice. ⛔ The summary is the subject, never the tool name. */
@Composable
internal fun ProposalCard(card: HqVoiceProposalCard, canConfirm: Boolean, actions: HqVoiceActions) {
    DistrictCard(modifier = Modifier.testTag(hqVoiceProposalTag(card.id))) {
        Text(
            text = card.proposal.summary.ifEmpty { stringResource(R.string.hq_voice_proposal_fallback) },
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
        )
        val outcome = card.outcome
        if (outcome != null) {
            Text(
                text = stringResource(outcome.label()),
                style = MaterialTheme.typography.bodySmall,
                color = if (outcome == HqResolutionStatus.FAILED) {
                    DistrictTheme.colors.destructive
                } else {
                    DistrictTheme.colors.mutedForeground
                },
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
            )
        } else {
            Row(
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                if (canConfirm) {
                    DistrictButton(
                        text = stringResource(R.string.hq_voice_confirm),
                        onClick = { actions.onConfirm(card.id) },
                        size = ButtonSize.Sm,
                        enabled = !card.isWorking,
                        modifier = Modifier.testTag(hqVoiceConfirmTag(card.id)),
                    )
                }
                DistrictButton(
                    text = stringResource(R.string.hq_voice_cancel),
                    onClick = { actions.onCancel(card.id) },
                    variant = ButtonVariant.Ghost,
                    size = ButtonSize.Sm,
                    enabled = !card.isWorking,
                    modifier = Modifier.testTag(hqVoiceCancelTag(card.id)),
                )
            }
        }
    }
}

/**
 * The consent notice: where the voice is processed, before the microphone is opened or asked for.
 *
 * ⛔ THE REGION IS THE SERVICE'S `processingRegion`, never a list kept here: the line above the
 * disclosure appears only when it is not the workspace's own region.
 *
 * ⚠️ DISMISSED (BACK, A TAP OUTSIDE) IS NOT NOW: the session is discarded, nothing was joined.
 */
@Composable
internal fun HqVoiceNotice(regionLine: String?, onStart: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(HQ_VOICE_NOTICE_DESCRIPTION),
        title = { Text(stringResource(R.string.hq_voice_notice_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                regionLine?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(HQ_VOICE_NOTICE_REGION_DESCRIPTION),
                    )
                }
                Text(text = stringResource(R.string.hq_voice_notice_body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onStart, modifier = Modifier.testTag(HQ_VOICE_NOTICE_START_DESCRIPTION)) {
                Text(stringResource(R.string.hq_voice_notice_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag(HQ_VOICE_NOTICE_CANCEL_DESCRIPTION)) {
                Text(stringResource(R.string.hq_voice_notice_cancel))
            }
        },
    )
}

private fun HqResolutionStatus.label(): Int = when (this) {
    HqResolutionStatus.APPLIED -> R.string.hq_voice_applied
    HqResolutionStatus.FAILED -> R.string.hq_voice_failed
    HqResolutionStatus.CANCELLED -> R.string.hq_voice_cancelled
}

/** Per card, so two pending proposals never share a handle. */
fun hqVoiceConfirmTag(id: String): String = "district-hq-voice-confirm-$id"

fun hqVoiceCancelTag(id: String): String = "district-hq-voice-cancel-$id"
