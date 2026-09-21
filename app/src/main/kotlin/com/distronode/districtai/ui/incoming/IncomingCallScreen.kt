package com.distronode.districtai.ui.incoming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.distronode.districtai.call.IncomingCallPhase
import com.distronode.districtai.call.IncomingCallUiState
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.dialer.DialerHandlers
import com.distronode.districtai.ui.dialer.InCallScreen
import com.distronode.districtai.ui.resolve

/**
 * One inbound call, from the ring to the hang-up.
 *
 * ⛔ THE CONNECTED HALF IS `InCallScreen`, REUSED RATHER THAN REBUILT. Mute, speaker, hang up, the
 * duration, the reconnecting banner and the ended summary are identical for an inbound and an
 * outbound call — they are properties of a live audio call, not of how it started — and a second
 * copy would be the thing that drifts. What is genuinely different is the RING, which outbound does
 * not have, and that is the only part drawn here.
 *
 * ⛔ THE CALLER IS SHOWN AS A LABEL BECAUSE THIS CLIENT NEVER LEARNS WHO IT IS. The push payload
 * carries identifiers only (the server's push sender sends no number and no name, because a
 * notification is readable by the OS and by any notification-listener app), and the answer route
 * returns a join credential rather than a caller. So [R.string.incoming_call_caller] is not a
 * placeholder waiting to be filled in; it is the whole of what is knowable. ⚠️ `formatDialEntry`
 * returns a string with no digits unchanged, which is what lets a label pass through the number slot
 * without being regrouped into nonsense.
 *
 * ⛔ THERE IS NO DECLINE BUTTON ONCE ANSWERING HAS STARTED, AND THE PHASE IS WHAT ENFORCES IT
 * RATHER THAN A DISABLED FLAG. A decline landing mid-join would tear down a call whose credential
 * has already been spent and whose rendezvous the server has already read.
 *
 * ⚠️ A SCREEN RATHER THAN A NAVIGATION DESTINATION, DELIBERATELY, AND FOR THE REASON
 * `DialerUiState` GIVES FOR HOLDING ITS CALL IN STATE: a destination restored from the back stack
 * after process death would re-run whatever effect put it there, and here that would be a ringing
 * screen for a call that died with the process. The activity draws this over the graph while a call
 * exists and stops when it does not.
 */
@Composable
fun IncomingCallScreen(
    state: IncomingCallUiState,
    handlers: IncomingCallHandlers,
    modifier: Modifier = Modifier,
) {
    val call = state.call
    // ⛔ THE PHASE GATES THE HAND-OVER, NOT THE PRESENCE OF A SESSION, AND THE DIFFERENCE IS VISIBLE.
    // The controller starts mirroring the session's state the moment it is created, which is BEFORE
    // the LiveKit join resolves — so `call` becomes non-null while the phase is still ANSWERING. A
    // branch on nullability alone would flip to the in-call surface at that instant and draw
    // "Calling…" with a live hang-up button over a call that has not been joined yet, skipping the
    // connecting state entirely. ⚠️ ENDED keeps the in-call surface when it HAS a session, because
    // that is where the final duration and the call-log notice live.
    if (call != null && state.phase != IncomingCallPhase.ANSWERING) {
        InCallScreen(
            // ⚠️ THE LABEL IS SUBSTITUTED HERE, IN COMPOSITION, rather than being carried on the
            // state. The state is produced by a controller with no `Context`, and a hardcoded
            // English string on a model in an app whose manifest declares `supportsRtl` is exactly
            // the copy `stringResource` cannot reach.
            call = call.copy(number = stringResource(R.string.incoming_call_caller)),
            handlers = DialerHandlers(
                // ⚠️ THE FOUR KEYPAD HANDLERS ARE NO-OPS AND CANNOT BE REACHED: `InCallScreen`
                // draws only the in-call controls. They exist because the type is shared with the
                // dialler, which is the point of sharing it.
                onEntryChange = {},
                onCallBack = {},
                onDial = {},
                onToggleMicrophone = handlers.onToggleMicrophone,
                onToggleSpeaker = handlers.onToggleSpeaker,
                onHangUp = handlers.onHangUp,
                onDismissEndedCall = handlers.onDismiss,
                onBack = handlers.onDismiss,
            ),
            modifier = modifier,
        )
        return
    }
    RingingScreen(state = state, handlers = handlers, modifier = modifier)
}

/**
 * The ring, and the two outcomes that never reach a media session.
 *
 * ⚠️ IT ALSO DRAWS THE **ENDED-WITHOUT-CONNECTING** CASE — a caller who hung up first, or an answer
 * the server refused. There is no duration to show and no `InCallScreen` to hand to, so the message
 * and a dismiss button are the whole of it.
 */
