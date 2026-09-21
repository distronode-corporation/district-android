package com.distronode.districtai.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle

/**
 * The framework's entry point into this app's calls.
 *
 * ⛔ THE ONLY THING IT DOES IS BUILD A [DistrictConnection] AND PUBLISH IT. It starts no media,
 * holds no state, and knows nothing about workspaces, tokens or rooms. This class is constructed by
 * the system, on the system's schedule, with no access to whichever object owns the call, so any
 * state it kept would be a second copy that drifts — which is the classic self-managed mistake.
 *
 * ⛔ `onCreateIncomingConnection` MUST NEVER LEAVE A RINGING CONNECTION NOBODY WILL RESOLVE. An
 * incoming override is only honest because an inbound path exists: the FCM pipe
 * (`DistrictFirebaseMessagingService` → `IncomingCallController`) wakes the app, the server rings
 * devices through `POST /api/district/actions/ring-app`, and the ring is bounded at both ends (the
 * controller disconnects it on a ~30s timeout and the server's rendezvous expires at 25s
 * regardless). Without that bound, a `Connection` could sit in RINGING forever, holding audio
 * focus.
 *
 * ⛔ BOTH DIRECTIONS ROUTE THEIR CALLBACKS THROUGH [DistrictCallRegistry] RATHER THAN CAPTURING A
 * HANDLER HERE. This object is built by the system and has no reference to the call's owner; the
 * registry is where the owner left one, and routing through it means a connection can never hold a
 * handler belonging to a call that has already ended.
 *
 * ⚠️ DECLARED IN THE MANIFEST WITH `BIND_TELECOM_CONNECTION_SERVICE`, which is a SYSTEM-signature
 * permission: it is not something this app holds, it is what this app REQUIRES of whoever binds, and
 * it is what stops another app binding this service and driving its calls. Without it the platform
 * refuses to use the service at all.
 */
class DistrictConnectionService : android.telecom.ConnectionService() {

    /**
     * Build the connection for a dial this app already placed.
     *
     * ⚠️ THE REQUEST'S ADDRESS IS THE SOURCE OF THE NUMBER, not anything this class holds. Telecom
     * echoes back the `tel:` URI the bridge handed `placeCall`, so the two can never disagree —
     * and a request that somehow carries no address is one Telecom would not know how to display,
     * so it is refused rather than shown as a call to nowhere.
     */
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection? {
        val number = request?.address?.schemeSpecificPart
        if (number.isNullOrBlank()) {
            return Connection.createFailedConnection(
                DisconnectCause(DisconnectCause.ERROR, "No number to dial"),
            )
        }
        val connection = DistrictConnection.outgoing(number) { DistrictCallRegistry.requestHangUp() }
        DistrictCallRegistry.adopt(connection)
        return connection
    }

    /**
     * Build the RINGING connection for a call a push told us about.
     *
     * ⛔ THE REQUEST IS NOT READ AT ALL, WHICH IS THE OPPOSITE OF THE OUTGOING PATH AND IS RIGHT FOR
     * THE OPPOSITE REASON. An outgoing request echoes back the address the bridge supplied, so it is
     * the source of truth for the number. An incoming one carries whatever `addNewIncomingCall`'s
     * extras carried — and this app deliberately supplies no address, because the push payload
     * carries identifiers only and inventing one would write a meaningless string into the system
     * call log. There is nothing here worth reading, and a request that arrived with an address
     * would be one this app did not put there.
     *
     * ⛔ IT IS NEVER REFUSED FOR MISSING DATA, UNLIKE THE OUTGOING PATH. A failed connection here
     * would leave the ring notification and the app's own controller believing a call is live while
     * Telecom has already torn its half down — and there is no missing datum that could justify it,
     * since the call id lives in the controller rather than in the request.
     *
     * ⚠️ THE ANSWER CALLBACK IS THE ONE GENUINELY NEW WIRE. It is how an answer made on a car head
     * unit, a watch or a headset reaches the app, and without it those surfaces would show an Answer
     * button that puts the connection ACTIVE while this app never joins the room — silence, on a
     * call the OS says is connected.
     */
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        val connection = DistrictConnection.incoming(
            onHangUp = { DistrictCallRegistry.requestHangUp() },
            onAnswerRequested = { DistrictCallRegistry.requestAnswer() },
        )
        DistrictCallRegistry.adopt(connection)
        return connection
    }

    /**
     * ⚠️ TELECOM REFUSED THE CALL BEFORE IT EXISTED — an emergency call in progress, or an account
     * it will not honour. There is nothing to tear down here, and the media half is not this
     * object's to stop: the call's owner is not reachable from a system-constructed service. What
     * this override buys is that the registry does not keep a handler armed for a call the OS never
     * made, which would otherwise let a LATER stray callback hang up a call this one has nothing to
     * do with.
     */
    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        DistrictCallRegistry.disarm()
    }

    /**
     * ⚠️ THE SAME CLEAN-UP, FOR THE INBOUND CASE. Telecom refuses an incoming call it cannot honour
     * — most commonly because a cellular call is already in progress, which is exactly when a
     * softphone ring is least welcome. ⛔ Nothing is told to the server here on purpose: an incoming
     * call the OS refused must look identical to one the user declined and to one nobody heard, so
     * the rendezvous is left to time out. See `IncomingCallController`.
     */
    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        DistrictCallRegistry.disarm()
    }
}
