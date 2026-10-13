package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.model.TelemetryTokenResponse
import com.distronode.districtai.core.network.ApiResult

/**
 * One workspace's live telemetry connection, as a pure state machine.
 *
 * ⛔ NO SOCKET, NO TIMER AND NO NETWORK CALL IN HERE, ONLY EVENTS IN AND COMMANDS OUT. Every rule
 * of the protocol is an assertion about a command list, tested with an injected time;
 * [TelemetryConnectionRunner] performs the commands over a [TelemetrySocketTransport].
 *
 * What it does (ported from district-core-swift's `TelemetryConnection`):
 *
 * - Mints a credential, opens the socket with it, and reports [TelemetryUpdate.Connected].
 * - A minute before the credential expires it closes the socket and reconnects at once on a fresh
 *   credential, because the server closes a socket whose credential expired.
 * - 4401: mints a new credential and reconnects. 4403: stops.
 * - Any other close, a broken socket, 90 s of silence, a 15 s handshake, or a mint that failed for
 *   a reason that may pass: reconnects after a jittered backoff that doubles per consecutive
 *   failure, capped at 60 s. A socket that stayed open 30 s resets the count.
 * - ⛔ EVERY OPEN RE-SENDS THE CONNECTION'S OPS, because the server keeps them per socket:
 *   `socket.mode` first when [broadcast] is false, then a `transcript.subscribe` for every
 *   subscribed call, in call-id order.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED because each transition is one small function, which is what
 * keeps every rule readable beside its test. Splitting the class would split one state.
 */
