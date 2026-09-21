package com.distronode.districtai.telecom

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * What the softphone tells the operating system about the call it is on.
 *
 * ⛔ AN INTERFACE, AND THE SEAM IS NOT DECORATION. Every implementation of this talks to
 * `TelecomManager`, which on a real device arbitrates the audio route, the ringer and collisions
 * with the cellular radio — none of which Robolectric models, and one of which (`placeCall`) can
 * throw `SecurityException` depending on device state. A ViewModel that reached for `TelecomManager`
 * directly could not be tested at all without an emulator, so the ViewModel programs against this
 * and the tests hand it a recorder.
 *
 * ⛔ THE STATE FLOWS ONE WAY, AND THE ONE EXCEPTION IS NAMED RATHER THAN GENERAL. The
 * [android.telecom.Connection] is the SINGLE source of truth for Telecom's view of the call, and it
 * is DRIVEN by the ViewModel rather than driving it — the opposite arrangement is how a
 * self-managed VoIP app ends up with two state machines that disagree about whether a call is live,
 * and the one the user can see is not the one holding the microphone. The exception is
 * [startOutgoing]'s `onSystemHangUp`: the OS draws its own end-call affordances for a self-managed
 * call (the notification, a headset button, a car head unit), and a hang-up made there MUST reach
 * the app or the media keeps flowing after the user believes they stopped it. That is a live
 * microphone the user thinks is off, which is the one thing worth a second direction.
 *
 * ⚠️ THE CALLBACK TRAVELS WITH THE CALL THAT STARTS, not as a separate registration, so it cannot
 * outlive the call it belongs to and a second call cannot inherit the first one's handler.
 *
 * ⚠️ SO A DEVICE WHERE ALL OF THIS FAILS STILL PLACES CALLS. Telecom integration here buys
 * politeness: audio focus, the OS knowing not to ring over a call in progress, and the cellular
 * stack knowing a VoIP call is up. None of it is required for media to flow, so every method fails
 * SILENTLY rather than propagating — losing the call because the OS declined to be told about it
 * would be a worse outcome than the OS not knowing.
 *
 * ⚠️ AUDIO ROUTING IS THE KNOWN OPEN QUESTION AND IT IS DELIBERATELY NOT SETTLED HERE. LiveKit's
 * AudioSwitch and Telecom's `setAudioRoute` both want to own the route, and the classic symptom is
 * earpiece audio while Bluetooth is connected. This bridge does NOT call `setAudioRoute`; the
 * engine's `setSpeakerphoneOn` remains the only route control, so there is exactly one owner until
 * on-device testing says otherwise. See the note on
 * [com.distronode.districtai.core.media.CallEngine].
 */
interface TelecomBridge {

    /**
     * Tell the OS a call is being placed to [number].
     *
     * ⚠️ FIRE AND FORGET. `TelecomManager.placeCall` is asynchronous — the framework calls back
     * into [DistrictConnectionService] to build the connection — so nothing here can hand back a
     * connection to hold, and the ViewModel must not wait on one. The dial to the CARRIER has
     * already happened server-side by this point regardless.
     */
    fun startOutgoing(number: String, onSystemHangUp: () -> Unit)

    /**
     * Tell the OS a call is RINGING on this device.
     *
     * ⛔ THE PHONE ACCOUNT MUST ALREADY BE REGISTERED BY THE TIME THIS IS CALLED, WHICH IS WHY THE
     * IMPLEMENTATION REGISTERS IT ITSELF RATHER THAN RELYING ON A DIAL HAVING HAPPENED FIRST. The
     * account was previously asserted per-dial only, so on a device that had never placed an
     * outbound call — a freshly installed app, or one whose registration Telecom dropped across an
     * upgrade — `addNewIncomingCall` would throw `SecurityException` and the ring would be silently
     * absent. That is the ordinary case for inbound, not the exotic one.
     *
     * ⛔ NO ADDRESS IS SUPPLIED, AND THE CALLER'S NUMBER IS NOT AVAILABLE TO SUPPLY. The push
     * payload carries identifiers only — deliberately, because a notification is readable by the OS
     * and by any notification-listener app — so this client does not know who is calling. See
     * [DistrictConnection.Companion.incoming].
     *
     * ⚠️ FIRE AND FORGET, like [startOutgoing]: the framework builds the connection on its own
     * schedule and there is nothing to hand back.
     *
     * @param onSystemAnswer the OS answered on the user's behalf (a car head unit, a headset, a
     *   watch). ⛔ It must reach the app, or the connection goes ACTIVE while this app never joins
     *   the room — silence, on a call the OS says is connected.
     */
    fun startIncoming(onSystemAnswer: () -> Unit, onSystemHangUp: () -> Unit)

