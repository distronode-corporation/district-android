package com.distronode.districtai.ui.dialer

import com.distronode.districtai.call.CALL_TICK_MILLIS
import com.distronode.districtai.call.CallSessionCore
import com.distronode.districtai.call.newCallScope
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.telecom.TelecomBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ONE outbound call: its engine, its scope, its Telecom connection and its clock.
 *
 * ⛔ ITS OWN OBJECT RATHER THAN MORE ViewModel, AND THE SPLIT IS ALONG THE REAL SEAM. The dialler
 * destination is long-lived and places a SEQUENCE of calls; each call is a short-lived thing that
 * owns a socket, the device's audio focus and a duration. Keeping them in one class meant the
 * ViewModel could not express "one engine per call" as anything but discipline — and
 * [CallEngine]'s own contract is one instance per call, because `LiveKitCallEngine` wraps a single
 * `Room` and a second `connect` on a disconnected one is not a supported operation.
 *
 * ⛔ THE SCOPE IS SUPPLIED AND IS NOT `viewModelScope`. `ViewModel.onCleared` fires AFTER
 * `viewModelScope` is cancelled, so a disconnect launched there never reaches the socket — and on
 * a softphone that leaves a PSTN leg bridged to a room nobody is in, billing, until the server
 * times it out. [scope] outlives the ViewModel by exactly the length of [end]'s disconnect and is
 * cancelled from inside it.
 *
 * ⚠️ IT HOLDS THE CALL'S STATE AND THE ViewModel MIRRORS IT, rather than the other way round. The
 * duration ticker and the answer latch both belong to the call, so a session that ended cannot
 * keep writing into a screen that has moved on.
 *
 * ⚠️ THE LIFECYCLE ITSELF (the release latch, the toggles, the teardown, the in-flight hang-up guard
 * and the clock) IS [CallSessionCore], SHARED WITH `InboundCallSession`. What stays here is what is
 * outbound-only: announcing the call to Telecom, and the participant answer signal.
 */
internal class SoftphoneSession(
    /** ⚠️ As the operator typed it. The server normalised its own copy; this is what they recognise. */
    val number: String,
    private val engine: CallEngine,
    /** ⛔ Outlives `onCleared`. See the class doc. */
    scope: CoroutineScope,
    private val telecom: TelecomBridge,
    tickMillis: Long,
) {

    private val core = CallSessionCore(engine, scope, telecom, tickMillis, number)

    val state: StateFlow<ActiveCallUiState> get() = core.state

    /**
     * Start watching the engine and tell the OS a call is being placed.
     *
     * ⛔ TELECOM IS TOLD BEFORE THE MEDIA CONNECTS, NOT AFTER. The carrier is already ringing the
     * callee by this point; the OS needs to know a call is up so it stops the ringer and grants
     * call audio focus, and doing it after `connect` returns would leave a window in which a
     * notification could ring over a live call.
     *
     * @param observeScope the ViewModel's own scope. ⚠️ The collectors feed the UI, so they should
     *   stop when the screen does — the disconnect is the one thing that must not, and it runs in
     *   the session's own scope.
     */
    fun begin(observeScope: CoroutineScope, onSystemHangUp: () -> Unit) {
        core.observe(observeScope)
        observeScope.launch {
            engine.participants.collect { people ->
                // ⛔ THE ANSWER SIGNAL, AND THE ONLY ONE THIS CLIENT HAS. LiveKit's SIP bridge adds
                // the callee as a participant when the call is PICKED UP; the dial route returns
                // without waiting for an answer, so there is no signalling channel that says so.
                //
                // ⛔ LATCHED IN THE CORE, NEVER RECOMPUTED. Once answered, an emptying list means
                // the callee hung up rather than that they were never there — a flag that fell
                // back to false would redraw the ring UI over a call that had just ended and
                // restart the duration from zero.
                if (people.isNotEmpty()) core.markAnswered(observeScope)
            }
        }
        telecom.startOutgoing(number, onSystemHangUp)
    }

    /**
     * Join the room the dial created.
     *
     * ⛔ THE url/token PAIR IS USED VERBATIM. The room exists only on the deployment the SIP dial
     * created it on — the TRUNK's, not the workspace's — so a client that derived a URL would join
     * a bus that has never heard of this room, on a call that is already live and billed.
     *
     * ⛔ A HANG-UP THAT LANDS WHILE THIS IS IN FLIGHT NEVER TURNS THE MICROPHONE ON, and the room is
     * disconnected again once the join resolves. That guard, the failure handling and the
     * cancellation handling are [CallSessionCore.connect]'s; the outcome is not needed here, because
     * the participant signal (not the join) is what answers an outbound call.
     */
    suspend fun connect(url: String, token: String) {
        core.connect(url, token)
    }

    fun toggleMicrophone(observeScope: CoroutineScope) = core.toggleMicrophone(observeScope)

    fun toggleSpeaker() = core.toggleSpeaker()

    /** End the call, then tell the caller. See [CallSessionCore.end] for the ordering. */
    fun end(onEnded: () -> Unit) = core.end(onEnded)
}

