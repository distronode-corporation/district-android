package com.distronode.districtai.ui.calls

import com.distronode.districtai.core.model.TranscriptSegment
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.live.FinalTranscriptState
import com.distronode.districtai.core.network.live.LiveDependencies
import com.distronode.districtai.core.network.live.LiveTranscriptPhase
import com.distronode.districtai.core.network.live.TelemetryConnectionRunner
import com.distronode.districtai.core.network.live.TelemetryMessage
import com.distronode.districtai.core.network.live.TelemetryUpdate
import com.distronode.districtai.core.network.live.TranscriptCommand
import com.distronode.districtai.core.network.live.TranscriptReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The live transcript of one call in progress, for the call's screen (iOS `LiveTranscriptModel`).
 *
 * ⛔ IT DECIDES NOTHING ABOUT THE TRANSCRIPT OR THE SOCKET. [TranscriptReducer] (what the frames
 * mean) and [TelemetryConnectionRunner] (the socket, its renewal, its close codes and re-sending
 * the subscribe on every open) are core-network's. This object starts and stops a socket for the
 * screen, feeds the reducer what arrives, performs the reducer's commands on the clock, and
 * publishes the state.
 *
 * ⛔ THE SOCKET RUNS ONLY WHILE THE SCREEN IS STARTED. [activate] when the destination starts,
 * [deactivate] when it stops (backgrounded, or another screen pushed on top): a phone holds no
 * socket for a screen nobody is looking at. Coming back opens a new socket, whose subscribe brings
 * a snapshot that replaces what is on screen.
 *
 * ⚠️ `broadcast = false`, SENT AS `socket.mode` ON EVERY OPEN: this socket wants only its call's
 * transcript, not the workspace's call and message events.
 *
 * ⚠️ EVERYTHING RUNS IN [scope], WHICH MUST BE SINGLE-THREADED (the ViewModel's, on the main
 * thread), so the reducer is only touched from one thread.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED: the screen's four entry points and one small function per
 * command the reducer can give, around one state, as on the reducer itself.
 */
