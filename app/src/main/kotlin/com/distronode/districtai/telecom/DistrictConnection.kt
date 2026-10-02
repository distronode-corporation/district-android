package com.distronode.districtai.telecom

import android.net.Uri
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.TelecomManager

/**
 * Telecom's view of one softphone call, outbound or inbound.
 *
 * ⛔ A RECORDER, NOT A CONTROLLER, AND THE DIRECTION OF THAT ARROW IS THE WHOLE DESIGN. This object
 * is told what the call is doing by the app — `DialerViewModel` through [TelecomBridge] for an
 * outbound call, `IncomingCallController` for an inbound one; it never decides anything and it never
 * mutates app state. The inverse — Telecom callbacks driving app state — produces two state machines
 * that can disagree about whether a call is live, and the losing one is still holding the microphone.
 *
 * ⛔ THE TWO THINGS IT DOES ORIGINATE ARE BOTH THE USER ACTING ON A SURFACE THIS APP DOES NOT DRAW.
 * [onDisconnect]/[onAbort]/[onReject] are a hang-up or a refusal made from a headset button, a car
 * head unit or the OS's own call notification; [onAnswer] is an answer made the same way. Both must
 * reach the app: the first because otherwise media keeps flowing after the user believes they
 * stopped it — a live microphone they think is off — and the second because a call the OS believes
 * is ACTIVE while this app never joined the room is silence for the person on the line.
 *
 * ⛔ THE INBOUND CONNECTION CARRIES **NO ADDRESS**, AND THAT IS THE PAYLOAD'S PRIVACY DISCIPLINE
 * REACHING THE TELECOM LAYER RATHER THAN AN OVERSIGHT. The server's push sender sends identifiers only
 * — no caller number, no name — because a push is readable by the OS and by any notification-
 * listener app. So this client genuinely does not know who is calling at ring time, and
 * `PRESENTATION_UNKNOWN` is the honest declaration. Inventing an address (the call id in a `tel:`
 * URI, say) would put a meaningless string in the system call log, on every device, forever.
 *
 * ⚠️ `PROPERTY_SELF_MANAGED` IS NOT OPTIONAL AND ITS ABSENCE IS NOT A DEGRADED MODE. Without it
 * Telecom treats this as a managed connection and expects the default dialer's in-call UI to drive
 * it, so the framework shows call UI this app does not own and the two disagree immediately.
 *
 * ⚠️ `setAudioModeIsVoip(true)` puts the device in communication audio mode, which is what makes the
 * earpiece and the volume rocker behave like a call rather than like media. ⛔ It is NOT the same as
 * choosing a route, and nothing here calls `setAudioRoute` — see the routing note on [TelecomBridge].
 */