    /** The callee answered. ⚠️ A no-op when the OS never gave us a connection. */
    fun setActive()

    /** The call ended, locally or remotely. ⚠️ Idempotent: hang-up paths race. */
    fun setDisconnected()
}

/**
 * The real bridge.
 *
 * ⛔ THE PHONE ACCOUNT IS REGISTERED LAZILY AND REPEATEDLY, RATHER THAN ONCE AT STARTUP. Telecom
 * drops registrations when the app is upgraded or its data is cleared, and a `placeCall` against an
 * unregistered handle throws `SecurityException` — which would surface as "calls do not work after
 * an update" with nothing in the app changed. `registerPhoneAccount` overwrites an identical
 * registration harmlessly, so re-asserting it on every dial is cheaper than detecting the state.
 *
 * ⛔ `CAPABILITY_SELF_MANAGED`, NOT `CAPABILITY_CALL_PROVIDER`. A call-provider account asks to be
 * a candidate for the user's ordinary phone calls and must be ENABLED by the user in Settings
 * before it works at all — this app is not a dialer replacement and would simply be inert. A
 * self-managed account manages its own UI and needs no enablement, which is exactly the shape of a
 * softphone inside a business app.
 */
class AndroidTelecomBridge(context: Context) : TelecomBridge {

    // ⚠️ The APPLICATION context. This object outlives any Activity — a call survives rotation —
    // and a captured Activity would be a leak and, after a configuration change, a stale one.
    private val appContext = context.applicationContext

    private val telecomManager: TelecomManager? =
        appContext.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager

    /**
     * Tell the OS a call is ringing.
     *
     * ⛔ THE REGISTRATION IS RE-ASSERTED HERE TOO, AND ON THIS PATH IT IS LOAD-BEARING RATHER THAN
     * CHEAP INSURANCE. `startOutgoing` could rely on the user having dialled; nothing precedes an
     * inbound ring, so on a device that has never placed a call — a fresh install, which is every
     * device on its first inbound call — an unregistered handle makes `addNewIncomingCall` throw and
     * the ring is simply absent, with nothing in the app to point at.
     *
     * ⚠️ AND IT IS THE SAME `SecurityException` / `IllegalStateException` SWALLOW as the outbound
     * path, for the same reason: losing the OS's awareness of the call costs politeness, while
     * letting the throw escape would lose the call. ⛔ The DIFFERENCE is that on this path losing
     * Telecom does NOT lose the call — the notification is drawn independently, so the user can
     * still answer. That is the degraded-first design the plan calls for, and it is why nothing here
     * reports failure upward.
     */
    override fun startIncoming(onSystemAnswer: () -> Unit, onSystemHangUp: () -> Unit) {
        val manager = telecomManager ?: return
        val handle = phoneAccountHandle(appContext)
        // ⛔ ARMED BEFORE `addNewIncomingCall`, NEVER AFTER — the same ordering rule the outbound
        // path states. The framework builds the connection on its own schedule and can call back
        // before this method returns.
        DistrictCallRegistry.arm(onSystemHangUp = onSystemHangUp, onSystemAnswer = onSystemAnswer)
        try {
            manager.registerPhoneAccount(selfManagedAccount(handle))
            // ⚠️ AN EMPTY EXTRAS BUNDLE, DELIBERATELY. `EXTRA_INCOMING_CALL_ADDRESS` is the only
            // thing worth putting here and this client has no caller number to put in it; the
            // framework accepts the call without one and the connection declares
            // PRESENTATION_UNKNOWN, which is the honest statement.
            manager.addNewIncomingCall(handle, Bundle())
        } catch (ignored: SecurityException) {
            // The OS declined to be told: an unregistered or revoked self-managed account. The
            // notification still rings and the call is still answerable.
        } catch (ignored: IllegalStateException) {
            // Telecom is in a state that forbids a new call — an emergency call in progress.
        }
    }

