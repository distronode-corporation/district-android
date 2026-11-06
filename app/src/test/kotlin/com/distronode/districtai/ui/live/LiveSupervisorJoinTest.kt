package com.distronode.districtai.ui.live

import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.CallMonitorTokenResponse
import com.distronode.districtai.core.model.E2eeInfo
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.live.LiveSupervisor.Phase
import com.distronode.districtai.ui.rooms.FakeCallEngine
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What a join may claim and how it ends (review M1, L1 and N3 of PR #117): "listening" and "on
 * the call" only once true, a room that closes at the join, a call that ends under the
 * microphone's publish, a takeover whose join fails, and a rotation's stop. The rest of the
 * supervisor is [LiveCallsViewModelTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSupervisorJoinTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeSuperviseApi()
    private val log = LiveCallsLog()
    private val engines = mutableListOf<FakeCallEngine>()
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val ai = FakeSuperviseApi.AI_CALL.id

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    /** @param make the engine for the Nth join (from 1); every engine's fake is kept in [engines]. */
    private fun TestScope.viewModel(make: (Int) -> CallEngine = { FakeCallEngine() }): LiveCallsViewModel {
        var made = 0
        val factory = CallEngineFactory {
            made += 1
            make(made).also { engines += it.fake() }
        }
        return LiveCallsViewModel(
            LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
            "ws-1",
            true,
            factory,
            feed = null,
            engineScope = engineScope,
        ).also { runCurrent() }
    }

    private fun failing() = FakeCallEngine().apply { connectFailure = IllegalStateException("no") }

    private fun TestScope.listeningThenTakingOver(vm: LiveCallsViewModel) {
        vm.listen(ai)
        runCurrent()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()
    }

    private val takeOverFailed = UiText.Resource(R.string.live_calls_take_over_join_failed)

    /** Idle, with the reason the room's `ROOM_DELETED` carries said at the call. */
    private val callEnded =
        LiveSupervisor.State(failedCallId = ai, failure = UiText.Resource(R.string.api_error_call_ended))

    @Test
    fun `a stop that is a rotation keeps the call and the pending microphone answer`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()

        vm.onScreenStarted(false, recreating = true)
        vm.onScreenStarted(true)
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals(listOf("ws-1/$ai"), api.takeovers)
        assertTrue(vm.supervision.value.takenOver && vm.supervision.value.live)
    }

    @Test
    fun `a microphone answer for a call that left the list meanwhile sends nothing`() = runTest(dispatcher) {
        listOf(true, false).forEach { granted ->
            log.callsResult = ApiResult.Success(listOf(FakeSuperviseApi.AI_CALL))
            val vm = viewModel()
            vm.listen(ai)
            runCurrent()
            vm.takeOver.ask(ai)
            vm.takeOver.confirm()

            // The call ended while the permission dialog was up.
            log.callsResult = ApiResult.Success(emptyList())
            vm.refresh()
            runCurrent()
            vm.takeOver.onMicrophonePermissionResult(granted)
            runCurrent()

            assertTrue("granted=$granted: no takeover for a call that is gone", api.takeovers.isEmpty())
            assertEquals("granted=$granted", LiveSupervisor.State(), vm.supervision.value)
        }
    }

    // ── What the card may claim (M1) ──────────────────────────────────────────────────────────────

    @Test
    fun `a takeover is not live until its microphone is, and is busy until then`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel { n -> if (n == 1) FakeCallEngine() else SlowMicEngine(gate) }
        listeningThenTakingOver(vm)

        val joining = vm.supervision.value
        assertEquals("the room itself is up", Phase.CONNECTED, joining.phase)
        assertTrue(joining.takenOver)
        assertFalse("not on the call before the microphone is live", joining.live)
        assertTrue(joining.busy)

        gate.complete(Unit)
        runCurrent()
        assertTrue(vm.supervision.value.live)
        assertFalse(vm.supervision.value.busy)
    }

    @Test
    fun `a joined room that reconnects is not live, and is live again once back`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()

        engines.single().emitConnection(CallConnectionState.Reconnecting)
        runCurrent()
        assertFalse(vm.supervision.value.live)
        assertTrue(vm.supervision.value.joined)
        engines.single().emitConnection(CallConnectionState.Connected)
        runCurrent()
        assertTrue(vm.supervision.value.live)
    }

    // ── How a join ends (L1) ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a room that closes the moment it is joined resets to idle`() = runTest(dispatcher) {
        val vm = viewModel { BlinkEngine() }

        vm.listen(ai)
        runCurrent()

        assertEquals(callEnded, vm.supervision.value)
        assertFalse("Listen and Take over are offered again", vm.supervision.value.busy)
        assertEquals("disconnect", engines.single().calls.last())
    }

    @Test
    fun `a call that ends while the takeover's microphone publishes is the call ending, not a microphone fault`() =
        runTest(dispatcher) {
            val vm = viewModel { n -> if (n == 1) FakeCallEngine() else EndingMicEngine() }
            listeningThenTakingOver(vm)

            assertEquals(callEnded, vm.supervision.value)
            assertEquals("no rejoin into a call that ended", listOf(ai), api.monitors)
        }

    @Test
    fun `a room that closes while the takeover's microphone publishes is read when the join ends`() =
        runTest(dispatcher) {
            // The watch sees the room go while the join is still running and must leave it to the
            // join; only the join's own read of the room's state, as it ends, catches it.
            val gate = CompletableDeferred<Unit>()
            val vm = viewModel { n -> if (n == 1) FakeCallEngine() else SlowMicEngine(gate) }
            listeningThenTakingOver(vm)
            engines[1].emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
            runCurrent()
            assertTrue("the watch left it to the join", vm.supervision.value.takenOver)

            gate.complete(Unit)
            runCurrent()

            assertEquals(callEnded, vm.supervision.value)
            assertEquals("no rejoin into a call that ended", listOf(ai), api.monitors)
        }

    // ── The room's key (L4) ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a listen grant's end-to-end key reaches the engine`() = runTest(dispatcher) {
        api.monitorResult = ApiResult.Success(
            CallMonitorTokenResponse(
                success = true,
                token = "monitor-jwt",
                url = FakeSuperviseApi.ROOM_URL,
                e2ee = E2eeInfo("room-key"),
            ),
        )
        val vm = viewModel()

        vm.listen(ai)
        runCurrent()

        assertEquals("room-key", engines.single().lastE2eeKey)
        assertTrue(vm.supervision.value.live)
    }

    // ── A takeover whose join fails (N3) ──────────────────────────────────────────────────────────

    @Test
    fun `a takeover whose join fails puts the supervisor back to listening, saying why`() = runTest(dispatcher) {
        // The listen (1st) and the rejoin (3rd) join; the takeover (2nd) does not.
        val vm = viewModel { n -> if (n == 2) failing() else FakeCallEngine() }
        listeningThenTakingOver(vm)

        val back = LiveSupervisor.State(callId = ai, phase = Phase.CONNECTED, joined = true)
        assertEquals(back.copy(failedCallId = ai, failure = takeOverFailed), vm.supervision.value)
        assertEquals(listOf(ai, ai), api.monitors)
        assertEquals(listOf("connect:${FakeSuperviseApi.ROOM_URL}:monitor-jwt", "speaker:true"), engines[2].calls)
    }

    @Test
    fun `a rejoin the server refuses keeps the takeover's sentence`() = runTest(dispatcher) {
        val vm = viewModel { n -> if (n == 2) failing() else FakeCallEngine() }
        vm.listen(ai)
        runCurrent()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        api.monitorResult = ApiResult.HttpFailure(409, "That call has ended.", "call_ended")
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals(LiveSupervisor.State(failedCallId = ai, failure = takeOverFailed), vm.supervision.value)
    }
}

/** The [FakeCallEngine] behind a test engine, or the engine itself. */
private fun CallEngine.fake(): FakeCallEngine = when (this) {
    is BlinkEngine -> inner
    is SlowMicEngine -> inner
    is EndingMicEngine -> inner
    else -> this as FakeCallEngine
}

/** Joins, then the room closes before the supervisor's watch has seen `Connected` (a conflated flow). */
private class BlinkEngine(val inner: FakeCallEngine = FakeCallEngine()) : CallEngine by inner {
    override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
        inner.connect(url, token, e2eeKeyBase64)
        inner.emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
    }
}

/** A [FakeCallEngine] whose microphone takes until [gate] to publish. */
private class SlowMicEngine(
    private val gate: CompletableDeferred<Unit>,
    val inner: FakeCallEngine = FakeCallEngine(),
) : CallEngine by inner {
    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        gate.await()
        inner.setMicrophoneEnabled(enabled)
    }
}

/** A [FakeCallEngine] whose room closes while the microphone publishes, which then throws. */
private class EndingMicEngine(val inner: FakeCallEngine = FakeCallEngine()) : CallEngine by inner {
    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        inner.emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
        error("the room closed under the publish")
    }
}
