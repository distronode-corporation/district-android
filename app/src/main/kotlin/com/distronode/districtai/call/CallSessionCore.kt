package com.distronode.districtai.call

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.dialer.ActiveCallUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The lifecycle every live telephone call shares: one engine, one scope, the release latch, the
 * toggles, the teardown and the duration clock.
 *
 * ⛔ ONE COPY, BECAUSE TWO COPIES DRIFTED. `SoftphoneSession` and [InboundCallSession] each carried
 * this lifecycle verbatim, and the fix for a hang-up that lands while the join is still in flight
 * reached only the inbound one: the outbound session went on turning the microphone on in a room
 * the user had already left. A fix here now reaches both calls by construction.
 *
 * ⛔ WHAT IS **NOT** HERE IS THE ANSWER SIGNAL, AND THAT LINE IS THE ONE THE TWO SESSIONS' OWN DOCS
 * DRAW. An outbound call is answered when a participant appears; an inbound one when our media comes
 * up. Each session decides WHEN to call [markAnswered]; this class only makes the latch one-way and
 * starts the clock. Folding the signals in would mean a flag choosing which state machine runs.
 *
 * ⛔ THE SCOPE IS SUPPLIED AND IS NOT `viewModelScope`. `ViewModel.onCleared` fires AFTER
 * `viewModelScope` is cancelled, so a disconnect launched there never reaches the socket, and on a
 * call that strands a PSTN leg bridged to a room nobody is in, billing, until the server times it
 * out. [scope] outlives its owner by exactly the length of [end]'s disconnect and is cancelled from
 * inside it.
 */
