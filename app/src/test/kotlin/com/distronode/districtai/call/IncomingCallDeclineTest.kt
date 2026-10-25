package com.distronode.districtai.call

import android.telecom.DisconnectCause
import com.distronode.districtai.core.data.InboundCallRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.CallAnswerResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.push.RecordingPushNotifier
import com.distronode.districtai.push.RingCancelReason
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.rooms.FakeCallEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Which exits of a ringing call tell the server `calls/{id}/decline`, and which do not.
 *
 * ⛔ A PERSON'S DECLINE WHILE RINGING SENDS EXACTLY ONCE (Sean, 2026-10-24: when every member the
 * ring reached has declined, the server ends the ring and the agent takes a message). A timeout, a
 * withdrawn ring and a hang-up after the answer send NOTHING: reported as declines, they would let
 * the server end a ring another member was about to answer.
 *
 * ⚠️ A CLASS OF ITS OWN BECAUSE [IncomingCallControllerTest] SITS AT detekt's LargeClass CEILING.
 * The harness is the same construction, without the join gate none of these tests needs; the
 * `callTest` wrapper is copied for the reason documented there (a connected call's ticker would
 * otherwise spin `runTest`'s drain forever).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallDeclineTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val controllerScope = CoroutineScope(dispatcher)
    private val engineScope = CoroutineScope(dispatcher)

    private inner class Harness {
        val api = FakeDistrictApi()
        val telecom = FakeTelecomBridge()
        val notifier = RecordingPushNotifier()
        val ringer = RecordingRinger()
        val controller = IncomingCallController(
            repository = InboundCallRepository(api),
            surfaces = IncomingCallSurfaces(
                telecom = telecom,
                notifier = notifier,
                sessions = InboundCallSessionFactory(
                    engineFactory = CallEngineFactory { FakeCallEngine() },
                    telecom = telecom,
                    engineScopeFactory = { engineScope },
                    tickMillis = 1_000L,
                ),
                foreground = ForegroundCallHost {},
                ringer = ringer,
            ),
            scope = controllerScope,
            ringTimeoutMillis = RING_MILLIS,
        )

        fun answerable() {
            api.pushApi.answerResult = ApiResult.Success(
                CallAnswerResponse(
                    success = true,
                    url = "wss://livekit-wss.distronode.com",
                    token = "join-jwt",
                    roomName = "call_ws-1_CA1",
                ),
            )
        }
    }

    private fun TestScope.settle() = runCurrent()

    private fun callTest(body: suspend TestScope.(Harness) -> Unit) = runTest {
        val harness = Harness()
        try {
            body(harness)
        } finally {
            harness.controller.hangUp()
            runCurrent()
            controllerScope.cancel()
            engineScope.cancel()
        }
    }

    @Test
    fun `a person's decline tells the server once and takes the ring away`() = callTest { h ->
        // ⛔ THE NEW RULE, ASSERTED: one decline, for the push's workspace, and never an answer.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        val sent = h.controller.decline()
        advanceUntilIdle()

        assertNotNull("a ringing decline hands back its request", sent)
        assertTrue(sent!!.isCompleted)
        assertEquals(listOf("CA1/ws-1"), h.api.pushApi.declineRequests)
        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertTrue(h.notifier.drawn.contains("cancel"))
        assertTrue(h.telecom.calls.contains("disconnected"))
    }

    @Test
    fun `a refused decline is not retried`() = callTest { h ->
        h.api.pushApi.declineResult = ApiResult.HttpFailure(status = 503, message = "unavailable")

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.decline()
        advanceUntilIdle()

        assertEquals(listOf("CA1/ws-1"), h.api.pushApi.declineRequests)
        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
    }

    @Test
    fun `a decline that never answers is abandoned at its budget`() = callTest { h ->
        // ⚠️ THE NOTIFICATION'S RECEIVER HOLDS ITS BROADCAST OPEN FOR THIS REQUEST, so it must end
        // inside the receiver's budget whatever the network does.
        h.api.pushApi.declineGate = CompletableDeferred()

        h.controller.onIncomingCall("ws-1", "CA1")
        val sent = h.controller.decline()!!
        advanceTimeBy(IncomingCallController.DECLINE_BUDGET_MILLIS - 1)
        runCurrent()
        assertFalse("still in flight just inside the budget", sent.isCompleted)

        advanceTimeBy(2)
        runCurrent()

        assertTrue(sent.isCompleted)
        assertEquals(listOf("CA1/ws-1"), h.api.pushApi.declineRequests)
    }

    @Test
    fun `an unanswered ring times out without telling the server anything`() = callTest { h ->
        // ⛔ NOBODY DECLINED. A timeout reported as a decline would let the server end a ring
        // another member was about to answer.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        advanceTimeBy(RING_MILLIS + 1)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), h.api.pushApi.declineRequests)
        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertTrue(h.notifier.drawn.contains("cancel"))
    }

    @Test
    fun `a decline after the answer press is a hang-up and sends nothing`() = callTest { h ->
        // ⚠️ THE RING IS OVER ONCE ANSWER IS PRESSED, so there is nothing left to decline: a headset
        // button or the OS's surface reaching `decline` now is an ordinary hang-up.
        h.answerable()
        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        settle()
        assertEquals(IncomingCallPhase.IN_CALL, h.controller.state.value?.phase)

        val sent = h.controller.decline()
        settle()

        assertNull(sent)
        assertEquals(emptyList<String>(), h.api.pushApi.declineRequests)
        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
    }

    @Test
    fun `a hang-up made on a system surface while ringing is a person's decline`() = callTest { h ->
        // ⛔ THE LOCK SCREEN'S DECLINE, A HEADSET, A CAR OR A WATCH: each is a person refusing the
        // ring, and each reaches the controller through Telecom rather than through this app's UI.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.telecom.systemHangUp()
        advanceUntilIdle()

        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertEquals(listOf("CA1/ws-1"), h.api.pushApi.declineRequests)
        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
    }

    @Test
    fun `a decline made on another of this person's devices stops the ring as rejected, not missed`() =
        callTest { h ->
            // ⚠️ THE PERSON SAW IT AND SAID NO ELSEWHERE, so the OS's call log must not call it missed.
            // ⛔ And this device sends nothing: the decline was already sent by the device that made it.
            h.controller.onIncomingCall("ws-1", "CA1")

            h.controller.onRingCancelled("CA1", RingCancelReason.DECLINED)
            advanceUntilIdle()

            assertFalse(h.ringer.ringing)
            assertEquals(listOf("incoming", "disconnected:${DisconnectCause.REJECTED}"), h.telecom.calls)
            assertNull(h.controller.state.value)
            assertEquals(emptyList<String>(), h.api.pushApi.declineRequests)
        }

    private companion object {
        const val RING_MILLIS = 30_000L
    }
}
