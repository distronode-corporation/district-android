package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
 * One persona audition, on screen.
 *
 * ⛔ IT SAYS OUT LOUD THAT THIS IS A REAL CALL BEFORE IT STARTS ONE. The token invites the voice
 * agent into a room on the workspace's own pipeline, where it answers with speech recognition, a
 * model and speech synthesis exactly as it would on a telephone call; the route is capped at 10/min
 * per workspace and is not idempotent. A control that read "Preview" with no further wording would
 * be describing a dry run that does not exist.
 *
 * ⛔ THE START BUTTON IS DISABLED THROUGH THE COOLDOWN, INCLUDING AFTER A REFUSAL. The commonest
 * refusal is the rate limit itself, and a button that re-arms instantly invites somebody to spend
 * the rest of the minute's slots finding out it is still refused.
 *
 * ⚠️ `Waiting` IS NOT `Live`, AND THE COPY MUST NOT BLUR THEM. The agent is dispatched to the room
 * and takes a moment to arrive; telling somebody to start talking before it has would have them
 * speak into a room nothing is listening to and then conclude the persona is broken.
 */
@Composable
internal fun PersonaPreviewDialog(
    state: PersonaPreviewUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        // ⛔ A TAP OUTSIDE DISMISSES *AND STOPS*. The caller ends the session on dismissal, because
        // a sheet that merely disappeared would leave a room publishing this phone's microphone
        // with nothing on screen able to reach it.
        onDismissRequest = onDismiss,
        containerColor = DistrictTheme.colors.card,
        titleContentColor = DistrictTheme.colors.foreground,
        textContentColor = DistrictTheme.colors.foreground,
        modifier = Modifier.semantics { contentDescription = PERSONA_PREVIEW_ROOT_DESCRIPTION },
        title = { Text(stringResource(R.string.persona_preview_title)) },
        text = { PreviewBody(state) },
        confirmButton = {
            if (state.phase.isRunning) {
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.semantics {
                        contentDescription = PERSONA_PREVIEW_STOP_DESCRIPTION
                    },
                ) {
                    Text(stringResource(R.string.persona_preview_stop))
                }
            } else {
                TextButton(
                    onClick = onStart,
                    enabled = state.canStart,
                    modifier = Modifier.semantics {
                        contentDescription = PERSONA_PREVIEW_START_DESCRIPTION
                    },
                ) {
                    Text(stringResource(R.string.persona_preview_start))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics {
                    contentDescription = PERSONA_PREVIEW_CLOSE_DESCRIPTION
                },
            ) {
                Text(stringResource(R.string.persona_preview_close))
            }
        },
    )
}

@Composable
private fun PreviewBody(state: PersonaPreviewUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        Text(
            text = stringResource(phaseCopy(state.phase)),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.semantics { contentDescription = PERSONA_PREVIEW_PHASE_DESCRIPTION },
        )
        // ⛔ THE COST IS STATED BEFORE THE FIRST TAP, NOT AFTER. See the ⛔ on the dialog.
        Text(
            text = stringResource(R.string.persona_preview_billed),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
        if (state.agentSpeaking) {
            Text(
                text = stringResource(R.string.persona_preview_speaking),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = PERSONA_PREVIEW_SPEAKING_DESCRIPTION
                },
            )
        }
        // ⚠️ SAID OUT LOUD RATHER THAN LEFT AS SILENCE. A denied microphone still leaves a usable
        // audition, so the session goes ahead and this explains why nothing is heard back.
        if (state.microphoneDenied) {
            Text(
                text = stringResource(R.string.persona_preview_no_microphone),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics {
                    contentDescription = PERSONA_PREVIEW_NO_MIC_DESCRIPTION
                },
            )
        }
        state.failure?.let { failure ->
            Text(
                text = failure.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.semantics {
                    contentDescription = PERSONA_PREVIEW_FAILURE_DESCRIPTION
                },
            )
        }
    }
}

private fun phaseCopy(phase: PersonaPreviewPhase): Int = when (phase) {
    PersonaPreviewPhase.Idle -> R.string.persona_preview_idle
    PersonaPreviewPhase.Minting -> R.string.persona_preview_minting
    PersonaPreviewPhase.Connecting -> R.string.persona_preview_connecting
    PersonaPreviewPhase.Waiting -> R.string.persona_preview_waiting
    PersonaPreviewPhase.Live -> R.string.persona_preview_live
    PersonaPreviewPhase.Reconnecting -> R.string.persona_preview_reconnecting
    PersonaPreviewPhase.Failed -> R.string.persona_preview_failed
    // ⛔ TWO ENDINGS, TWO SENTENCES. Telling somebody they stopped a session the server dropped is
    // how a second billed one gets started.
    is PersonaPreviewPhase.Ended -> when (phase.reason) {
        PersonaPreviewEnding.Stopped -> R.string.persona_preview_ended_stopped
        is PersonaPreviewEnding.DroppedRemotely -> R.string.persona_preview_ended_dropped
    }
}

const val PERSONA_PREVIEW_ROOT_DESCRIPTION: String = "district-persona-preview-root"
const val PERSONA_PREVIEW_START_DESCRIPTION: String = "district-persona-preview-start"
const val PERSONA_PREVIEW_STOP_DESCRIPTION: String = "district-persona-preview-stop"
const val PERSONA_PREVIEW_CLOSE_DESCRIPTION: String = "district-persona-preview-close"
const val PERSONA_PREVIEW_PHASE_DESCRIPTION: String = "district-persona-preview-phase"
const val PERSONA_PREVIEW_SPEAKING_DESCRIPTION: String = "district-persona-preview-speaking"
const val PERSONA_PREVIEW_NO_MIC_DESCRIPTION: String = "district-persona-preview-no-microphone"
const val PERSONA_PREVIEW_FAILURE_DESCRIPTION: String = "district-persona-preview-failure"