    override fun startOutgoing(number: String, onSystemHangUp: () -> Unit) {
        val manager = telecomManager ?: return
        val handle = phoneAccountHandle(appContext)
        // ⛔ ARMED BEFORE `placeCall`, NEVER AFTER. The framework builds the connection on its own
        // schedule and can call back before this method returns; a handler installed afterwards
        // would leave a window in which a hang-up from the OS reaches a connection with nothing to
        // tell. See DistrictCallRegistry.
        DistrictCallRegistry.arm(onSystemHangUp)
        // ⛔ AN EXPLICIT `catch (SecurityException)` RATHER THAN `runCatching`, AND THE DIFFERENCE
        // IS A LINT ERROR RATHER THAN A STYLE PREFERENCE. `placeCall` is annotated
        // `@RequiresPermission(anyOf = {CALL_PHONE, MANAGE_OWN_CALLS})`, and CALL_PHONE is a
        // DANGEROUS permission — so Android Lint's `MissingPermission` demands either a runtime
        // check or a visible `SecurityException` handler, and it does not recognise `runCatching`
        // as one. This app holds the other option, MANAGE_OWN_CALLS, which is install-time and
        // needs no check; the handler is what says so to the tooling and to the next reader.
        //
        // ⚠️ AND IT REALLY CAN THROW. Telecom refuses `placeCall` outright during an emergency
        // call, and a self-managed registration can be dropped by the platform across an app
        // upgrade — neither is a reason to fail a call whose media is about to flow anyway. Losing
        // the OS's awareness of the call costs politeness (audio focus, ringer suppression); losing
        // the call costs the call.
        try {
            manager.registerPhoneAccount(selfManagedAccount(handle))
            manager.placeCall(
                // ⚠️ `tel:` SPECIFICALLY. A self-managed account declares its supported URI
                // schemes, and Telecom rejects a `placeCall` whose scheme the account did not
                // claim — silently, from the app's point of view, because it throws inside the
                // framework rather than telling the caller.
                Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null),
                Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    // ⛔ WITHOUT THIS, TELECOM TREATS THE CALL AS A CELLULAR ONE and hands it to
                    // the default dialer, which would place a SECOND, real, billed call on the
                    // carrier network while this app is already connected over SIP.
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, false)
                },
            )
        } catch (ignored: SecurityException) {
            // See above: the OS declined to be told. The call proceeds without it.
        } catch (ignored: IllegalStateException) {
            // Telecom is in a state that forbids a new call — an emergency call in progress.
        }
    }

    override fun setActive() {
        DistrictCallRegistry.current()?.setActive()
    }

    override fun setDisconnected() {
        // ⚠️ DISARMED FIRST. `setDisconnectedAndDestroy` drives the connection to a terminal state,
        // and a framework that answered by calling `onDisconnect` would re-enter the ViewModel's
        // teardown from the far side — the app's own hang-up must not look like the OS's.
        DistrictCallRegistry.disarm()
        DistrictCallRegistry.current()
            ?.setDisconnectedAndDestroy(DisconnectCause(DisconnectCause.LOCAL))
    }

    internal companion object {

        /**
         * The account this app's calls belong to.
         *
         * ⛔ THE ID IS A STABLE CONSTANT AND MUST STAY ONE. It is half of the identity Telecom
         * stores registrations and per-account settings against; changing it strands the old
         * registration and starts again, which on a user's device looks like the call history and
         * the account entry duplicating.
         */
        const val ACCOUNT_ID = "district-ai-softphone"

        fun phoneAccountHandle(context: Context): PhoneAccountHandle = PhoneAccountHandle(
            ComponentName(context, DistrictConnectionService::class.java),
            ACCOUNT_ID,
        )

        /**
         * ⚠️ `SCHEME_TEL` ONLY. The softphone dials E.164 numbers over a carrier trunk; claiming
         * `sip:` would advertise a capability the server does not offer this client, and claiming
         * schemes an account does not handle is how a `placeCall` from ANOTHER app gets routed
         * here and fails.
         *
         * ⚠️ `CAPABILITY_SELF_MANAGED` IS DEPRECATED AT compileSdk AND IS STILL THE RIGHT CALL AT
         * minSdk 26 — the warning is expected, not something to silence. Its replacement is
         * `TelecomManager.addCall(CallAttributes, …)`, which is **API 34**, so on this app's
         * floor it would cover barely the newest third of devices and the ConnectionService path
         * would have to exist anyway for the rest. The other route is the `androidx.core.telecom`
         * Jetpack library, which wraps both — a real option, and a NEW DEPENDENCY, so it is a
         * decision to take deliberately rather than as a side effect of a deprecation warning.
         * Revisit when minSdk reaches 34, or when the audio-routing work (see the class doc) needs
         * the Jetpack library's route arbitration anyway.
         */
        @Suppress("DEPRECATION")
        fun selfManagedAccount(handle: PhoneAccountHandle): PhoneAccount =
            PhoneAccount.builder(handle, DISPLAY_NAME)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                .build()

        /** ⚠️ Not from `strings.xml`: Telecom renders this outside this app's configuration. */
        private const val DISPLAY_NAME = "District AI"
    }
}

