package com.distronode.districtai.ui.dialer

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.telecom.TelecomBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
internal class SoftphoneSession(
    /** ⚠️ As the operator typed it. The server normalised its own copy; this is what they recognise. */
    val number: String,
    private val engine: CallEngine,
    /** ⛔ Outlives `onCleared`. See the class doc. */
    private val scope: CoroutineScope,
    private val telecom: TelecomBridge,
    private val tickMillis: Long,
) {

    private val _state = MutableStateFlow(ActiveCallUiState(number = number))
    val state: StateFlow<ActiveCallUiState> = _state.asStateFlow()

    /**
     * ⛔ ONE-WAY, AND IT IS WHAT MAKES "HANG UP DISCONNECTS EXACTLY ONCE" TRUE. Three paths reach
     * [end] on an ordinary exit — the hang-up button, the OS's own end-call affordance, and the
     * ViewModel clearing — and a second disconnect would race the first one's cancellation of
     * [scope].
     */
    private var released = false

    private var timer: Job? = null

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
     *   [scope].
     */
    fun begin(observeScope: CoroutineScope, onSystemHangUp: () -> Unit) {
        observeScope.launch {
            engine.connectionState.collect { connection ->
                _state.value = _state.value.copy(connection = connection)
            }
        }
        observeScope.launch {
            engine.participants.collect { people ->
                // ⛔ THE ANSWER SIGNAL, AND THE ONLY ONE THIS CLIENT HAS. LiveKit's SIP bridge adds
                // the callee as a participant when the call is PICKED UP; the dial route returns
                // without waiting for an answer, so there is no signalling channel that says so.
                //
                // ⛔ LATCHED IN [answer], NEVER RECOMPUTED. Once answered, an emptying list means
                // the callee hung up rather than that they were never there — a flag that fell
                // back to false would redraw the ring UI over a call that had just ended and
                // restart the duration from zero.
                if (people.isNotEmpty()) answer(observeScope)
            }
        }
        observeScope.launch {
            engine.isMicrophoneEnabled.collect { on ->
                _state.value = _state.value.copy(micEnabled = on)
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
     * ⚠️ IT DOES NOT THROW. The engine sets its own state to `Failed` and rethrows; swallowing that
     * here is correct because the connection state IS the user-facing outcome, and letting it
     * escape would crash the process over a network condition.
     */
    suspend fun connect(url: String, token: String) {
        runCatching { engine.connect(url, token) }
            .onFailure { failure ->
                // ⛔ TELECOM IS TOLD, BECAUSE THE OS ALREADY BELIEVES A CALL IS UP. A failed connect
                // that left the connection DIALING would hold audio focus and suppress the ringer
                // indefinitely, for a call that is not happening.
                telecom.setDisconnected()
                _state.value = _state.value.copy(
                    connection = CallConnectionState.Failed(failure.message),
                )
                return
            }
        // ⚠️ UNCONDITIONALLY ON, unlike the room screen's `canPublish` gate: the dial route excludes
        // viewers server-side, so a token that reached here always carries publish rights, and the
        // permission was answered before the request was sent.
        engine.setMicrophoneEnabled(true)
    }

    fun toggleMicrophone(observeScope: CoroutineScope) {
        observeScope.launch { engine.setMicrophoneEnabled(!_state.value.micEnabled) }
    }

    /**
     * ⚠️ SET SYNCHRONOUSLY, AND THE FLAG IS THIS SCREEN'S OWN BELIEF. The engine exposes no route to
     * read back — see the audio-routing note on [CallEngine] — so this records what the app asked
     * for. Honest for a toggle; it would not be honest as a status readout.
     */
    fun toggleSpeaker() {
        val next = !_state.value.speakerOn
        engine.setSpeakerphoneOn(next)
        _state.value = _state.value.copy(speakerOn = next)
    }

    /**
     * End the call, then tell the caller.
     *
     * ⛔ THE CALLBACK RUNS AFTER THE DISCONNECT RESOLVES. Reporting first would let the caller tear
     * this object down while the socket was still closing.
     *
     * ⚠️ THE UI MOVES TO `ended` IMMEDIATELY, BEFORE THE SOCKET CLOSES. A hang-up that looked
     * unresponsive for the length of a network round trip is one the operator presses again.
     */
    fun end(onEnded: () -> Unit) {
        if (released) {
            onEnded()
            return
        }
        released = true
        timer?.cancel()
        timer = null
        _state.value = _state.value.copy(ended = true)
        telecom.setDisconnected()
        scope.launch {
            try {
                engine.disconnect()
            } finally {
                onEnded()
                // ⚠️ LAST, AND FROM INSIDE THE SCOPE IT CANCELS. The disconnect is the only reason
                // this scope outlives the ViewModel; leaving it alive would leak the engine's
                // event collector.
                scope.cancel()
            }
        }
    }

    private fun answer(observeScope: CoroutineScope) {
        if (_state.value.answered || released) return
        telecom.setActive()
        _state.value = _state.value.copy(answered = true)
        // ⛔ THE TIMER STARTS AT THE ANSWER, NOT AT THE DIAL, and the operator will compare it to an
        // invoice. The platform bills answered time; counting from the dial would overstate every
        // call by its ring duration and make the app the thing that looks wrong.
        //
        // ⚠️ ONE JOB. Two tickers would advance the same counter twice per second.
        timer?.cancel()
        timer = observeScope.launch {
            while (true) {
                delay(tickMillis)
                // ⚠️ Stops counting the moment the call ends, rather than being cancelled from
                // outside and racing one last tick past the frozen duration.
                if (_state.value.ended) return@launch
                _state.value = _state.value.copy(
                    elapsedSeconds = _state.value.elapsedSeconds + 1,
                )
            }
        }
    }
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
    private val engineScopeFactory: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    },
    /** ⚠️ The timer's granularity, not a poll interval — nothing is fetched. Injectable for tests. */
    private val tickMillis: Long = TICK_MILLIS,
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

    private companion object {
        /** One second. Named because detekt counts a bare 1000 as a magic number. */
        const val TICK_MILLIS = 1000L
    }
}