@Suppress("TooManyFunctions")
class TelemetryConnection(
    val workspaceId: String,
    /** Whether this socket takes the workspace-wide relay. ⚠️ The phone says false. */
    val broadcast: Boolean = true,
) {
    /** Where the connection is. */
    sealed interface Phase {
        data object Idle : Phase
        data object Minting : Phase

        /** The handshake is under way on a credential expiring at [expiresAt]. */
        data class Connecting(val expiresAt: Long) : Phase
        data class Open(val openedAt: Long, val lastHeardAt: Long) : Phase
        data object Waiting : Phase
        data object Ended : Phase
    }

    private val subscriptions = sortedSetOf<String>()

    /** The calls whose live transcript this connection receives. */
    val transcriptSubscriptions: Set<String> get() = subscriptions.toSet()

    var phase: Phase = Phase.Idle
        private set

    /** The attempt every command is currently issued for. */
    var attempt: Int = 0
        private set

    /** Consecutive failed attempts, for the backoff. */
    var failures: Int = 0
        private set

    /** The credential the last socket used, kept while it has time left. */
    private var token: TelemetryTokenResponse? = null

    /**
     * Apply one [event] at [now] (epoch milliseconds). [jitter] (0 to 1) is used by a backoff
     * this event may start, and ignored otherwise.
     *
     * ⚠️ EVERY ASYNCHRONOUS RESULT CARRIES THE ATTEMPT ITS COMMAND WAS ISSUED FOR, and a result for
     * any other attempt is stale and changes nothing.
     */
    fun handle(event: TelemetryConnectionEvent, now: Long, jitter: Double): List<TelemetryConnectionCommand> =
        when (event) {
            is TelemetryConnectionEvent.Lifecycle -> lifecycle(event, now, jitter)
            is TelemetryConnectionEvent.TranscriptOp -> transcriptOp(event)
        }

    private fun lifecycle(
        event: TelemetryConnectionEvent.Lifecycle,
        now: Long,
        jitter: Double,
    ): List<TelemetryConnectionCommand> = when (event) {
        is TelemetryConnectionEvent.Start -> if (phase == Phase.Idle) beginAttempt(now) else emptyList()
        is TelemetryConnectionEvent.Stop -> stop()
        is TelemetryConnectionEvent.Minted -> minted(event, jitter)
        is TelemetryConnectionEvent.Opened -> opened(event, now)
        is TelemetryConnectionEvent.OpenFailed ->
            if (event.attempt == attempt && phase is Phase.Connecting) {
                retry(TelemetryDisconnect.Network(event.reason), stable = false, jitter = jitter)
            } else {
                emptyList()
            }
        is TelemetryConnectionEvent.Received -> received(event, now, jitter)
        is TelemetryConnectionEvent.Broken -> broken(event, now, jitter)
        is TelemetryConnectionEvent.TimerFired ->
            if (event.attempt == attempt) timerFired(event.timer, now, jitter) else emptyList()
    }

    // ── Transcript ops ──────────────────────────────────────────────────────────────────────────

    private fun transcriptOp(event: TelemetryConnectionEvent.TranscriptOp): List<TelemetryConnectionCommand> =
        when (event) {
            is TelemetryConnectionEvent.SubscribeTranscript -> subscribe(event.callId)
            is TelemetryConnectionEvent.UnsubscribeTranscript ->
                if (subscriptions.remove(event.callId)) {
                    sendIfOpen(TranscriptClientOp.Unsubscribe(event.callId))
                } else {
                    emptyList()
                }
            is TelemetryConnectionEvent.ResubscribeTranscript ->
                if (event.callId in subscriptions) {
                    sendIfOpen(TranscriptClientOp.Subscribe(event.callId))
                } else {
                    emptyList()
                }
        }

    /** ⚠️ A call id the server would refuse is not recorded, so it is never re-sent on every open. */
    private fun subscribe(callId: String): List<TelemetryConnectionCommand> {
        if (!TranscriptClientOp.isValidCallId(callId) || !subscriptions.add(callId)) return emptyList()
        return sendIfOpen(TranscriptClientOp.Subscribe(callId))
    }

    /** While closed nothing is sent: the next open sends every subscription. */
    private fun sendIfOpen(op: TranscriptClientOp): List<TelemetryConnectionCommand> =
        if (phase is Phase.Open) send(op) else emptyList()

    /**
     * ⚠️ THE `!!` IS SAFE: an op's text is null only for an invalid call id, and none reaches here
     * ([subscribe] refuses one before it is recorded).
     */
    private fun send(op: TranscriptClientOp): List<TelemetryConnectionCommand> =
        listOf(TelemetryConnectionCommand.Send(op.text!!, attempt))

    private fun opsOnOpen(): List<TelemetryConnectionCommand> {
        val mode = if (broadcast) emptyList() else send(TranscriptClientOp.SocketMode(broadcast = false))
        return mode + subscriptions.flatMap { send(TranscriptClientOp.Subscribe(it)) }
    }

    // ── Transitions ─────────────────────────────────────────────────────────────────────────────

    /** A new attempt: reuse the credential while it has more than the renewal lead left, else mint. */
    private fun beginAttempt(now: Long): List<TelemetryConnectionCommand> {
        attempt += 1
        val held = token
        if (held != null && held.expiresAt - now > TelemetryProtocol.RENEWAL_LEAD_MILLIS) return open(held)
        token = null
        phase = Phase.Minting
        return listOf(TelemetryConnectionCommand.Mint(attempt))
    }

    private fun minted(event: TelemetryConnectionEvent.Minted, jitter: Double): List<TelemetryConnectionCommand> {
        if (event.attempt != attempt || phase != Phase.Minting) return emptyList()
        return when (val result = event.result) {
            is ApiResult.Success -> {
                val minted = result.value
                // ⚠️ `success: false` in a 2xx is as unusable as a token with characters a header
                // cannot carry: neither is fixed by asking again.
                if (!minted.success || !TelemetryProtocol.isPresentable(minted.token)) {
                    end(TelemetryLiveError.InvalidGrant)
                } else {
                    token = minted
                    open(minted)
                }
            }
            is ApiResult.Failure ->
                if (isTransient(result)) {
                    retry(TelemetryDisconnect.Mint(result), stable = false, jitter = jitter)
                } else {
                    end(TelemetryLiveError.Mint(result))
                }
        }
    }

    private fun open(token: TelemetryTokenResponse): List<TelemetryConnectionCommand> =
        when (val url = TelemetryProtocol.socketUrl(token.wsUrl, workspaceId)) {
            is SocketUrl.Refused -> end(TelemetryLiveError.Endpoint(url.error))
            is SocketUrl.Accepted -> {
                phase = Phase.Connecting(expiresAt = token.expiresAt)
                listOf(
                    TelemetryConnectionCommand.Open(
                        url = url.url,
                        subprotocols = TelemetryProtocol.offeredSubprotocols(token.token),
                        attempt = attempt,
                    ),
                    TelemetryConnectionCommand.Schedule(
                        TelemetryTimer.CONNECT,
                        TelemetryProtocol.CONNECT_TIMEOUT_MILLIS,
                        attempt,
                    ),
                )
            }
        }

    /**
     * ⛔ A STALE SOCKET IS CLOSED, NOT IGNORED. The driver already holds it; a handshake that
     * finished after its timeout or after a stop would otherwise stay open with nothing reading it.
     */
    private fun opened(event: TelemetryConnectionEvent.Opened, now: Long): List<TelemetryConnectionCommand> {
        val connecting = phase as? Phase.Connecting
        if (event.attempt != attempt || connecting == null) {
            return listOf(TelemetryConnectionCommand.Close(event.attempt))
        }
        if (event.selectedSubprotocol != TelemetryProtocol.SUBPROTOCOL) {
            return listOf(TelemetryConnectionCommand.Close(attempt)) + end(TelemetryLiveError.ProtocolMismatch)
        }
        phase = Phase.Open(openedAt = now, lastHeardAt = now)
        return listOf(
            TelemetryConnectionCommand.Emit(TelemetryUpdate.Connected),
            TelemetryConnectionCommand.Schedule(
                TelemetryTimer.RENEWAL,
                TelemetryProtocol.renewalDelayMillis(connecting.expiresAt, now),
                attempt,
            ),
            TelemetryConnectionCommand.Schedule(
                TelemetryTimer.SILENCE,
                TelemetryProtocol.SILENCE_LIMIT_MILLIS,
                attempt,
            ),
        ) + opsOnOpen()
    }

    private fun received(
        event: TelemetryConnectionEvent.Received,
        now: Long,
        jitter: Double,
    ): List<TelemetryConnectionCommand> {
        val open = phase as? Phase.Open
        if (event.attempt != attempt || open == null) return emptyList()
        phase = open.copy(lastHeardAt = now)
        return when (val frame = event.frame) {
            is TelemetrySocketFrame.Text -> {
                val message = TelemetryMessages.read(frame.text, workspaceId)
                listOf(TelemetryConnectionCommand.Emit(TelemetryUpdate.Message(message)))
            }
            TelemetrySocketFrame.Heartbeat -> emptyList()
            is TelemetrySocketFrame.Closed -> closed(frame, stable = isStable(open.openedAt, now), jitter = jitter)
        }
    }

    private fun broken(
        event: TelemetryConnectionEvent.Broken,
        now: Long,
        jitter: Double,
    ): List<TelemetryConnectionCommand> {
        val open = phase as? Phase.Open
        if (event.attempt != attempt || open == null) return emptyList()
        val stable = isStable(open.openedAt, now)
        return retry(TelemetryDisconnect.Network(event.reason), stable = stable, jitter = jitter)
    }

    private fun closed(
        frame: TelemetrySocketFrame.Closed,
        stable: Boolean,
        jitter: Double,
    ): List<TelemetryConnectionCommand> {
        val close = listOf(TelemetryConnectionCommand.Close(attempt))
        return when (frame.code) {
            TelemetryProtocol.CLOSE_UNAUTHORIZED -> {
                token = null
                close + retry(TelemetryDisconnect.Unauthorized(frame.reason), stable, jitter)
            }
            TelemetryProtocol.CLOSE_FORBIDDEN -> close + end(TelemetryLiveError.Forbidden(frame.reason))
            else -> close + retry(TelemetryDisconnect.Closed(frame.code, frame.reason), stable, jitter)
        }
    }

    private fun timerFired(timer: TelemetryTimer, now: Long, jitter: Double): List<TelemetryConnectionCommand> {
        val current = phase
        val close = listOf(TelemetryConnectionCommand.Close(attempt))
        return when {
            timer == TelemetryTimer.CONNECT && current is Phase.Connecting ->
                close + retry(TelemetryDisconnect.ConnectTimedOut, stable = false, jitter = jitter)
            timer == TelemetryTimer.RENEWAL && current is Phase.Open -> {
                token = null
                close + retry(TelemetryDisconnect.Renewal, stable = isStable(current.openedAt, now), jitter = jitter)
            }
            timer == TelemetryTimer.SILENCE && current is Phase.Open -> silenceCheck(current, now, jitter)
            timer == TelemetryTimer.BACKOFF && current == Phase.Waiting -> beginAttempt(now)
            // ⚠️ A TIMER FOR ANOTHER PHASE OF THE SAME ATTEMPT (the handshake's deadline after it
            // opened, say). A cancelled timer is a promise; the phase is the fact.
            else -> emptyList()
        }
    }

    /**
     * ⚠️ NOT A RESET PER MESSAGE: the watchdog re-checks the fact when it fires and re-arms for what
     * is left, so a busy socket costs no timer churn.
     */
    private fun silenceCheck(open: Phase.Open, now: Long, jitter: Double): List<TelemetryConnectionCommand> {
        val quietFor = now - open.lastHeardAt
        if (quietFor < TelemetryProtocol.SILENCE_LIMIT_MILLIS) {
            val left = TelemetryProtocol.SILENCE_LIMIT_MILLIS - quietFor
            return listOf(TelemetryConnectionCommand.Schedule(TelemetryTimer.SILENCE, left, attempt))
        }
        return listOf(TelemetryConnectionCommand.Close(attempt)) +
            retry(TelemetryDisconnect.Silent, stable = isStable(open.openedAt, now), jitter = jitter)
    }

    /** A reconnect: a renewal after an established socket goes again at once; anything else backs off. */
    private fun retry(cause: TelemetryDisconnect, stable: Boolean, jitter: Double): List<TelemetryConnectionCommand> {
        if (stable) failures = 0
        var delay = 0L
        if (!(stable && cause == TelemetryDisconnect.Renewal)) {
            failures += 1
            delay = TelemetryProtocol.backoffMillis(failures, jitter)
        }
        phase = Phase.Waiting
        return listOf(
            TelemetryConnectionCommand.Emit(TelemetryUpdate.Reconnecting(delay, cause)),
            TelemetryConnectionCommand.Schedule(TelemetryTimer.BACKOFF, delay, attempt),
        )
    }

    private fun stop(): List<TelemetryConnectionCommand> = when (phase) {
        Phase.Ended -> emptyList()
        is Phase.Connecting, is Phase.Open -> listOf(TelemetryConnectionCommand.Close(attempt)) + end(null)
        Phase.Idle, Phase.Minting, Phase.Waiting -> end(null)
    }

    private fun end(error: TelemetryLiveError?): List<TelemetryConnectionCommand> {
        phase = Phase.Ended
        return listOf(
            TelemetryConnectionCommand.CancelTimers,
            TelemetryConnectionCommand.Emit(TelemetryUpdate.Ended(error)),
            TelemetryConnectionCommand.Finish,
        )
    }

    private fun isStable(openedAt: Long, now: Long): Boolean =
        now - openedAt >= TelemetryProtocol.STABLE_AFTER_MILLIS

    companion object {
        private const val SERVER_ERROR_FLOOR = 500

        /**
         * Whether a failed read may succeed if tried again later: no answer, a 5xx, a 429, or the
         * regional databases not answering.
         *
         * ⚠️ A 401 IS FINAL HERE, UNLIKE ON THE APPLE CLIENT: this client's API layer already
         * refreshed and re-sent before answering [ApiResult.Unauthorized], so it means the session
         * has ended, which the app's sign-in flow handles.
         */
        fun isTransient(failure: ApiResult.Failure): Boolean = when (failure) {
            is ApiResult.NetworkFailure, is ApiResult.RateLimited, is ApiResult.RegionsDegraded -> true
            is ApiResult.HttpFailure -> failure.status >= SERVER_ERROR_FLOOR
            is ApiResult.Unauthorized,
            is ApiResult.Forbidden,
            is ApiResult.NotFound,
            is ApiResult.DecodeFailure,
            -> false
        }
    }
}

