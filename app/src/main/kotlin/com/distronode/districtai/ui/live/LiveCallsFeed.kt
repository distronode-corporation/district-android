package com.distronode.districtai.ui.live

import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.core.network.live.LiveDependencies
import com.distronode.districtai.core.network.live.TelemetryConnectionRunner
import com.distronode.districtai.core.network.live.TelemetryMessage
import com.distronode.districtai.core.network.live.TelemetryUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the Live calls screen hears from the telemetry socket. */
sealed interface LiveFeedEvent {
    /** The socket opened (again): events may have been missed, so the list is read again. */
    data object Opened : LiveFeedEvent

    /** A call of this workspace started or ended. */
    data object CallsChanged : LiveFeedEvent

    /** The receptionist's receipt for a whisper on [callId]. */
    data class Receipt(val callId: String, val data: WhisperAppliedData) : LiveFeedEvent
}

/**
 * The workspace's telemetry socket, for the Live calls screen (the web Live page's own socket).
 *
 * ⛔ BROADCAST MODE, UNLIKE THE CALL DETAIL'S TRANSCRIPT SOCKET. `whisper_applied`, `call_started`
 * and `call_ended` are workspace RELAY events, which a socket that sent `broadcast: false` never
 * receives; this screen exists to watch them.
 *
 * ⛔ IT RUNS ONLY WHILE THE SCREEN IS STARTED ([start] and [stop] follow the destination's
 * lifecycle), as the transcript socket does. A runner is not restartable, so each [start] makes one.
 *
 * ⚠️ A SOCKET THAT ENDS FOR GOOD IS NOT AN ERROR ON SCREEN: the list still reads on demand, and
 * only the receipts and the automatic refresh stop. [start] makes a new one next time.
 *
 * @param scope single-threaded (the ViewModel's), so [onEvent] runs on the main thread.
 */
class LiveCallsFeed(
    private val workspaceId: String,
    private val dependencies: LiveDependencies,
    private val scope: CoroutineScope,
    private val onEvent: (LiveFeedEvent) -> Unit,
) {
    /** The socket and the coroutine reading it, set and cleared together. */
    private class Running(val runner: TelemetryConnectionRunner, val pump: Job)

    private var running: Running? = null

    fun start() {
        if (running != null) return
        val opened = TelemetryConnectionRunner(workspaceId, broadcast = true, dependencies, scope)
        running = Running(opened, scope.launch { opened.updates.collect(::handle) })
        opened.start()
    }

    fun stop() {
        val closing = running ?: return
        running = null
        closing.pump.cancel()
        closing.runner.stop()
    }

    private fun handle(update: TelemetryUpdate) {
        when (update) {
            TelemetryUpdate.Connected -> onEvent(LiveFeedEvent.Opened)
            is TelemetryUpdate.Message -> relay(update.message)
            is TelemetryUpdate.Reconnecting -> Unit
            // ⚠️ Only an end the app did not ask for is read here: [stop] cancels the pump first.
            is TelemetryUpdate.Ended -> running = null
        }
    }

    private fun relay(message: TelemetryMessage) {
        when (message) {
            is TelemetryMessage.WhisperApplied -> onEvent(LiveFeedEvent.Receipt(message.callId, message.data))
            is TelemetryMessage.CallChanged -> onEvent(LiveFeedEvent.CallsChanged)
            else -> Unit
        }
    }
}
