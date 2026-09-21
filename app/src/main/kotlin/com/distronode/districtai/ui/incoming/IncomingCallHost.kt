package com.distronode.districtai.ui.incoming

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.distronode.districtai.call.IncomingCallController

/**
 * Draws the ringing/in-call screen while there is a call, and nothing when there is not.
 *
 * ⛔ THE CONTROLLER IS PROCESS-SCOPED AND IS **NOT** A ViewModel, WHICH IS THE WHOLE REASON THIS
 * FUNCTION IS ONE LINE OF PLUMBING RATHER THAN A DESTINATION WITH A FACTORY. A ring arrives at a
 * `FirebaseMessagingService`, possibly into a cold process with no Activity at all; a ViewModel
 * would be constructed after the event it needs to have received, and a second one (per Activity
 * recreation) would be a second state machine for one telephone call. The Activity subscribes to
 * state it did not create, which is exactly the relationship it has with the login controller.
 *
 * ⛔ `collectAsStateWithLifecycle`, NOT `collectAsState`, FOR THE REASON EVERY OTHER SCREEN USES IT
 * — and here there is a second one: a backgrounded Activity that kept collecting would keep
 * recomposing a ringing screen nobody can see, while the notification and the Telecom connection
 * (which are what a backgrounded user actually interacts with) carry the call.
 *
 * ⚠️ NO STATE OF ITS OWN, DELIBERATELY. Every handler goes straight to the controller, so a
 * recomposition or an Activity recreation cannot desynchronise the screen from the call: there is
 * nothing here to be out of date.
 *
 * ⛔ ANSWER ASKS FOR THE MICROPHONE AND ONLY THEN ANSWERS, THE RULE THE DIALLER ALREADY FOLLOWS.
 * Nothing before this screen has asked for `RECORD_AUDIO` on a fresh install — the dialler and the
 * rooms ask for themselves — so an answered call published a silent track and, on API 34+, the
 * `microphone`-typed foreground service threw at `startForeground`. `RequestPermission` invokes
 * its callback immediately when the permission is already held, so this is one path rather than
 * two, and the ring is still on screen behind the dialog, which is the reason a user needs. ⚠️ A
 * denial still answers: the user pressed Answer, they can hear the caller, and the service no
 * longer claims a type the app cannot back (see [foregroundServiceTypes]). The controller drops
 * the answer if the ring timed out while the dialog was up.
 *
 * ⛔ THE LAUNCHER IS REMEMBERED BEFORE THE EARLY RETURN. `rememberLauncherForActivityResult` must
 * see the same remember slots on every composition of this function, and the `call ?: return`
 * below makes the slot count differ between a ringing and an idle composition if it came after.
 */
@Composable
internal fun IncomingCallHost(controller: IncomingCallController) {
    val answerAfterMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { controller.answer() }
    // ⚠️ THE NOTIFICATION'S ANSWER ARRIVES HERE TOO, as a request the controller recorded (see
    // ``IncomingCallController.answerRequested`` for why the Activity cannot ask itself). Consumed
    // before the launch so a recomposition cannot replay the dialog; a request for a call that has
    // since ended still reaches `answer()`, which drops anything not RINGING.
    val answerRequested by controller.answerRequested.collectAsStateWithLifecycle()
    LaunchedEffect(answerRequested) {
        if (answerRequested) {
            controller.consumeAnswerRequest()
            answerAfterMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val call by controller.state.collectAsStateWithLifecycle()
    val state = call ?: return
    IncomingCallScreen(
        state = state,
        handlers = IncomingCallHandlers(
            onAnswer = { answerAfterMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
            onDecline = controller::decline,
            onToggleMicrophone = controller::toggleMicrophone,
            onToggleSpeaker = controller::toggleSpeaker,
            onHangUp = controller::hangUp,
            onDismiss = controller::dismiss,
        ),
    )
}
