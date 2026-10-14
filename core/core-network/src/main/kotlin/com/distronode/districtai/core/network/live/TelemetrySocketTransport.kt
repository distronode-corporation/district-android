package com.distronode.districtai.core.network.live

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * The one seam between the live transcript and a real WebSocket.
 *
 * What an adapter must do, and the traps in doing it:
 *
 * - [connect] returns only once the handshake completed, with the protocol the server selected.
 *   It throws an [IOException] for a refused handshake or a network failure.
 * - ⛔ IT MUST SURFACE LIVENESS AS [TelemetrySocketFrame.Heartbeat]. A quiet, healthy socket
 *   delivers nothing at all, and the 90 s silence watchdog would drop it every 90 s.
 * - ⛔ IT MUST REPORT THE SERVER'S CLOSE CODE as [TelemetrySocketFrame.Closed], because 4401 and
 *   4403 are the protocol: one means "mint again", the other "stop".
 */
interface TelemetrySocketTransport {
    /** Open [url], offering [subprotocols] in order, and return once the handshake has completed. */
    suspend fun connect(url: String, subprotocols: List<String>): TelemetrySocket
}

/** One open socket. */
interface TelemetrySocket {
    /** The subprotocol the server selected in its handshake response, if any. */
    val selectedSubprotocol: String?

    /**
     * The next frame. After [TelemetrySocketFrame.Closed] the socket is finished and is not read
     * again. ⚠️ An [IOException] means the connection broke.
     */
    suspend fun receive(): TelemetrySocketFrame

    /** Send one text message. An [IOException] means the connection broke; the read side reports it. */
    suspend fun send(text: String)

    /** Close with a normal closure (1000) and stop reading. ⚠️ Idempotent. */
    fun close()
}

/**
 * [TelemetrySocketTransport] over OkHttp's WebSocket.
 *
 * ⚠️ HOW LIVENESS IS SURFACED. OkHttp answers the server's pings itself and hands neither pings nor
 * pongs to the listener, so a healthy quiet socket is silent. This client therefore pings every
 * [heartbeatMillis] (`pingInterval`), and OkHttp FAILS the socket when a ping goes unanswered
 * until the next one is due. So a [receive] that waited [heartbeatMillis] without a frame and
 * without a failure is proof the socket is alive, and answers [TelemetrySocketFrame.Heartbeat].
 * A dead socket fails within two intervals, well inside the 90 s watchdog.
 *
 * ⛔ A CLONE OF THE APP'S ONE CLIENT, sharing its pool and dispatcher, with the call timeout off:
 * a socket is a call that never ends, and the shared 30 s budget would cut every one of them.
 */
class OkHttpTelemetryTransport(
    client: OkHttpClient,
    private val heartbeatMillis: Long = DEFAULT_HEARTBEAT_MILLIS,
) : TelemetrySocketTransport {

    private val socketClient: OkHttpClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(heartbeatMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun connect(url: String, subprotocols: List<String>): TelemetrySocket =
        suspendCancellableCoroutine { continuation ->
            val socket = OkHttpTelemetrySocket(heartbeatMillis)
            val request = Request.Builder()
                .url(url)
                .header(SUBPROTOCOL_HEADER, subprotocols.joinToString(", "))
                .build()
            val webSocket = socketClient.newWebSocket(
                request,
                socket.Listener(
                    onOpen = { continuation.resume(socket) },
                    onOpenFailed = { continuation.resumeWithException(it) },
                ),
            )
            continuation.invokeOnCancellation { webSocket.cancel() }
        }

    companion object {
        /** The server pings every 30 s; this client pings as often. */
        const val DEFAULT_HEARTBEAT_MILLIS: Long = 30_000
        internal const val SUBPROTOCOL_HEADER = "Sec-WebSocket-Protocol"
        internal const val NORMAL_CLOSURE = 1000
    }
}

/** One OkHttp socket, its frames queued as they arrive on OkHttp's thread. */
private class OkHttpTelemetrySocket(private val heartbeatMillis: Long) : TelemetrySocket {
    private val frames = Channel<TelemetrySocketFrame>(Channel.UNLIMITED)

    /**
     * ⛔ SET IN `onOpen`, FROM THE LISTENER'S OWN ARGUMENT, NOT FROM `newWebSocket`'s RETURN. OkHttp
     * may call `onOpen` on its thread before `newWebSocket` has returned on this one, and the socket
     * escapes to its reader the moment `onOpen` resumes [OkHttpTelemetryTransport.connect].
     */
    private lateinit var webSocket: WebSocket

    @Volatile
    override var selectedSubprotocol: String? = null
        private set

    override suspend fun receive(): TelemetrySocketFrame =
        withTimeoutOrNull(heartbeatMillis) { frames.receive() } ?: TelemetrySocketFrame.Heartbeat

    override suspend fun send(text: String) {
        // ⚠️ `send` answers false once the socket is closing or its queue is full: either way the
        // connection is gone, and the read side says so.
        if (!webSocket.send(text)) throw IOException("the telemetry socket is closed")
    }

    override fun close() {
        webSocket.close(OkHttpTelemetryTransport.NORMAL_CLOSURE, null)
        frames.close()
    }

    inner class Listener(
        private val onOpen: () -> Unit,
        private val onOpenFailed: (IOException) -> Unit,
    ) : WebSocketListener() {
        private var opened = false

        override fun onOpen(webSocket: WebSocket, response: Response) {
            this@OkHttpTelemetrySocket.webSocket = webSocket
            selectedSubprotocol = response.header(OkHttpTelemetryTransport.SUBPROTOCOL_HEADER)
            opened = true
            onOpen()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.trySend(TelemetrySocketFrame.Text(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            frames.trySend(TelemetrySocketFrame.Text(bytes.utf8()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            frames.trySend(TelemetrySocketFrame.Closed(code, reason))
            webSocket.close(OkHttpTelemetryTransport.NORMAL_CLOSURE, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val failure = IOException(t.message, t)
            if (opened) frames.close(failure) else onOpenFailed(failure)
        }
    }
}