/** What a connection reports to the app. */
sealed interface TelemetryUpdate {
    /**
     * The socket is open. ⛔ EVERY `Connected` AFTER THE FIRST FOLLOWS A GAP IN WHICH EVENTS MAY
     * HAVE BEEN MISSED: read the state again on each one.
     */
    data object Connected : TelemetryUpdate

    /** A message from the socket, already decoded for this workspace. */
    data class Message(val message: TelemetryMessage) : TelemetryUpdate

    /** The socket closed or could not be opened, and the connection tries again after [delayMillis]. */
    data class Reconnecting(val delayMillis: Long, val cause: TelemetryDisconnect) : TelemetryUpdate

    /** The connection has ended and will not reconnect. Null when the app stopped it. Always last. */
    data class Ended(val error: TelemetryLiveError?) : TelemetryUpdate
}

/** Why a connection is reconnecting. */
sealed interface TelemetryDisconnect {
    /** Routine: the credential was about to expire. Not worth showing. */
    data object Renewal : TelemetryDisconnect

    /** The server refused the credential (4401). A new one is minted first. */
    data class Unauthorized(val reason: String) : TelemetryDisconnect

    /** The server closed the socket with another code. */
    data class Closed(val code: Int, val reason: String) : TelemetryDisconnect

    /** Nothing arrived for [TelemetryProtocol.SILENCE_LIMIT_MILLIS], not even a heartbeat. */
    data object Silent : TelemetryDisconnect

