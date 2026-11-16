package com.distronode.districtai.ui.hq.voice

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.distronode.districtai.core.data.HqVoiceRepository
import com.distronode.districtai.core.media.HqVoiceEngineEvent
import com.distronode.districtai.core.media.HqVoiceEngineFactory
import com.distronode.districtai.core.model.HqAgentState
import com.distronode.districtai.core.model.HqConfirmResponse
import com.distronode.districtai.core.model.HqMicMode
import com.distronode.districtai.core.model.HqResolutionStatus
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoice
import com.distronode.districtai.core.model.HqVoiceDiscardResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice session's rules, against a scripted engine and API: the consent before the
 * microphone, the discard-then-end of a session that never went live, the end of one that did,
 * push-to-talk, and every way a session stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HqVoiceSessionTest {

    private val api = FakeHqVoiceApi()
    private val hq = FakeDistrictApi()
    private val engines = mutableListOf<FakeHqVoiceEngine>()
    private var busy = false
    private var permission = true
    private val audio = object : HqVoiceAudio {
        override fun isBusy() = busy
        override fun hasMicrophonePermission() = permission
    }

    /** Set before an engine is built, so a test can script its join. */
    private var prepare: (FakeHqVoiceEngine) -> Unit = {}

    private fun TestScope.session(canConfirm: Boolean = true) = HqVoiceSession(
        workspaceId = "ws-1",
        canConfirm = canConfirm,
        repository = HqVoiceRepository(api, hq),
        engineFactory = HqVoiceEngineFactory { _, key -> FakeHqVoiceEngine(key).also(prepare).also { engines += it } },
        audio = audio,
        scope = backgroundScope,
    )

    private val engine get() = engines.single()
    private val HqVoiceSession.phase get() = state.value.phase

    private fun TestScope.toNotice(session: HqVoiceSession, history: List<HqTurn> = emptyList()) {
        session.start(history, "en")
        runCurrent()
    }

    private fun TestScope.toLive(session: HqVoiceSession) {
        toNotice(session)
        session.begin()
        runCurrent()
    }

    private fun push() {
        api.tokenResult = ApiResult.Success(FakeHqVoiceApi.CREDENTIAL.copy(micMode = "push"))
    }

    // ── Before the microphone ────────────────────────────────────────────────────────────────────

    @Test
    fun `Talk mints a token and shows the notice, and nothing is joined or asked for yet`() = runTest {
        val session = session()
        permission = false
        val history = listOf(HqTurn("user", "How many calls?"))

        toNotice(session, history)

        assertEquals(HqVoicePhase.Notice(FakeHqVoiceApi.CREDENTIAL), session.phase)
        assertEquals(Triple("ws-1", "en", history), api.tokenRequests.single())
        assertTrue("no room before the member's Start", engines.isEmpty())
        assertEquals("no permission prompt before the consent", 0, session.state.value.microphoneRequest)
        assertTrue(session.phase.isActive)
    }

    @Test
    fun `voice does not start during a call or a meeting, before or at Start`() = runTest {
        val session = session()
        busy = true
        toNotice(session)
        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.Busy), session.phase)
        assertTrue("no token is minted", api.calls.isEmpty())

        busy = false
        toNotice(session)
        busy = true
        session.begin()
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.Busy), session.phase)
        assertEquals(listOf("token", "discard:s1"), api.calls)
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `the allowance and capacity refusals are the service's own, anything else a failure`() = runTest {
        val session = session()
        api.tokenResult = ApiResult.HttpFailure(409, "All 120 minutes used.", HqVoice.ALLOWANCE_REACHED_CODE)
        toNotice(session)
        val refused = (session.phase as HqVoicePhase.Ended).reason as HqVoiceEndReason.Refused
        assertEquals("All 120 minutes used.", refused.failure.message.literalOrNull)

        api.tokenResult = ApiResult.HttpFailure(409, "Busy.", HqVoice.BUSY_CODE)
        toNotice(session)
        assertTrue((session.phase as HqVoicePhase.Ended).reason is HqVoiceEndReason.Refused)

        api.tokenResult = ApiResult.NetworkFailure(IOException("offline"))
        toNotice(session)
        assertTrue((session.phase as HqVoicePhase.Ended).reason is HqVoiceEndReason.Failed)
    }

    @Test
    fun `declining the notice joins nothing and discards the session`() = runTest {
        val session = session()
        toNotice(session)

        session.cancelNotice()
        runCurrent()

        assertEquals(HqVoicePhase.Idle, session.phase)
        assertEquals(listOf("token", "discard:s1"), api.calls)
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `a discard the service refuses, or that fails, is followed by an end`() = runTest {
        val session = session()
        api.discardResult = ApiResult.Success(HqVoiceDiscardResponse(success = true, discarded = false))
        toNotice(session)
        session.cancelNotice()
        runCurrent()
        assertEquals(listOf("token", "discard:s1", "end:s1"), api.calls)

        api.calls.clear()
        api.discardResult = ApiResult.NetworkFailure(IOException("offline"))
        toNotice(session)
        session.stop()
        runCurrent()
        assertEquals(listOf("token", "discard:s1", "end:s1"), api.calls)
    }

    @Test
    fun `the microphone is asked for only after Start, and a refusal discards the session`() = runTest {
        val session = session()
        permission = false
        toNotice(session)

        session.begin()
        runCurrent()

        assertEquals(HqVoicePhase.Permission(FakeHqVoiceApi.CREDENTIAL), session.phase)
        assertEquals(1, session.state.value.microphoneRequest)
        session.onMicrophoneRequested()
        assertEquals("asked once, not again on a rotation", 0, session.state.value.microphoneRequest)

        session.onMicrophonePermission(false)
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.MicrophoneDenied), session.phase)
        assertEquals(listOf("token", "discard:s1"), api.calls)
        assertTrue(engines.isEmpty())
    }

    @Test
    fun `a granted microphone joins, and Stop while it is asked discards`() = runTest {
        val session = session()
        permission = false
        toNotice(session)
        session.begin()
        runCurrent()

        session.onMicrophonePermission(true)
        runCurrent()
        assertEquals(HqVoicePhase.Live, session.phase)

        val second = session()
        toNotice(second)
        second.begin()
        runCurrent()
        second.stop()
        runCurrent()
        assertEquals(HqVoicePhase.Idle, second.phase)
        // ⚠️ A late answer to a request that no longer matters does nothing.
        second.onMicrophonePermission(true)
        runCurrent()
        assertEquals(HqVoicePhase.Idle, second.phase)
        assertEquals("discard:s1", api.calls.last())
    }

    // ── Live ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Start joins the encrypted room, opens an open microphone and reports the HQ screen`() = runTest {
        val session = session()

        toLive(session)

        assertEquals(HqVoicePhase.Live, session.phase)
        assertEquals("key", engine.key)
        assertEquals(listOf("wss://media.example" to "jwt"), engine.joined)
        assertEquals(listOf(true), engine.microphone)
        assertTrue(session.state.value.isMicrophoneOn)
        assertEquals(listOf(1 to "hq"), api.contextReports)
        assertEquals("nothing is ended or discarded while live", listOf("token", "context"), api.calls)
    }

    @Test
    fun `Stop ends a live session by its id, so only the minutes used are counted`() = runTest {
        val session = session()
        toLive(session)

        session.stop()
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.ByMember), session.phase)
        assertEquals("end:s1", api.calls.last())
        assertFalse(api.calls.contains("discard:s1"))
        assertEquals(1, engine.disconnects)
        assertFalse(session.state.value.isMicrophoneOn)
        assertFalse(session.phase.isActive)
    }

    @Test
    fun `push-to-talk starts muted and opens only while held`() = runTest {
        push()
        val session = session()
        toLive(session)
        assertEquals(HqMicMode.PUSH, session.state.value.micMode)
        assertTrue("muted at the join", engine.microphone.isEmpty())

        session.toggleMute()
        runCurrent()
        assertTrue("mute is not push-to-talk's", engine.microphone.isEmpty())

        session.holdToTalk(true)
        runCurrent()
        assertTrue(session.state.value.isMicrophoneOn)
        session.holdToTalk(false)
        runCurrent()
        assertFalse(session.state.value.isMicrophoneOn)
        assertEquals(listOf(true, false), engine.microphone)
    }

    @Test
    fun `an open microphone mutes and unmutes, and ignores Hold to talk`() = runTest {
        val session = session()
        toLive(session)

        session.holdToTalk(false)
        runCurrent()
        session.toggleMute()
        runCurrent()
        assertFalse(session.state.value.isMicrophoneOn)
        session.toggleMute()
        runCurrent()

        assertTrue(session.state.value.isMicrophoneOn)
        assertEquals(listOf(true, false, true), engine.microphone)
    }

    @Test
    fun `a quick press and release finish in order, and an overtaken change is skipped`() = runTest {
        push()
        val session = session()
        toLive(session)
        val gate = CompletableDeferred<Unit>()
        engine.microphoneGates += gate

        session.holdToTalk(true)
        runCurrent()
        session.holdToTalk(false)
        session.holdToTalk(true)
        session.holdToTalk(false)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertFalse("released last, so closed", session.state.value.isMicrophoneOn)
        assertEquals("the overtaken open is never sent", listOf(true, false, false), engine.microphone)
    }

    @Test
    fun `a refused microphone change shows the state the SDK was left in`() = runTest {
        push()
        val session = session()
        toLive(session)
        engine.microphoneAnswer = { !it }

        session.holdToTalk(true)
        runCurrent()

        assertFalse(session.state.value.isMicrophoneOn)
    }

    @Test
    fun `a session lasts thirty minutes, then it is ended`() = runTest {
        val session = session()
        toLive(session)
        engine.emit(HqVoiceEngineEvent.AgentState("listening"))
        runCurrent()

        advanceTimeBy(HqVoice.SESSION_SECONDS * 1_000 - 1)
        runCurrent()
        assertEquals(HqVoicePhase.Live, session.phase)
        advanceTimeBy(2)
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.TimedOut), session.phase)
        assertEquals("end:s1", api.calls.last())
    }

    @Test
    fun `no agent within twenty seconds discards the live session`() = runTest {
        val session = session()
        toLive(session)

        advanceTimeBy(HqVoice.AGENT_JOIN_SECONDS * 1_000 + 1)
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.NoAgent), session.phase)
        assertEquals("discard:s1", api.calls.last())
        assertFalse(api.calls.contains("end:s1"))
    }

    @Test
    fun `an agent's state closes the join window, and one heard during the join never opens it`() = runTest {
        val session = session()
        toLive(session)
        engine.emit(HqVoiceEngineEvent.AgentState("speaking"))
        engine.emit(HqVoiceEngineEvent.AgentState("dancing"))
        runCurrent()
        advanceTimeBy(HqVoice.AGENT_JOIN_SECONDS * 1_000 + 1)
        runCurrent()
        assertEquals(HqVoicePhase.Live, session.phase)
        assertEquals("an unknown state is ignored", HqAgentState.SPEAKING, session.state.value.agentState)

        val gate = CompletableDeferred<Unit>()
        prepare = { it.connectGate = gate }
        val early = session()
        toNotice(early)
        early.begin()
        runCurrent()
        engines.last().emit(HqVoiceEngineEvent.AgentState("listening"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(HqVoice.AGENT_JOIN_SECONDS * 1_000 + 1)
        runCurrent()
        assertEquals(HqVoicePhase.Live, early.phase)
    }

    @Test
    fun `the transcript keeps final segments in order, replacing one by its id`() = runTest {
        val session = session()
        toLive(session)

        engine.emit(HqVoiceEngineEvent.Transcript("a", "Hello", fromMember = true))
        engine.emit(HqVoiceEngineEvent.Transcript("b", "Hi.", fromMember = false))
        engine.emit(HqVoiceEngineEvent.Transcript("a", "Hello there", fromMember = true))
        runCurrent()

        assertEquals(
            listOf(HqVoiceSegment("a", "Hello there", true), HqVoiceSegment("b", "Hi.", false)),
            session.state.value.segments,
        )
    }

    // ── Proposals ────────────────────────────────────────────────────────────────────────────────

    private val proposal =
        """{"type":"HQ_PROPOSAL","proposalId":"p1","tool":"update_persona","args":{},"summary":"S"}"""

    private fun confirmed(executed: Boolean) =
        ApiResult.Success(HqConfirmResponse(success = true, executed = executed, tool = "update_persona"))

    private fun FakeHqVoiceEngine.propose(json: String = proposal) =
        emit(HqVoiceEngineEvent.Packet(json.encodeToByteArray()))

    @Test
    fun `proposals arrive once each, and a voice resolution settles one`() = runTest {
        val session = session()
        toLive(session)

        engine.propose()
        engine.propose()
        engine.propose("not a packet")
        engine.propose("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"p1","status":"applied","via":"voice"}""")
        engine.propose("""{"type":"HQ_PROPOSAL_RESOLVED","proposalId":"zz","status":"failed","via":"voice"}""")
        runCurrent()

        val card = session.state.value.proposals.single()
        assertEquals("p1", card.id)
        assertEquals(HqResolutionStatus.APPLIED, card.outcome)
    }

    @Test
    fun `a tap confirm applies the proposal, once`() = runTest {
        val session = session()
        toLive(session)
        engine.propose()
        runCurrent()
        hq.hqConfirmResult = confirmed(executed = true)

        session.confirm("p1")
        session.confirm("p1")
        runCurrent()
        session.confirm("p1")
        session.confirm("unknown")
        runCurrent()

        assertEquals(HqResolutionStatus.APPLIED, session.state.value.proposals.single().outcome)
        assertEquals("p1", hq.hqConfirms.single().confirm.proposalId)
    }

    @Test
    fun `a declined or failed tap confirm reads as not applied, and a viewer cannot confirm`() = runTest {
        val session = session()
        toLive(session)
        engine.propose()
        engine.propose(proposal.replace("p1", "p2"))
        runCurrent()

        hq.hqConfirmResult = confirmed(executed = false)
        session.confirm("p1")
        runCurrent()
        hq.hqConfirmResult = ApiResult.NetworkFailure(IOException("offline"))
        session.confirm("p2")
        runCurrent()

        assertEquals(
            listOf(HqResolutionStatus.FAILED, HqResolutionStatus.FAILED),
            session.state.value.proposals.map { it.outcome },
        )

        val viewer = session(canConfirm = false)
        toLive(viewer)
        engines.last().propose()
        runCurrent()
        viewer.confirm("p1")
        runCurrent()
        assertNull(viewer.state.value.proposals.single().outcome)
        assertEquals(2, hq.hqConfirms.size)
    }

    @Test
    fun `a tap cancel cancels the proposal, and a failed one leaves it open`() = runTest {
        val session = session()
        toLive(session)
        engine.propose()
        engine.propose(proposal.replace("p1", "p2"))
        runCurrent()

        session.cancel("p1")
        session.cancel("p1")
        runCurrent()
        session.cancel("p1")
        session.cancel("unknown")
        api.cancelResult = ApiResult.NetworkFailure(IOException("offline"))
        session.cancel("p2")
        runCurrent()

        assertEquals(
            listOf(HqResolutionStatus.CANCELLED, null),
            session.state.value.proposals.map { it.outcome },
        )
        assertEquals(listOf("cancel:p1", "cancel:p2"), api.calls.filter { it.startsWith("cancel") })
    }

    @Test
    fun `a second tap while a cancel is in flight sends nothing`() = runTest {
        val session = session()
        toLive(session)
        engine.propose()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        api.cancelGate = gate

        session.cancel("p1")
        runCurrent()
        assertTrue(session.state.value.proposals.single().isWorking)
        session.cancel("p1")
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(listOf("cancel:p1"), api.calls.filter { it.startsWith("cancel") })
        assertEquals(HqResolutionStatus.CANCELLED, session.state.value.proposals.single().outcome)
    }

    @Test
    fun `a stop ends the session unless the activity is only being recreated`() {
        assertTrue(endsOnStop(false))
        assertTrue("no activity is not a rotation", endsOnStop(null))
        assertFalse(endsOnStop(true))
    }

    // ── How it stops ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the room closing on its own ends the session and tells the service nothing`() = runTest {
        val session = session()
        toLive(session)

        engine.emit(HqVoiceEngineEvent.Disconnected)
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.Disconnected), session.phase)
        assertEquals(listOf("token", "context"), api.calls)
    }

    @Test
    fun `the agent leaving, or the audio being taken, ends a live session`() = runTest {
        val session = session()
        toLive(session)
        engine.emit(HqVoiceEngineEvent.AgentLeft)
        runCurrent()
        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.AgentLeft), session.phase)
        assertEquals("end:s1", api.calls.last())

        val second = session()
        toLive(second)
        engines.last().emit(HqVoiceEngineEvent.AudioInterrupted)
        runCurrent()
        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.Interrupted), second.phase)
        assertEquals("end:s1", api.calls.last())
    }

    @Test
    fun `a failed join, or a room closed during it, discards the session`() = runTest {
        prepare = { it.connectError = IOException("no route") }
        val session = session()
        toLive(session)
        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.JoinFailed), session.phase)
        assertEquals("discard:s1", api.calls.last())

        val gate = CompletableDeferred<Unit>()
        prepare = { it.connectGate = gate }
        val second = session()
        toLive(second)
        assertEquals(HqVoicePhase.Connecting, second.phase)
        engines.last().emit(HqVoiceEngineEvent.AgentLeft)
        engines.last().emit(HqVoiceEngineEvent.Disconnected)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.JoinFailed), second.phase)
        assertEquals("discard:s1", api.calls.last())
    }

    @Test
    fun `Stop during the join discards, and a join that lands after it is left at once`() = runTest {
        val gate = CompletableDeferred<Unit>()
        prepare = { it.connectGate = gate }
        val session = session()
        toLive(session)

        session.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.ByMember), session.phase)
        assertEquals("discard:s1", api.calls.last())
        assertEquals("disconnected by the Stop and again when the join landed", 2, engine.disconnects)
        assertTrue(engine.microphone.isEmpty())
    }

    @Test
    fun `a Stop while the token is in flight gives the token back when it lands`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.tokenGate = gate
        val session = session()
        session.start(emptyList(), "en")
        runCurrent()
        assertEquals(HqVoicePhase.Minting, session.phase)

        session.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(HqVoicePhase.Idle, session.phase)
        assertEquals(listOf("token", "discard:s1"), api.calls)
    }

    @Test
    fun `a token overtaken by a Stop and a new Talk is given back, and the new one shown`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.tokenGate = gate
        val session = session()
        session.start(emptyList(), "en")
        runCurrent()
        session.end()
        runCurrent()
        session.start(emptyList(), "fr")
        session.start(emptyList(), "fr")
        runCurrent()

        gate.complete(Unit)
        runCurrent()

        assertEquals(HqVoicePhase.Notice(FakeHqVoiceApi.CREDENTIAL), session.phase)
        assertEquals("one Talk while active is ignored", 2, api.tokenRequests.size)
        assertEquals(1, api.calls.count { it == "discard:s1" })
    }

    @Test
    fun `an overtaken token that failed has nothing to give back`() = runTest {
        val gate = CompletableDeferred<Unit>()
        api.tokenGate = gate
        api.tokenResult = ApiResult.NetworkFailure(IOException("offline"))
        val session = session()
        session.start(emptyList(), "en")
        runCurrent()
        session.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(HqVoicePhase.Idle, session.phase)
        assertEquals(listOf("token"), api.calls)
    }

    @Test
    fun `a Stop while the open microphone publishes ends the session without a clock or a report`() = runTest {
        val gate = CompletableDeferred<Unit>()
        prepare = { it.microphoneGates += gate }
        val session = session()
        toLive(session)
        session.toggleMute()
        runCurrent()

        session.stop()
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.ByMember), session.phase)
        assertFalse(session.state.value.isMicrophoneOn)
        assertEquals("the queued mute never reaches a gone room", listOf(true), engine.microphone)
        assertEquals(listOf("token", "end:s1"), api.calls)
    }

    @Test
    fun `the screen going away ends a session in any phase`() = runTest {
        val live = session()
        toLive(live)
        live.end()
        runCurrent()
        assertEquals("end:s1", api.calls.last())

        val notice = session()
        toNotice(notice)
        notice.end()
        runCurrent()
        assertEquals(HqVoicePhase.Idle, notice.phase)
        assertEquals("discard:s1", api.calls.last())

        val idle = session()
        idle.end()
        runCurrent()
        assertEquals(HqVoicePhase.Idle, idle.phase)

        val gate = CompletableDeferred<Unit>()
        prepare = { it.connectGate = gate }
        val joining = session()
        toLive(joining)
        joining.end()
        runCurrent()
        assertEquals(HqVoicePhase.Ended(HqVoiceEndReason.ByMember), joining.phase)
    }

    @Test
    fun `controls outside their phase do nothing`() = runTest {
        val session = session()
        session.begin()
        session.cancelNotice()
        session.onMicrophonePermission(true)
        session.holdToTalk(true)
        session.toggleMute()
        session.stop()
        runCurrent()

        assertEquals(HqVoicePhase.Idle, session.phase)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `clearing the ViewModel ends its session`() = runTest {
        val session = session()
        toLive(session)
        val store = ViewModelStore()
        val viewModel = ViewModelProvider.create(store, HqVoiceViewModel.factory { session })[HqVoiceViewModel::class]
        assertTrue(viewModel.session === session)

        store.clear()
        runCurrent()

        assertEquals("end:s1", api.calls.last())
    }

    @Test
    fun `each phase and reason answers what the bar draws`() {
        val credential = FakeHqVoiceApi.CREDENTIAL
        assertEquals(
            listOf(false, true, true, true, true, true, false),
            listOf(
                HqVoicePhase.Idle,
                HqVoicePhase.Minting,
                HqVoicePhase.Notice(credential),
                HqVoicePhase.Permission(credential),
                HqVoicePhase.Connecting,
                HqVoicePhase.Live,
                HqVoicePhase.Ended(HqVoiceEndReason.ByMember),
            ).map { it.isActive },
        )
        val failure = com.distronode.districtai.ui.FailureText(com.distronode.districtai.ui.UiText.Literal("x"))
        assertEquals(
            listOf(false, false, false, false, false, true, true, true, true, true, true),
            listOf(
                HqVoiceEndReason.ByMember, HqVoiceEndReason.Interrupted, HqVoiceEndReason.TimedOut,
                HqVoiceEndReason.Disconnected, HqVoiceEndReason.AgentLeft, HqVoiceEndReason.Busy,
                HqVoiceEndReason.NoAgent, HqVoiceEndReason.MicrophoneDenied, HqVoiceEndReason.JoinFailed,
                HqVoiceEndReason.Refused(failure), HqVoiceEndReason.Failed(failure),
            ).map { it.isFailure },
        )
    }

    @Test
    fun `a coded failure's code is read wherever the client carries one`() {
        assertEquals("c", ApiResult.HttpFailure(409, "m", "c").code())
        assertEquals("c", ApiResult.Forbidden("m", "c").code())
        assertEquals("c", ApiResult.NotFound("m", "c").code())
        assertEquals("c", ApiResult.RateLimited("m", code = "c").code())
        assertNull(ApiResult.Unauthorized(null).code())
        assertNull(ApiResult.RegionsDegraded("m", emptyList()).code())
        assertNull(ApiResult.NetworkFailure(IOException()).code())
        assertNull(ApiResult.DecodeFailure(IllegalStateException(), "").code())
    }
}