internal class DistrictConnection private constructor(
    /**
     * Invoked when the OS ends or refuses the call on the user's behalf.
     *
     * ⚠️ NOT invoked by [setDisconnectedAndDestroy], which is the app ending its OWN call — feeding
     * that back would make an ordinary hang-up re-enter the caller's teardown from the far side.
     */
    private val onHangUp: () -> Unit,
    /**
     * Invoked when the OS answers on the user's behalf.
     *
     * ⚠️ A NO-OP FOR AN OUTBOUND CALL, which is why it defaults rather than being nullable: Telecom
     * cannot answer a call that is dialling, so the outbound factory has nothing to supply and a
     * nullable field would only add a branch nobody can reach.
     */
    private val onAnswerRequested: () -> Unit,
) : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        // ⚠️ HOLD AND MUTE ARE DECLARED BECAUSE THE OS OFFERS THEM ANYWAY on a self-managed call,
        // and a capability we do not declare is one the user's headset button silently cannot use.
        // Mute is honoured (the engine owns the mic, and the app is what changes it); hold is
        // declared and not implemented, which is why `onHold` is absent and Telecom will simply not
        // offer it.
        connectionCapabilities = CAPABILITY_MUTE
        setAudioModeIsVoip(true)
    }

    /**
     * ⛔ `destroy()` MUST FOLLOW, AND IT MUST FOLLOW `setDisconnected`. A connection left
     * undestroyed keeps audio focus and keeps the OS suppressing the ringer for a call that ended;
     * destroying one that has not been disconnected first throws, because Telecom has no terminal
     * cause to report.
     */
    fun setDisconnectedAndDestroy(cause: DisconnectCause) {
        setDisconnected(cause)
        destroy()
        DistrictCallRegistry.release(this)
    }

    /** The user ended the call from a system surface. See the ⛔ on the class. */
    override fun onDisconnect() {
        onHangUp()
    }

    /**
     * ⚠️ THE SAME HANDLING AS [onDisconnect], DELIBERATELY. `onAbort` is Telecom cancelling a call
     * that never connected — which for this app is a dial the callee never answered — and the app's
     * response is identical: tear the media down and tell the user. Treating it as a distinct
     * outcome would mean two paths to the one thing that must happen exactly once.
     */
    override fun onAbort() {
        onHangUp()
    }

    /**
     * The user refused a RINGING call from a system surface.
     *
     * ⚠️ ROUTED TO THE SAME HANDLER AS A HANG-UP, AND THE SERVER-SIDE CONSEQUENCE IS IDENTICAL BY
     * DESIGN: nothing is told. A declined ring and an unanswered one must look the same from
     * outside — see `IncomingCallController` — so there is no separate path for this to take.
     */
    override fun onReject() {
        onHangUp()
    }

    /**
     * The user answered from a system surface.
     *
     * ⛔ THIS ONLY REQUESTS AN ANSWER; IT DOES NOT MAKE THE CALL ACTIVE. The connection goes ACTIVE
     * when MEDIA is up, which is several hundred milliseconds and one authenticated round trip away
     * (`POST calls/{id}/answer`, then a LiveKit join). Calling `setActive()` here would tell the OS
     * a conversation is under way while this app is still fetching the credential to join with — the
     * mirror image of the outbound path's DIALING-not-ACTIVE rule, and audible in the same way.
     */
    override fun onAnswer() {
        onAnswerRequested()
    }

    internal companion object {

        /**
         * An outbound call, already placed server-side.
         *
         * ⛔ DIALING, NOT ACTIVE, AND THE DISTINCTION IS AUDIBLE TO THE USER. The server returns
         * before the callee's phone has rung — it deliberately does not wait for an answer — so a
         * connection that went straight to ACTIVE would tell the OS a conversation is under way
         * while the far end is still ringing. Among other things, that starts the OS's own call
         * duration counter at the wrong moment.
         */
        fun outgoing(number: String, onHangUp: () -> Unit): DistrictConnection =
            DistrictConnection(onHangUp = onHangUp, onAnswerRequested = {}).apply {
                // ⚠️ `PRESENTATION_ALLOWED`: the number is one the operator typed, so there is
                // nothing to withhold. A restricted presentation here would hide the callee from the
                // OS's own call surfaces while this app still displayed it, which is the confusing
                // half of both.
                setAddress(
                    Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null),
                    TelecomManager.PRESENTATION_ALLOWED,
                )
                setDialing()
            }

        /**
         * An inbound call this device is being asked to take.
         *
         * ⛔ RINGING IS THE STATE THAT MAKES THE REST OF TELECOM WORK. It is what suppresses a
         * second incoming call, what makes a headset button's answer reach [onAnswer], and what the
         * OS's own call surfaces render. A connection created ACTIVE would be a call nobody agreed
         * to; one created DIALING would be an outbound call the OS then offers to hang up rather
         * than to answer.
         *
         * ⚠️ NO ADDRESS AND `PRESENTATION_UNKNOWN` — see the ⛔ on the class. The push carries ids
         * only, so the caller genuinely is unknown at this point, and it stays unknown: this client
         * never learns the number, because the answer route returns a join credential rather than a
         * caller.
         */
        fun incoming(
            onHangUp: () -> Unit,
            onAnswerRequested: () -> Unit,
        ): DistrictConnection =
            DistrictConnection(onHangUp = onHangUp, onAnswerRequested = onAnswerRequested).apply {
                setAddress(null, TelecomManager.PRESENTATION_UNKNOWN)
                setRinging()
            }
    }
}
