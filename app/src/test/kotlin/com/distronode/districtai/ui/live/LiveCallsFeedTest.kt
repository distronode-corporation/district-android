package com.distronode.districtai.ui.live

import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.WhisperAppliedData
import com.distronode.districtai.core.network.live.LiveDependencies
import com.distronode.districtai.core.network.live.TelemetrySocketFrame
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.core.network.testing.FakeTelemetryApi
import com.distronode.districtai.core.network.testing.FakeTransport
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.core.network.testing.VirtualClock
import com.distronode.districtai.ui.rooms.FakeCallEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Live calls socket: broadcast mode, the list read again when a call starts or ends or the
 * socket reopens, and the whisper receipt handed to its call's box.
 *
 * ⚠️ `runCurrent`, NEVER `advanceUntilIdle`, and every test stops the screen before it ends, for the
 * reason `CallDetailLiveViewModelTest` gives: an open socket always has a timer pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallsFeedTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeSuperviseApi()
    private val transport = FakeTransport()
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun TestScope.viewModel(): LiveCallsViewModel {
        val dependencies = LiveDependencies(FakeTelemetryApi(), transport, VirtualClock(this), jitter = { 0.0 })
        return LiveCallsViewModel(
            SuperviseRepository(api),
            WORKSPACE,
            canWrite = true,
            engineFactory = CallEngineFactory { FakeCallEngine() },
            feed = { scope, onEvent -> LiveCallsFeed(WORKSPACE, dependencies, scope, onEvent) },
            engineScope = engineScope,
        ).also { runCurrent() }
    }

    private fun frame(type: String, data: String, callId: String = FakeSuperviseApi.AI_CALL.id) =
        TelemetrySocketFrame.Text(
            """{"workspaceId":"$WORKSPACE","callId":"$callId","eventType":"$type","data":$data,"timestamp":"t"}""",
        )

    @Test
    fun `the socket opens in broadcast mode and reads the list again on open`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        assertEquals(1, api.activeReads.size)

        vm.onScreenStarted(true)
        vm.onScreenStarted(true)
        runCurrent()

        assertEquals("one socket, however often the screen starts", 1, transport.connects.size)
        // ⛔ No socket.mode: broadcast is the default, and the relay events are the point.
        assertTrue(socket.sent.isEmpty())
        assertEquals(2, api.activeReads.size)

        vm.onScreenStarted(false)
        vm.onScreenStarted(false)
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test
    fun `a call starting or ending reads the list again, and an update does not`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        val before = api.activeReads.size

        socket.frames.send(frame("call_updated", "{}"))
        socket.frames.send(frame("transcript_ended", "{}"))
        runCurrent()
        assertEquals(before, api.activeReads.size)

        socket.frames.send(frame("call_started", """{"from":"+15550100"}"""))
        runCurrent()
        socket.frames.send(frame("call_ended", "{}"))
        runCurrent()
        assertEquals(before + 2, api.activeReads.size)

        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a whisper receipt lands in its call's box`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()

        socket.frames.send(frame("whisper_applied", """{"applied":true,"firstWords":"Thursday at 3 works."}"""))
        runCurrent()

        assertEquals(
            WhisperAppliedData(applied = true, firstWords = "Thursday at 3 works."),
            vm.whispers.value.getValue(FakeSuperviseApi.AI_CALL.id).receipt,
        )
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a socket that ends for good is replaced the next time the screen starts`() = runTest(dispatcher) {
        val first = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()

        // ⚠️ 4403: the member may not hold a socket; the connection ends without retrying.
        first.frames.send(TelemetrySocketFrame.Closed(4403, "member"))
        runCurrent()
        vm.onScreenStarted(false)
        transport.socket()
        vm.onScreenStarted(true)
        runCurrent()

        assertEquals(2, transport.connects.size)
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a reconnect reads the list again`() = runTest(dispatcher) {
        val first = transport.socket()
        transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        val before = api.activeReads.size

        first.frames.send(TelemetrySocketFrame.Closed(1001, "restart"))
        runCurrent()
        dispatcher.scheduler.advanceTimeBy(RECONNECT_WAIT_MILLIS)
        runCurrent()

        assertEquals(2, transport.connects.size)
        assertEquals(before + 1, api.activeReads.size)
        vm.onScreenStarted(false)
        runCurrent()
    }

    private companion object {
        const val WORKSPACE = "ws-1"

        /** Longer than the first backoff wait with no jitter. */
        const val RECONNECT_WAIT_MILLIS = 5_000L
    }
}
