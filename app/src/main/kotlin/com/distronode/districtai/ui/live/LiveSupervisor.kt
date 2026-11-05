package com.distronode.districtai.ui.live

import com.distronode.districtai.R
import com.distronode.districtai.core.data.LiveCallGrant
import com.distronode.districtai.core.data.SuperviseOutcome
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one live call this supervisor is in, listening or taken over, and the media engine for it.
 *
 * ⛔ ONE ENGINE PER JOIN, NEVER REUSED. Listening and taking over are two credentials for two
 * different participants (listen-only, then microphone-only with the agent's barge-in mode), so a
 * takeover leaves the listening room and joins again on a fresh engine, the way the web replaces
 * its `Room`. The takeover joins under the same server-derived identity, so the server replaces
 * the listener rather than adding a second participant.
 *
 * ⛔ THE GRANT IS FETCHED BEFORE ANYTHING IS LEFT. A refused takeover leaves the supervisor
 * listening (the web's order); a refused listen changes nothing.
 *
 * ⛔ LISTENING NEVER OPENS THE MICROPHONE, and the token could not publish if it did. The engine
 * already joins subscribe-only: `LiveKitCallEngine.connect` publishes nothing until
 * `setMicrophoneEnabled(true)`, which is called on a taken-over call and nowhere else.
 *
 * ⛔ SINGLE-FLIGHT. A second listen or takeover while one is being fetched or joined is dropped:
 * two taps before a re-render would mint two credentials and leave one leg orphaned in a
 * customer's call.
 *
 * ⛔ NEVER RETRIED. A refusal or a failed join is shown at the call and the supervisor decides.
 *
 * @param scope the ViewModel's. @param engineScope outlives it, so a disconnect launched by
 *   [release] still runs (see `CallEngineFactory`).
 */
class LiveSupervisor(
    private val repository: SuperviseRepository,
    private val workspaceId: String,
    private val engineFactory: CallEngineFactory,
    private val scope: CoroutineScope,
    private val engineScope: CoroutineScope,
) {
    /** Where the supervised call's room connection stands. */
    enum class Phase { IDLE, CONNECTING, CONNECTED, RECONNECTING }

    /**
     * What the screen draws.
     *
     * @property callId the call whose room this supervisor is in, or joining.
     * @property requestingCallId a credential is being fetched for this call. ⚠️ Apart from [phase]
     *   on purpose: a takeover is requested WHILE listening, and the listening room's own states
     *   go on arriving meanwhile.
     * @property failure shown at [failedCallId].
     */
    data class State(
        val callId: String? = null,
        val takenOver: Boolean = false,
        val phase: Phase = Phase.IDLE,
        val requestingCallId: String? = null,
        val failedCallId: String? = null,
        val failure: UiText? = null,
    ) {
        /** A request or a join is in flight: listen and take over wait. */
        val busy: Boolean get() = requestingCallId != null || phase == Phase.CONNECTING
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: CallEngine? = null
    private var watch: Job? = null
    private var action: Job? = null
    private var released = false

    /** Listen to [callId], leaving any call this supervisor is in first. */
    fun listen(callId: String) = act(callId, takeOver = false)

    /** Take [callId] over by voice. ⚠️ The caller has the microphone permission already. */
    fun takeOver(callId: String) = act(callId, takeOver = true)

    /** Stop listening, or hand the call back: leaving the room is both. */
    fun leave() {
        action?.cancel()
        leaveEngine()
        _state.value = State()
    }

    /** Show [text] at [callId] without any request (a refusal the screen made itself). */
    fun fail(callId: String, text: UiText) {
        _state.value = _state.value.copy(failedCallId = callId, failure = text)
    }

    /** The ViewModel is gone: leave, and let the engine scope end once the disconnect has run. */
    fun release() {
        if (released) return
        released = true
        action?.cancel()
        watch?.cancel()
        val last = engine
        engine = null
        engineScope.launch {
            try {
                last?.disconnect()
            } finally {
                engineScope.cancel()
            }
        }
    }

    private fun act(callId: String, takeOver: Boolean) {
        if (released || _state.value.busy) return
        _state.value = _state.value.copy(requestingCallId = callId, failedCallId = null, failure = null)
        action = scope.launch {
            val outcome = if (takeOver) repository.takeOver(workspaceId, callId) else repository.monitor(callId)
            val refusal = when (outcome) {
                is SuperviseOutcome.Done -> {
                    join(callId, outcome.value, takeOver)
                    null
                }
                SuperviseOutcome.NotACall -> UiText.Resource(R.string.live_calls_not_a_call)
                is SuperviseOutcome.Refused -> superviseFailureText(
                    outcome.failure,
                    if (takeOver) R.string.live_calls_take_over_failed else R.string.live_calls_connect_failed,
                )
            }
            // ⚠️ A refusal changes nothing else: the room this supervisor is in, if any, stays.
            if (refusal != null) {
                _state.value = _state.value.copy(requestingCallId = null, failedCallId = callId, failure = refusal)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun join(callId: String, grant: LiveCallGrant, takeOver: Boolean) {
        leaveEngine()
        val joined = engineFactory.create(engineScope)
        engine = joined
        _state.value = State(callId = callId, takenOver = takeOver, phase = Phase.CONNECTING)
        watch = scope.launch { joined.connectionState.collect { follow(joined, it) } }
        val failure = try {
            // ⛔ THE GRANT'S URL, VERBATIM: the room's deployment, never the workspace's default.
            // No key: a phone call's room is never end-to-end encrypted (it has a SIP leg).
            joined.connect(grant.url, grant.token)
            if (takeOver) {
                // ⛔ The one place this screen opens the microphone. At the ear, like a phone call.
                joined.setSpeakerphoneOn(false)
                joined.setMicrophoneEnabled(true)
            } else {
                joined.setSpeakerphoneOn(true)
            }
            null
        } catch (cancelled: CancellationException) {
            // ⛔ Left while joining: the room may have been joined anyway, so it is left again.
            withContext(NonCancellable) { joined.disconnect() }
            throw cancelled
        } catch (thrown: Exception) {
            thrown
        }
        if (failure != null) {
            // ⛔ NO HALF-JOINED LEG: a supervisor in the room without a working microphone is a
            // participant the caller cannot hear and the agent never sees.
            leaveEngine()
            val text = if (takeOver) R.string.live_calls_take_over_join_failed else R.string.live_calls_connect_failed
            _state.value = State(failedCallId = callId, failure = UiText.Resource(text))
        }
    }

    /** ⚠️ Only the current engine's states count: a left engine's late `Disconnected` says nothing. */
    private fun follow(source: CallEngine, connection: CallConnectionState) {
        if (source !== engine) return
        val current = _state.value
        when (connection) {
            CallConnectionState.Connected -> _state.value = current.copy(phase = Phase.CONNECTED)
            CallConnectionState.Reconnecting -> _state.value = current.copy(phase = Phase.RECONNECTING)
            // ⚠️ THE ROOM WENT AWAY WITHOUT US: the call ended, or the session was lost for good.
            is CallConnectionState.Disconnected -> ended(current, failure = null)
            is CallConnectionState.Failed ->
                ended(current, failure = UiText.Resource(R.string.live_calls_connect_failed))
            CallConnectionState.Idle, CallConnectionState.Connecting -> Unit
        }
    }

    private fun ended(current: State, failure: UiText?) {
        if (current.phase == Phase.CONNECTING) return
        leaveEngine()
        _state.value = State(
            requestingCallId = current.requestingCallId,
            failedCallId = failure?.let { current.callId },
            failure = failure,
        )
    }

    private fun leaveEngine() {
        watch?.cancel()
        watch = null
        val leaving = engine ?: return
        engine = null
        engineScope.launch { leaving.disconnect() }
    }
}
