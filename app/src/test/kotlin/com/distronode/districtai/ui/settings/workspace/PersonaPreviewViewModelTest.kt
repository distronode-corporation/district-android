package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.E2eeInfo
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestPersonaApi
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
        factory: FakeCallEngineFactory = FakeCallEngineFactory(),
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

    private companion object {
        const val COOLDOWN = 5_000L
    }
}
