package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.E2eeInfo
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestPersonaApi
import com.distronode.districtai.ui.rooms.FakeCallEngine
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One persona audition, which is a real billed call to the workspace's own voice agent.
 *
 * ⛔ THE STATE MACHINE IS THE ONLY PART OF THIS THAT CAN BE CHECKED AT ALL. "Start it and see" is
 * not available: every run mints a token, invites the agent into a room on the workspace's own
 * pipeline and burns STT, LLM and TTS minutes. Everything above the media seam is ordinary code
 * with a fake behind it; everything below it is the SDK.
 *
 * ⛔ AND THE TEST THAT MATTERS MOST IS THE ONE ABOUT TELECOM: this ViewModel holds no
 * `TelecomBridge` and cannot create a system `Connection`. That is asserted structurally by its
 * constructor rather than by a mock, which is why there is no test named for it — a test could only
 * observe an absence the type system already guarantees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonaPreviewViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var engineScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engineScope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() {
        engineScope.cancel()
        Dispatchers.resetMain()
    }

    private val credential = PersonaPreviewTokenResponse(
        success = true,
        token = "jwt",
        url = "wss://media",
        roomName = "preview_ws-1_0000",
        e2ee = E2eeInfo(key = "YfxKDUkaaGp2WrLLGHCHbe2nn5ArCWBd+x+k7EzDr/8="),
    )

    private fun api() = TestPersonaApi().apply { previewResult = ApiResult.Success(credential) }

    private fun viewModel(
        api: TestPersonaApi,
        factory: CallEngineFactory = FakeCallEngineFactory(),
    ) = PersonaPreviewViewModel(
        repository = PersonaOptionsRepository(api),
        workspaceId = "ws-1",
        engineFactory = factory,
        engineScope = engineScope,
        cooldownMillis = COOLDOWN,
    )

    private fun PersonaPreviewViewModel.audition(granted: Boolean = true) {
        start(PersonaPreviewForm(name = "Ada"))
        onMicrophonePermissionResult(granted)
    }

    @Test
    fun `the sheet opens idle and mints nothing`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        // ⛔ MINTING ON APPEAR WOULD CHARGE FOR A SCREEN SOMEBODY OPENED TO READ.
        assertEquals(PersonaPreviewPhase.Idle, vm.state.value.phase)
        assertTrue(api.previewCalls.isEmpty())
    }

    @Test
    fun `a started audition joins with the key verbatim and publishes the microphone`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()

        // ⛔ THE PASSPHRASE IS HANDED TO THE SDK UNCHANGED. Decoding the base64 text to 32 raw
        // bytes would select a different derivation, and the failure is not an error: both sides
        // join and every track is undecryptable noise.
        assertEquals(credential.e2ee?.key, factory.engine.lastE2eeKey)
        // ⛔ SPEAKER ON AND THE MICROPHONE PUBLISHED, which is the opposite of a meeting and right
        // here: an audition joined muted is an audition of nothing.
        assertTrue("speaker:true" in factory.engine.calls)
        assertTrue("mic:true" in factory.engine.calls)
    }

    @Test
    fun `connected without the agent is waiting, not live`() = runTest {
        // ⛔ TELLING SOMEBODY TO SPEAK BEFORE THE AGENT HAS JOINED has them talk into a room that
        // nothing is listening to, and then conclude the persona is broken.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Waiting, vm.state.value.phase)

        factory.engine.emitParticipants(
            listOf(MediaParticipant(identity = "agent", name = "Agent", isAgent = true)),
        )
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Live, vm.state.value.phase)
        assertTrue(vm.state.value.agentPresent)
    }

    @Test
    fun `a denied microphone still runs the session and says so`() = runTest {
        // ⚠️ NOT FATAL. The agent greets and can be heard, which is most of an audition — but it is
        // fatal to the POINT of one, so the screen says why nothing is being heard back.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition(granted = false)
        advanceUntilIdle()

        assertTrue(vm.state.value.microphoneDenied)
        assertEquals(PersonaPreviewPhase.Waiting, vm.state.value.phase)
        assertFalse("no microphone may be published", "mic:true" in factory.engine.calls)
    }

    @Test
    fun `a failed mint is final, and the cooldown runs on the refusal too`() = runTest {
        // ⛔ THE ROUTE IS NOT IDEMPOTENT AND EACH TOKEN STARTS A BILLED SESSION, so nothing retries.
        // ⚠️ AND THE COMMONEST REFUSAL IS THE 10/MIN CEILING ITSELF: a button that re-armed
        // instantly would invite somebody to spend the rest of the minute's slots finding out.
        val api = TestPersonaApi().apply {
            previewResult = ApiResult.NetworkFailure(IOException("down"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.audition()
        // ⚠️ `runCurrent`, NOT `advanceUntilIdle`. The cooldown is a real `delay`, and advancing to
        // idle would run it out — so the assertion below would pass on a ViewModel that never
        // started one.
        runCurrent()

        assertEquals(PersonaPreviewPhase.Failed, vm.state.value.phase)
        assertTrue(vm.state.value.cooling)
        assertFalse(vm.state.value.canStart)
        assertEquals(1, api.previewCalls.size)

        // ⚠️ AND IT ENDS. A cooldown that never cleared would be a control disabled for the life of
        // the screen.
        advanceUntilIdle()
        assertFalse(vm.state.value.cooling)
    }

    @Test
    fun `a second start while one is running mints nothing`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()
        vm.start(PersonaPreviewForm(name = "Ada"))
        advanceUntilIdle()

        assertEquals("exactly one billed session", 1, api.previewCalls.size)
    }

    @Test
    fun `stopping disconnects, reports who stopped it, and cools down`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()
        vm.stop()
        // ⚠️ See the note in the failed-mint test: advancing to idle would run the cooldown out.
        runCurrent()

        assertEquals(
            PersonaPreviewPhase.Ended(PersonaPreviewEnding.Stopped),
            vm.state.value.phase,
        )
        assertTrue("disconnect" in factory.engine.calls)
        assertTrue(vm.state.value.cooling)
    }

    @Test
    fun `a session the server dropped is NOT reported as one the operator stopped`() = runTest {
        // ⛔ ON A SCREEN WHOSE NEXT ACTION COSTS MONEY, telling somebody they stopped something
        // they did not is how a second billed session gets started.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()
        factory.engine.emitConnection(CallConnectionState.Disconnected("room_deleted"))
        advanceUntilIdle()

        assertEquals(
            PersonaPreviewPhase.Ended(PersonaPreviewEnding.DroppedRemotely("room_deleted")),
            vm.state.value.phase,
        )
    }

    @Test
    fun `a roster update after the end does not put a finished audition back on screen`() = runTest {
        // ⛔ A ROSTER CHANGE CAN ARRIVE AFTER THE SDK HAS REPORTED A DISCONNECT. A phase machine
        // that let it through would show a finished session as live, with the Start button disabled
        // and nothing to stop.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()
        vm.stop()
        advanceUntilIdle()

        factory.engine.emitParticipants(
            listOf(MediaParticipant(identity = "agent", name = "Agent", isAgent = true)),
        )
        advanceUntilIdle()

        assertEquals(
            PersonaPreviewPhase.Ended(PersonaPreviewEnding.Stopped),
            vm.state.value.phase,
        )
    }

    private val agent = MediaParticipant(identity = "agent", name = "Agent", isAgent = true)

    @Test
    fun `a permission answer that arrives after the sheet was stopped mints nothing`() = runTest {
        // ⛔ THE PERMISSION CALLBACK CAN OUTLIVE THE SHEET. Minting then would start a billed
        // session for a screen nobody is looking at.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.start(PersonaPreviewForm(name = "Ada"))
        vm.stop()
        vm.onMicrophonePermissionResult(true)
        advanceUntilIdle()

        assertTrue(api.previewCalls.isEmpty())
        assertEquals(PersonaPreviewPhase.Idle, vm.state.value.phase)
    }

    @Test
    fun `a repeated permission answer during the cooldown mints nothing`() = runTest {
        // ⚠️ THE FORM IS STILL PENDING AFTER A REFUSAL, so a second answer from the OS prompt would
        // reach the mint if the cooldown did not also gate this entry point.
        val api = TestPersonaApi().apply {
            previewResult = ApiResult.NetworkFailure(IOException("down"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.audition()
        runCurrent()
        assertTrue(vm.state.value.cooling)
        vm.onMicrophonePermissionResult(true)
        runCurrent()

        assertEquals("exactly one billed attempt", 1, api.previewCalls.size)
    }

    @Test
    fun `a join that throws ends in Failed and publishes nothing`() = runTest {
        // ⚠️ The engine reports Failed before it throws; the ViewModel must neither swallow that
        // phase nor go on to open the speaker and the microphone on a room it never joined.
        val factory = FakeCallEngineFactory().apply {
            engine.connectFailure = IllegalStateException("media unreachable")
        }
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        vm.audition()
        runCurrent()

        assertEquals(PersonaPreviewPhase.Failed, vm.state.value.phase)
        assertTrue(vm.state.value.cooling)
        assertFalse("speaker:true" in factory.engine.calls)
        assertFalse("mic:true" in factory.engine.calls)
    }

    @Test
    fun `stopping a sheet that never started still tears down, and does not cool down`() =
        runTest {
            // ⛔ THE TEARDOWN RUNS FROM EVERY STATE. Only the phase and the cooldown depend on
            // whether anything was running, and nothing was.
            val factory = FakeCallEngineFactory()
            val vm = viewModel(api(), factory)
            advanceUntilIdle()

            vm.stop()
            runCurrent()

            assertEquals(PersonaPreviewPhase.Idle, vm.state.value.phase)
            assertFalse(vm.state.value.cooling)
            assertEquals(listOf("disconnect"), factory.engine.calls)
        }

    @Test
    fun `the phase follows the media layer through connecting, live and reconnecting`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api(), factory)
        advanceUntilIdle()

        // ⚠️ A roster update before any join moves no phase: the sheet is not in a room yet.
        factory.engine.emitParticipants(listOf(agent))
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Idle, vm.state.value.phase)

        // The agent was already in the room, so a completed join is live at once.
        vm.audition()
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Live, vm.state.value.phase)

        factory.engine.emitConnection(CallConnectionState.Connecting)
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Connecting, vm.state.value.phase)

        factory.engine.emitConnection(CallConnectionState.Reconnecting)
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Reconnecting, vm.state.value.phase)

        // ⚠️ The engine at rest says nothing about the session: the phase is left where it was.
        factory.engine.emitConnection(CallConnectionState.Idle)
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Reconnecting, vm.state.value.phase)

        // The agent leaving mid-session drops the sheet back to waiting rather than live.
        factory.engine.emitParticipants(emptyList())
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Waiting, vm.state.value.phase)
        assertFalse(vm.state.value.agentPresent)
    }

    @Test
    fun `a failure after a drop restarts the cooldown rather than stacking a second one`() =
        runTest {
            // ⚠️ THE LATER ENDING OWNS THE COOLDOWN. Two timers running would clear the flag at the
            // first one's deadline and re-arm the button early.
            val factory = FakeCallEngineFactory()
            val vm = viewModel(api(), factory)
            advanceUntilIdle()
            vm.audition()
            advanceUntilIdle()

            factory.engine.emitConnection(CallConnectionState.Disconnected("room_deleted"))
            runCurrent()
            advanceTimeBy(COOLDOWN / 2)
            factory.engine.emitConnection(CallConnectionState.Failed("gone"))
            runCurrent()
            assertEquals(PersonaPreviewPhase.Failed, vm.state.value.phase)

            advanceTimeBy(COOLDOWN / 2 + 1)
            assertTrue("the first deadline no longer clears it", vm.state.value.cooling)
            advanceTimeBy(COOLDOWN / 2)
            assertFalse(vm.state.value.cooling)
        }

    @Test
    fun `the factory's ViewModel tears the session down once when it is cleared`() = runTest {
        // ⛔ A DISMISSED SCREEN MUST NOT LEAVE A ROOM PUBLISHING THIS PHONE'S MICROPHONE. Clearing
        // after a stop must not send a second disconnect either.
        val factory = FakeCallEngineFactory()
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            PersonaPreviewViewModel.factory(PersonaOptionsRepository(api()), "ws-1", factory),
        )[PersonaPreviewViewModel::class.java]
        advanceUntilIdle()

        vm.audition()
        advanceUntilIdle()
        assertEquals(PersonaPreviewPhase.Waiting, vm.state.value.phase)

        vm.stop()
        store.clear()
        advanceUntilIdle()

        assertEquals(1, factory.engine.calls.count { it == "disconnect" })
        assertFalse(vm.state.value.micEnabled)
    }

    @Test
    fun `clearing a live session disconnects it`() = runTest {
        val factory = FakeCallEngineFactory()
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            PersonaPreviewViewModel.factory(PersonaOptionsRepository(api()), "ws-1", factory),
        )[PersonaPreviewViewModel::class.java]
        advanceUntilIdle()
        vm.audition()
        advanceUntilIdle()
        assertTrue(vm.state.value.micEnabled)

        store.clear()
        advanceUntilIdle()

        assertEquals("disconnect", factory.engine.calls.last())
        assertFalse("nothing may still claim a live microphone", vm.state.value.micEnabled)
    }

    /**
     * An engine whose `connect` waits for [gate]: a join in flight.
     *
     * @param ignoresCancellation true models an SDK whose join does not stop when its coroutine is
     *   cancelled, which is the case the released check after the connect exists for.
     */
    private fun gatedFactory(gate: CompletableDeferred<Unit>, engine: FakeCallEngine, ignoresCancellation: Boolean) =
        CallEngineFactory {
            object : CallEngine by engine {
                override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
                    if (ignoresCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
                    engine.connect(url, token, e2eeKeyBase64)
                }
            }
        }

    @Test
    fun `stopping while the token is minting cancels the join`() = runTest {
        // ⛔ THE BUG THIS PINS: nothing cancelled the mint, so a sheet dismissed during the round
        // trip joined a billed agent session afterwards, speaker on and microphone published, with
        // no UI left to stop it and `onCleared` a no-op.
        val api = api().apply { previewGate = CompletableDeferred() }
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api, factory)
        advanceUntilIdle()
        vm.audition()
        runCurrent()
        assertEquals(PersonaPreviewPhase.Minting, vm.state.value.phase)

        vm.stop()
        runCurrent()
        api.previewGate?.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("disconnect"), factory.engine.calls)
        assertEquals(PersonaPreviewPhase.Ended(PersonaPreviewEnding.Stopped), vm.state.value.phase)
    }

    @Test
    fun `stopping while the join is in flight leaves the room and publishes nothing`() = runTest {
        // ⛔ THE JOIN IS CANCELLED WITH THE MINT, AND A DISCONNECT FOLLOWS IT ANYWAY: nothing orders
        // `release`'s disconnect against a half-finished join inside the SDK.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine()
        val vm = viewModel(api(), gatedFactory(gate, engine, ignoresCancellation = false))
        advanceUntilIdle()
        vm.audition()
        runCurrent()
        assertEquals(PersonaPreviewPhase.Connecting, vm.state.value.phase)

        vm.stop()
        runCurrent()

        assertEquals(listOf("disconnect", "disconnect"), engine.calls)
        assertEquals(PersonaPreviewPhase.Ended(PersonaPreviewEnding.Stopped), vm.state.value.phase)
    }

    @Test
    fun `a join that resolves after the stop is disconnected again, with nothing published`() = runTest {
        // ⛔ AN SDK THAT FINISHES ITS JOIN DESPITE THE CANCELLATION still reaches the line after
        // `connect`; the released check there is what keeps the microphone and speaker off.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine()
        val vm = viewModel(api(), gatedFactory(gate, engine, ignoresCancellation = true))
        advanceUntilIdle()
        vm.audition()
        runCurrent()

        vm.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("disconnect", "connect:wss://media:jwt", "disconnect"), engine.calls)
        assertEquals(PersonaPreviewPhase.Ended(PersonaPreviewEnding.Stopped), vm.state.value.phase)
    }

    private companion object {
        const val COOLDOWN = 5_000L
    }
}