@Composable
private fun RingingScreen(
    state: IncomingCallUiState,
    handlers: IncomingCallHandlers,
    modifier: Modifier = Modifier,
) {
    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = INCOMING_ROOT_DESCRIPTION },
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
                Text(
                    text = stringResource(R.string.incoming_call_caller),
                    style = MaterialTheme.typography.headlineSmall,
                    color = DistrictTheme.colors.foreground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { contentDescription = INCOMING_CALLER_DESCRIPTION },
                )
                PhaseLine(state)
                Spacer(modifier = Modifier.weight(1f))
                Actions(state, handlers)
            }
        }
    }
}

@Composable
private fun PhaseLine(state: IncomingCallUiState) {
    val message = state.message
    // ⛔ THE MESSAGE WINS OVER THE PHASE. An ended call whose message says the caller hung up is
    // more useful than the word "Ended", and the two are never both worth showing.
    if (message != null) {
        Text(
            text = message.message.resolve(),
            style = MaterialTheme.typography.titleMedium,
            color = DistrictTheme.colors.mutedForeground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = INCOMING_MESSAGE_DESCRIPTION },
        )
        return
    }
    Text(
        text = stringResource(
            if (state.phase == IncomingCallPhase.ANSWERING) {
                R.string.incoming_call_connecting
            } else {
                R.string.incoming_call_ringing
            },
        ),
        style = MaterialTheme.typography.titleMedium,
        color = DistrictTheme.colors.mutedForeground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { contentDescription = INCOMING_PHASE_DESCRIPTION },
    )
}

/**
 * ⛔ THE BUTTONS ARE CHOSEN BY PHASE, NOT DISABLED BY IT. A disabled Answer button on a call that is
 * already connecting is a control that looks momentarily broken; an absent one is a state change the
 * user can read. And the ENDED phase must offer neither — its only action is to get out of the way.
 */
@Composable
private fun Actions(state: IncomingCallUiState, handlers: IncomingCallHandlers) {
    when (state.phase) {
        IncomingCallPhase.RINGING -> {
            Text(
                text = stringResource(R.string.incoming_call_notice),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = INCOMING_NOTICE_DESCRIPTION },
            )
            DistrictButton(
                text = stringResource(R.string.incoming_call_answer),
                onClick = handlers.onAnswer,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = INCOMING_ANSWER_DESCRIPTION },
            )
            DistrictButton(
                text = stringResource(R.string.incoming_call_decline),
                onClick = handlers.onDecline,
                variant = ButtonVariant.Danger,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = INCOMING_DECLINE_DESCRIPTION },
            )
        }
        // ⚠️ NOTHING TO PRESS WHILE THE ANSWER ROUND TRIP IS OUT. See the ⛔ on the screen: a
        // decline landing here would tear down a call whose credential has already been spent.
        IncomingCallPhase.ANSWERING -> Unit
        IncomingCallPhase.ENDED -> DistrictButton(
            text = stringResource(R.string.call_done),
            onClick = handlers.onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = INCOMING_DISMISS_DESCRIPTION },
        )
        // ⚠️ UNREACHABLE HERE BY CONSTRUCTION: IN_CALL always carries a session, and the screen
        // hands that to `InCallScreen` before this composable is reached.
        IncomingCallPhase.IN_CALL -> Unit
    }
}

/**
 * ⚠️ A TYPE RATHER THAN FIVE PARAMETERS, matching `DialerHandlers` and `RoomControls`: detekt caps a
 * composable's parameter list, and these five are one concept — what the user can do about a call
 * that is ringing at them.
 */
data class IncomingCallHandlers(
    val onAnswer: () -> Unit,
    val onDecline: () -> Unit,
    val onToggleMicrophone: () -> Unit,
    val onToggleSpeaker: () -> Unit,
    /**
     * ⚠️ DISTINCT FROM [onDecline] EVEN THOUGH BOTH END THE CALL, because they are different acts
     * at different moments and only one of them is reachable at a time: decline refuses a ring, hang
     * up ends a conversation. They happen to share a teardown, and the controller is where that is
     * expressed — collapsing them here would put a "Decline" label on a live call.
     */
    val onHangUp: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val INCOMING_ROOT_DESCRIPTION: String = "district-incoming-root"
const val INCOMING_CALLER_DESCRIPTION: String = "district-incoming-caller"
const val INCOMING_PHASE_DESCRIPTION: String = "district-incoming-phase"
const val INCOMING_MESSAGE_DESCRIPTION: String = "district-incoming-message"
const val INCOMING_NOTICE_DESCRIPTION: String = "district-incoming-notice"
const val INCOMING_ANSWER_DESCRIPTION: String = "district-incoming-answer"
const val INCOMING_DECLINE_DESCRIPTION: String = "district-incoming-decline"
const val INCOMING_DISMISS_DESCRIPTION: String = "district-incoming-dismiss"

@Preview(showBackground = true)
@Composable
private fun IncomingCallScreenPreview() {
    DistrictTheme {
        IncomingCallScreen(
            state = IncomingCallUiState(
                workspaceId = "ws-preview",
                callId = "CA00000000000000000000000000000000",
                phase = IncomingCallPhase.RINGING,
            ),
            handlers = IncomingCallHandlers({}, {}, {}, {}, {}, {}),
        )
    }
}
