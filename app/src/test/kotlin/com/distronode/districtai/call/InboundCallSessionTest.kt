package com.distronode.districtai.call

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.ui.dialer.CallPhase
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.rooms.FakeCallEngine
import com.distronode.districtai.core.media.CallEngine
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * One ANSWERED inbound call: its engine, its Telecom latch and its clock.
 *
 * ⛔ THE ANSWER SIGNAL IS THE WHOLE REASON THIS IS NOT `SoftphoneSession`. An outbound call is
 * answered when a PARTICIPANT APPEARS — the SIP bridge adds the callee to the room at pickup, and
 * that is the only ring-versus-answer signal that path has. An inbound call joins a room that
 * ALREADY contains the caller and the AI, so participants are present from the first frame and a
 * session that latched on them would report "answered" before it had connected to anything.
 *
 * ⛔ AND THE TELECOM ORDERING IS THE SECOND REASON. The connection is created RINGING when the push
 * arrives, minutes of user-visible ringing before this object exists; it becomes ACTIVE only when
 * media is up. A session that announced the call again would ask Telecom for a SECOND connection for
 * the same call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboundCallSessionTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * The scope the session's ENGINE is built with.
     *
     * ⛔ SEPARATE FROM THE OBSERVE SCOPE AND FROM `runTest`'s OWN, because [InboundCallSession.end]
     * CANCELS IT FROM INSIDE — that is the whole reason it exists in production, where it outlives
     * `onCleared` by exactly the length of a disconnect. Sharing one with the test would have the
     * first hang-up cancel the test itself.
     */
    private lateinit var engineScope: CoroutineScope

    /** What the UI would collect on. ⚠️ Test-owned so `advanceUntilIdle()` actually runs it. */
    private lateinit var observeScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        engineScope = CoroutineScope(dispatcher)
        observeScope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() {
        observeScope.cancel()
        engineScope.cancel()
        Dispatchers.resetMain()
    }

    /**
     * ⛔ `runCurrent()` RATHER THAN `advanceUntilIdle()` ONCE A CALL HAS CONNECTED. A connected call
     * runs a duration ticker — `while (true) { delay(tick) ... }` — so a task is ALWAYS pending on
     * the virtual clock, and `advanceUntilIdle()` advances forever and never returns. The test does
     * not fail; the JVM spins, and a suite run sat at 100% CPU for 24 minutes with no output.
     *
     * ⚠️ Enough on its own because nothing on the connect path delays: the join, the collectors and
     * the teardown are zero-delay dispatches, and `runCurrent` drains chained ones at the current
     * instant. Where virtual TIME must pass, the tests advance it by an explicit amount.
     */
    private fun TestScope.settle() = runCurrent()

    /**
     * ⛔ EVERY TEST HERE RUNS THROUGH THIS RATHER THAN THROUGH `runTest` DIRECTLY. A connected session
     * runs a duration ticker — `while (true) { delay(tick) ... }` — and `runTest` DRAINS THE SCHEDULER
     * AFTER THE BODY RETURNS, so an armed infinite delay loop makes that drain advance virtual time
     * forever: the test does not fail, the JVM spins. ⚠️ Cancelling in `@After` is too late, because
     * `@After` runs after `runTest` has already returned and hung.
     */
    private fun callTest(body: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness()
        try {
            body(harness)
        } finally {
            // ⛔ THE SESSION IS ENDED EXPLICITLY RATHER THAN LEFT TO SCOPE CANCELLATION. `end`
            // cancels the ticker's Job synchronously, which is the only thing that provably clears
            // the scheduler before `runTest` drains it; cancelling the scope alone was tried and the
            // drain still spun.
            harness.session.end {}
            runCurrent()
            observeScope.cancel()
            engineScope.cancel()
        }
    }

    private inner class Harness {
        val engine = FakeCallEngine()
        val telecom = FakeTelecomBridge()
        val session = InboundCallSessionFactory(
            engineFactory = CallEngineFactory { engine },
            telecom = telecom,
            engineScopeFactory = { engineScope },
            tickMillis = TICK,
        ).create()
    }

    @Test
    fun `begin watches the engine and does NOT announce a second call to Telecom`() = callTest { h ->
        // ⛔ THE CONNECTION ALREADY EXISTS AND IS RINGING. `TelecomBridge.startIncoming` created it
        // when the push arrived; announcing it here would be a second connection for one call.

        h.session.begin(observeScope)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), h.telecom.calls)
    }

    @Test
    fun `connecting turns the microphone on and latches the answer`() = callTest { h ->
        // ⚠️ UNCONDITIONALLY ON. The answer route excludes viewers server-side, so a token that
        // reached here always carries publish rights — and unlike a meeting there is no listen-only
        // seat worth having on a telephone call: the caller would experience it as silence.
        h.session.begin(observeScope)

        val connected = h.session.connect("wss://x", "t", observeScope)
        settle()

        assertTrue(connected)
        assertEquals(listOf("connect:wss://x:t", "mic:true"), h.engine.calls)
        assertTrue(h.session.state.value.answered)
        assertEquals(listOf("active"), h.telecom.calls)
    }

    @Test
    fun `the duration starts when media is up, not when Answer was pressed`() = callTest { h ->
        // ⛔ THE OPERATOR WILL COMPARE THIS TO AN INVOICE. The platform bills answered time; counting
        // from the press would include the answer round trip and the LiveKit join, and make the app
        // the thing that looks wrong.
        h.session.begin(observeScope)

        assertEquals(0, h.session.state.value.elapsedSeconds)
        h.session.connect("wss://x", "t", observeScope)
        advanceTimeBy(TICK * 3 + 1)

        assertEquals(3, h.session.state.value.elapsedSeconds)
    }

    @Test
    fun `a failed join tells Telecom the call is over and reports false`() = callTest { h ->
        // ⛔ A CONNECTION LEFT RINGING OR ACTIVE AFTER A FAILED JOIN KEEPS AUDIO FOCUS and keeps the
        // OS suppressing the ringer, indefinitely, for a call that is not happening.
        h.engine.connectFailure = IllegalStateException("no route to host")
        h.session.begin(observeScope)

        val connected = h.session.connect("wss://x", "t", observeScope)
        advanceUntilIdle()

        assertFalse(connected)
        assertEquals(listOf("disconnected"), h.telecom.calls)
        assertTrue(h.session.state.value.connection is CallConnectionState.Failed)
        assertFalse("nothing was answered", h.session.state.value.answered)
    }

    @Test
    fun `ending disconnects exactly once even when every path fires`() = callTest { h ->
        // ⛔ ONE-WAY, AND IT IS WHAT MAKES "HANG UP DISCONNECTS EXACTLY ONCE" TRUE. Four paths reach
        // it — the button, the OS's own affordance, a failed join and the controller tearing down —
        // and a second disconnect would race the first one's cancellation of the engine scope.
        h.session.begin(observeScope)
        h.session.connect("wss://x", "t", observeScope)
        settle()

        var ended = 0
        h.session.end { ended++ }
        h.session.end { ended++ }
        settle()

        assertEquals(2, ended)
        assertEquals(1, h.engine.calls.count { it == "disconnect" })
    }

    @Test
    fun `the UI moves to ended before the socket closes`() = callTest { h ->
        // ⚠️ A HANG-UP THAT LOOKED UNRESPONSIVE FOR A NETWORK ROUND TRIP IS ONE THE OPERATOR PRESSES
        // AGAIN.
        h.session.begin(observeScope)
        h.session.connect("wss://x", "t", observeScope)
        settle()

        h.session.end {}

        assertTrue(h.session.state.value.ended)
        assertEquals(CallPhase.ENDED, h.session.state.value.phase)
    }

    @Test
    fun `the timer stops when the call ends rather than racing one last tick`() = callTest { h ->
        h.session.begin(observeScope)
        h.session.connect("wss://x", "t", observeScope)
        advanceTimeBy(TICK * 2 + 1)

        h.session.end {}
        val frozen = h.session.state.value.elapsedSeconds
        advanceTimeBy(TICK * 5)

        assertEquals(frozen, h.session.state.value.elapsedSeconds)
    }

    @Test
    fun `speaker and microphone toggles reach the engine`() = callTest { h ->
        h.session.begin(observeScope)
        h.session.connect("wss://x", "t", observeScope)
        settle()

        h.session.toggleMicrophone(observeScope)
        h.session.toggleSpeaker()
        settle()

        assertTrue(h.engine.calls.contains("mic:false"))
        assertTrue(h.engine.calls.contains("speaker:true"))
        // ⚠️ THE SPEAKER FLAG IS THIS SCREEN'S OWN BELIEF: the engine exposes no route to read back,
        // so it records what the app asked for. Honest for a toggle, not for a status readout.
        assertTrue(h.session.state.value.speakerOn)
    }

    @Test
    fun `each toggle flips back, so a second press undoes the first`() = callTest { h ->
        h.session.begin(observeScope)
        h.session.connect("wss://x", "t", observeScope)
        settle()

        h.session.toggleMicrophone(observeScope)
        settle()
        h.session.toggleMicrophone(observeScope)
        h.session.toggleSpeaker()
        h.session.toggleSpeaker()
        settle()

        assertEquals(listOf("mic:true", "mic:false", "mic:true"), h.engine.calls.filter { it.startsWith("mic") })
        assertEquals(listOf("speaker:true", "speaker:false"), h.engine.calls.filter { it.startsWith("speaker") })
        assertTrue(h.session.state.value.micEnabled)
        assertFalse(h.session.state.value.speakerOn)
    }

    @Test
    fun `a second connect does not tell Telecom twice or start a second ticker`() = callTest { h ->
        h.session.begin(observeScope)
        h.session.connect("wss://x", "t", observeScope)
        h.session.connect("wss://x", "t", observeScope)
        advanceTimeBy(TICK * 3 + 1)

        assertEquals(listOf("active"), h.telecom.calls)
        assertEquals("one ticker, one second per second", 3, h.session.state.value.elapsedSeconds)
    }

    /** A session whose engine holds `connect` open until [gate] completes: a join in flight. */
    private fun gatedSession(gate: CompletableDeferred<Unit>, engine: FakeCallEngine, telecom: FakeTelecomBridge) =
        InboundCallSessionFactory(
            engineFactory = CallEngineFactory {
                object : CallEngine by engine {
                    override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
                        gate.await()
                        engine.connect(url, token, e2eeKeyBase64)
                    }
                }
            },
            telecom = telecom,
            engineScopeFactory = { engineScope },
            tickMillis = TICK,
        ).create()

    @Test
    fun `a hang-up during the join leaves the room disconnected, with the microphone never on`() = runTest {
        // ⛔ THE RACE THIS CLOSES: `end` launches its disconnect while `connect` is still inside the
        // engine, and nothing orders the two inside the SDK. A connect that resolved AFTER that
        // disconnect used to turn the microphone on and report media up, leaving the room joined
        // with a live microphone after the user hung up. The session must issue a disconnect after
        // the connect has resolved, and never publish the microphone.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine()
        val telecom = FakeTelecomBridge()
        val session = gatedSession(gate, engine, telecom)
        session.begin(observeScope)
        val joined = async { session.connect("wss://x", "t", observeScope) }
        runCurrent()

        var ended = 0
        session.end { ended++ }
        runCurrent()
        assertEquals("end's own disconnect ran while the connect was still open", listOf("disconnect"), engine.calls)
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(TICK * 3)

        assertFalse("a released session reports no media", joined.await())
        assertEquals(listOf("disconnect", "connect:wss://x:t", "disconnect"), engine.calls)
        assertFalse(engine.calls.contains("mic:true"))
        assertEquals("Telecom was told once, by end", listOf("disconnected"), telecom.calls)
        assertFalse(session.state.value.answered)
        assertEquals(0, session.state.value.elapsedSeconds)
        assertEquals("onEnded runs exactly once", 1, ended)
        assertFalse("end cancelled the engine scope after its disconnect", engineScope.isActive)
        observeScope.cancel()
    }

    @Test
    fun `a hang-up during a join that then fails still disconnects after it, and says nothing more`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine().apply { connectFailure = IllegalStateException("socket closed") }
        val telecom = FakeTelecomBridge()
        val session = gatedSession(gate, engine, telecom)
        session.begin(observeScope)
        val joined = async { session.connect("wss://x", "t", observeScope) }
        runCurrent()

        var ended = 0
        session.end { ended++ }
        gate.complete(Unit)
        runCurrent()

        assertFalse(joined.await())
        assertEquals("disconnect", engine.calls.last())
        assertTrue(
            "the last disconnect follows the connect attempt",
            engine.calls.lastIndexOf("disconnect") > engine.calls.indexOf("connect:wss://x:t"),
        )
        assertFalse(engine.calls.contains("mic:true"))
        assertEquals("Telecom is not told a second time", listOf("disconnected"), telecom.calls)
        assertEquals(1, ended)
        observeScope.cancel()
    }

    @Test
    fun `the production factory builds each engine on a Main-bound scope of its own`() {
        val scopes = mutableListOf<CoroutineScope>()
        val factory = InboundCallSessionFactory(
            engineFactory = CallEngineFactory { scope ->
                scopes += scope
                FakeCallEngine()
            },
            telecom = FakeTelecomBridge(),
        )

        factory.create()
        factory.create()

        assertEquals(2, scopes.size)
        assertTrue("one scope per call", scopes[0] !== scopes[1])
        scopes.forEach { scope ->
            assertEquals(Dispatchers.Main.immediate, scope.coroutineContext[ContinuationInterceptor])
            scope.cancel()
        }
    }

    @Test
    fun `the caller is never named, because the payload carries no identity`() = callTest { h ->
        // ⛔ A REAL PRODUCT LIMITATION, WRITTEN DOWN RATHER THAN PAPERED OVER WITH THE CALL ID. The
        // push carries identifiers only and the answer route returns a join credential rather than a
        // caller, so the number is empty here and the screen substitutes a label.

        assertEquals("", h.session.state.value.number)
    }

    private companion object {
        const val TICK = 1_000L
    }
}
