package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.network.TelemetryApi
import java.io.IOException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** The time, for the connection's rules and waits. A test passes the virtual clock. */
interface LiveClock {
    /** Epoch milliseconds. */
    fun nowMillis(): Long

    /** Wait [millis]. */
    suspend fun sleep(millis: Long)
}

/** The wall clock and `delay`. */
object SystemLiveClock : LiveClock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override suspend fun sleep(millis: Long) = delay(millis)
}

/**
 * What a [TelemetryConnectionRunner] is made of, so a test can replace each part.
 *
 * @param jitter a number between 0 and 1 for each backoff wait: random in the app, a constant in a
 *   test.
 */
class LiveDependencies(
    val minter: TelemetryApi,
    val transport: TelemetrySocketTransport,
    val clock: LiveClock = SystemLiveClock,
    val jitter: () -> Double = { Random.nextDouble() },
)

/**
 * Runs one [TelemetryConnection] over a real socket and a real clock, in [scope].
 *
 * ⛔ IT DECIDES NOTHING. Every branch about the protocol is in the state machine; this performs its
 * commands (mint, open, read, close, wait) and feeds each result back as an event, tagged with the
 * attempt the command was issued for. A decision added here is a decision tested only through
 * timing.
 *
 * ⚠️ ONE LOOP APPLIES EVERY EVENT, in arrival order, so the machine and the socket table are only
 * ever touched from it. Results from the network arrive through [inputs] like everything else.
 *
 * ⚠️ [updates] is unbounded so a slow reader never delays the socket. Cancelling [scope] closes
 * every socket the runner holds.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED: one function per command it performs.
 */