internal class CallSessionCore(
    private val engine: CallEngine,
    /** ⛔ Outlives the owner. See the class doc. */
    private val scope: CoroutineScope,
    private val telecom: TelecomBridge,
    private val tickMillis: Long,
    /** ⚠️ As the operator typed it for an outbound call; EMPTY for an inbound one (ids-only push). */
    number: String,
) {

    private val _state = MutableStateFlow(ActiveCallUiState(number = number))
    val state: StateFlow<ActiveCallUiState> = _state.asStateFlow()

    /**
     * ⛔ ONE-WAY, AND IT IS WHAT MAKES "HANG UP DISCONNECTS EXACTLY ONCE" TRUE. The hang-up button,
     * the OS's own end-call affordance, a failed join and the owner tearing down all reach [end], and
     * a second disconnect would race the first one's cancellation of [scope].
     */
    private var released = false

    private var timer: Job? = null

    /**
     * Mirror the engine's connection and microphone onto [state].
     *
     * @param observeScope the owner's scope. ⚠️ The collectors feed the UI, so they should stop when
     *   the screen does; the disconnect is the one thing that must not, and it runs in [scope].
     */
    fun observe(observeScope: CoroutineScope) {
        observeScope.launch {
            engine.connectionState.collect { connection ->
                _state.value = _state.value.copy(connection = connection)
            }
        }
        observeScope.launch {
            engine.isMicrophoneEnabled.collect { on ->
                _state.value = _state.value.copy(micEnabled = on)
            }
        }
    }

    /**
     * Join the room the server named, and publish the microphone once joined.
     *
     * ⚠️ A FAILED JOIN DOES NOT THROW. The engine sets its own state to `Failed` and rethrows;
     * swallowing that here is correct because the connection state IS the user-facing outcome, and
     * letting it escape would crash the process over a network condition.
     *
     * ⛔ CANCELLATION IS NOT A FAILED JOIN, AND IT IS RETHROWN. Reporting it as one told Telecom the
     * call was over and painted `Failed` with a coroutine-internals message over a teardown, and then
     * let the cancelled coroutine carry on as if it had returned.
     *
     * @return true when media is up. ⛔ False means the call is over: either [end] already ran, or
     *   Telecom was told here. A connection left DIALING, RINGING or ACTIVE after a failed join holds
     *   audio focus and keeps the OS suppressing the ringer for a call that is not happening.
     */
    suspend fun connect(url: String, token: String): Boolean {
        val failure = runCatching { engine.connect(url, token) }.exceptionOrNull()
        // ⛔ HUNG UP WHILE THE JOIN WAS IN FLIGHT: [end] already ran, told Telecom and launched its
        // disconnect, but that disconnect may have reached the engine BEFORE this connect resolved,
        // and nothing orders the two inside the SDK. So the room could be left joined after the user
        // hung up. The microphone is never turned on here, and a disconnect is issued now, after the
        // connect has resolved, whatever it resolved to (a cancellation included). ⚠️ In THIS
        // coroutine and `NonCancellable`, not in [scope]: [end] cancels [scope] once its own
        // disconnect finishes, and a disconnect launched into a cancelled scope never runs. [end]'s
        // `onEnded` and cancellation are left to [end], so each still happens exactly once.
        if (released) withContext(NonCancellable) { engine.disconnect() }
        if (failure is CancellationException) throw failure
        if (released) return false
        if (failure != null) {
            telecom.setDisconnected()
            _state.value = _state.value.copy(connection = CallConnectionState.Failed(failure.message))
            return false
        }
        // ⚠️ UNCONDITIONALLY ON. Both the dial and the answer route exclude viewers server-side, so a
        // token that reached here always carries publish rights, and unlike a meeting there is no
        // listen-only seat worth having on a telephone call: the far end hears it as silence.
        engine.setMicrophoneEnabled(true)
        return true
    }

    fun toggleMicrophone(observeScope: CoroutineScope) {
        observeScope.launch { engine.setMicrophoneEnabled(!_state.value.micEnabled) }
    }

    /**
     * ⚠️ SET SYNCHRONOUSLY, AND THE FLAG IS THIS SCREEN'S OWN BELIEF. The engine exposes no route to
     * read back (see the audio-routing note on [CallEngine]), so this records what the app asked
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
                // this scope outlives its owner; leaving it alive would leak the engine's collector.
                scope.cancel()
            }
        }
    }

    /**
     * The call was answered: tell Telecom it is ACTIVE and start the clock.
     *
     * ⛔ THE CLOCK STARTS HERE, AT THE ANSWER, AND THE OPERATOR WILL COMPARE IT TO AN INVOICE. The
     * platform bills answered time; counting from the dial (or from the press of Answer) would
     * overstate every call and make the app the thing that looks wrong.
     *
     * ⛔ LATCHED, AND REFUSED ONCE RELEASED. A second answer signal would tell Telecom twice and start
     * a second ticker advancing the same counter; one arriving after [end] (a participant joining a
     * room the user already left) would answer a call that is over.
     */
    fun markAnswered(observeScope: CoroutineScope) {
        if (_state.value.answered || released) return
        telecom.setActive()
        _state.value = _state.value.copy(answered = true)
        // ⚠️ No `ended` check inside the loop: [end] cancels this job before it sets `ended`, on the
        // same thread, so a tick resumed after that is cancelled at its `delay` and never reads it.
        timer = observeScope.launch {
            while (true) {
                delay(tickMillis)
                _state.value = _state.value.copy(elapsedSeconds = _state.value.elapsedSeconds + 1)
            }
        }
    }
}

/**
 * The scope one call's engine and teardown live in: a fresh supervisor per call.
 *
 * ⛔ NOT `viewModelScope` (see [CallSessionCore]), and a SUPERVISOR so one failed child cannot end
 * the call. `Main.immediate` because the state it writes is read by Compose. Both session factories
 * default to this; a test injects its own to own the lifetime.
 */
internal fun newCallScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * The duration clock's granularity: one second. ⚠️ Not a poll interval, nothing is fetched. Named
 * because detekt counts a bare 1000 as a magic number.
 */
internal const val CALL_TICK_MILLIS: Long = 1000L
