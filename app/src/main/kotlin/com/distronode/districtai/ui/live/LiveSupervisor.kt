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
 * The one rejoin is a takeover whose join failed after the listening room was left: the
 * supervisor is put back to listening (one listen request, its failure shown as any other), so a
 * failed takeover never leaves them silently out of the call.
 *
 * ⛔ "LISTENING" AND "ON THE CALL" ARE CLAIMED ONLY ONCE THEY ARE TRUE ([State.live]): the room
 * connected and, for a takeover, the microphone published. Until then the card says connecting,
 * and a reconnecting room says so (the web gates the same states on `connectionState`).
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
     * @property joined the join finished: `connect` returned and, for a takeover, the microphone
     *   is on. ⚠️ Apart from [phase], which the room's own states move while the join is running.
     */
    data class State(
        val callId: String? = null,
        val takenOver: Boolean = false,
        val phase: Phase = Phase.IDLE,
        val requestingCallId: String? = null,
        val failedCallId: String? = null,
        val failure: UiText? = null,
        val joined: Boolean = false,
    ) {
        /** A request or a join is in flight: listen and take over wait. */
        val busy: Boolean get() = requestingCallId != null || (callId != null && !joined)

        /** Listening, or on the call with the microphone live: what the card may claim. */
        val live: Boolean get() = joined && phase == Phase.CONNECTED
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
        // ⚠️ No "already released" exit: `onCleared` runs once per ViewModel, and nothing else calls this.
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

    /**
     * @param carried a failure to keep showing at [callId] through this action: the takeover
     *   failure while the supervisor is put back to listening (see the class).
     */
    private fun act(callId: String, takeOver: Boolean, carried: UiText? = null) {
        if (released || _state.value.busy) return
        _state.value = _state.value.copy(
            requestingCallId = callId,
            failedCallId = carried?.let { callId },
            failure = carried,
        )
        action = scope.launch {
            val outcome = if (takeOver) repository.takeOver(workspaceId, callId) else repository.monitor(callId)
            val refusal = when (outcome) {
                is SuperviseOutcome.Done -> {
                    join(callId, outcome.value, takeOver, carried)
                    null
                }
                SuperviseOutcome.NotACall -> UiText.Resource(R.string.live_calls_not_a_call)
                is SuperviseOutcome.Refused -> carried ?: superviseFailureText(
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
    private suspend fun join(callId: String, grant: LiveCallGrant, takeOver: Boolean, carried: UiText?) {
        leaveEngine()
        val joined = engineFactory.create(engineScope)
        engine = joined
        _state.value = State(
            callId = callId,
            takenOver = takeOver,
            phase = Phase.CONNECTING,
            failedCallId = carried?.let { callId },
            failure = carried,
        )
        watch = scope.launch { joined.connectionState.collect(::follow) }
        var connected = false
        val failure = try {
            // ⛔ THE GRANT'S URL, VERBATIM: the room's deployment, never the workspace's default.
            // ⚠️ A phone call's room carries no key today (it has a SIP leg); one the grant does
            // carry is used, as the rooms use theirs, rather than joining a room it cannot hear.
            joined.connect(grant.url, grant.token, grant.e2eeKey)
            connected = true
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
        // ⚠️ THE ROOM'S STATE AS THE JOIN ENDS, READ HERE: the watch collects a conflated flow, so a
        // room that connected and closed during the join may only ever show it `Disconnected`,
        // which [ended] ignores until the join is over. A room that closed under a joined
        // `connect` (the call ended, during the microphone's publish too) is the call ending,
        // not a failure to name.
        val connection = joined.connectionState.value
        if (failure != null && !(connected && connection is CallConnectionState.Disconnected)) {
            // ⛔ NO HALF-JOINED LEG: a supervisor in the room without a working microphone is a
            // participant the caller cannot hear and the agent never sees.
            leaveEngine()
            val text = carried ?: UiText.Resource(
                if (takeOver) R.string.live_calls_take_over_join_failed else R.string.live_calls_connect_failed,
            )
            _state.value = State(failedCallId = callId, failure = text)
            // The listening room was left for this join: put the supervisor back in it.
            if (takeOver) act(callId, takeOver = false, carried = text)
            return
        }
        _state.value = _state.value.copy(joined = true)
        follow(connection)
    }

    /**
     * ⚠️ No "is this still the current engine" check: [leaveEngine] cancels an engine's watch before
     * it lets the engine go, so a left engine's late `Disconnected` never arrives here.
     */
    private fun follow(connection: CallConnectionState) {
        val current = _state.value
        when (connection) {
            CallConnectionState.Connected -> _state.value = current.copy(phase = Phase.CONNECTED)
            CallConnectionState.Reconnecting -> _state.value = current.copy(phase = Phase.RECONNECTING)
            // ⚠️ THE ROOM WENT AWAY WITHOUT US: the call ended, the supervisor was removed or
            // joined elsewhere, or the connection was lost. The card says which.
            is CallConnectionState.Disconnected -> ended(current, failure = droppedText(connection.reason))
            is CallConnectionState.Failed ->
                ended(current, failure = UiText.Resource(R.string.live_calls_connect_failed))
            CallConnectionState.Idle, CallConnectionState.Connecting -> Unit
        }
    }

    /**
     * The room went away without us.
     *
     * ⚠️ Not while the join runs: [join] reads the room's state itself when it ends.
     *
     * ⛔ A TAKEOVER OF THE CALL WHOSE ROOM ENDED IS CANCELLED HERE. It was asked for from that room;
     * a grant landing after it closed would join the call again with the microphone live, into a
     * call that is over. A listen asked for ANOTHER call is not about this room, and goes on.
     */
    private fun ended(current: State, failure: UiText?) {
        if (!current.joined) return
        leaveEngine()
        val sameCall = current.requestingCallId != null && current.requestingCallId == current.callId
        if (sameCall) action?.cancel()
        _state.value = State(
            requestingCallId = current.requestingCallId.takeUnless { sameCall },
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

/**
 * Why a joined room went away, from the engine's disconnect reason (LiveKit's `DisconnectReason`
 * name), as the card says it.
 *
 * ⚠️ Top-level, not a member: [LiveSupervisor] sits at detekt's function ceiling. A reason this
 * app does not know reads as a lost connection, never as nothing: the card must not just reset.
 */
internal fun droppedText(reason: String?): UiText? = when (reason) {
    "ROOM_DELETED", "ROOM_CLOSED" -> UiText.Resource(R.string.api_error_call_ended)
    "PARTICIPANT_REMOVED" -> UiText.Resource(R.string.live_calls_removed)
    "DUPLICATE_IDENTITY" -> UiText.Resource(R.string.live_calls_joined_elsewhere)
    // This app's own leave: never seen here, since the watch is cancelled before the disconnect.
    "CLIENT_INITIATED" -> null
    else -> UiText.Resource(R.string.live_calls_connection_lost)
}
