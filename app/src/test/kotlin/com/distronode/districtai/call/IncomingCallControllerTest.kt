package com.distronode.districtai.call

import com.distronode.districtai.core.data.InboundCallRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.CallAnswerResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.push.RecordingPushNotifier
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.dialer.FakeTelecomBridge
import com.distronode.districtai.ui.rooms.FakeCallEngine
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A [ForegroundCallHost] that records the start/stop it was asked for.
 *
 * ⚠️ THE ORDERING IS THE ASSERTION, not the final value. A `phoneCall`-typed foreground service
 * outliving its call is what Android 14 kills a process for, and one that never starts is a call the
 * system may freeze mid-conversation — both are readable from a list and neither needs a service.
 */
private class RecordingForegroundHost : ForegroundCallHost {
    val calls: MutableList<Boolean> = mutableListOf()

    override fun setCallActive(active: Boolean) {
        calls += active
    }
}

/**
 * The inbound call state machine: ring, answer, decline, time out.
 *
 * ⛔ THE MOST IMPORTANT ASSERTIONS IN THIS FILE ARE THAT **NOTHING IS SENT** ON A DECLINE AND ON A
 * TIMEOUT. `actions/ring-app` blocks on a Redis rendezvous for ~25 seconds and falls back to PSTN
 * when it expires, so the server already has everything it needs from the ABSENCE of an answer.
 * Reporting a decline would make a deliberate refusal distinguishable from a phone in a pocket — to
 * the agent, and through it to the caller — and there is no version of that distinction the product
 * wants.
 *
 * ⚠️ NONE OF THIS IS VERIFIABLE AT RUNTIME ON THIS MACHINE. Waydroid is API 33 with no Play
 * Services, so the push cannot be delivered, the foreground-service types are API 34 concepts, and
 * the audio route is an on-device question. Every transition below is driven through injected seams
 * instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCallControllerTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * The scope the CONTROLLER runs on, standing in for the application graph's.
     *
     * ⚠️ TEST-OWNED RATHER THAN `runTest`'s OWN SCOPE, and separate from [engineScope] below. In
     * production these are two different scopes for a reason the session documents — the engine's
     * outlives its owner by exactly the length of a disconnect and is CANCELLED FROM INSIDE `end` —
     * so sharing one here would have the first hang-up cancel the test itself.
     */
    private lateinit var controllerScope: CoroutineScope

    /**
     * The scope every call's engine is built with.
     *
     * ⛔ A SCOPE THE TEST OWNS, NOT `runTest`'s `backgroundScope` — the trap `ActiveRoomViewModelTest`
     * records: work launched into `backgroundScope` is NOT run by `advanceUntilIdle()`, so a
     * disconnect simply never executes and the assertion reads like code that forgot to disconnect.
     */
    private lateinit var engineScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        controllerScope = CoroutineScope(dispatcher)
        engineScope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() {
        controllerScope.cancel()
        // ⚠️ May already be cancelled from inside `InboundCallSession.end`; cancelling twice is a
        // no-op, and asserting it was is not this file's job.
        engineScope.cancel()
        Dispatchers.resetMain()
    }

    private inner class Harness(
        val engine: FakeCallEngine = FakeCallEngine(),
        /** When set, the engine's connect waits for it: a join still in flight. */
        joinGate: CompletableDeferred<Unit>? = null,
    ) {
        val api = TestDistrictApi()
        val telecom = FakeTelecomBridge()
        val notifier = RecordingPushNotifier()
        val foreground = RecordingForegroundHost()
        val controller = IncomingCallController(
            repository = InboundCallRepository(api),
            surfaces = IncomingCallSurfaces(
                telecom = telecom,
                notifier = notifier,
                sessions = InboundCallSessionFactory(
                    engineFactory = CallEngineFactory {
                        joinGate?.let { gate ->
                            object : CallEngine by engine {
                                override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
                                    gate.await()
                                    engine.connect(url, token, e2eeKeyBase64)
                                }
                            }
                        } ?: engine
                    },
                    telecom = telecom,
                    engineScopeFactory = { engineScope },
                    tickMillis = 1_000L,
                ),
                foreground = foreground,
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

    /**
     * ⛔ `runCurrent()` RATHER THAN `advanceUntilIdle()` ONCE A CALL HAS CONNECTED, AND THAT IS A
     * HARD REQUIREMENT RATHER THAN A PREFERENCE. A connected call runs a duration ticker —
     * `while (true) { delay(tick) ... }` — so there is ALWAYS a task pending on the virtual clock,
     * and `advanceUntilIdle()` advances forever and never returns. The test does not fail; the JVM
     * spins. Measured here: a suite run sat at 100% CPU for 24 minutes with no output.
     *
     * ⚠️ `runCurrent()` IS ENOUGH BECAUSE NOTHING ON THE ANSWER PATH DELAYS. The answer round trip,
     * the join, the collectors and the teardown are all zero-delay dispatches, and `runCurrent`
     * drains chained dispatches at the current instant. Where virtual TIME genuinely has to pass
     * (the ring timeout, the ticker itself) the tests use `advanceTimeBy` with an explicit amount.
     */
    private fun TestScope.settle() = runCurrent()

    /**
     * ⛔ EVERY TEST HERE RUNS THROUGH THIS RATHER THAN THROUGH `runTest` DIRECTLY, AND IT IS NOT
     * ceremony. A CONNECTED call runs a duration ticker — `while (true) { delay(tick) ... }` — in the
     * controller's scope, and `runTest` DRAINS THE SCHEDULER AFTER THE BODY RETURNS. With an infinite
     * delay loop still armed, that drain advances virtual time forever: the test does not fail, the
     * JVM spins. Measured here — a worker sat at 120% CPU for ten minutes with no output, and the
     * thread dump pointed at `runTest`'s own `advanceUntilIdleOr`, not at anything the test wrote.
     *
     * ⚠️ CANCELLING IN `@After` IS TOO LATE, which is the part that makes this non-obvious: `@After`
     * runs after `runTest` has already returned, so the drain has already hung. The scopes have to be
     * cancelled INSIDE the body, which is what the `finally` does.
     *
     * ⚠️ AND IT IS WHY THE PRODUCTION CODE IS FINE: `InboundCallSession.end` cancels the ticker, and
     * every real exit goes through it. Only a test that asserts the middle of a live call leaves one
     * armed.
     */
    private fun callTest(
        harness: Harness = Harness(),
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        try {
            body(harness)
        } finally {
            // ⛔ THE CALL IS ENDED EXPLICITLY, NOT MERELY BY CANCELLING THE SCOPE IT RUNS IN.
            // `hangUp` reaches `InboundCallSession.end`, which cancels the ticker's Job
            // SYNCHRONOUSLY — the one thing that provably clears the scheduler. Cancelling the scope
            // alone was tried and did not: the drain still spun, which is how a class of sixteen
            // tests took 656 seconds and then got killed.
            harness.controller.hangUp()
            runCurrent()
            controllerScope.cancel()
            engineScope.cancel()
        }
    }

    @Test
    fun `a push rings the OS and the notification shade together`() = callTest { h ->

        h.controller.onIncomingCall("ws-1", "CA1")

        assertEquals(IncomingCallPhase.RINGING, h.controller.state.value?.phase)
        assertEquals("ws-1", h.controller.state.value?.workspaceId)
        assertEquals("CA1", h.controller.state.value?.callId)
        assertEquals(listOf("incoming"), h.telecom.calls)
        assertEquals(listOf("call:ws-1:CA1"), h.notifier.drawn)
        // ⚠️ NO DURATION YET. `call` stays null until media is up, which is what stops a ringing
        // screen drawing a timer.
        assertNull(h.controller.state.value?.call)
    }

    @Test
    fun `a second push while one is live is dropped, not queued and not a replacement`() = callTest { h ->
        // ⛔ ONE ENGINE, ONE AUDIO FOCUS, ONE TELECOM CONNECTION. A replacement would tear down a
        // conversation the user is having in order to ring them about another. The dropped call
        // still reaches the server's timeout and falls back to PSTN, which is the right outcome for
        // someone already on the phone.

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.onIncomingCall("ws-1", "CA2")

        assertEquals("CA1", h.controller.state.value?.callId)
        assertEquals(listOf("incoming"), h.telecom.calls)
        assertEquals(listOf("call:ws-1:CA1"), h.notifier.drawn)
    }

    @Test
    fun `declining sends nothing to the server and takes the ring away`() = callTest { h ->
        // ⛔ THE PRIVACY PROPERTY, ASSERTED. An unanswered ring and a declined one must look
        // identical from outside, and the only way to guarantee that is for neither to send
        // anything.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.decline()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertTrue(h.notifier.drawn.contains("cancel"))
        assertTrue(h.telecom.calls.contains("disconnected"))
    }

    @Test
    fun `an unanswered ring times out exactly as a decline does`() = callTest { h ->
        // ⛔ THE SAME CODE PATH, NOT MERELY THE SAME BEHAVIOUR TODAY. See the class doc: the two
        // must be indistinguishable, and sharing the path is what guarantees it stays that way.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        advanceTimeBy(RING_MILLIS + 1)
        advanceUntilIdle()

        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertTrue(h.notifier.drawn.contains("cancel"))
    }

    @Test
    fun `a notification answer while ringing is recorded for the screen, and consuming clears it`() =
        callTest { h ->
            h.controller.onIncomingCall("ws-1", "CA1")
            settle()
            assertEquals(false, h.controller.answerRequested.value)

            h.controller.requestAnswerFromNotification()

            assertEquals(true, h.controller.answerRequested.value)
            // ⚠️ NOTHING ANSWERED YET: the screen owns the microphone request and the answer.
            assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
            assertEquals(IncomingCallPhase.RINGING, h.controller.state.value?.phase)

            h.controller.consumeAnswerRequest()

            assertEquals(false, h.controller.answerRequested.value)
        }

    @Test
    fun `a notification answer for a call that is not ringing raises nothing`() = callTest { h ->
        // No call at all: a stale pending intent after the call ended.
        h.controller.requestAnswerFromNotification()
        assertEquals(false, h.controller.answerRequested.value)

        // Already answering: the on-screen button won the race.
        h.answerable()
        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        h.controller.requestAnswerFromNotification()
        settle()

        assertEquals(false, h.controller.answerRequested.value)
        assertEquals(listOf("CA1/ws-1"), h.api.pushApi.answerRequests)
    }

    @Test
    fun `answering fetches the credential, connects, and only then tells Telecom it is active`() =
        callTest { h ->
            // ⛔ `setActive()` AFTER MEDIA, NOT ON THE PRESS. Telling the OS a call is ACTIVE while
            // the credential is still being fetched starts its own duration counter early and, on a
            // failed join, leaves the user looking at a connected call with no audio.
            h.answerable()

            h.controller.onIncomingCall("ws-1", "CA1")
            h.controller.answer()
            settle()

            assertEquals(listOf("CA1/ws-1"), h.api.pushApi.answerRequests)
            assertEquals(IncomingCallPhase.IN_CALL, h.controller.state.value?.phase)
            assertEquals(
                listOf("connect:wss://livekit-wss.distronode.com:join-jwt", "mic:true"),
                h.engine.calls,
            )
            assertEquals(
                "Telecom is told the call rings, then that it is active — in that order",
                listOf("incoming", "active"),
                h.telecom.calls,
            )
            assertEquals(listOf(true), h.foreground.calls)
        }

    @Test
    fun `the ring notification goes away at the press, before the answer round trip`() = callTest { h ->
        // ⛔ AT THE PRESS, NOT AT THE CONNECT, AND THE TEST FAILING IS HOW THAT WAS FOUND. Leaving it
        // up during the round trip leaves an Answer/Decline pair on screen for a call that is already
        // being joined — Decline would tear down a call whose credential has been spent, Answer would
        // be a second answer — and the notification is deliberately `ongoing`, so the user cannot
        // swipe it away themselves.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        assertEquals(listOf("call:ws-1:CA1"), h.notifier.drawn)

        h.controller.answer()

        // ⚠️ ASSERTED BEFORE `settle()`: the cancel must be synchronous with the press rather than a
        // consequence of the request completing, which is the whole distinction.
        assertEquals(listOf("call:ws-1:CA1", "cancel"), h.notifier.drawn)
    }

    @Test
    fun `a second answer while the first is in flight is dropped`() = callTest { h ->
        // ⚠️ THE NOTIFICATION'S Answer BUTTON, THE ON-SCREEN BUTTON AND A CAR HEAD UNIT CAN ALL
        // FIRE, and the OS can deliver one twice. A second round trip would write the server's
        // rendezvous again for a call that is already being joined.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        h.controller.answer()
        settle()

        assertEquals(1, h.api.pushApi.answerRequests.size)
    }

    @Test
    fun `a call that ended while the phone rang says so rather than reporting an error`() = callTest { h ->
        // ⛔ NOT AN ERROR AND NOT WORDED AS ONE. The overwhelmingly common way to reach this is that
        // the caller hung up between the phone ringing and a thumb arriving.
        h.api.pushApi.answerResult = ApiResult.NotFound("Call not found")

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        advanceUntilIdle()

        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertNotNull("the screen must say why", h.controller.state.value?.message)
        assertTrue(h.notifier.drawn.contains("cancel"))
        // ⚠️ NO FOREGROUND SERVICE WAS EVER STARTED, so the only entry is the stop from the exit
        // path — a `phoneCall` service with no call is what Android 14 kills the process for.
        assertFalse(h.foreground.calls.contains(true))
    }

    @Test
    fun `a viewer's refusal ends the call with the mapped failure text`() = callTest { h ->
        // ⛔ A VIEWER'S PHONE GENUINELY RINGS: the server fans a push out to every registered device
        // in the workspace without consulting roles, so the 403 is reachable by an ordinary user.
        h.api.pushApi.answerResult = ApiResult.Forbidden("Forbidden")

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        advanceUntilIdle()

        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertNotNull(h.controller.state.value?.message)
    }

    @Test
    fun `a media join that fails ends the call rather than leaving a connected screen`() = callTest { h ->
        h.answerable()
        h.engine.connectFailure = IllegalStateException("no route to host")

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        advanceUntilIdle()

        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertNotNull(h.controller.state.value?.message)
        // ⛔ AND THE FOREGROUND SERVICE IS NEVER STARTED. It starts only after the join succeeds.
        assertFalse(h.foreground.calls.contains(true))
        assertTrue(h.notifier.drawn.contains("cancel"))
    }

    @Test
    fun `hanging up a connected call disconnects the media and stops the service`() = callTest { h ->
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        settle()
        h.controller.hangUp()
        settle()

        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertTrue(h.engine.calls.contains("disconnect"))
        assertEquals(listOf(true, false), h.foreground.calls)
    }

    @Test
    fun `the ring timeout does not fire after the call was answered`() = callTest { h ->
        // ⛔ THE TIMEOUT IS CANCELLED AT THE PRESS. Left armed, it would tear down a conversation
        // thirty seconds in — and the user would see a call end for no reason they could observe.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        settle()
        advanceTimeBy(RING_MILLIS * 2)
        settle()

        assertEquals(IncomingCallPhase.IN_CALL, h.controller.state.value?.phase)
    }

    @Test
    fun `dismissing an ended call clears the screen, and only when it is ended`() = callTest { h ->
        // ⛔ ENDED IS A STATE THE USER LEAVES, NOT ONE THAT EXPIRES. A call that vanished on hang-up
        // would answer "how long was that" with nothing.

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.dismiss()
        assertNotNull("a ringing call is not dismissible", h.controller.state.value)

        h.controller.decline()
        advanceUntilIdle()
        h.controller.dismiss()

        assertNull(h.controller.state.value)
    }

    @Test
    fun `an answer arriving from a system surface takes the same path as the button`() = callTest { h ->
        // ⛔ A CAR HEAD UNIT, A WATCH OR A HEADSET ANSWERS THROUGH TELECOM, and without this wire the
        // connection would go ACTIVE while the app never joined the room — silence, on a call the OS
        // says is connected.
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.telecom.systemAnswer()
        settle()

        assertEquals(IncomingCallPhase.IN_CALL, h.controller.state.value?.phase)
        assertEquals(listOf("CA1/ws-1"), h.api.pushApi.answerRequests)
    }

    @Test
    fun `a hang-up made on a system surface ends the call and sends nothing`() = callTest { h ->
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.telecom.systemHangUp()
        advanceUntilIdle()

        assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
    }

    @Test
    fun `microphone and speaker toggles are no-ops before a session exists`() = callTest { h ->
        // ⚠️ REACHABLE: the controls belong to the in-call surface, but the state object exists from
        // the first ring, and a recomposition can invoke a handler against a call that has since
        // ended. Doing nothing is correct; throwing would crash the process over a stale tap.

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.toggleMicrophone()
        h.controller.toggleSpeaker()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), h.engine.calls)
    }

    @Test
    fun `toggles reach the engine once a call is connected`() = callTest { h ->
        h.answerable()

        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        settle()
        h.controller.toggleMicrophone()
        h.controller.toggleSpeaker()
        settle()

        assertTrue(h.engine.calls.contains("mic:false"))
        assertTrue(h.engine.calls.contains("speaker:true"))
    }

    @Test
    fun `dismissing a call that connected and ended stops mirroring its session`() = callTest { h ->
        // ⚠️ THE MIRROR OUTLIVES `finish` ON PURPOSE (the ended summary is drawn from its last
        // emission), so it is `dismiss` that has to cut it: the old session emitting afterwards
        // must not paint its finished call onto the NEXT call to ring.
        h.answerable()
        h.controller.onIncomingCall("ws-1", "CA1")
        h.controller.answer()
        settle()
        h.controller.hangUp()
        settle()
        assertEquals(true, h.controller.state.value?.call?.ended)

        h.controller.dismiss()
        assertNull(h.controller.state.value)
        h.controller.onIncomingCall("ws-1", "CA2")
        h.engine.emitConnection(CallConnectionState.Reconnecting)
        settle()

        assertEquals("CA2", h.controller.state.value?.callId)
        assertNull("the new ring carries no call from the old session", h.controller.state.value?.call)
    }

    @Test
    fun `answer, decline and dismiss with no call are quiet no-ops`() = callTest { h ->
        // ⚠️ REACHABLE: a system surface or a stale notification can fire any of these after the
        // screen was cleared, and none may conjure a call or send anything.
        h.controller.answer()
        h.controller.decline()
        h.controller.dismiss()
        advanceUntilIdle()

        assertNull(h.controller.state.value)
        assertEquals(emptyList<String>(), h.api.pushApi.answerRequests)
        assertEquals(emptyList<String>(), h.telecom.calls)
    }

    @Test
    fun `a hang-up while the answer is joining stays hung up when the join completes`() {
        // ⛔ THE BUG THIS PINS: `join` acted on the connect's outcome without asking whether the call
        // had been ended while it was in flight. A headset or the OS's own call surface reaches
        // `decline` at any phase, so a hang-up during the join left the screen ENDED only until the
        // engine connected, then repainted it IN_CALL and started the `phoneCall` foreground service
        // for a call that was over, with nothing left to stop it.
        val gate = CompletableDeferred<Unit>()
        callTest(Harness(joinGate = gate)) { h ->
            h.answerable()
            h.controller.onIncomingCall("ws-1", "CA1")
            h.controller.answer()
            settle()
            assertEquals(IncomingCallPhase.ANSWERING, h.controller.state.value?.phase)

            h.telecom.systemHangUp()
            settle()
            gate.complete(Unit)
            settle()

            assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
            assertNull("a hang-up is not a failure", h.controller.state.value?.message)
            assertEquals("the service is never started for it", listOf(false), h.foreground.calls)
        }
    }

    @Test
    fun `a hang-up while a failing join is in flight keeps its own ending`() {
        val gate = CompletableDeferred<Unit>()
        val engine = FakeCallEngine().apply { connectFailure = IllegalStateException("socket closed") }
        callTest(Harness(engine = engine, joinGate = gate)) { h ->
            h.answerable()
            h.controller.onIncomingCall("ws-1", "CA1")
            h.controller.answer()
            settle()

            h.controller.hangUp()
            settle()
            gate.complete(Unit)
            settle()

            assertEquals(IncomingCallPhase.ENDED, h.controller.state.value?.phase)
            assertNull(
                "the media failure is not reported over the user's own hang-up",
                h.controller.state.value?.message,
            )
        }
    }

    private companion object {
        /** ⚠️ Short so the virtual clock does not have to advance thirty seconds. */
        const val RING_MILLIS = 30_000L
    }
}
