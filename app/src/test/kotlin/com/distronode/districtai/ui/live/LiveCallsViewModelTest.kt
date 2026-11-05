package com.distronode.districtai.ui.live

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.MAX_WHISPER_CHARS
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.live.LiveSupervisor.Phase
import com.distronode.districtai.ui.rooms.FakeCallEngine
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Live calls ViewModel: the list, listening and taking over through [LiveSupervisor], and the
 * notes through [WhisperDesk]. The socket half is [LiveCallsFeedTest].
 *
 * ⛔ EVERY ACTION TEST COUNTS REQUESTS AND ENGINE CALLS, because the rules are about what reaches a
 * customer's call: once, never retried, never with a microphone while listening, and never on a
 * team member's own call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveCallsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeSuperviseApi()
    private val log = LiveCallsLog()
    private val engines = mutableListOf<FakeCallEngine>()
    private val factory = CallEngineFactory { FakeCallEngine().also { engines += it } }
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val ai = FakeSuperviseApi.AI_CALL.id
    private val team = FakeSuperviseApi.TEAM_CALL.id

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun TestScope.viewModel(canWrite: Boolean = true): LiveCallsViewModel =
        LiveCallsViewModel(
            LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
            "ws-1",
            canWrite,
            factory,
            feed = null,
            engineScope = engineScope,
        )
            .also { runCurrent() }

    // ── The list ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the list is the call log's live calls, read once on creation`() = runTest(dispatcher) {
        val vm = viewModel()

        // ⛔ The completed call is not live; the ringing one is.
        assertEquals(LiveCallList.Loaded(listOf(FakeSuperviseApi.AI_CALL, FakeSuperviseApi.TEAM_CALL)), vm.list.value)
        assertEquals(1, log.reads)
    }

    @Test
    fun `a failed read is a failure, never no calls, and a refresh recovers`() = runTest(dispatcher) {
        log.callsResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()
        assertTrue(vm.list.value is LiveCallList.Failed)

        log.callsResult = ApiResult.Success(listOf(FakeSuperviseApi.ENDED_CALL))
        vm.refresh()
        runCurrent()
        assertEquals(LiveCallList.Loaded(emptyList()), vm.list.value)
    }

    @Test
    fun `a refresh asked for during a read runs once after it, never alongside`() = runTest(dispatcher) {
        val vm = viewModel()
        val gate = CompletableDeferred<Unit>()
        log.gate = gate
        vm.refresh()
        runCurrent()
        vm.refresh()
        vm.refresh()
        runCurrent()
        assertEquals("one read in flight", 2, log.reads)

        log.gate = null
        gate.complete(Unit)
        runCurrent()

        assertEquals("the asks during it are one read after it", 3, log.reads)
    }

    // ── Listening ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `listening joins the grant's room on the speaker and never opens the microphone`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.listen(ai)
        runCurrent()

        assertEquals(listOf(ai), api.monitors)
        val engine = engines.single()
        // ⛔ THE ROOM'S DEPLOYMENT, VERBATIM, never a workspace default.
        assertEquals(listOf("connect:${FakeSuperviseApi.ROOM_URL}:monitor-jwt", "speaker:true"), engine.calls)
        assertNull("a phone call's room is never encrypted", engine.lastE2eeKey)
        assertEquals(LiveSupervisor.State(callId = ai, phase = Phase.CONNECTED), vm.supervision.value)
    }

    @Test
    fun `a team member's call can be listened to`() = runTest(dispatcher) {
        val vm = viewModel(canWrite = false)

        vm.listen(team)
        runCurrent()

        assertEquals(listOf(team), api.monitors)
        assertEquals(Phase.CONNECTED, vm.supervision.value.phase)
    }

    @Test
    fun `a listen refused because the call ended shows the server's code at the call`() = runTest(dispatcher) {
        api.monitorResult = ApiResult.HttpFailure(409, "This call has ended.", "call_ended")
        val vm = viewModel()

        vm.listen(ai)
        runCurrent()

        val state = vm.supervision.value
        assertEquals(ai, state.failedCallId)
        // ⚠️ THE CODE IS READ, so the catalog's sentence takes over once it has `call_ended`.
        assertEquals(
            UiText.Server("This call has ended.", "call_ended", fallback = R.string.live_calls_connect_failed),
            state.failure
        )
        assertNull(state.callId)
        assertTrue("nothing was joined", engines.isEmpty())
        assertEquals("sent once, never retried", 1, api.monitors.size)
    }

    @Test
    fun `a room id is refused before anything is sent`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.listen("video_ws-1_lobby")
        runCurrent()

        assertTrue(api.monitors.isEmpty())
        assertEquals(UiText.Resource(R.string.live_calls_not_a_call), vm.supervision.value.failure)
    }

    @Test
    fun `two taps before an answer mint one credential`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.listen(ai)
        vm.listen(ai)
        vm.listen(team)
        runCurrent()

        assertEquals(listOf(ai), api.monitors)
        assertEquals(1, engines.size)
    }

    @Test
    fun `stopping leaves the room`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()

        vm.leave()
        runCurrent()

        assertEquals("disconnect", engines.single().calls.last())
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `listening to another call leaves the first on a fresh engine`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()

        vm.listen(team)
        runCurrent()

        assertEquals(2, engines.size)
        assertEquals("disconnect", engines[0].calls.last())
        assertEquals(team, vm.supervision.value.callId)
        // ⚠️ The first engine's late Disconnected is not this call's.
        assertEquals(Phase.CONNECTED, vm.supervision.value.phase)
    }

    @Test
    fun `a join that fails leaves nothing behind and says so`() = runTest(dispatcher) {
        val vm = viewModel()
        val failing = FakeCallEngine().apply { connectFailure = IllegalStateException("unreachable") }
        val failingVm = LiveCallsViewModel(
            LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
            "ws-1",
            true,
            { failing },
            null,
            engineScope,
        )
        runCurrent()

        failingVm.listen(ai)
        runCurrent()

        assertEquals("disconnect", failing.calls.last())
        assertEquals(
            LiveSupervisor.State(failedCallId = ai, failure = UiText.Resource(R.string.live_calls_connect_failed)),
            failingVm.supervision.value
        )
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `the room's own states move the phase, and an end it did not ask for clears it`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()
        val engine = engines.single()

        engine.emitConnection(CallConnectionState.Reconnecting)
        runCurrent()
        assertEquals(Phase.RECONNECTING, vm.supervision.value.phase)
        engine.emitConnection(CallConnectionState.Connected)
        runCurrent()
        assertEquals(Phase.CONNECTED, vm.supervision.value.phase)
        engine.emitConnection(CallConnectionState.Connecting)
        runCurrent()
        engine.emitConnection(CallConnectionState.Idle)
        runCurrent()
        assertEquals(Phase.CONNECTED, vm.supervision.value.phase)

        // The call ended: the room went away.
        engine.emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
        runCurrent()
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        assertEquals("disconnect", engine.calls.last())
    }

    @Test
    fun `a room lost for good says the connection failed`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.listen(ai)
        runCurrent()

        engines.single().emitConnection(CallConnectionState.Failed("lost"))
        runCurrent()

        assertEquals(
            LiveSupervisor.State(failedCallId = ai, failure = UiText.Resource(R.string.live_calls_connect_failed)),
            vm.supervision.value,
        )
    }

    // ── Taking over ─────────────────────────────────────────────────────────────────────────────

    private fun TestScope.listeningThenConfirmed(vm: LiveCallsViewModel) {
        vm.listen(ai)
        runCurrent()
        vm.takeOver.ask(ai)
        assertEquals(ai, vm.prompt.value.confirmingTakeover)
        vm.takeOver.confirm()
        assertEquals(LiveCallsPrompt(microphoneRequest = 1), vm.prompt.value)
    }

    @Test
    fun `a confirmed takeover asks for the microphone, then joins with it at the ear`() = runTest(dispatcher) {
        val vm = viewModel()
        listeningThenConfirmed(vm)
        assertTrue("nothing is taken over before the microphone answer", api.takeovers.isEmpty())

        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals(listOf("ws-1/$ai"), api.takeovers)
        assertEquals("disconnect", engines[0].calls.last())
        assertEquals(
            listOf("connect:${FakeSuperviseApi.ROOM_URL}:takeover-jwt", "speaker:false", "mic:true"),
            engines[1].calls,
        )
        assertEquals(LiveSupervisor.State(callId = ai, takenOver = true, phase = Phase.CONNECTED), vm.supervision.value)
    }

    @Test
    fun `handing back leaves the room`() = runTest(dispatcher) {
        val vm = viewModel()
        listeningThenConfirmed(vm)
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        vm.leave()
        runCurrent()

        assertEquals("disconnect", engines[1].calls.last())
        assertFalse(vm.supervision.value.takenOver)
    }

    @Test
    fun `a refused microphone takes nothing over and says why`() = runTest(dispatcher) {
        val vm = viewModel()
        listeningThenConfirmed(vm)

        vm.takeOver.onMicrophonePermissionResult(false)
        runCurrent()

        assertTrue(api.takeovers.isEmpty())
        assertEquals(UiText.Resource(R.string.live_calls_microphone_denied), vm.supervision.value.failure)
        assertEquals("still listening", Phase.CONNECTED, vm.supervision.value.phase)
    }

    @Test
    fun `a microphone answer with nothing pending is dropped`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.takeOver.onMicrophonePermissionResult(true)
        vm.takeOver.confirm()
        runCurrent()

        assertTrue(api.takeovers.isEmpty())
        assertEquals(LiveCallsPrompt(), vm.prompt.value)
    }

    @Test
    fun `a refused takeover leaves the supervisor listening, with the server's code`() = runTest(dispatcher) {
        api.takeoverResult = ApiResult.HttpFailure(409, "This call is not in progress.", "call_not_in_progress")
        val vm = viewModel()
        listeningThenConfirmed(vm)

        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        val state = vm.supervision.value
        assertEquals(ai, state.callId)
        assertEquals(Phase.CONNECTED, state.phase)
        assertFalse(state.takenOver)
        assertEquals(
            UiText.Server(
                "This call is not in progress.",
                "call_not_in_progress",
                fallback = R.string.live_calls_take_over_failed
            ),
            state.failure,
        )
        assertEquals(1, engines.size)
        assertEquals("sent once, never retried", 1, api.takeovers.size)
    }

    @Test
    fun `a takeover granted but not joined says the microphone join failed`() = runTest(dispatcher) {
        val joined = mutableListOf<FakeCallEngine>()
        val vm = LiveCallsViewModel(
            LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
            "ws-1",
            true,
            { FakeCallEngine().apply { connectFailure = IllegalStateException("no") }.also { joined += it } },
            null,
            engineScope,
        )
        runCurrent()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals(UiText.Resource(R.string.live_calls_take_over_join_failed), vm.supervision.value.failure)
        assertTrue(joined.single().calls.none { it == "mic:true" })
    }

    @Test
    fun `whisper and take over are never offered without the receptionist on the call, to a viewer, or for no call`() =
        runTest(dispatcher) {
            val viewer = viewModel(canWrite = false)
            viewer.takeOver.ask(ai)
            viewer.setWhisperDraft(ai, "Offer Thursday.")
            viewer.sendWhisper(ai)
            assertNull(viewer.prompt.value.confirmingTakeover)

            val writer = viewModel()
            writer.takeOver.ask(team)
            writer.takeOver.ask("cmgone")
            writer.setWhisperDraft(team, "Offer Thursday.")
            writer.sendWhisper(team)
            runCurrent()

            assertNull(writer.prompt.value.confirmingTakeover)
            assertTrue(api.whispers.isEmpty())
            assertTrue(viewer.whispers.value.isEmpty())
            assertTrue(writer.whispers.value.isEmpty())
        }

    @Test
    fun `a takeover the server refuses for want of a receptionist says so, once`() = runTest(dispatcher) {
        // ⛔ THE FALLBACK: the flag said the receptionist was on the call, the server knew better.
        api.takeoverResult = ApiResult.HttpFailure(409, "A team member's own call.", "call_has_no_receptionist")
        val vm = viewModel()
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals(
            UiText.Server(
                "A team member's own call.",
                "call_has_no_receptionist",
                fallback = R.string.live_calls_take_over_failed
            ),
            vm.supervision.value.failure,
        )
        assertEquals(1, api.takeovers.size)
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `nothing is writable before the list has loaded`() = runTest(dispatcher) {
        log.callsResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()

        vm.takeOver.ask(ai)

        assertNull(vm.prompt.value.confirmingTakeover)
    }

    @Test
    fun `cancelling closes the confirmation`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.takeOver.ask(ai)

        vm.takeOver.cancel()

        assertNull(vm.prompt.value.confirmingTakeover)
    }

    // ── Notes ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a note is sent trimmed, once, and cleared when it went`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.setWhisperDraft(ai, "  Offer Thursday at 3.  ")

        vm.sendWhisper(ai)
        assertTrue(vm.whispers.value.getValue(ai).sending)
        runCurrent()

        assertEquals(listOf("ws-1/$ai/Offer Thursday at 3."), api.whispers)
        assertEquals(WhisperBox(), vm.whispers.value.getValue(ai))
    }

    @Test
    fun `a blank note is not sent, and a second tap while sending is dropped`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.sendWhisper(ai)
        vm.setWhisperDraft(ai, "   ")
        vm.sendWhisper(ai)
        assertTrue(api.whispers.isEmpty())

        val gate = CompletableDeferred<Unit>()
        api.whisperGate = gate
        vm.setWhisperDraft(ai, "Offer Thursday.")
        vm.sendWhisper(ai)
        runCurrent()
        vm.sendWhisper(ai)
        runCurrent()
        // Typed while the first was in flight: kept, and not cleared by the first's success.
        vm.setWhisperDraft(ai, "Offer Friday.")
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, api.whispers.size)
        assertEquals("Offer Friday.", vm.whispers.value.getValue(ai).draft)
    }

    @Test
    fun `a refused note keeps its text and says why, with the server's code`() = runTest(dispatcher) {
        api.whisperResult = ApiResult.HttpFailure(409, "A team member's own call.", "call_has_no_receptionist")
        val vm = viewModel()
        vm.setWhisperDraft(ai, "Offer Thursday.")

        vm.sendWhisper(ai)
        runCurrent()

        val box = vm.whispers.value.getValue(ai)
        assertEquals("Offer Thursday.", box.draft)
        assertFalse(box.sending)
        assertEquals(
            UiText.Server(
                "A team member's own call.",
                "call_has_no_receptionist",
                fallback = R.string.live_calls_whisper_failed
            ),
            box.error,
        )
        assertEquals("sent once, never retried", 1, api.whispers.size)
    }

    @Test
    fun `a note that failed on the network uses the shared offline sentence`() = runTest(dispatcher) {
        api.whisperResult = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()
        vm.setWhisperDraft(ai, "Offer Thursday.")

        vm.sendWhisper(ai)
        runCurrent()

        assertEquals(UiText.Resource(R.string.failure_offline), vm.whispers.value.getValue(ai).error)
    }

    @Test
    fun `a note to a row whose id is a room's is refused before it is sent`() = runTest(dispatcher) {
        val odd = FakeSuperviseApi.AI_CALL.copy(id = "meet_ws-1_x")
        log.callsResult = ApiResult.Success(listOf(odd))
        val vm = viewModel()
        vm.setWhisperDraft(odd.id, "Hello")

        vm.sendWhisper(odd.id)
        runCurrent()

        assertTrue(api.whispers.isEmpty())
        assertEquals(UiText.Resource(R.string.live_calls_not_a_call), vm.whispers.value.getValue(odd.id).error)
    }

    @Test
    fun `a draft is held to the route's limit`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.setWhisperDraft(ai, "x".repeat(MAX_WHISPER_CHARS + 5))

        assertEquals(MAX_WHISPER_CHARS, vm.whispers.value.getValue(ai).draft.length)
    }

    // ── The screen's lifecycle ──────────────────────────────────────────────────────────────────

    @Test
    fun `stopping the screen leaves the call and closes the confirmation`() = runTest(dispatcher) {
        val vm = viewModel()
        listeningThenConfirmed(vm)

        vm.onScreenStarted(false)
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals("disconnect", engines.single().calls.last())
        assertTrue("a result after the screen stopped takes nothing over", api.takeovers.isEmpty())
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        vm.onScreenStarted(true)
    }

    @Test
    fun `clearing the ViewModel leaves the room and ends the engine scope`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            LiveCallsViewModel.factory(
                SuperviseRepository(api),
                CallsRepository(log),
                "ws-1",
                true,
                factory,
                feed = null
            ),
        )[LiveCallsViewModel::class.java]
        runCurrent()
        vm.listen(ai)
        runCurrent()

        store.clear()
        runCurrent()

        assertEquals("disconnect", engines.single().calls.last())
        vm.listen(team)
        runCurrent()
        assertEquals("a released supervisor joins nothing", listOf(ai), api.monitors)
    }

    @Test
    fun `an end reported while joining is the join's own failure, said once`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val dropping = DroppingEngine(gate)
        val vm =
            LiveCallsViewModel(
                LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
                "ws-1",
                true,
                { dropping },
                null,
                engineScope
            )
        runCurrent()

        vm.listen(ai)
        runCurrent()
        assertEquals("the join is still the one deciding", Phase.CONNECTING, vm.supervision.value.phase)
        gate.complete(Unit)
        runCurrent()

        assertEquals(
            LiveSupervisor.State(failedCallId = ai, failure = UiText.Resource(R.string.live_calls_connect_failed)),
            vm.supervision.value,
        )
        assertEquals("disconnect", dropping.inner.calls.last())
    }

    @Test
    fun `leaving while the credential is fetched joins nothing`() = runTest(dispatcher) {
        val vm = viewModel()
        val gate = CompletableDeferred<Unit>()
        api.monitorGate = gate

        vm.listen(ai)
        runCurrent()
        vm.leave()
        gate.complete(Unit)
        runCurrent()

        assertTrue("the cancelled request joined nothing", engines.isEmpty())
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `leaving while the room is being joined leaves the room it may have joined`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val slow = GatedEngine(gate)
        val vm =
            LiveCallsViewModel(
                LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
                "ws-1",
                true,
                { slow },
                null,
                engineScope
            )
        runCurrent()

        vm.listen(ai)
        runCurrent()
        assertEquals(Phase.CONNECTING, vm.supervision.value.phase)
        // ⛔ Joining is busy too: no second credential while the room is being joined.
        vm.listen(FakeSuperviseApi.TEAM_CALL.id)
        runCurrent()
        assertEquals(listOf(ai), api.monitors)
        vm.leave()
        runCurrent()

        assertTrue(slow.inner.calls.isNotEmpty())
        assertTrue(slow.inner.calls.all { it == "disconnect" })
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        assertTrue(engineScope.isActive)
    }
}

/** A [FakeCallEngine] that reports the room gone while its join is still in flight, then fails it. */
private class DroppingEngine(
    private val gate: CompletableDeferred<Unit>,
    val inner: FakeCallEngine = FakeCallEngine(),
) : com.distronode.districtai.core.media.CallEngine by inner {
    override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
        inner.emitConnection(CallConnectionState.Disconnected("JOIN_FAILURE"))
        gate.await()
        error("never joined")
    }
}

/** A [FakeCallEngine] whose join waits for [gate]: a join still in flight. */
private class GatedEngine(
    private val gate: CompletableDeferred<Unit>,
    val inner: FakeCallEngine = FakeCallEngine(),
) : com.distronode.districtai.core.media.CallEngine by inner {
    override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
        gate.await()
        inner.connect(url, token, e2eeKeyBase64)
    }
}
