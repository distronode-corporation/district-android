package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSocket
import com.distronode.districtai.core.network.testing.FakeTelemetryApi
import com.distronode.districtai.core.network.testing.FakeTransport
import com.distronode.districtai.core.network.testing.VirtualClock
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryConnectionRunnerTest {

    private val minter = FakeTelemetryApi()
    private val transport = FakeTransport()

    private fun TestScope.runner(scope: CoroutineScope = backgroundScope, broadcast: Boolean = false) =
        TelemetryConnectionRunner(
            workspaceId = "ws_1",
            broadcast = broadcast,
            dependencies = LiveDependencies(minter, transport, VirtualClock(this), jitter = { 0.0 }),
            scope = scope,
        )

    /** Collect every update in the background, for reading after the fact. */
    private fun TestScope.collect(runner: TelemetryConnectionRunner): MutableList<TelemetryUpdate> {
        val updates = mutableListOf<TelemetryUpdate>()
        backgroundScope.launch { runner.updates.collect { updates += it } }
        return updates
    }

    @Test
    fun `it mints, opens, sends the ops in order, and relays messages`() = runTest {
        val socket = transport.socket()
        val runner = runner()
        val updates = collect(runner)
        runner.subscribeTranscript(CALL)
        runner.start()
        runCurrent()

        assertEquals(listOf("ws_1"), minter.mints)
        assertEquals(
            "ws://127.0.0.1:9/ws/telemetry?workspaceId=ws_1" to
                listOf("distronode.telemetry.v1", "distronode.token.header.payload.signature"),
            transport.connects.single(),
        )
        assertEquals(
            listOf(
                """{"op":"socket.mode","v":1,"broadcast":false}""",
                """{"op":"transcript.subscribe","v":1,"callId":"call_1"}""",
            ),
            socket.sent,
        )
        assertTrue(runner.phase is TelemetryConnection.Phase.Open)

        socket.frames.send(TelemetrySocketFrame.Text(contract("telemetry-event-transcript-ended.json")))
        runCurrent()
        assertEquals(TelemetryUpdate.Connected, updates[0])
        assertTrue(((updates[1] as TelemetryUpdate.Message).message) is TelemetryMessage.Transcript)

        runner.resubscribeTranscript(CALL)
        runner.unsubscribeTranscript(CALL)
        runCurrent()
        assertEquals(
            listOf(
                """{"op":"transcript.subscribe","v":1,"callId":"call_1"}""",
                """{"op":"transcript.unsubscribe","v":1,"callId":"call_1"}""",
            ),
            socket.sent.takeLast(2),
        )

        runner.stop()
        runCurrent()
        assertTrue(socket.closed)
        assertEquals(TelemetryUpdate.Ended(null), updates.last())
    }

    @Test
    fun `a failed handshake backs off and tries again`() = runTest {
        transport.refuse()
        val socket = transport.socket()
        val runner = runner()
        val updates = collect(runner)
        runner.start()
        runCurrent()
        assertEquals(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Network("refused")), updates.single())
        advanceTimeBy(1_001)
        assertEquals(2, transport.connects.size)
        assertEquals(TelemetryUpdate.Connected, updates.last())
        assertTrue(!socket.closed)
    }

    @Test
    fun `a socket that breaks or closes is replaced`() = runTest {
        val first = transport.socket()
        val second = transport.socket()
        val runner = runner()
        val updates = collect(runner)
        runner.start()
        runCurrent()
        first.frames.close(IOException("reset"))
        runCurrent()
        assertEquals(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.Network("reset")), updates.last())
        assertTrue(first.closed.not())
        advanceTimeBy(1_001)
        second.frames.send(TelemetrySocketFrame.Closed(1001, "bye"))
        runCurrent()
        assertTrue(second.closed)
        assertEquals(TelemetryUpdate.Reconnecting(2_000, TelemetryDisconnect.Closed(1001, "bye")), updates.last())
    }

    @Test
    fun `a handshake that times out is abandoned, and its late socket is closed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val late = FakeSocket()
        transport.answers += {
            gate.await()
            late
        }
        transport.socket()
        val runner = runner()
        val updates = collect(runner)
        runner.start()
        advanceTimeBy(15_001)
        assertEquals(TelemetryUpdate.Reconnecting(1_000, TelemetryDisconnect.ConnectTimedOut), updates.single())
        advanceTimeBy(1_000)
        assertEquals(TelemetryUpdate.Connected, updates.last())
        gate.complete(Unit)
        runCurrent()
        assertTrue(late.closed)
    }

    @Test
    fun `a socket that opens after the runner finished is closed by the runner`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val late = FakeSocket()
        transport.answers += {
            gate.await()
            late
        }
        val runner = runner()
        runner.start()
        runCurrent()
        runner.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertTrue(late.closed)
    }

    @Test
    fun `a server that selects another protocol is closed and the connection ends`() = runTest {
        val socket = transport.socket(FakeSocket(selectedSubprotocol = null))
        val runner = runner()
        val updates = collect(runner)
        runner.start()
        runCurrent()
        assertTrue(socket.closed)
        assertEquals(TelemetryUpdate.Ended(TelemetryLiveError.ProtocolMismatch), updates.last())
    }

    @Test
    fun `a mint that fails for good ends the connection`() = runTest {
        minter.results += ApiResult.Forbidden("no")
        val runner = runner()
        val updates = runner.updates
        runner.start()
        assertEquals(
            listOf(TelemetryUpdate.Ended(TelemetryLiveError.Mint(ApiResult.Forbidden("no")))),
            updates.toList()
        )
    }

    @Test
    fun `a failed send is left for the read side to report`() = runTest {
        val socket = transport.socket()
        socket.failSends = true
        val runner = runner()
        runner.subscribeTranscript(CALL)
        runner.start()
        runCurrent()
        assertEquals(emptyList<String>(), socket.sent)
        assertTrue(runner.phase is TelemetryConnection.Phase.Open)
    }

    @Test
    fun `the renewal replaces the socket on a fresh credential`() = runTest {
        val first = transport.socket()
        transport.socket()
        // The first credential expires at 900 s; the next one (the fallback) far later.
        minter.results += ApiResult.Success((minter.fallback as ApiResult.Success).value.copy(expiresAt = 900_000))
        val runner = runner()
        runner.start()
        runCurrent()
        // A heartbeat every 80 s keeps the silence watchdog quiet past the renewal at 840 s.
        repeat(11) {
            advanceTimeBy(80_000)
            first.frames.trySend(TelemetrySocketFrame.Heartbeat)
        }
        assertTrue(first.closed)
        assertEquals(2, minter.mints.size)
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `cancelling the scope closes every socket the runner holds`() = runTest {
        val socket = transport.socket()
        val scope = CoroutineScope(coroutineContext + Job())
        val runner = runner(scope = scope)
        runner.start()
        runCurrent()
        scope.cancel()
        runCurrent()
        assertTrue(socket.closed)
    }
}