/**
 * Where the connection the FRAMEWORK built becomes reachable from the ViewModel's bridge.
 *
 * ⛔ A PROCESS-SCOPED HOLDER EXISTS BECAUSE THE TELECOM API GIVES NO OTHER ANSWER, not because it
 * was convenient. `TelecomManager.placeCall` returns `void` and the connection is constructed later,
 * on a different object ([DistrictConnectionService]), by the framework. There is no handle to pass
 * back, so the service publishes the one it made and the bridge reads it.
 *
 * ⛔ AT MOST ONE, AND THAT IS AN INVARIANT OF THE PRODUCT RATHER THAN A LIMITATION OF THIS OBJECT.
 * The softphone places one call at a time — one engine, one audio focus — so a second entry would
 * mean two connections competing for the route, which is the failure `CallEngine` itself warns
 * about. A new connection REPLACES the old one and the old one is disconnected, rather than being
 * queued behind it.
 *
 * ⚠️ IT HOLDS NO CALL DATA. The number, the state and the duration all live in the ViewModel; this
 * holds a framework object so a state change can be forwarded to it. Nothing here is a source of
 * truth for anything.
 */
internal object DistrictCallRegistry {

    @Volatile
    private var connection: DistrictConnection? = null

    /**
     * What to run when the OS ends the call for us.
     *
     * ⚠️ `@Volatile` AND NULLABLE FOR THE SAME REASON [connection] IS: it is written on the main
     * thread by the bridge and read on a Binder thread by the framework's callback. Null means "no
     * call of ours is live", and a hang-up arriving then is Telecom cleaning up after something we
     * have already forgotten — dropping it is correct.
     */
    @Volatile
    private var onSystemHangUp: (() -> Unit)? = null

    /**
     * What to run when the OS ANSWERS the call for us.
     *
     * ⛔ A SECOND HANDLER RATHER THAN A PARAMETER ON THE FIRST, BECAUSE ONLY ONE DIRECTION HAS ONE.
     * An outbound call cannot be answered by the OS — it is dialling — so the outbound arm leaves
     * this null, and a null here means an answer arriving from a system surface is dropped. That is
     * correct: the only way to reach it on an outbound call would be a framework bug, and acting on
     * it would put a call ACTIVE that this app never joined.
     *
     * ⚠️ `@Volatile` FOR THE REASON [onSystemHangUp] IS: written on the main thread by the bridge,
     * read on a Binder thread by the framework's callback.
     */
    @Volatile
    private var onSystemAnswer: (() -> Unit)? = null

    fun current(): DistrictConnection? = connection

    /**
     * @param onSystemAnswer ⚠️ NULL FOR AN OUTBOUND CALL, which is why it defaults. See the ⛔ on
     *   [onSystemAnswer].
     */
    fun arm(onSystemHangUp: () -> Unit, onSystemAnswer: (() -> Unit)? = null) {
        this.onSystemHangUp = onSystemHangUp
        this.onSystemAnswer = onSystemAnswer
    }

    /**
     * ⛔ CLEARS BOTH, ALWAYS. A disarm that left the answer handler behind would let a stray
     * callback from a call the OS has already torn down answer the NEXT one — the same stale-handler
     * failure the hang-up half was written to prevent, in the one direction where the consequence is
     * joining a stranger's conversation rather than ending your own.
     */
    fun disarm() {
        onSystemHangUp = null
        onSystemAnswer = null
    }

    /** Invoked from [DistrictConnection.onDisconnect]. ⚠️ A no-op when nothing is armed. */
    fun requestHangUp() {
        onSystemHangUp?.invoke()
    }

    /**
     * Invoked from [DistrictConnection.onAnswer]. ⚠️ A no-op when nothing is armed — see the ⛔ on
     * [onSystemAnswer] for why that is the right answer rather than a missed case.
     */
    fun requestAnswer() {
        onSystemAnswer?.invoke()
    }

    /**
     * ⚠️ DISCONNECTS THE PREVIOUS ONE RATHER THAN LEAKING IT. A connection Telecom still believes
     * is live keeps audio focus and keeps the OS suppressing the ringer, indefinitely.
     */
    fun adopt(next: DistrictConnection) {
        connection?.takeIf { it !== next }?.setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        connection = next
    }

    /** ⚠️ Only clears when [released] is still the current one; a late teardown must not evict a newer call. */
    fun release(released: DistrictConnection) {
        if (connection === released) connection = null
    }
}
