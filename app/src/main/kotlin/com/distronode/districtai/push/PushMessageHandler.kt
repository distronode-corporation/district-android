package com.distronode.districtai.push

/**
 * What happens to a push once it has been parsed: the gate, and the two destinations.
 *
 * ⛔ IT IS A PLAIN CLASS WITH INJECTED SEAMS RATHER THAN LOGIC INSIDE THE SERVICE, AND ON THIS PATH
 * THAT IS THE DIFFERENCE BETWEEN TESTED AND UNTESTABLE. `FirebaseMessagingService` is constructed by
 * the system, on the system's schedule, and none of it can be exercised on this development machine
 * at all — Waydroid is API 33 with no Play Services. Every decision worth being sure about therefore
 * lives here, where a `Map<String, String>` goes in and a recorder comes out.
 *
 * ## The gate
 *
 * ⛔ **A SIGNED-OUT DEVICE DRAWS NOTHING.** Two things make this reachable rather than theoretical:
 * the server's `DevicePushToken` row survives a sign-out whose unregister could not be delivered
 * (see [PushRegistrar]), and FCM will keep delivering to a live token regardless of what this app
 * thinks. So a phone that was signed out on an aeroplane can be woken by a push for the account that
 * used to be on it — and a notification saying a message arrived, on a handset somebody else is now
 * holding, is a disclosure the payload's ids-only discipline was supposed to prevent.
 *
 * ⛔ **A MALFORMED PAYLOAD DRAWS NOTHING**, which [PushPayload] enforces by answering null. An
 * unknown `type` is forward compatibility (the server may ship a push type before this build is on
 * every handset); a missing id is a notification about nothing, or — worse, on the call path — a
 * ringing screen whose Answer button can only 404.
 *
 * ⚠️ **THERE IS NO CLIENT-SIDE MEMBERSHIP CHECK, AND ITS ABSENCE IS A FINDING RATHER THAN A GAP.**
 * "Is this workspace one the user belongs to" is not answerable offline: the app holds no membership
 * list it could consult without a request, and the selected workspace is one id out of however many
 * the account has — gating on it would silently drop every push for a workspace the user was not
 * currently looking at, which is most of them on a multi-tenant account. What makes that acceptable
 * is that the question is already answered twice by parties that CAN answer it:
 * `sendPushToWorkspace` fans out only to devices belonging to members, and
 * `POST /api/district/calls/{id}/answer` re-checks membership and role against the bearer before it
 * mints anything. The payload is also not attacker-supplied in any ordinary sense — reaching this
 * function requires the ability to send through our own Firebase project. Adding a check that could
 * only ever be weaker than those two would trade a real capability for the appearance of one.
 */
internal class PushMessageHandler(
    /**
     * ⛔ SUSPENDING BECAUSE THE HONEST ANSWER IS A DISK READ. "Am I signed in" is answered by the
     * credential store, which on this device is the AndroidKeyStore plus an AES-GCM decrypt — not
     * something to do on whatever thread the system handed the service. A cached boolean would be a
     * second source of truth that is wrong precisely after a sign-out, which is the case this gate
     * exists for.
     */
    private val signedIn: suspend () -> Boolean,
    private val notifier: PushNotifier,
    /**
     * ⚠️ THE CALL PATH IS A CALLBACK RATHER THAN A NOTIFIER METHOD, because answering a call is not
     * a notification: it registers a Telecom connection, arms a ring timeout and eventually holds a
     * microphone. See `IncomingCallController`, which owns all of that; this class decides only
     * whether it is reached.
     */
    private val onIncomingCall: (PushEvent.IncomingCall) -> Unit,
) {

    /**
     * @param data the raw FCM `data` map, exactly as delivered.
     * @return the event that was acted on, or null when the push was dropped. ⚠️ Returned for the
     *   tests' benefit rather than for a caller's: the service ignores it, and "what did it do"
     *   would otherwise only be observable through two different recorders.
     */
    suspend fun handle(data: Map<String, String>): PushEvent? {
        val event = PushPayload.parse(data) ?: return null
        // ⛔ THE SESSION CHECK COMES AFTER THE PARSE AND BEFORE ANYTHING VISIBLE. After, so a
        // malformed payload costs no disk read; before, so nothing is drawn or rung for an account
        // that is not on this device any more.
        if (!signedIn()) return null
        when (event) {
            is PushEvent.Message -> notifier.showMessage(event.workspaceId, event.messageId)
            is PushEvent.IncomingCall -> onIncomingCall(event)
        }
        return event
    }
}
