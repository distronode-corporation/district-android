package com.distronode.districtai.ui.dialer

import com.distronode.districtai.telecom.TelecomBridge

/**
 * A [TelecomBridge] whose every call is recorded and whose one inbound path is drivable.
 *
 * ⛔ A FAKE RATHER THAN THE REAL BRIDGE, AND THE REASON IS NOT SPEED. `AndroidTelecomBridge` calls
 * `TelecomManager.placeCall`, which Robolectric does not meaningfully model and which on a real
 * device can throw `SecurityException` depending on state this test has no way to establish (an
 * emergency call in progress, a registration Telecom dropped on upgrade). A ViewModel test that
 * touched it would be testing the shadow, and would be flaky about a class of failure the bridge
 * already swallows on purpose.
 *
 * ⛔ AND IT IS A BRIDGE, NOT A TELECOM SIMULATOR. It holds no connection state: what is under test
 * is that the ViewModel DRIVES Telecom in the right order — dialing, then active at the answer,
 * then disconnected exactly once — which is assertable by reading [calls] as a list. A fake that
 * modelled `Connection`'s state machine would be testing the model.
 *
 * ⚠️ [hangUp] IS THE ONE DIRECTION THAT GOES THE OTHER WAY, and it exists because the OS draws its
 * own end-call affordances for a self-managed call. A hang-up made there must reach the app or the
 * media outlives the user's belief that they stopped it — a live microphone they think is off.
 */
internal class FakeTelecomBridge : TelecomBridge {

    /**
     * Every call made on this bridge, in order.
     *
     * ⚠️ STRINGS RATHER THAN A SEALED TYPE, matching `FakeCallEngine`: the assertions are about
     * ORDER and COUNT, and a list of strings makes a failed one readable in the report without a
     * custom toString.
     */
    val calls: MutableList<String> = mutableListOf()

    /** What the OS would invoke if the user ended the call from a system surface. */
    private var onSystemHangUp: (() -> Unit)? = null

    /**
     * What the OS would invoke if the user ANSWERED from a system surface.
     *
     * ⚠️ ONLY EVER SET BY [startIncoming]. An outbound call cannot be answered by the OS — it is
     * dialling — which is exactly the asymmetry `DistrictCallRegistry` encodes with a nullable
     * handler, and a fake that armed both would hide it.
     */
    private var onSystemAnswer: (() -> Unit)? = null

    override fun startOutgoing(number: String, onSystemHangUp: () -> Unit) {
        calls += "outgoing:$number"
        this.onSystemHangUp = onSystemHangUp
    }

    override fun startIncoming(onSystemAnswer: () -> Unit, onSystemHangUp: () -> Unit) {
        calls += "incoming"
        this.onSystemAnswer = onSystemAnswer
        this.onSystemHangUp = onSystemHangUp
    }

    override fun setActive() {
        calls += "active"
    }

    override fun setDisconnected() {
        calls += "disconnected"
    }

    /** Drive the OS's own end-call affordance. ⚠️ A no-op when no call armed a handler. */
    fun systemHangUp() {
        onSystemHangUp?.invoke()
    }

    /**
     * Drive an answer made on a car head unit, a watch or a headset.
     *
     * ⚠️ A NO-OP AFTER [startOutgoing], which is the property worth being able to assert: the real
     * registry leaves the answer handler null for an outbound call, so a stray callback there must
     * do nothing rather than put a dialling call ACTIVE.
     */
    fun systemAnswer() {
        onSystemAnswer?.invoke()
    }
}
