package com.distronode.districtai.ui.live

import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeSuperviseApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Each request the supervisor sends, when the state it was sent from ends before its answer lands
 * (the supervise audit of PR #117): the listening room ending, the call leaving the list, Stop,
 * and the session ending. Plus what the card says when a joined room goes away on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveSupervisorEndsTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeSuperviseApi()
    private val log = LiveCallsLog()
    private val engines = mutableListOf<FakeCallEngine>()
    private val engineScope = CoroutineScope(SupervisorJob() + dispatcher)

    private val ai = FakeSuperviseApi.AI_CALL.id
    private val team = FakeSuperviseApi.TEAM_CALL.id

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private fun TestScope.viewModel(): LiveCallsViewModel = LiveCallsViewModel(
        LiveCallsSources(SuperviseRepository(api), CallsRepository(log)),
        "ws-1",
        true,
        CallEngineFactory { FakeCallEngine().also { engines += it } },
        feed = null,
        engineScope = engineScope,
    ).also { runCurrent() }

    private fun TestScope.listening(vm: LiveCallsViewModel) {
        vm.listen(ai)
        runCurrent()
        assertTrue(vm.supervision.value.live)
    }

    /** Listening, then a takeover asked for whose grant waits on [gate]. */
    private fun TestScope.takeOverPending(vm: LiveCallsViewModel, gate: CompletableDeferred<Unit>) {
        listening(vm)
        api.takeoverGate = gate
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()
        assertEquals("the grant is on its way", ai, vm.supervision.value.requestingCallId)
    }

    private fun said(text: Int) = LiveSupervisor.State(failedCallId = ai, failure = UiText.Resource(text))

    // ── Takeover (calls/{id}/takeover) ───────────────────────────────────────────────────────────

    @Test
    fun `a takeover grant that lands after the listening room ended joins nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel()
        takeOverPending(vm, gate)

        engines.single().emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals("no second engine, so no microphone", 1, engines.size)
        assertTrue(engines.single().calls.none { it == "mic:true" })
        assertEquals(said(R.string.api_error_call_ended), vm.supervision.value)
        assertFalse(vm.supervision.value.busy)
    }

    @Test
    fun `a takeover grant that lands after the call left the list joins nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel()
        takeOverPending(vm, gate)

        log.callsResult = ApiResult.Success(listOf(FakeSuperviseApi.TEAM_CALL))
        vm.refresh()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, engines.size)
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `a takeover grant that lands after Hand back or Stop joins nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val vm = viewModel()
        takeOverPending(vm, gate)

        vm.leave()
        gate.complete(Unit)
        runCurrent()

        assertEquals(1, engines.size)
        assertEquals(LiveSupervisor.State(), vm.supervision.value)
    }

    @Test
    fun `a microphone answer after Stop takes nothing over`() = runTest(dispatcher) {
        val vm = viewModel()
        listening(vm)
        vm.takeOver.ask(ai)
        vm.takeOver.confirm()

        vm.leave()
        vm.takeOver.onMicrophonePermissionResult(true)
        runCurrent()

        assertTrue(api.takeovers.isEmpty())
        assertNull("the confirmation closed with Stop", vm.prompt.value.confirmingTakeover)
    }

    // ── Listen (calls/token) ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a listen asked for another call goes on when the first call's room ends`() = runTest(dispatcher) {
        val vm = viewModel()
        listening(vm)
        val gate = CompletableDeferred<Unit>()
        api.monitorGate = gate
        vm.listen(team)
        runCurrent()

        engines.single().emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
        runCurrent()
        assertEquals("not about the room that ended", team, vm.supervision.value.requestingCallId)
        gate.complete(Unit)
        runCurrent()

        assertEquals(team, vm.supervision.value.callId)
        assertTrue(vm.supervision.value.live)
    }

    // ── Whisper (calls/whisper) ──────────────────────────────────────────────────────────────────

    @Test
    fun `a note answered after the listening room ended rejoins nothing and sends nothing more`() =
        runTest(dispatcher) {
            val vm = viewModel()
            listening(vm)
            val gate = CompletableDeferred<Unit>()
            api.whisperGate = gate
            vm.setWhisperDraft(ai, "Offer Thursday.")
            vm.sendWhisper(ai)
            runCurrent()

            engines.single().emitConnection(CallConnectionState.Disconnected("ROOM_DELETED"))
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(1, api.whispers.size)
            assertEquals(1, engines.size)
            assertEquals(said(R.string.api_error_call_ended), vm.supervision.value)
        }

    // ── The session (the list read) ──────────────────────────────────────────────────────────────

    @Test
    fun `a re-read answered Unauthorized leaves the call`() = runTest(dispatcher) {
        val vm = viewModel()
        listening(vm)

        log.callsResult = ApiResult.Unauthorized(null)
        vm.refresh()
        runCurrent()

        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        assertEquals("disconnect", engines.single().calls.last())
    }

    @Test
    fun `a session change leaves the call, then reads the list again`() = runTest(dispatcher) {
        val vm = viewModel()
        listening(vm)
        val reads = log.reads

        vm.refresh(sessionChanged = true)
        runCurrent()

        assertEquals(LiveSupervisor.State(), vm.supervision.value)
        assertEquals("disconnect", engines.single().calls.last())
        assertEquals(reads + 1, log.reads)
    }

    // ── Why a joined room went away ──────────────────────────────────────────────────────────────

    @Test
    fun `a room that removed the supervisor says so at the call`() = runTest(dispatcher) {
        val vm = viewModel()
        listening(vm)

        engines.single().emitConnection(CallConnectionState.Disconnected("PARTICIPANT_REMOVED"))
        runCurrent()

        assertEquals(said(R.string.live_calls_removed), vm.supervision.value)
        assertEquals("disconnect", engines.single().calls.last())
    }

    @Test
    fun `each disconnect reason reads as the call ending, a removal, joining elsewhere, or a lost connection`() {
        val ended = UiText.Resource(R.string.api_error_call_ended)
        val lost = UiText.Resource(R.string.live_calls_connection_lost)
        assertEquals(ended, droppedText("ROOM_DELETED"))
        assertEquals(ended, droppedText("ROOM_CLOSED"))
        assertEquals(UiText.Resource(R.string.live_calls_removed), droppedText("PARTICIPANT_REMOVED"))
        assertEquals(UiText.Resource(R.string.live_calls_joined_elsewhere), droppedText("DUPLICATE_IDENTITY"))
        assertEquals(lost, droppedText("SIGNAL_CLOSE"))
        assertEquals(lost, droppedText("A_REASON_FROM_A_NEWER_SDK"))
        assertEquals("no reason is still a lost connection, never nothing", lost, droppedText(null))
        assertNull("this app's own leave", droppedText("CLIENT_INITIATED"))
    }
}
