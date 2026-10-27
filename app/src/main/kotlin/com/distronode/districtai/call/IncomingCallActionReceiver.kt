package com.distronode.districtai.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.distronode.districtai.AppContainerOwner
import com.distronode.districtai.push.PushIntents

/**
 * The Decline button on the ringing notification.
 *
 * ⛔ A BROADCAST RECEIVER RATHER THAN AN ACTIVITY, AND THE ASYMMETRY WITH Answer IS THE POINT.
 * Answering has to put a UI in front of the user — they are about to be in a conversation and need
 * mute, speaker and hang up — so it names `MainActivity` directly (a notification action may not
 * trampoline through anything else on Android 12+). Declining has nothing to show, and routing it
 * through an Activity would raise the whole app over whatever the user was doing in order to close a
 * notification.
 *
 * ⛔ `exported="false"` IN THE MANIFEST AND AN EXPLICIT COMPONENT ON THE INTENT. The action string is
 * this app's own, but an implicit broadcast would be delivered to any app that registered for it —
 * and, on API 26+, would not be delivered to a manifest-registered receiver at all, so the feature
 * would fail in the ordinary case and leak the call id in the exotic one. See [PushIntents.declineCall].
 *
 * ⛔ **A PERSON PRESSED IT, SO IT DECLINES: `decline()` SENDS `calls/{id}/decline` ONCE** (see
 * [IncomingCallController]; a ring timeout takes a different exit and sends nothing). The request
 * runs on the application scope, and this receiver holds its broadcast open with [goAsync] until it
 * is done: a receiver's process may be reclaimed as soon as `onReceive` returns, which on a phone
 * with no Activity up would cut the request off before the server heard it. The controller bounds
 * the request well inside the receiver's budget.
 *
 * ⚠️ IT READS NO EXTRAS BEYOND THE ACTION. The call id travels on the intent for diagnostics and for
 * `PendingIntent` uniqueness, but there is at most one live inbound call by construction (one
 * Telecom connection, one engine, one audio focus), so declining "the ringing call" is unambiguous —
 * and a decline that matched on an id would be one stale `PendingIntent` away from doing nothing at
 * all, silently, with the ring still on screen.
 */
class IncomingCallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PushIntents.ACTION_DECLINE) return
        // ⚠️ The application, not a held reference: this object is constructed by the system per
        // broadcast and has no lifetime of its own. A null cast is only reachable under a test
        // harness with a stub application, and doing nothing is the right answer there.
        val container = (context.applicationContext as? AppContainerOwner)?.container ?: return
        // ⚠️ NULLABLE ON PURPOSE: `goAsync` answers null when the receiver was invoked directly
        // rather than by the system (a test harness), and there is then nothing to hold open.
        val pending: PendingResult? = goAsync()
        val sent = container.incomingCallController.decline()
        if (sent == null) {
            pending?.finish()
        } else {
            sent.invokeOnCompletion { pending?.finish() }
        }
    }
}