@Suppress("TooManyFunctions")
class LiveTranscriptController(
    private val workspaceId: String,
    private val callId: String,
    private val dependencies: LiveDependencies,
    private val fetchTranscript: suspend () -> ApiResult<String>,
    private val scope: CoroutineScope,
) {
    /** Where the socket stands, for the line under the heading. */
    enum class Connection {
        /** No socket: not yet activated, or deactivated. */
        IDLE,

        /** Opening, or reopening after a gap. */
        CONNECTING,
        OPEN,

        /** The socket ended for good. The screen falls back to the transcript after the call. */
        FAILED,
    }

    /** What the screen draws. */
    data class State(
        val lines: List<TranscriptSegment> = emptyList(),
        val phase: LiveTranscriptPhase = LiveTranscriptPhase.Subscribing,
        val complete: Boolean = true,
        val finalTranscript: FinalTranscriptState = FinalTranscriptState.NotRequested,
        val connection: Connection = Connection.IDLE,
    ) {
        /**
         * Whether the screen should show the transcript after the call instead: the server has no
         * live transcript for this call, or the socket cannot be had.
         */
        val fallsBack: Boolean
            get() = phase is LiveTranscriptPhase.Unavailable || connection == Connection.FAILED
    }

    private val reducer = TranscriptReducer(callId)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var connection = Connection.IDLE
    private var runner: TelemetryConnectionRunner? = null
    private var pump: Job? = null
    private val timers = mutableMapOf<Timer, Job>()

    /** Set once the call needs no socket (the full transcript is in, or it is unavailable). */
    private var finished = false

    /** The screen is started: a socket may be open. */
    private var active = false

    private enum class Timer { RESUBSCRIBE, GAP, FETCH }

    // ── The screen's lifecycle ──────────────────────────────────────────────────────────────────

    /** The screen started: open a socket, or resume fetching the full transcript. */
    fun activate() {
        active = true
        if (reducer.finalTranscript is FinalTranscriptState.Fetching) {
            schedule(Timer.FETCH, 0) { fetchFinal() }
            return
        }
        openSocket()
    }

    /** The screen stopped: close the socket and stop every wait. What is on screen stays. */
    fun deactivate() {
        active = false
        closeSocket()
        cancelTimers()
        if (connection != Connection.FAILED) connection = Connection.IDLE
        publish()
    }

    // ── The call row ────────────────────────────────────────────────────────────────────────────

    /**
     * The call row shows the call in progress. ⛔ THE ONLY WAY BACK FROM `not_live`, at most once per
     * 30 s (the reducer's rule). This socket takes no workspace relay, so the call row, as this
     * screen already reads it, is the only call status it sees.
     */
    fun callShownInProgress() {
        perform(reducer.callShownInProgress(dependencies.clock.nowMillis()))
        publish()
    }

    /** The call row was loaded again and the call is over. */
    fun callEnded() {
        perform(reducer.callEnded())
        publish()
    }

    // ── The socket ──────────────────────────────────────────────────────────────────────────────

    private fun openSocket() {
        if (!active || finished || runner != null) return
        val opened = TelemetryConnectionRunner(workspaceId, broadcast = false, dependencies, scope)
        runner = opened
        connection = Connection.CONNECTING
        opened.subscribeTranscript(callId)
        pump = scope.launch { opened.updates.collect(::handle) }
        opened.start()
        publish()
    }

    private fun handle(update: TelemetryUpdate) {
        when (update) {
            TelemetryUpdate.Connected -> {
                // ⚠️ EVERY OPEN, THE FIRST INCLUDED: the runner re-sent the subscribe, so a snapshot
                // is coming, and anything half-received on an earlier socket is stale.
                connection = Connection.OPEN
                reducer.reconnected()
                cancel(Timer.GAP)
            }
            is TelemetryUpdate.Message -> {
                val message = update.message
                if (message is TelemetryMessage.Transcript) {
                    perform(reducer.apply(message.event, dependencies.clock.nowMillis()))
                }
            }
            is TelemetryUpdate.Reconnecting -> connection = Connection.CONNECTING
            // ⚠️ ALWAYS A FAILURE: [closeSocket] cancels the pump before it stops the runner, so the
            // end the app asked for is never read here, and every end that is read is one it did not.
            is TelemetryUpdate.Ended -> {
                runner = null
                connection = Connection.FAILED
            }
        }
        publish()
    }

    private fun perform(commands: List<TranscriptCommand>) {
        commands.forEach { command ->
            when (command) {
                is TranscriptCommand.Resubscribe ->
                    schedule(Timer.RESUBSCRIBE, command.afterMillis) { runner?.resubscribeTranscript(callId) }
                is TranscriptCommand.CheckGap -> schedule(Timer.GAP, command.afterMillis) {
                    perform(reducer.gapCheck(dependencies.clock.nowMillis()))
                    publish()
                }
                is TranscriptCommand.FetchFinal -> schedule(Timer.FETCH, command.afterMillis) { fetchFinal() }
                // ⚠️ THE SOCKET IS THIS CALL'S ALONE, so nothing more wanted from it means closing
                // it. No unsubscribe first: the server drops a socket's ops with it.
                TranscriptCommand.Unsubscribe -> {
                    finished = true
                    closeSocket()
                }
                // A call-status signal after `not_live`: a new socket, whose open sends the
                // subscribe, once the screen is started.
                TranscriptCommand.Subscribe -> {
                    finished = false
                    openSocket()
                }
            }
        }
    }

    private suspend fun fetchFinal() {
        perform(reducer.finalFetched(fetchTranscript()))
        publish()
    }

    private fun closeSocket() {
        val closing = runner ?: return
        runner = null
        pump?.cancel()
        pump = null
        closing.stop()
    }

    private fun publish() {
        _state.value = State(
            lines = reducer.lines,
            phase = reducer.phase,
            complete = reducer.complete,
            finalTranscript = reducer.finalTranscript,
            connection = connection,
        )
    }

    // ── Waits ───────────────────────────────────────────────────────────────────────────────────

    /** Run [work] after [millis], replacing any pending wait of [timer]'s kind. */
    private fun schedule(timer: Timer, millis: Long, work: suspend () -> Unit) {
        timers.remove(timer)?.cancel()
        timers[timer] = scope.launch {
            dependencies.clock.sleep(millis)
            work()
        }
    }

    private fun cancel(timer: Timer) {
        timers.remove(timer)?.cancel()
    }

    private fun cancelTimers() {
        timers.values.forEach(Job::cancel)
        timers.clear()
    }
}
