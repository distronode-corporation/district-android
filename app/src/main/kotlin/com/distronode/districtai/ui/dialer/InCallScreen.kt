package com.distronode.districtai.ui.dialer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallConnectionState

/**
 * One outbound call, while it is happening.
 *
 * ⛔ NO CAMERA CONTROL EXISTS ON THIS SCREEN AND NONE MAY BE ADDED. The softphone publishes no
 * video: `CallEngine.setCameraEnabled` is simply never called, and the token the dial route mints
 * would permit it — so the ONLY thing keeping a phone call from becoming a video call is the
 * absence of the control. A camera track published into a `direct_` room would be encoded,
 * uploaded and billed with nobody able to see it, because the far end is a telephone.
 *
 * ⛔ "Calling…" AND THE TIMER ARE DIFFERENT PHASES AND NEVER BOTH. The dial route returns before
 * the callee's phone rings, so being in the room is not being on a call; the timer starts when the
 * SIP participant joins, which is the only answer signal this client has. Showing a duration
 * during the ring would count ringing as conversation, on the number an operator compares against
 * an invoice. See [ActiveCallUiState.phase].
 *
 * ⛔ `Reconnecting` IS A BANNER OVER THE CALL, NEVER A FAILURE SCREEN — the same rule the room
 * screen follows, for the same reason: a phone walking out of wifi onto its radio does this
 * routinely and the SDK recovers by itself. Tearing the call down would end calls that were about
 * to survive.
 *
 * ⚠️ THE ENDED STATE IS A SCREEN, NOT AN IMMEDIATE POP. The operator needs to see the duration and
 * that the call is in the log; a screen that vanished on hang-up would answer "how long was that"
 * with nothing.
 */
@Composable
fun InCallScreen(
    call: ActiveCallUiState,
    handlers: DialerHandlers,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = IN_CALL_ROOT_DESCRIPTION },
    ) { inset ->
        ContentContainer(modifier = inset.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(DistrictTheme.spacing.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                Callee(call)
                PhaseLine(call)
                if (call.reconnecting) ReconnectingBanner()
                Spacer(modifier = Modifier.weight(1f))
                if (call.ended) EndedActions(handlers) else LiveControls(call, handlers)
            }
        }
    }
}

@Composable
private fun Callee(call: ActiveCallUiState) {
    Text(
        // ⚠️ THE FORMATTED FORM, for reading only. What was dialled is the raw entry; see
        // `formatDialEntry`.
        text = formatDialEntry(call.number),
        style = MaterialTheme.typography.headlineSmall,
        color = DistrictTheme.colors.foreground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { contentDescription = IN_CALL_NUMBER_DESCRIPTION },
    )
}

/**
 * The one line that says what is happening.
 *
 * ⛔ THE PHASE IS READ FROM [ActiveCallUiState.phase] RATHER THAN RE-DERIVED HERE. Two of the
 * phases share a connection state — ringing and in-call are both `Connected` — so a screen that
 * branched on the connection would start the timer at the wrong moment, and the label and the
 * timer could disagree.
 */
@Composable
private fun PhaseLine(call: ActiveCallUiState) {
    val (textId, description) = when (call.phase) {
        CallPhase.DIALING -> R.string.call_state_dialing to IN_CALL_DIALING_DESCRIPTION
        CallPhase.RINGING -> R.string.call_state_ringing to IN_CALL_RINGING_DESCRIPTION
        CallPhase.FAILED -> R.string.call_state_failed to IN_CALL_FAILED_DESCRIPTION
        // ⚠️ Handled below with the duration, which is the whole content of these two.
        CallPhase.IN_CALL, CallPhase.ENDED -> null to null
    }
    if (textId != null && description != null) {
        Text(
            text = stringResource(textId),
            style = MaterialTheme.typography.titleMedium,
            color = DistrictTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = description },
        )
        return
    }
    Duration(call)
}

