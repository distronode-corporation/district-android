package com.distronode.districtai.ui.live

import com.distronode.districtai.core.data.CallsRepository
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
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
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
    private val log = LiveCallsLog()
    private val engines = mutableListOf<FakeCallEngine>()
    private val transport = FakeTransport()
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun TestScope.viewModel(): LiveCallsViewModel {
        val dependencies = LiveDependencies(FakeTelemetryApi(), transport, VirtualClock(this), jitter = { 0.0 })
        return LiveCallsViewModel(
            LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
            WORKSPACE,
            canWrite = true,
            engineFactory = CallEngineFactory { FakeCallEngine().also { engines += it } },
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
        assertEquals(1, log.reads)

        vm.onScreenStarted(true)
        vm.onScreenStarted(true)
        runCurrent()

        assertEquals("one socket, however often the screen starts", 1, transport.connects.size)
        // ⛔ No socket.mode: broadcast is the default, and the relay events are the point.
        assertTrue(socket.sent.isEmpty())
        assertEquals(2, log.reads)

        vm.onScreenStarted(false)
        vm.onScreenStarted(false)
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test
    fun `a call starting or changing reads the list again, and other events do not`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        val before = log.reads

        socket.frames.send(frame("message_received", "{}"))
        socket.frames.send(frame("transcript_ended", "{}"))
        runCurrent()
        assertEquals(before, log.reads)

        socket.frames.send(frame("call_started", """{"from":"+15550100"}"""))
        runCurrent()
        socket.frames.send(frame("call_updated", """{"status":"in-progress"}"""))
        runCurrent()
        assertEquals(before + 2, log.reads)

        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a call ending drops its row at once, without a read`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        val before = log.reads

        socket.frames.send(frame("call_ended", """{"status":"completed"}"""))
        runCurrent()

        assertEquals(LiveCallList.Loaded(listOf(FakeSuperviseApi.TEAM_CALL)), vm.list.value)
        assertEquals(before, log.reads)
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `the supervised call ending leaves its session, and another call ending does not`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        vm.listen(FakeSuperviseApi.AI_CALL.id)
        runCurrent()

        socket.frames.send(frame("call_ended", "{}", callId = FakeSuperviseApi.TEAM_CALL.id))
        runCurrent()
        assertEquals(LiveSupervisor.Phase.CONNECTED, vm.supervision.value.phase)

        socket.frames.send(frame("call_ended", "{}"))
        runCurrent()
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        assertEquals("disconnect", engines.single().calls.last())
        assertEquals(LiveCallList.Loaded(emptyList()), vm.list.value)

        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a call ending keeps the refresh notice on screen`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        log.callsResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.refresh()
        runCurrent()

        socket.frames.send(frame("call_ended", "{}"))
        runCurrent()

        val list = vm.list.value as LiveCallList.Loaded
        assertEquals(listOf(FakeSuperviseApi.TEAM_CALL), list.calls)
        assertTrue(list.refreshFailure != null)
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a read older than a call's end does not bring the call back`() = runTest(dispatcher) {
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        log.gate = gate
        vm.refresh()
        runCurrent()

        socket.frames.send(frame("call_ended", "{}"))
        runCurrent()
        log.gate = null
        gate.complete(Unit)
        runCurrent()

        assertEquals(LiveCallList.Loaded(listOf(FakeSuperviseApi.TEAM_CALL)), vm.list.value)
        vm.onScreenStarted(false)
        runCurrent()
    }

    @Test
    fun `a call ending while the list could not be read changes nothing`() = runTest(dispatcher) {
        log.callsResult = ApiResult.NetworkFailure(IOException("offline"))
        val socket = transport.socket()
        val vm = viewModel()
        vm.onScreenStarted(true)
        runCurrent()

        socket.frames.send(frame("call_ended", "{}"))
        runCurrent()

        assertTrue(vm.list.value is LiveCallList.Failed)
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
        val before = log.reads

        first.frames.send(TelemetrySocketFrame.Closed(1001, "restart"))
        runCurrent()
        dispatcher.scheduler.advanceTimeBy(RECONNECT_WAIT_MILLIS)
        runCurrent()

        assertEquals(2, transport.connects.size)
        assertEquals(before + 1, log.reads)
        vm.onScreenStarted(false)
        runCurrent()
    }

    private companion object {
        const val WORKSPACE = "ws-1"

        /** Longer than the first backoff wait with no jitter. */
        const val RECONNECT_WAIT_MILLIS = 5_000L
    }
}
