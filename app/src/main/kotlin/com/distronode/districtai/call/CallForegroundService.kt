package com.distronode.districtai.call

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.distronode.districtai.R
import com.distronode.districtai.push.AndroidPushNotifier

/**
 * Keeps the process alive while a call is connected.
 *
 * ⛔ IT EXISTS TO SURVIVE THE SCREEN GOING OFF, AND NOTHING ELSE. It starts no media, owns no state
 * and holds no reference to the call — [IncomingCallController] owns all of that, in the application
 * graph, which outlives this service. The outbound softphone deliberately has no foreground service
 * at all (see the manifest's own note: it is screen-on by construction, dialled from a screen the
 * operator is looking at); an ANSWERED INBOUND call is the case where the user puts the phone to
 * their ear and the Activity stops, and a process without a foreground service is one Android is
 * entitled to freeze mid-conversation.
 *
 * ⛔ `phoneCall|microphone` AS THE TYPE, BOTH OF THEM, AND NEITHER IS OPTIONAL ON API 34+. Android
 * 14 requires a foreground service to declare a type and to actually hold the permission that backs
 * it; `phoneCall` is what this is, and `microphone` is what it does — the LiveKit engine publishes
 * an audio track for the whole duration. Declaring only `phoneCall` throws
 * `SecurityException`/`ForegroundServiceStartNotAllowedException` the moment the mic is used, and
 * declaring only `microphone` misdescribes a telephone call to a Play reviewer who has to approve
 * the declaration.
 *
 * ⛔ `START_NOT_STICKY`, WHICH IS THE OPPOSITE OF WHAT A "KEEP ALIVE" SERVICE USUALLY WANTS. If the
 * system kills this process, the call is over: the socket is gone, the Telecom connection is gone
 * and the LiveKit token has been spent on a room the far end has already left. Restarting the
 * service would put a "call in progress" notification on screen for a call that ended, with no way
 * for the user to end it.
 *
 * ⚠️ UNVERIFIABLE ON THIS MACHINE. Foreground-service types are an API 34 concept and Waydroid is
 * API 33, so the type enforcement, the `POST_NOTIFICATIONS` interaction and the behaviour when the
 * screen goes off are all real-device work. What is checked here is the shape: the manifest
 * declares both types, the permissions are present, and the service starts and stops from exactly
 * the two places [IncomingCallController] calls.
 */
class CallForegroundService : Service() {

    /** ⚠️ Nothing binds to this. A started service is the whole of the contract. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ⚠️ `ServiceCompat` RATHER THAN `startForeground` DIRECTLY, because the type argument is an
        // API 29+ overload and this app's floor is 26. The compat wrapper drops the type on older
        // levels, where the platform neither wants nor accepts one.
        //
        // ⚠️ LINT REPORTS `InlinedApi` ON BOTH CONSTANTS AND THAT WARNING IS EXPECTED, NOT A BUG TO
        // SILENCE. `FOREGROUND_SERVICE_TYPE_PHONE_CALL` is API 29 and `..._MICROPHONE` is API 30,
        // while minSdk is 26 — but they are `int` constants, so the compiler inlines the VALUES and
        // nothing resolves a field at runtime. The API-level question is about the framework CALL,
        // which is exactly what the compat wrapper answers. Left as a warning rather than suppressed
        // by name, because `warningsAsErrors` is deliberately false and a suppression here would also
        // hide a genuinely unsafe inline added later in the same file.
        //
        // ⛔ THE `microphone` TYPE IS CLAIMED ONLY WHEN `RECORD_AUDIO` IS HELD, AND THE CALL IS WRAPPED.
        // API 34+ throws `SecurityException` from `startForeground` for a microphone-typed service
        // whose app does not hold the permission, and that throw happens HERE, inside the service —
        // outside the `runCatching` in [AndroidForegroundCallHost], which guards only the start
        // request. On a fresh install with no permission requested, the first answered call would
        // kill the process mid-connect. The answer paths ask first; this is the floor under them.
        // A service the platform refuses in any form stops itself rather than crashing: the call
        // survives without its keep-alive, which is strictly better than no call.
        val microphoneGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        val notification = ongoingCallNotification(this)
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                AndroidPushNotifier.NOTIFICATION_ID_ONGOING_CALL,
                notification,
                foregroundServiceTypes(microphoneGranted),
            )
        }.recoverCatching {
            ServiceCompat.startForeground(
                this,
                AndroidPushNotifier.NOTIFICATION_ID_ONGOING_CALL,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
        }.isSuccess
        if (!started) stopSelf()
        return START_NOT_STICKY
    }
}

/**
 * The foreground-service type mask for an answered call.
 *
 * `phoneCall` always: that is what the service is. `microphone` only when the app actually holds
 * `RECORD_AUDIO`, because on API 34+ claiming a type without its backing permission is a
 * `SecurityException` at `startForeground`, not a warning. Pure, so the one decision here is
 * asserted without a service, a context or a device.
 */
internal fun foregroundServiceTypes(microphoneGranted: Boolean): Int =
    if (microphoneGranted) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
    }