@Suppress("TooManyFunctions")
class TelemetryConnectionRunner(
    workspaceId: String,
    broadcast: Boolean,
    private val dependencies: LiveDependencies,
    private val scope: CoroutineScope,
) {
    private val clock = dependencies.clock
    private val machine = TelemetryConnection(workspaceId, broadcast)
    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val updateChannel = Channel<TelemetryUpdate>(Channel.UNLIMITED)
    private val sockets = mutableMapOf<Int, TelemetrySocket>()
    private val readers = mutableMapOf<Int, Job>()
    private val timers = mutableMapOf<TelemetryTimer, Job>()

    /** The last send issued on each attempt's socket, which the next one waits for. */
    private val sends = mutableMapOf<Int, Job>()

    /** Every update the connection reports, ending after [TelemetryUpdate.Ended]. */
    val updates: Flow<TelemetryUpdate> = updateChannel.receiveAsFlow()

    /** Where the connection is. */
    val phase: TelemetryConnection.Phase get() = machine.phase

    private sealed interface Input {
        data class Event(val event: TelemetryConnectionEvent) : Input

        /** ⚠️ The socket is held before the machine hears of it, so a stale one can be closed. */
        data class SocketOpened(val socket: TelemetrySocket, val attempt: Int) : Input
    }

    /** Start connecting. ⚠️ Once: a stopped runner is not restarted; make a new one. */
    fun start() {
        scope.launch {
            try {
                for (input in inputs) {
                    when (input) {
                        is Input.Event -> apply(input.event)
                        is Input.SocketOpened -> opened(input.socket, input.attempt)
                    }
                }
            } finally {
                release()
            }
        }
        post(TelemetryConnectionEvent.Start)
    }

    /** Stop: close the socket, cancel every wait, and report [TelemetryUpdate.Ended] with no error. */
    fun stop() = post(TelemetryConnectionEvent.Stop)

    /** Receive [callId]'s live transcript, now and after every reconnect. */
    fun subscribeTranscript(callId: String) = post(TelemetryConnectionEvent.SubscribeTranscript(callId))

    /** Stop receiving [callId]'s live transcript. */
    fun unsubscribeTranscript(callId: String) = post(TelemetryConnectionEvent.UnsubscribeTranscript(callId))

    /** Ask again for [callId]'s transcript, for a fresh snapshot. */
    fun resubscribeTranscript(callId: String) = post(TelemetryConnectionEvent.ResubscribeTranscript(callId))

    private fun post(event: TelemetryConnectionEvent) {
        inputs.trySend(Input.Event(event))
    }

    private fun apply(event: TelemetryConnectionEvent) {
        machine.handle(event, clock.nowMillis(), dependencies.jitter()).forEach(::perform)
    }

    private fun perform(command: TelemetryConnectionCommand) {
        when (command) {
            is TelemetryConnectionCommand.Mint -> scope.launch {
                val result = dependencies.minter.telemetryToken(machine.workspaceId)
                inputs.trySend(Input.Event(TelemetryConnectionEvent.Minted(result, command.attempt)))
            }
            is TelemetryConnectionCommand.Open -> open(command)
            is TelemetryConnectionCommand.Close -> close(command.attempt)
            is TelemetryConnectionCommand.Send -> send(command)
            is TelemetryConnectionCommand.Schedule -> {
                timers.remove(command.timer)?.cancel()
                timers[command.timer] = scope.launch {
                    clock.sleep(command.afterMillis)
                    inputs.trySend(Input.Event(TelemetryConnectionEvent.TimerFired(command.timer, command.attempt)))
                }
            }
            TelemetryConnectionCommand.CancelTimers -> cancelTimers()
            is TelemetryConnectionCommand.Emit -> updateChannel.trySend(command.update)
            TelemetryConnectionCommand.Finish -> {
                updateChannel.close()
                inputs.close()
            }
        }
    }

    private fun open(command: TelemetryConnectionCommand.Open) {
        scope.launch {
            val input = try {
                Input.SocketOpened(dependencies.transport.connect(command.url, command.subprotocols), command.attempt)
            } catch (failure: IOException) {
                Input.Event(TelemetryConnectionEvent.OpenFailed(failure.message.orEmpty(), command.attempt))
            }
            // ⛔ A SOCKET THAT OPENED AFTER THE RUNNER FINISHED HAS NOBODY TO CLOSE IT: close it here.
            if (inputs.trySend(input).isFailure && input is Input.SocketOpened) input.socket.close()
        }
    }

    private fun opened(socket: TelemetrySocket, attempt: Int) {
        sockets[attempt] = socket
        apply(TelemetryConnectionEvent.Opened(socket.selectedSubprotocol, attempt))
        if (sockets[attempt] == null) return
        readers[attempt] = scope.launch { read(socket, attempt) }
    }

    private suspend fun read(socket: TelemetrySocket, attempt: Int) {
        try {
            while (true) {
                val frame = socket.receive()
                inputs.trySend(Input.Event(TelemetryConnectionEvent.Received(frame, attempt)))
                if (frame is TelemetrySocketFrame.Closed) return
            }
        } catch (failure: IOException) {
            inputs.trySend(Input.Event(TelemetryConnectionEvent.Broken(failure.message.orEmpty(), attempt)))
        }
        // ⚠️ A SOCKET CLOSED FROM THIS SIDE ENDS ITS READ BY CANCELLATION: [close] and [release]
        // cancel the reader before they close the socket, so its `receive` never sees the close.
    }

    /**
     * ⚠️ A CLOSE DOES NOT WAIT FOR SENDS STILL PENDING on the socket, which then fail on a closed
     * socket. Nothing is lost: the server keeps a socket's ops per socket and drops them with it.
     */
    private fun close(attempt: Int) {
        sends.remove(attempt)
        readers.remove(attempt)?.cancel()
        sockets.remove(attempt)?.close()
    }

    /**
     * ⛔ CHAINED, NOT FIRED: two coroutines started one after the other may run in either order,
     * and `socket.mode` must reach the server before the subscriptions. A failed send is not
     * reported here; the broken socket's read reports it.
     */
    private fun send(command: TelemetryConnectionCommand.Send) {
        // ⚠️ THE MACHINE SENDS ONLY WHILE OPEN, which only [opened] makes true after holding the
        // socket, and every close removes both together: the socket is always here.
        val socket = sockets.getValue(command.attempt)
        val previous = sends[command.attempt]
        sends[command.attempt] = scope.launch {
            previous?.join()
            try {
                socket.send(command.text)
            } catch (_: IOException) {
                // The read side reports the broken socket.
            }
        }
    }

    private fun cancelTimers() {
        timers.values.forEach(Job::cancel)
        timers.clear()
    }

    /** When the loop ends, for any reason: nothing the runner opened may outlive it. */
    private fun release() {
        cancelTimers()
        readers.values.forEach(Job::cancel)
        readers.clear()
        sockets.values.forEach(TelemetrySocket::close)
        sockets.clear()
        updateChannel.close()
    }
}
