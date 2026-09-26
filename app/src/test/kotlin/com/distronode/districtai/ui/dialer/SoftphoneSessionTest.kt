package com.distronode.districtai.ui.dialer

import com.distronode.districtai.ui.rooms.FakeCallEngine
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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

    private companion object {
        const val TICK = 1000L
    }
}