/**
 * The "call in progress" notification.
 *
 * ⛔ ON THE `incoming_calls` CHANNEL RATHER THAN A THIRD ONE, DELIBERATELY. A user who silences
 * calls has silenced this too, which is the behaviour they asked for; a separate channel would let
 * an ongoing-call notification survive a choice the user made about calls. ⚠️ It carries no actions:
 * hanging up belongs on the in-call screen, and a notification action that ended a call from the
 * shade would be one more path into a teardown that has to happen exactly once.
 *
 * ⚠️ A TOP-LEVEL FUNCTION so it can be built and asserted without starting a service — Robolectric
 * will construct the notification, and what is worth checking (the channel, the ongoing flag, the
 * absence of actions) is all readable from the built object.
 */
internal fun ongoingCallNotification(context: Context): Notification =
    Notification.Builder(context, AndroidPushNotifier.CHANNEL_INCOMING_CALLS)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(context.getString(R.string.push_ongoing_call_title))
        .setContentText(context.getString(R.string.push_ongoing_call_body))
        .setCategory(Notification.CATEGORY_CALL)
        // ⛔ ONGOING, so it cannot be swiped away while the call is live. The service would keep
        // running and the user would have lost their way back to the hang-up button.
        .setOngoing(true)
        .build()

/**
 * The real [ForegroundCallHost].
 *
 * ⛔ EVERY START IS WRAPPED, BECAUSE `startForegroundService` THROWS FOR REASONS THAT ARE NOT THIS
 * APP'S FAULT AND THAT MUST NOT END A LIVE CALL. On API 31+ a start from the background is refused
 * outright (`ForegroundServiceStartNotAllowedException`), and a call answered from a notification
 * while the app is backgrounded is exactly that shape — the exemption for a Telecom self-managed
 * call exists but is not something to rely on across OEM builds. Losing the service costs the
 * process its protection from being frozen; letting the throw escape costs the call itself, on the
 * path where the user has just started talking.
 *
 * ⚠️ `stopService` IS SAFE FOR A SERVICE THAT WAS NEVER STARTED — it returns false rather than
 * throwing — which is what lets [IncomingCallController]'s single exit call this unconditionally.
 */
internal class AndroidForegroundCallHost(context: Context) : ForegroundCallHost {

    // ⚠️ The APPLICATION context: this object lives in the application graph and outlives every
    // Activity, and a captured Activity would be a leak and, after a configuration change, a stale
    // one.
    private val appContext = context.applicationContext

    override fun setCallActive(active: Boolean) {
        val intent = Intent(appContext, CallForegroundService::class.java)
        runCatching {
            if (active) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.stopService(intent)
            }
        }
    }
}