    /** The handshake did not finish within [TelemetryProtocol.CONNECT_TIMEOUT_MILLIS]. */
    data object ConnectTimedOut : TelemetryDisconnect

    /** No connection, a TLS failure, a refused handshake, a socket that broke. */
    data class Network(val reason: String) : TelemetryDisconnect

    /** The credential could not be minted, for a reason that may pass. */
    data class Mint(val failure: ApiResult.Failure) : TelemetryDisconnect
}

/** Why a connection ended for good. ⛔ None of these is fixed by retrying on a timer. */
sealed interface TelemetryLiveError {
    /** The server would not mint a credential. */
    data class Mint(val failure: ApiResult.Failure) : TelemetryLiveError

    /** The server answered with a credential that cannot be presented. */
    data object InvalidGrant : TelemetryLiveError

    /** The socket's address is missing or unusable. */
    data class Endpoint(val error: TelemetryEndpointError) : TelemetryLiveError

    /** The server did not select [TelemetryProtocol.SUBPROTOCOL]. */
    data object ProtocolMismatch : TelemetryLiveError

    /** The server closed the socket with 4403. */
    data class Forbidden(val reason: String) : TelemetryLiveError
}

/** The timers a connection runs. At most one of each is pending. */
enum class TelemetryTimer { CONNECT, RENEWAL, SILENCE, BACKOFF }

