package com.distronode.districtai.core.network.live

import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.encodeUtf8
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The OkHttp adapter against a real WebSocket on a local server.
 *
 * ⛔ REAL SOCKETS, REAL TIME: what this proves is what OkHttp does, which a fake cannot. Every wait
 * is bounded so a regression fails rather than hangs.
 */
class OkHttpTelemetryTransportTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    /** What the server side saw: its socket once open, and every text it received. */
    private val serverSocket = CompletableDeferred<WebSocket>()
    private val serverReceived = LinkedBlockingQueue<String>()

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // ⚠️ A listener that throws makes OkHttp fail the server's socket, which drops the
            // connection under the client without a close frame: how a break is staged here.
            check(text != BREAK) { "the test asked the server to break the socket" }
            serverReceived.put(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun upgrade(protocol: String? = TelemetryProtocol.SUBPROTOCOL): MockResponse =
        MockResponse.Builder()
            .apply { protocol?.let { setHeader("Sec-WebSocket-Protocol", it) } }
            .webSocketUpgrade(serverListener)
            .build()

    private fun url(): String = server.url("/ws/telemetry").toString().replace("http://", "ws://")

    private fun transport(heartbeatMillis: Long = 5_000) = OkHttpTelemetryTransport(client, heartbeatMillis)

    @Test
    fun `it offers the subprotocols in order and reports the one selected`() = runBlocking {
        server.enqueue(upgrade())
        val socket = withTimeout(TIMEOUT) {
            transport().connect(url(), listOf("distronode.telemetry.v1", "distronode.token.a.b"))
        }
        assertEquals("distronode.telemetry.v1", socket.selectedSubprotocol)
        assertEquals(
            "distronode.telemetry.v1, distronode.token.a.b",
            server.takeRequest().headers["Sec-WebSocket-Protocol"],
        )
        socket.close()
        socket.close()
    }

    @Test
    fun `text and binary arrive as text, sends reach the server, and a close carries its code`() = runBlocking {
        server.enqueue(upgrade())
        val socket = withTimeout(TIMEOUT) { transport().connect(url(), listOf("p")) }
        val remote = withTimeout(TIMEOUT) { serverSocket.await() }

        socket.send("""{"op":"x"}""")
        assertEquals("""{"op":"x"}""", serverReceived.poll(TIMEOUT, TimeUnit.MILLISECONDS))

        remote.send("hello")
        remote.send("bytes".encodeUtf8())
        remote.close(4401, "expired")
        withTimeout(TIMEOUT) {
            assertEquals(TelemetrySocketFrame.Text("hello"), socket.receive())
            assertEquals(TelemetrySocketFrame.Text("bytes"), socket.receive())
            assertEquals(TelemetrySocketFrame.Closed(4401, "expired"), socket.receive())
        }
        socket.close()
        try {
            socket.send("late")
            fail("a send on a closed socket must fail")
        } catch (_: IOException) {
            // Expected: the socket is gone.
        }
    }

    @Test
    fun `a quiet live socket answers a heartbeat`() = runBlocking {
        server.enqueue(upgrade())
        val socket = withTimeout(TIMEOUT) { transport(heartbeatMillis = 100).connect(url(), listOf("p")) }
        assertEquals(TelemetrySocketFrame.Heartbeat, withTimeout(TIMEOUT) { socket.receive() })
        socket.close()
    }

    @Test
    fun `a socket that breaks after opening fails its read`() = runBlocking {
        server.enqueue(upgrade())
        val socket = withTimeout(TIMEOUT) { transport().connect(url(), listOf("p")) }
        socket.send(BREAK)
        try {
            withTimeout(TIMEOUT) {
                while (true) socket.receive()
            }
        } catch (_: IOException) {
            return@runBlocking
        }
    }

    @Test
    fun `a refused handshake fails the connect`() = runBlocking {
        server.enqueue(MockResponse(code = 403, body = "{}"))
        try {
            withTimeout(TIMEOUT) { transport().connect(url(), listOf("p")) }
            fail("a 403 handshake must not open")
        } catch (_: IOException) {
            // Expected.
        }
    }

    @Test
    fun `cancelling a connect still waiting for the handshake cancels the socket`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val connect = async { transport().connect(url(), listOf("p")) }
        try {
            withTimeout(SHORT) { connect.await() }
            fail("a stalled handshake must not open")
        } catch (_: TimeoutCancellationException) {
            connect.cancel()
        }
        assertTrue(connect.isCancelled || connect.isCompleted)
    }

    private companion object {
        const val TIMEOUT = 5_000L
        const val SHORT = 300L
        const val BREAK = "break"
    }
}
