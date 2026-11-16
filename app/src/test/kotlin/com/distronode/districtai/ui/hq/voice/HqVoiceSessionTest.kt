package com.distronode.districtai.ui.hq.voice

import com.distronode.districtai.core.media.HqVoiceEngineEvent
import com.distronode.districtai.core.model.HqAgentState
import com.distronode.districtai.core.model.HqMicMode
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoice
import com.distronode.districtai.core.model.HqVoiceDiscardResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeHqVoiceApi
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The voice session's rules, against a scripted engine and API: the consent before the
 * microphone, the discard-then-end of a session that never went live, the end of one that did,
 * push-to-talk, and every way a session stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HqVoiceSessionTest : HqVoiceSessionCase() {

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
}