/**
 * Builds one [SoftphoneSession] per call.
 *
 * ⛔ A FACTORY RATHER THAN FOUR CONSTRUCTOR PARAMETERS ON THE ViewModel, AND THE CEILING THAT
 * FORCED IT WAS WORTH RESPECTING. detekt caps a constructor's parameter list, and the four things
 * needed to build a call — the engine factory, the Telecom bridge, the scope its teardown runs in
 * and the timer's granularity — are one concept: how a call session comes into existence. Bundling
 * them also puts the ⛔ below in ONE place instead of at every call site.
 *
 * ⛔ THE SCOPE AND THE ENGINE ARE MINTED TOGETHER, WHICH IS THE PART THAT MUST NOT DRIFT.
 * [CallEngineFactory] takes the scope its event collection lives in, and [SoftphoneSession] runs
 * its disconnect in the same one — so building them from two separate calls would leave the
 * engine's collector alive after the session that owned it had gone.
 *
 * ⚠️ ONE ENGINE PER CALL, NEVER A REUSED ONE. `LiveKitCallEngine` wraps a single `Room` and a
 * second `connect` on a disconnected one is not a supported operation, which is why this is called
 * per call rather than held as a field.
 */
class SoftphoneSessionFactory(
    private val engineFactory: CallEngineFactory,
    private val telecom: TelecomBridge,
    /**
     * ⛔ NOT `viewModelScope`. `ViewModel.onCleared` fires AFTER that scope is cancelled, so a
     * disconnect launched there never reaches the socket — and on a softphone that strands a PSTN
     * leg bridged to a room nobody is in, billing, until the server times it out. Each call gets a
     * scope that outlives `onCleared` by exactly the length of its own disconnect.
     *
     * ⚠️ Injectable so a test can drive a call's lifetime deterministically; the production default
     * is `Main.immediate` because the state it writes is read by Compose.
     */
    private val engineScopeFactory: () -> CoroutineScope = ::newCallScope,
    /** ⚠️ The timer's granularity, not a poll interval — nothing is fetched. Injectable for tests. */
    private val tickMillis: Long = CALL_TICK_MILLIS,
) {

    /**
     * ⚠️ `internal` BECAUSE [SoftphoneSession] IS, and the visibility is the honest direction: a
     * live call is this module's business and nothing outside it should be able to hold one. The
     * FACTORY is public only so `DialerViewModel`'s constructor can name it.
     */
    internal fun create(number: String): SoftphoneSession {
        val scope = engineScopeFactory()
        return SoftphoneSession(
            number = number,
            engine = engineFactory.create(scope),
            scope = scope,
            telecom = telecom,
            tickMillis = tickMillis,
        )
    }
}