/** What a socket can deliver. */
sealed interface TelemetrySocketFrame {
    /** A text message (a binary one is decoded as UTF-8 and delivered as this). */
    data class Text(val text: String) : TelemetrySocketFrame

    /** Proof the socket is alive and carrying nothing. See [TelemetrySocketTransport]. */
    data object Heartbeat : TelemetrySocketFrame

    /** The server closed the socket with [code]. */
    data class Closed(val code: Int, val reason: String) : TelemetrySocketFrame
}

/** Everything that can happen to a connection. */
sealed interface TelemetryConnectionEvent {
    /** The socket's own life: starting, stopping, and the results of its commands. */
    sealed interface Lifecycle : TelemetryConnectionEvent

    /** What the app asks of the transcript subscriptions. */
    sealed interface TranscriptOp : TelemetryConnectionEvent

    data object Start : Lifecycle

    /** The app stopped the connection. */
    data object Stop : Lifecycle
    data class Minted(val result: ApiResult<TelemetryTokenResponse>, val attempt: Int) : Lifecycle
    data class Opened(val selectedSubprotocol: String?, val attempt: Int) : Lifecycle
    data class OpenFailed(val reason: String, val attempt: Int) : Lifecycle
    data class Received(val frame: TelemetrySocketFrame, val attempt: Int) : Lifecycle

    /** Reading the socket failed: the connection broke. */
    data class Broken(val reason: String, val attempt: Int) : Lifecycle
    data class TimerFired(val timer: TelemetryTimer, val attempt: Int) : Lifecycle

    /** Receive [callId]'s live transcript, now and after every reconnect. */
    data class SubscribeTranscript(val callId: String) : TranscriptOp

    /** Stop receiving [callId]'s live transcript. */
    data class UnsubscribeTranscript(val callId: String) : TranscriptOp

    /** Ask again for [callId]'s transcript, which brings a fresh snapshot: how a gap is healed. */
    data class ResubscribeTranscript(val callId: String) : TranscriptOp
}

/** What a connection asks its driver to do. */
sealed interface TelemetryConnectionCommand {
    data class Mint(val attempt: Int) : TelemetryConnectionCommand
    data class Open(val url: String, val subprotocols: List<String>, val attempt: Int) : TelemetryConnectionCommand

    /** Close [attempt]'s socket, if there is one, and stop reading it. */
    data class Close(val attempt: Int) : TelemetryConnectionCommand

    /** Arm [timer], replacing any pending one of the same kind. */
    data class Schedule(
        val timer: TelemetryTimer,
        val afterMillis: Long,
        val attempt: Int,
    ) : TelemetryConnectionCommand
    data object CancelTimers : TelemetryConnectionCommand

    /** Send [text] on [attempt]'s socket, after every send issued before it. */
    data class Send(val text: String, val attempt: Int) : TelemetryConnectionCommand
    data class Emit(val update: TelemetryUpdate) : TelemetryConnectionCommand

    /** The connection has ended; release everything. */
    data object Finish : TelemetryConnectionCommand
}
