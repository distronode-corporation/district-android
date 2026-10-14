package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.network.live.LiveClock
import com.distronode.districtai.core.network.live.TelemetryProtocol
import com.distronode.districtai.core.network.live.TelemetrySocket
import com.distronode.districtai.core.network.live.TelemetrySocketFrame
import com.distronode.districtai.core.network.live.TelemetrySocketTransport
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope

/** A socket a test drives: it hands out [frames] and records what was sent and whether it closed. */
class FakeSocket(override val selectedSubprotocol: String? = TelemetryProtocol.SUBPROTOCOL) : TelemetrySocket {
    val frames = Channel<TelemetrySocketFrame>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var closed = false
        private set

    /** When set, [send] fails as a closed socket's would. */
    var failSends = false

    override suspend fun receive(): TelemetrySocketFrame = frames.receive()

    override suspend fun send(text: String) {
        if (failSends) throw IOException("closed")
        sent += text
    }

    override fun close() {
        closed = true
        frames.close()
    }
}

/** A transport that answers each connect from a queue: a socket, a failure, or a wait. */
class FakeTransport : TelemetrySocketTransport {
    val answers = ArrayDeque<suspend () -> TelemetrySocket>()
    val connects = mutableListOf<Pair<String, List<String>>>()

    /** Queue [socket] as the next connect's answer, and hand it back for the test to drive. */
    fun socket(socket: FakeSocket = FakeSocket()): FakeSocket = socket.also { answers += { it } }

    /** Queue a refused handshake as the next connect's answer. */
    fun refuse(reason: String = "refused") {
        answers += { throw IOException(reason) }
    }

    override suspend fun connect(url: String, subprotocols: List<String>): TelemetrySocket {
        connects += url to subprotocols
        return answers.removeFirst().invoke()
    }
}

/** The test scheduler's virtual time, as the connection's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClock(private val scope: TestScope) : LiveClock {
    override fun nowMillis(): Long = scope.testScheduler.currentTime

    override suspend fun sleep(millis: Long) = delay(millis)
}
