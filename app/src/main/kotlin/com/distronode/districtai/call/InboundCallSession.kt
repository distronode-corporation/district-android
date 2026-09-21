package com.distronode.districtai.call

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.dialer.ActiveCallUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ONE answered inbound call: its engine, its scope, its clock.
 *
 * ⛔ ITS OWN CLASS RATHER THAN A REUSE OF `SoftphoneSession`, AND THE DIFFERENCE IS THE ANSWER
 * SIGNAL RATHER THAN STYLE. An outbound call is answered when a PARTICIPANT APPEARS — the SIP bridge
 * adds the callee to the room at pickup, and that is the only ring-versus-answer signal that path
 * has. An inbound call joins a room that ALREADY contains the caller and the AI, so participants are
 * present from the first frame and a session that latched on them would report "answered" before it
 * had connected to anything. Here the answer moment is OUR MEDIA COMING UP, which is a different
 * event with a different failure mode, and folding both into one class would mean a flag deciding
 * which of two state machines is running.
 *
 * ⛔ TELECOM IS TOLD ACTIVE ONLY AFTER [connect] SUCCEEDS, WHICH IS THE INBOUND MIRROR OF THE
 * OUTBOUND ANSWER-LATCH. Marking the connection ACTIVE when the user pressed Answer would tell the
 * OS a conversation is under way while this app was still fetching a credential and opening a
 * socket — the OS's own duration counter would start early, and on a failed join the user would be
 * looking at a connected call that has no audio.
 *
 * ⛔ THE SCOPE IS SUPPLIED AND IS NOT `viewModelScope`, for the reason `SoftphoneSession` documents:
 * a disconnect launched in a scope that is already cancelled never reaches the socket, and on a call
 * that strands a PSTN leg bridged to a room nobody is in, billing, until the server times it out.
 *
 * ⚠️ IT HOLDS NO CALLER IDENTITY, AND IT CANNOT. The push carries identifiers only and the answer
 * route returns a join credential rather than a caller, so [ActiveCallUiState.number] is EMPTY here
 * and the screen substitutes a label. That is a real product limitation of the ids-only payload,
 * written down rather than papered over with the call id.
 */
internal class InboundCallSession(
    private val engine: CallEngine,
    /** ⛔ Outlives the ViewModel/controller. See the class doc. */
    private val scope: CoroutineScope,
    private val telecom: TelecomBridge,
    private val tickMillis: Long,
) {

    private val _state = MutableStateFlow(ActiveCallUiState(number = ""))
    val state: StateFlow<ActiveCallUiState> = _state.asStateFlow()

    /**
     * ⛔ ONE-WAY, AND IT IS WHAT MAKES "HANG UP DISCONNECTS EXACTLY ONCE" TRUE. Four paths reach
     * [end] — the hang-up button, the OS's own end-call affordance, a failed join, and the
     * controller tearing down — and a second disconnect would race the first one's cancellation of
     * [scope].
     */
    private var released = false

    private var timer: Job? = null

    /**
     * Start watching the engine.
     *
     * ⛔ IT DOES **NOT** TOUCH TELECOM, UNLIKE THE OUTBOUND SESSION'S `begin`. The connection
     * already exists and is RINGING: `TelecomBridge.startIncoming` created it when the push arrived,
     * which is minutes of user-visible ringing before this object is constructed. Announcing the
     * call again here would ask Telecom for a SECOND connection for the same call.
     *
     * @param observeScope the owner's scope. ⚠️ The collectors feed the UI, so they should stop when
     *   the screen does — the disconnect is the one thing that must not, and it runs in [scope].
     */
    fun begin(observeScope: CoroutineScope) {
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
     * Join the room the answer route named.
     *
     * ⛔ THE url/token PAIR IS USED VERBATIM. The room lives on the deployment that CREATED it —
     * for an inbound call, the US SIP bridge, regardless of the workspace's own region — and the
     * server resolved that by room rather than by workspace precisely because an EU tenant has rooms
     * on both buses. A client that derived a URL would create an empty room of the same name on the
     * wrong bus and sit in it alone while the caller waited.
     *
     * ⚠️ IT DOES NOT THROW. The engine sets its own state to `Failed` and rethrows; swallowing that
     * here is correct because the connection state IS the user-facing outcome, and letting it escape
     * would crash the process over a network condition.
     *
     * @return true when media is up. ⛔ False means Telecom was told the call is over — a connection
     *   left RINGING or ACTIVE after a failed join keeps audio focus and keeps the OS suppressing
     *   the ringer for a call that is not happening.
     */
    suspend fun connect(url: String, token: String, observeScope: CoroutineScope): Boolean {
        val failure = runCatching { engine.connect(url, token) }.exceptionOrNull()
        if (failure != null) {
            telecom.setDisconnected()
            _state.value = _state.value.copy(
                connection = CallConnectionState.Failed(failure.message),
            )
            return false
        }
        // ⚠️ UNCONDITIONALLY ON. The answer route excludes viewers server-side, so a token that
        // reached here always carries publish rights — and unlike a meeting there is no listen-only
        // seat worth having on a telephone call: the caller would experience it as silence.
        engine.setMicrophoneEnabled(true)
        markAnswered(observeScope)
        return true
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
                // this scope outlives its owner; leaving it alive would leak the engine's collector.
                scope.cancel()
            }
        }
    }

    /**
     * ⛔ THE TIMER STARTS WHEN MEDIA IS UP, NOT WHEN THE USER PRESSED ANSWER, and the operator will
     * compare it to an invoice. The platform bills answered time; counting from the press would
     * include the answer round trip and the join, and make the app the thing that looks wrong.
     *
     * ⚠️ ONE JOB. Two tickers would advance the same counter twice per second.
     */
    private fun markAnswered(observeScope: CoroutineScope) {
        if (_state.value.answered || released) return
        telecom.setActive()
        _state.value = _state.value.copy(answered = true)
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
 * Builds one [InboundCallSession] per answered call.
 *
 * ⛔ THE SCOPE AND THE ENGINE ARE MINTED TOGETHER, WHICH IS THE PART THAT MUST NOT DRIFT.
 * [CallEngineFactory] takes the scope its event collection lives in, and [InboundCallSession] runs
 * its disconnect in the same one — so building them from two separate calls would leave the engine's
 * collector alive after the session that owned it had gone. Same contract as
 * `SoftphoneSessionFactory`, which exists for the outbound half.
 *
 * ⚠️ ONE ENGINE PER CALL, NEVER A REUSED ONE. `LiveKitCallEngine` wraps a single `Room` and a second
 * `connect` on a disconnected one is not a supported operation.
 */
internal class InboundCallSessionFactory(
    private val engineFactory: CallEngineFactory,
    private val telecom: TelecomBridge,
    /** ⛔ NOT `viewModelScope` — see [InboundCallSession]. Injectable so a test owns the lifetime. */
    private val engineScopeFactory: () -> CoroutineScope = {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    },
    /** ⚠️ The timer's granularity, not a poll interval — nothing is fetched. Injectable for tests. */
    private val tickMillis: Long = TICK_MILLIS,
) {

    fun create(): InboundCallSession {
        val scope = engineScopeFactory()
        return InboundCallSession(
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
