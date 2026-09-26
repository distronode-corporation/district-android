package com.distronode.districtai.push

import com.distronode.districtai.AppContainer
import com.distronode.districtai.AppContainerOwner
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Where FCM hands this app a push.
 *
 * ⛔ IT IS A SHIM, AND EVERY LINE THAT IS NOT ONE IS A LINE THAT CANNOT BE TESTED. This class is
 * constructed by the system, on the system's schedule, on a process this development machine cannot
 * produce: Waydroid is API 33 with no Play Services, so FCM delivery cannot be exercised here at
 * all. Both decisions therefore live behind seams that can be — `PushMessageHandler` for what a
 * payload means and whether to act on it, `PushRegistrar` for when a token is registered — and what
 * remains here is the two lines the framework insists on owning.
 *
 * ⛔ THE WORK IS LAUNCHED ON THE **APPLICATION** SCOPE, NOT ON ANYTHING SCOPED TO THIS SERVICE. The
 * framework tears a `FirebaseMessagingService` down as soon as its callback returns, so a coroutine
 * scoped to it would be cancelled mid-request — and on the incoming-call path that request is the
 * one that decides whether a caller reaches a human. [AppContainer] owns a scope that outlives the
 * delivery; see `AppContainer.onPushReceived`.
 *
 * ⚠️ `onMessageReceived` RUNS ON A BACKGROUND THREAD the SDK provides, and it is subject to a ~10
 * second budget on a backgrounded app before the OS may reclaim the process. Nothing here waits: the
 * handler posts a notification and, for a call, registers a Telecom connection — both of which are
 * fast — and the authenticated round trip only happens once a human presses Answer.
 *
 * ⚠️ THE PAYLOAD IS DATA-ONLY, WHICH IS WHY THIS CALLBACK IS REACHED AT ALL. A `notification` block
 * is rendered by the system tray and does NOT reliably invoke this method on a backgrounded app;
 * the server's push sender sends no such block, deliberately, and also sets `android.priority: "high"`
 * so Doze does not hold a ringing call until the device next wakes.
 */
class DistrictFirebaseMessagingService : FirebaseMessagingService() {

    private val container: AppContainer?
        // ⚠️ Nullable rather than a cast: a service can be instantiated by a test harness whose
        // Application is the stock one, and doing nothing is the right answer there. Read through
        // [AppContainerOwner], as `MainActivity` does, so a test can hand it a faked graph.
        get() = (application as? AppContainerOwner)?.container

    /**
     * FCM issued a new registration token for this installation.
     *
     * ⛔ NOT OPTIONAL BOOKKEEPING. FCM rotates a token on its own schedule (app data cleared, a
     * restore onto a new device, a periodic refresh) and this callback is the only notification of
     * it. A missed rotation is SILENT: our server keeps accepting the old token and FCM keeps
     * rejecting it, so push simply stops with nothing anywhere reporting a problem.
     */
    override fun onNewToken(token: String) {
        container?.pushRegistrar?.onNewToken(token)
    }

    /**
     * A data push arrived.
     *
     * ⚠️ `message.data` IS THE WHOLE INPUT. Nothing else on `RemoteMessage` is read — not the
     * notification block (there is none), not the sender, not the message id — because the payload
     * is ids-only by design and reading anything else would be reading a field the server does not
     * promise to send.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        container?.onPushReceived(message.data)
    }
}
