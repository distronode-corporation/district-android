package com.distronode.districtai.ui.calls

import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_CALL_ENDED
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_NOT_LIVE
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_RATE_LIMITED
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.live.FinalTranscriptState
import com.distronode.districtai.core.network.live.LiveDependencies
import com.distronode.districtai.core.network.live.LiveTranscriptPhase
import com.distronode.districtai.core.network.live.TelemetrySocketFrame
import com.distronode.districtai.core.network.testing.FakeTelemetryApi
import com.distronode.districtai.core.network.testing.FakeTransport
import com.distronode.districtai.core.network.testing.VirtualClock
import com.distronode.districtai.ui.calls.LiveTranscriptController.Connection
import com.distronode.districtai.ui.calls.LiveTranscriptFrames.line
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranscriptControllerTest {

    private val transport = FakeTransport()
    private val fetches = ArrayDeque<ApiResult<String>>()
    private var fetchCount = 0

    private fun TestScope.controller() = LiveTranscriptController(
        workspaceId = LiveTranscriptFrames.WORKSPACE,
        callId = LiveTranscriptFrames.CALL,
        dependencies = LiveDependencies(FakeTelemetryApi(), transport, VirtualClock(this), jitter = { 0.0 }),
        fetchTranscript = {
            fetchCount += 1
            fetches.removeFirstOrNull() ?: ApiResult.Success("")
        },
        scope = backgroundScope,
    )

    private val subscribe = """{"op":"transcript.subscribe","v":1,"callId":"c1"}"""

    @Test
    fun `activating opens a socket that subscribes, and its snapshot goes live`() = runTest {
        val socket = transport.socket()
        val live = controller()
        assertEquals(Connection.IDLE, live.state.value.connection)
        live.activate()
        runCurrent()
        assertEquals(listOf("""{"op":"socket.mode","v":1,"broadcast":false}""", subscribe), socket.sent)
        assertEquals(Connection.OPEN, live.state.value.connection)

        socket.frames.send(LiveTranscriptFrames.snapshot(line("a", 0, 1)))
        runCurrent()
        val state = live.state.value
        assertEquals(LiveTranscriptPhase.Live, state.phase)
        assertEquals(listOf("a"), state.lines.map { it.segmentId })
        assertFalse(state.fallsBack)

        // A call event on the same socket is no transcript business.
        socket.frames.send(LiveTranscriptFrames.callEvent())
        runCurrent()
        assertEquals(state, live.state.value)
    }

    @Test
    fun `a second activation keeps the one socket, and deactivating closes it`() = runTest {
        val socket = transport.socket()
        val live = controller()
        live.activate()
        live.activate()
        runCurrent()
        assertEquals(1, transport.connects.size)
        live.deactivate()
        runCurrent()
        assertTrue(socket.closed)
        assertEquals(Connection.IDLE, live.state.value.connection)

        transport.socket()
        live.activate()
        runCurrent()
        assertEquals(2, transport.connects.size)
    }

    @Test
    fun `nothing opens before the screen starts`() = runTest {
        val live = controller()
        live.callShownInProgress()
        live.deactivate()
        runCurrent()
        assertEquals(0, transport.connects.size)
    }

    @Test
    fun `a socket that drops reads as connecting until it is back`() = runTest {
        val first = transport.socket()
        transport.socket()
        val live = controller()
        live.activate()
        runCurrent()
        first.frames.send(TelemetrySocketFrame.Closed(1001, "restart"))
        runCurrent()
        assertEquals(Connection.CONNECTING, live.state.value.connection)
        advanceTimeBy(1_001)
        assertEquals(Connection.OPEN, live.state.value.connection)
    }

    @Test
    fun `not live falls back, closes the socket, and the call row brings it back`() = runTest {
        val socket = transport.socket()
        val live = controller()
        live.activate()
        runCurrent()
        socket.frames.send(LiveTranscriptFrames.error(TRANSCRIPT_ERROR_NOT_LIVE))
        runCurrent()
        assertTrue(live.state.value.fallsBack)
        assertTrue(socket.closed)

        // A deactivate and activate does not reopen a finished socket.
        live.deactivate()
        live.activate()
        runCurrent()
        assertEquals(1, transport.connects.size)

        val again = transport.socket()
        live.callShownInProgress()
        runCurrent()
        assertEquals(2, transport.connects.size)
        assertEquals(subscribe, again.sent.last())
        assertFalse(live.state.value.fallsBack)
    }

    @Test
    fun `a refused socket fails for good and falls back`() = runTest {
        val socket = transport.socket()
        val live = controller()
        live.activate()
        runCurrent()
        // A rate-limited subscribe is retried later, but the socket is refused first.
        socket.frames.send(LiveTranscriptFrames.error(TRANSCRIPT_ERROR_RATE_LIMITED, retryAfterMs = 5_000))
        socket.frames.send(TelemetrySocketFrame.Closed(4403, "member"))
        runCurrent()
        assertEquals(Connection.FAILED, live.state.value.connection)
        assertTrue(live.state.value.fallsBack)
        // The retry finds no socket and does nothing.
        advanceTimeBy(5_001)
        live.deactivate()
        assertEquals(Connection.FAILED, live.state.value.connection)
    }

    @Test
    fun `a gap left open subscribes again on the same socket`() = runTest {
        val socket = transport.socket()
        val live = controller()
        live.activate()
        runCurrent()
        socket.frames.send(LiveTranscriptFrames.snapshot())
        socket.frames.send(LiveTranscriptFrames.segment(line("c", 2, 3)))
        runCurrent()
        val sentBefore = socket.sent.size
        advanceTimeBy(2_001)
        assertEquals(sentBefore + 1, socket.sent.size)
        assertEquals(subscribe, socket.sent.last())
    }

    @Test
    fun `the end fetches the full transcript and then closes the socket`() = runTest {
        val socket = transport.socket()
        val live = controller()
        live.activate()
        runCurrent()
        socket.frames.send(LiveTranscriptFrames.snapshot(line("a", 0, 1)))
        socket.frames.send(LiveTranscriptFrames.ended(2, TRANSCRIPT_ENDED_CALL_ENDED))
        runCurrent()
        assertEquals(FinalTranscriptState.Fetching(1), live.state.value.finalTranscript)
        fetches += ApiResult.Success("The whole call.")
        advanceTimeBy(2_001)
        assertEquals(FinalTranscriptState.Loaded("The whole call."), live.state.value.finalTranscript)
        assertTrue(socket.closed)
    }

    @Test
    fun `a fetch interrupted by the screen stopping resumes when it starts again`() = runTest {
        transport.socket()
        val live = controller()
        live.activate()
        runCurrent()
        live.callEnded()
        live.deactivate()
        advanceTimeBy(10_000)
        assertEquals(0, fetchCount)
        fetches += ApiResult.Success("Text")
        live.activate()
        runCurrent()
        assertEquals(1, fetchCount)
        assertEquals(FinalTranscriptState.Loaded("Text"), live.state.value.finalTranscript)
    }

    @Test
    fun `falls back only when unavailable or failed`() {
        val state = LiveTranscriptController.State()
        assertFalse(state.fallsBack)
        assertTrue(state.copy(phase = LiveTranscriptPhase.Unavailable("x")).fallsBack)
        assertTrue(state.copy(connection = Connection.FAILED).fallsBack)
    }
}