@Composable
private fun Duration(call: ActiveCallUiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (call.ended) {
            Text(
                text = stringResource(R.string.call_state_ended),
                style = MaterialTheme.typography.titleMedium,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier.semantics { contentDescription = IN_CALL_ENDED_DESCRIPTION },
            )
        }
        Text(
            text = if (call.ended) {
                stringResource(R.string.call_ended_duration, formatCallDuration(call.elapsedSeconds))
            } else {
                formatCallDuration(call.elapsedSeconds)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.semantics { contentDescription = IN_CALL_TIMER_DESCRIPTION },
        )
        if (call.ended) {
            // ⚠️ STATED, BECAUSE THIS APP NEVER WRITES THE CALL'S OUTCOME. The row's terminal
            // status and its billed duration come from the carrier's webhooks, so the log is the
            // record and this screen is not.
            Text(
                text = stringResource(R.string.call_log_notice),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = IN_CALL_LOG_NOTICE_DESCRIPTION },
            )
        }
    }
}

@Composable
private fun ReconnectingBanner() {
    Text(
        text = stringResource(R.string.call_state_reconnecting),
        style = MaterialTheme.typography.bodyMedium,
        color = DistrictTheme.colors.mutedForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { contentDescription = IN_CALL_RECONNECTING_DESCRIPTION },
    )
}

/**
 * Mute, speaker, hang up.
 *
 * ⚠️ THREE CONTROLS, AND THE ABSENT FOURTH IS THE POINT — see the ⛔ on the screen. A softphone
 * offers audio only, and the camera control's absence is what enforces that rather than any check.
 */
@Composable
private fun LiveControls(call: ActiveCallUiState, handlers: DialerHandlers) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        DistrictButton(
            text = stringResource(
                if (call.micEnabled) R.string.call_mic_on else R.string.call_mic_off,
            ),
            onClick = handlers.onToggleMicrophone,
            variant = ButtonVariant.Secondary,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = IN_CALL_MIC_DESCRIPTION },
        )
        DistrictButton(
            text = stringResource(
                if (call.speakerOn) R.string.call_speaker_on else R.string.call_speaker_off,
            ),
            onClick = handlers.onToggleSpeaker,
            variant = ButtonVariant.Secondary,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = IN_CALL_SPEAKER_DESCRIPTION },
        )
    }
    DistrictButton(
        text = stringResource(R.string.call_hang_up),
        onClick = handlers.onHangUp,
        variant = ButtonVariant.Danger,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = IN_CALL_HANG_UP_DESCRIPTION },
    )
}

@Composable
private fun EndedActions(handlers: DialerHandlers) {
    DistrictButton(
        text = stringResource(R.string.call_done),
        onClick = handlers.onDismissEndedCall,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = IN_CALL_DONE_DESCRIPTION },
    )
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val IN_CALL_ROOT_DESCRIPTION: String = "district-call-root"
const val IN_CALL_NUMBER_DESCRIPTION: String = "district-call-number"
const val IN_CALL_DIALING_DESCRIPTION: String = "district-call-dialing"
const val IN_CALL_RINGING_DESCRIPTION: String = "district-call-ringing"
const val IN_CALL_FAILED_DESCRIPTION: String = "district-call-failed"
const val IN_CALL_ENDED_DESCRIPTION: String = "district-call-ended"
const val IN_CALL_TIMER_DESCRIPTION: String = "district-call-timer"
const val IN_CALL_LOG_NOTICE_DESCRIPTION: String = "district-call-log-notice"
const val IN_CALL_RECONNECTING_DESCRIPTION: String = "district-call-reconnecting"
const val IN_CALL_MIC_DESCRIPTION: String = "district-call-mic"
const val IN_CALL_SPEAKER_DESCRIPTION: String = "district-call-speaker"
const val IN_CALL_HANG_UP_DESCRIPTION: String = "district-call-hang-up"
const val IN_CALL_DONE_DESCRIPTION: String = "district-call-done"

@Preview(showBackground = true)
@Composable
private fun InCallScreenPreview() {
    DistrictTheme {
        InCallScreen(
            call = ActiveCallUiState(
                number = "+14165550100",
                connection = CallConnectionState.Connected,
                answered = true,
                elapsedSeconds = 75,
            ),
            handlers = DialerHandlers({}, {}, {}, {}, {}, {}, {}, {}),
        )
    }
}
