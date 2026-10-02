package com.distronode.districtai.ui.dialer

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.ui.rooms.FakeCallEngine
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * One call session on its own, for the two promises the ViewModel's tests cannot reach.
 *
 * ⚠️ WHY NOT THROUGH `DialerViewModel`: the ViewModel forgets its session before ending it, so it
 * never calls [SoftphoneSession.end] twice, and its tests inject their own engine scope, so they
 * never run the factory's production default. Both are the session's own contract.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SoftphoneSessionTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `ending twice disconnects once, and both callers are still told`() = runTest(dispatcher) {
        // ⛔ A second disconnect would race the first one's cancellation of the session's scope.
        val engine = FakeCallEngine()
        val telecom = FakeTelecomBridge()
        val session = SoftphoneSession(
            number = "+14165550100",
            engine = engine,
            scope = CoroutineScope(dispatcher),
            telecom = telecom,
            tickMillis = TICK,
        )
        var ended = 0

        session.end { ended += 1 }
        session.end { ended += 1 }
        advanceUntilIdle()

        assertEquals(1, engine.calls.count { it == "disconnect" })
        assertEquals(1, telecom.calls.count { it == "disconnected" })
        assertEquals(2, ended)
    }

    @Test
    fun `the production scope is a fresh supervisor per call, so one failure cannot end the next call`() =
        runTest(dispatcher) {
            val engines = FakeCallEngineFactory()
            val sessions = SoftphoneSessionFactory(engines, FakeTelecomBridge())

            sessions.create("+14165550100")
            sessions.create("+14165550101")
            val (first, second) = engines.scopes

            assertNotSame("each call gets its own scope", first, second)
            first.launch(CoroutineExceptionHandler { _, _ -> }) { error("a child of this call failed") }
            advanceUntilIdle()
            assertTrue("a failed child does not cancel the call's scope", first.isActive)
            first.cancel()
            second.cancel()
        }

    /** A session whose engine holds `connect` open until [gate] completes: a join in flight. */
    private fun gatedSession(
        gate: CompletableDeferred<Unit>,
        engine: FakeCallEngine,
        telecom: FakeTelecomBridge,
        scope: CoroutineScope,
    ) = SoftphoneSession(
        number = NUMBER,
        engine = object : CallEngine by engine {
            override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
                gate.await()
                engine.connect(url, token, e2eeKeyBase64)
            }
        },
        scope = scope,
        telecom = telecom,
        tickMillis = TICK,
    )

    @Test
    fun `a hang-up during the join leaves the room disconnected, with the microphone never on`() =
        runTest(dispatcher) {
            // ⛔ THE INBOUND FIX THE OUTBOUND SESSION NEVER RECEIVED. `end` launches its disconnect
            // while `connect` is still inside the engine, and nothing orders the two inside the SDK.
            // A connect that resolved after that disconnect turned the microphone on in a room the
            // operator had left, and only the server's best-effort room delete removed it.
            val gate = CompletableDeferred<Unit>()
            val engine = FakeCallEngine()
            val telecom = FakeTelecomBridge()
            val observeScope = CoroutineScope(dispatcher)
            val session = gatedSession(gate, engine, telecom, CoroutineScope(dispatcher))
            session.begin(observeScope) {}
            launch { session.connect("wss://x", "t") }
            runCurrent()

            var ended = 0
            session.end { ended++ }
            runCurrent()
            assertEquals(
                "end's own disconnect ran while the connect was still open",
                listOf("disconnect"),
                engine.calls,
            )
            gate.complete(Unit)
            runCurrent()

            assertEquals(listOf("disconnect", "connect:wss://x:t", "disconnect"), engine.calls)
            assertFalse(engine.calls.contains("mic:true"))
            assertEquals("Telecom was told once, by end", listOf("outgoing:$NUMBER", "disconnected"), telecom.calls)
            assertEquals("onEnded runs exactly once", 1, ended)
            observeScope.cancel()
        }

    @Test
    fun `a cancelled join is not reported as a failed one, and does not carry on`() = runTest(dispatcher) {
        // ⚠️ `runCatching` CAUGHT THE CANCELLATION: Telecom was told the call was over, the screen
        // read `Failed` with a coroutine-internals message, and the cancelled coroutine went on.
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine()
        val telecom = FakeTelecomBridge()
        val observeScope = CoroutineScope(dispatcher)
        val session = gatedSession(gate, engine, telecom, CoroutineScope(dispatcher))
        session.begin(observeScope) {}
        var resumed = false
        val joining = launch {
            session.connect("wss://x", "t")
            resumed = true
        }
        runCurrent()

        joining.cancel()
        runCurrent()

        assertFalse("nothing runs after a cancelled join", resumed)
        assertEquals("Telecom is left to whoever cancelled", listOf("outgoing:$NUMBER"), telecom.calls)
        assertFalse(session.state.value.connection is CallConnectionState.Failed)
        session.end {}
        runCurrent()
        observeScope.cancel()
    }

    private companion object {
        const val TICK = 1000L
        const val NUMBER = "+14165550100"
    }
}
