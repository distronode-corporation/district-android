package com.distronode.districtai.ui.dialer

import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallControlRepository
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.DialRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.CODE_OVERAGE_CAP_REACHED
import com.distronode.districtai.core.model.CODE_SUBSCRIPTION_INACTIVE
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestCallControlApi
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The softphone's state machine.
 *
 * ⛔ THE SEVEN THINGS THIS SCREEN MUST NOT DO, none of which is visible from the rendering:
 *  1. It must not dial without an explicit tap, and must not dial TWICE for one tap. The route is
 *     not idempotent — the row is written and the carrier instructed before the response exists —
 *     so a second send is a second call to the same person, billed again.
 *  2. It must not connect before the permission answer arrives. Publishing a microphone track
 *     before the dialog is answered produces a SILENT track rather than an error, which on a phone
 *     call is a billed conversation the other person experiences as an empty line.
 *  3. It must not degrade a denied microphone into a listen-only call, the way the room screen
 *     legitimately does. There is nobody else in a phone call to notice.
 *  4. It must not start the duration timer when it joins the room. The dial route returns while
 *     the callee's phone is still ringing, so `Connected` is not "answered" — and the number on
 *     screen is what an operator compares against an invoice.
 *  5. It must not report the same refusal for opposite problems. A lapsed plan and a chosen hard
 *     cap have opposite remedies.
 *  6. It must not disconnect twice, or fail to disconnect at all. Leaving without disconnecting
 *     strands a PSTN leg bridged to a room nobody is in, billing, until the server times it out.
 *  7. It must not tell Telecom a call is active while the far end is still ringing, or leave a
 *     connection alive after the media is gone — either one holds audio focus for a call that is
 *     not happening.
 *
 * ⛔ `advanceUntilIdle()` MUST NOT BE CALLED WHILE A CALL IS ANSWERED, AND THIS COST A HUNG BUILD
 * RATHER THAN A FAILED TEST. The duration ticker is a `while (true) { delay(1s) }` loop, which is
 * correct in production — a call has no client-side maximum, so a bounded loop would freeze the
 * timer on the longest calls — and it means the scheduler is NEVER idle while a call is running.
 * `advanceUntilIdle` therefore spins forever, with no failure message and no test name in the
 * output. Use `runCurrent()` to let a state emission through and `advanceTimeBy(...)` to move the
 * clock, and hang up before the test ends so the ticker stops with it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /**
     * The scope every call's engine is built with.
     *
     * ⛔ A SCOPE THE TEST OWNS, NOT `runTest`'s `backgroundScope` — the trap `ActiveRoomViewModelTest`
     * records: work launched into `backgroundScope` is NOT run by `advanceUntilIdle()`, so the
     * hang-up's disconnect simply never executes and the assertion reads like a ViewModel that
     * forgot to disconnect. A plain scope on the test dispatcher is also the closer analogue of
     * production, where the ViewModel builds one per call precisely so it can outlive `onCleared`.
     */
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

    private val telecom = FakeTelecomBridge()

    /** ⛔ Recorded per test: the carrier hang-up must be sent exactly once and never retried. */
    private val callControlApi = TestCallControlApi()

    private fun api(
        dial: ApiResult<DialResponse> = ApiResult.Success(
            DialResponse(
                success = true,
                callId = "CA0123456789abcdef0123456789abcdef",
                roomName = "direct_ws-1-CA0123456789abcdef0123456789abcdef",
                token = "jwt-dial",
                url = "wss://livekit.test",
            ),
        ),
        callbacks: List<CallSummary> = emptyList(),
    ) = TestDistrictApi().apply {
        dialResult = dial
        callsResult = ApiResult.Success(callbacks)
    }

    private fun viewModel(
        api: TestDistrictApi = api(),
        factory: FakeCallEngineFactory = FakeCallEngineFactory(),
        role: WorkspaceRole? = WorkspaceRole.AGENCY,
    ) = DialerViewModel(
        dialRepository = DialRepository(api),
        callsRepository = CallsRepository(api),
        // ⛔ WITHOUT THIS THE SCREEN CANNOT END A CALL AT THE CARRIER. Ending locally removes this
        // device from the room and says nothing to the SIP participant.
        callControl = CallControlRepository(callControlApi),
        workspaceId = "ws-1",
        role = role,
        sessions = SoftphoneSessionFactory(
            engineFactory = factory,
            telecom = telecom,
            engineScopeFactory = { engineScope },
            tickMillis = TICK,
        ),
        // ⛔ THE PRODUCTION DEFAULT IS A SCOPE THAT OUTLIVES THE ViewModel, because `onCleared`
        // cancels `viewModelScope` on the very path a hang-up is sent from. A test drives it on the
        // same scope the engine uses so the request is observable.
        serverHangUpScope = engineScope,
    )

    /** Type a number and take the whole happy path up to "in the room, ringing". */
    private fun DialerViewModel.dialTo(number: String = "+14165550100") {
        onEntryChange(number)
        onDial()
        onMicrophonePermissionResult(granted = true)
    }

    // ── Ordering: permission, then dial, then connect ─────────────────────────

    @Test
    fun `pressing call asks for the microphone and dials nothing yet`() = runTest {
        // ⛔ THE RACE THIS PREVENTS IS SILENT AND EXPENSIVE. Dialling alongside the permission
        // request lets the engine publish a microphone track before the dialog is answered, and
        // Android hands it a track that carries no audio rather than an error — a billed call the
        // callee hears as silence.
        val api = api()
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api = api, factory = factory)

        vm.onEntryChange("+14165550100")
        vm.onDial()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.microphoneRequest)
        assertTrue("nothing may be dialled before the answer", api.dialRequests.isEmpty())
        assertTrue("the engine must not be connected", factory.engine.calls.isEmpty())
        assertNull(vm.state.value.call)
    }

    @Test
    fun `a granted permission dials once and connects with the credential verbatim`() = runTest {
        val api = api()
        val factory = FakeCallEngineFactory()
        val vm = viewModel(api = api, factory = factory)

        vm.dialTo()
        advanceUntilIdle()

        // ⛔ EXACTLY ONE DIAL. There is no retry anywhere on this path, deliberately.
        assertEquals(1, api.dialRequests.size)
        assertEquals("+14165550100", api.dialRequests.single().to)
        // ⛔ THE url/token PAIR IS USED VERBATIM. The room exists only on the deployment the SIP
        // dial created it on — the TRUNK's, not the workspace's — so a derived URL would join a
        // bus that has never heard of this room, on a call that is already live and billed.
        assertTrue(factory.engine.calls.contains("connect:wss://livekit.test:jwt-dial"))
        // ⚠️ MICROPHONE ON, unconditionally, and NO camera call anywhere. The softphone publishes
        // no video: the token would permit it, so the absence of the call is the whole of what
        // keeps a phone call from becoming a billed video upload nobody can see.
        assertTrue(factory.engine.calls.contains("mic:true"))
        assertTrue(factory.engine.calls.none { it.startsWith("camera:") })
    }

    @Test
    fun `a denied microphone refuses the call rather than placing a silent one`() = runTest {
        // ⛔ THE OPPOSITE OF THE ROOM SCREEN, DELIBERATELY. A meeting with no microphone is
        // listen-only attendance, a seat the product already sells. A phone call with no
        // microphone is a billed call the other person experiences as an empty line.
        val api = api()
        val vm = viewModel(api = api)

        vm.onEntryChange("+14165550100")
        vm.onDial()
        vm.onMicrophonePermissionResult(granted = false)
        advanceUntilIdle()

        assertTrue(api.dialRequests.isEmpty())
        assertNull(vm.state.value.call)
        assertEquals(
            R.string.dialer_needs_microphone,
            vm.state.value.refusal?.message?.resourceIdOrNull,
        )
    }

    @Test
    fun `a second tap while a call is live is dropped, not queued`() = runTest {
        // ⛔ TWO ENGINES WOULD FIGHT OVER THE DEVICE'S AUDIO FOCUS, which is the failure CallEngine
        // itself warns about — and the operator would be on two calls, both billed.
        val api = api()
        val vm = viewModel(api = api)

        vm.dialTo()
        advanceUntilIdle()
        val requestsAfterFirst = vm.state.value.microphoneRequest

        vm.onDial()
        vm.onMicrophonePermissionResult(granted = true)
        advanceUntilIdle()

        assertEquals(1, api.dialRequests.size)
        assertEquals(requestsAfterFirst, vm.state.value.microphoneRequest)
    }

    @Test
    fun `a short number cannot be dialled at all`() = runTest {
        // ⚠️ EIGHT DIGITS, MIRRORING THE SERVER'S OWN FLOOR, and counted on the DIGITS: "(416) 5"
        // is thirteen characters and five digits, and a client counting characters would enable
        // the button for a 400 that reads as a server fault.
        val vm = viewModel()

        vm.onEntryChange("(416) 5")
        advanceUntilIdle()

        assertFalse(vm.state.value.canPlaceCall)
        vm.onDial()
        assertEquals(0, vm.state.value.microphoneRequest)
    }

    // ── The answered signal ──────────────────────────────────────────────────

    @Test
    fun `being in the room is ringing, not answered, and no timer runs`() = runTest {
        // ⛔ THE DIAL ROUTE RETURNS BEFORE THE CALLEE'S PHONE RINGS, deliberately, so the app can
        // hear call progress from inside the room. A UI that treated `Connected` as answered would
        // count the ringing as conversation on the number an operator compares against an invoice.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.dialTo()
        advanceUntilIdle()
        advanceTimeBy(TICK * 5)

        val call = vm.state.value.call!!
        assertEquals(CallPhase.RINGING, call.phase)
        assertFalse(call.answered)
        assertEquals(0, call.elapsedSeconds)
        // ⛔ TELECOM MUST NOT BE TOLD THE CALL IS ACTIVE YET either — the connection stays DIALING,
        // which is what stops the OS starting its own duration counter at the wrong moment.
        assertEquals(listOf("outgoing:+14165550100"), telecom.calls)
    }

    @Test
    fun `a participant appearing is the answer, and it starts the timer`() = runTest {
        // ⛔ THE ONLY ANSWER SIGNAL THIS CLIENT HAS. There is no signalling channel for
        // ring-versus-answer; LiveKit's SIP bridge adds the callee as a participant when the call
        // is PICKED UP, so a non-empty participant list is the event.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.dialTo()
        advanceUntilIdle()
        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "sip_+14165550100", name = null)))
        // ⚠️ `runCurrent`, NOT `advanceUntilIdle` — see the ⛔ on this class: the ticker this
        // emission starts never lets the scheduler go idle again.
        runCurrent()

        assertTrue(vm.state.value.call!!.answered)
        assertEquals(CallPhase.IN_CALL, vm.state.value.call!!.phase)
        assertTrue("Telecom must learn the call went active", telecom.calls.contains("active"))

        advanceTimeBy(TICK * 3 + 1)
        assertEquals(3, vm.state.value.call!!.elapsedSeconds)

        vm.hangUp {}
        advanceUntilIdle()
    }

    @Test
    fun `the answer is latched, so a callee who hangs up does not restart the ring UI`() = runTest {
        // ⛔ ONCE ANSWERED, AN EMPTYING PARTICIPANT LIST MEANS THE CALLEE HUNG UP — not that they
        // were never there. A flag recomputed from the list would fall back to false and redraw
        // "Calling…" over a call that had just ended, with the duration reset.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.dialTo()
        advanceUntilIdle()
        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "sip_+14165550100", name = null)))
        runCurrent()
        advanceTimeBy(TICK * 2 + 1)
        factory.engine.emitParticipants(emptyList())
        runCurrent()

        assertTrue(vm.state.value.call!!.answered)
        assertEquals(2, vm.state.value.call!!.elapsedSeconds)

        vm.hangUp {}
        advanceUntilIdle()
    }

    @Test
    fun `reconnecting is a banner over a live call, never a failure`() = runTest {
        // ⛔ A PHONE HANDING OVER BETWEEN WIFI AND ITS RADIO DOES THIS ROUTINELY and the SDK
        // recovers by itself. A UI that treated it as failure would end calls that were about to
        // survive the exact ordinary event this state describes.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.dialTo()
        advanceUntilIdle()
        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "sip_x", name = null)))
        runCurrent()
        factory.engine.emitConnection(CallConnectionState.Reconnecting)
        runCurrent()

        val call = vm.state.value.call!!
        assertTrue(call.reconnecting)
        assertEquals(CallPhase.IN_CALL, call.phase)
        assertTrue("the call must not be torn down", telecom.calls.none { it == "disconnected" })

        vm.hangUp {}
        advanceUntilIdle()
    }

    // ── Refusals ─────────────────────────────────────────────────────────────

    @Test
    fun `each refusal gets its own message, and none leaves a call on screen`() = runTest {
        val lapsed = viewModel(
            api = api(
                dial = ApiResult.HttpFailure(
                    status = PAYMENT_REQUIRED,
                    message = "server prose",
                    code = CODE_SUBSCRIPTION_INACTIVE,
                ),
            ),
        )
        lapsed.dialTo()
        advanceUntilIdle()
        assertNull("a refused dial placed no call", lapsed.state.value.call)
        assertEquals(
            R.string.dialer_subscription_inactive,
            lapsed.state.value.refusal?.message?.resourceIdOrNull,
        )

        val capped = viewModel(
            api = api(
                dial = ApiResult.HttpFailure(
                    status = CONFLICT,
                    message = "server prose",
                    code = CODE_OVERAGE_CAP_REACHED,
                ),
            ),
        )
        capped.dialTo()
        advanceUntilIdle()
        // ⛔ A DIFFERENT MESSAGE FROM THE ONE ABOVE, because the remedies are opposite: one plan is
        // unpaid, the other is paid and capped by its own choice. Wording the cap as a payment
        // problem sends an operator to check a card that is working.
        assertEquals(
            R.string.dialer_overage_cap,
            capped.state.value.refusal?.message?.resourceIdOrNull,
        )
    }

    @Test
    fun `the DNC refusal reaches the operator as the server's own sentence`() = runTest {
        // ⛔ THE SERVER SENDS NO `code` HERE, so it is structurally identical to a role refusal and
        // no client can branch on it without matching English. The sentence IS the product, so it
        // is forwarded rather than replaced with something generic.
        val vm = viewModel(
            api = api(
                dial = ApiResult.Forbidden(
                    "This number has opted out of calls from this workspace (DNC).",
                ),
            ),
        )

        vm.dialTo()
        advanceUntilIdle()

        assertNull(vm.state.value.call)
        assertTrue(
            vm.state.value.refusal?.message?.literalOrNull?.contains("opted out") == true,
        )
    }

    @Test
    fun `changing the number clears the last refusal`() = runTest {
        val vm = viewModel(api = api(dial = ApiResult.Forbidden("nope")))
        vm.dialTo()
        advanceUntilIdle()

        vm.onEntryChange("+14165550111")

        // ⚠️ A message about a number the operator has already moved on from is worse than none.
        assertNull(vm.state.value.refusal)
    }

    @Test
    fun `an engine that throws is reported, not rethrown, and Telecom is released`() = runTest {
        // ⛔ THE OS ALREADY BELIEVES A CALL IS UP by the time the connect is attempted. A failed
        // connect that left the Telecom connection DIALING would hold audio focus and suppress the
        // ringer indefinitely, for a call that is not happening.
        val factory = FakeCallEngineFactory()
        factory.engine.connectFailure = IllegalStateException("media server unreachable")
        val vm = viewModel(factory = factory)

        vm.dialTo()
        advanceUntilIdle()

        assertEquals(CallPhase.FAILED, vm.state.value.call!!.phase)
        assertEquals(listOf("outgoing:+14165550100", "disconnected"), telecom.calls)
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    @Test
    fun `mute and speaker forward to the engine and reflect its state`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()

        vm.toggleMicrophone()
        advanceUntilIdle()
        assertFalse(vm.state.value.call!!.micEnabled)
        assertTrue(factory.engine.calls.contains("mic:false"))

        vm.toggleSpeaker()
        // ⚠️ THE ROUTE IS SET SYNCHRONOUSLY BUT THE SCREEN LEARNS ABOUT IT THROUGH THE SESSION'S
        // state flow, which the ViewModel mirrors — so the flag needs one dispatch to land. In
        // production that dispatcher is `Main.immediate` and the update is effectively synchronous.
        runCurrent()
        assertTrue(vm.state.value.call!!.speakerOn)
        assertTrue(factory.engine.calls.contains("speaker:true"))
    }

    @Test
    fun `the controls do nothing when no call is live`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.toggleMicrophone()
        vm.toggleSpeaker()
        advanceUntilIdle()

        assertTrue(factory.engine.calls.isEmpty())
    }

    // ── Hanging up ───────────────────────────────────────────────────────────

    @Test
    fun `hang up disconnects exactly once, freezes the duration, and reports back`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()
        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "sip_x", name = null)))
        runCurrent()
        advanceTimeBy(TICK * 4 + 1)

        var ended = 0
        vm.hangUp { ended++ }
        advanceUntilIdle()
        // ⛔ A SECOND PRESS MUST BE DROPPED, not raced against the first one's scope cancellation.
        vm.hangUp { ended++ }
        advanceUntilIdle()

        assertEquals(1, factory.engine.calls.count { it == "disconnect" })
        assertEquals(1, telecom.calls.count { it == "disconnected" })
        assertEquals(CallPhase.ENDED, vm.state.value.call!!.phase)
        // ⚠️ FROZEN. The timer stops with the call, so the duration on screen is the call's length
        // rather than the time the summary has been open.
        assertEquals(4, vm.state.value.call!!.elapsedSeconds)
        advanceTimeBy(TICK * 3 + 1)
        assertEquals(4, vm.state.value.call!!.elapsedSeconds)
        assertEquals(2, ended)
    }

    @Test
    fun `the OS's own end-call affordance ends the media too`() = runTest {
        // ⛔ WITHOUT THIS THE MEDIA OUTLIVES THE USER'S BELIEF THAT THEY HUNG UP. A self-managed
        // call gets an OS notification and responds to a headset button; a hang-up made there
        // reaches the app only through the handler the bridge armed.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()

        telecom.systemHangUp()
        advanceUntilIdle()

        assertEquals(1, factory.engine.calls.count { it == "disconnect" })
        assertEquals(CallPhase.ENDED, vm.state.value.call!!.phase)
    }

    @Test
    fun `the teardown runs on the scope that outlives the ViewModel, not viewModelScope`() = runTest {
        // ⛔ THE PROPERTY THAT KEEPS A PSTN LEG FROM BEING STRANDED. `onCleared` fires AFTER
        // `viewModelScope` is cancelled, so a disconnect launched there never reaches the socket
        // and the room keeps the participant — bridged to a live carrier call, billing, until the
        // server times it out. `onCleared` itself is `protected` and cannot be called from a test,
        // so the property is pinned from both sides instead: the engine is built with the injected
        // scope, and cancelling THAT scope is what stops the disconnect. If the teardown were
        // launched in `viewModelScope` the cancel below would not affect it and a disconnect would
        // still be recorded — which is exactly the arrangement that fails in production.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()

        assertSame(
            "the engine must be built with the scope that outlives the ViewModel",
            engineScope,
            factory.scopes.single(),
        )

        engineScope.cancel()
        vm.hangUp {}
        advanceUntilIdle()

        assertEquals(0, factory.engine.calls.count { it == "disconnect" })
    }

    @Test
    fun `dismissing the ended call returns to the keypad`() = runTest {
        val vm = viewModel()
        vm.dialTo()
        advanceUntilIdle()
        vm.hangUp {}
        advanceUntilIdle()

        vm.clearEndedCall()

        assertNull(vm.state.value.call)
    }

    // ── The call-back list ───────────────────────────────────────────────────

    @Test
    fun `the call-back list loads on construction and tapping one only fills the field`() = runTest {
        // ⛔ ONE TAP MUST NEVER PLACE A CALL, least of all from a scrolling list where a mis-scroll
        // lands on a row. The call-back handler IS `onEntryChange` — there is no second entry point
        // that could grow a dial, which is what makes that property structural.
        val api = api(callbacks = listOf(callRow()))
        val vm = viewModel(api = api)
        advanceUntilIdle()

        assertEquals(1, (vm.state.value.callbacks as CallbacksState.Ready).calls.size)

        vm.onEntryChange("+14165550100")

        assertEquals("+14165550100", vm.state.value.entry)
        assertTrue(api.dialRequests.isEmpty())
    }

    @Test
    fun `a failed call-back read leaves the keypad usable`() = runTest {
        // ⛔ THE LIST AND THE KEYPAD ARE UNRELATED SERVER SURFACES. An outage of the call log must
        // not become an inability to place a call.
        val api = api()
        api.callsResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        val vm = viewModel(api = api)
        advanceUntilIdle()

        assertTrue(vm.state.value.callbacks is CallbacksState.Failed)
        vm.onEntryChange("+14165550100")
        assertTrue(vm.state.value.canPlaceCall)
    }

    // ── The role gate ────────────────────────────────────────────────────────

    @Test
    fun `a viewer cannot dial, and an unrecognised role fails closed the same way`() = runTest {
        // ⛔ `POST calls/dial` EXCLUDES `viewer` SERVER-SIDE, so this is presence rather than
        // wording. ⛔ And a null role — what `fromWire` answers for a value it does not recognise
        // — must fail the same way: `null != VIEWER` is TRUE, so a `role != VIEWER` check would
        // hand an unknown role the ability to spend the workspace's minutes.
        for (role in listOf(WorkspaceRole.VIEWER, null)) {
            val api = api()
            val vm = viewModel(api = api, role = role)
            vm.onEntryChange("+14165550100")
            vm.onDial()
            vm.onMicrophonePermissionResult(granted = true)
            advanceUntilIdle()

            assertFalse("role=$role must not be able to dial", vm.state.value.canDial)
            assertFalse(vm.state.value.canPlaceCall)
            assertTrue(api.dialRequests.isEmpty())
        }
    }

    // ── Edges of the call lifecycle ──────────────────────────────────────────

    @Test
    fun `a dial abandoned in flight cannot be re-dialled until its answer lands`() = runTest {
        // ⛔ THE ABANDONED DIAL'S CARRIER LEG IS STILL BEING PLACED. A second dial now would be a
        // second call to the same person while the first is being torn down.
        val api = api()
        val vm = viewModel(api = api)
        vm.dialTo()
        vm.hangUp {}

        vm.onEntryChange("+14165550100")
        vm.onDial()
        vm.onMicrophonePermissionResult(granted = true)
        assertEquals("no new permission request while the first dial is out", 1, vm.state.value.microphoneRequest)
        advanceUntilIdle()

        assertEquals(1, api.dialRequests.size)
        assertNull(vm.state.value.call)
    }

    @Test
    fun `mute and speaker toggle back, each round trip reaching the engine`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()

        vm.toggleMicrophone()
        advanceUntilIdle()
        vm.toggleMicrophone()
        advanceUntilIdle()
        vm.toggleSpeaker()
        vm.toggleSpeaker()
        runCurrent()

        assertTrue(vm.state.value.call!!.micEnabled)
        val mic = factory.engine.calls.filter { it.startsWith("mic:") }
        assertEquals(listOf("mic:true", "mic:false", "mic:true"), mic)
        assertFalse(vm.state.value.call!!.speakerOn)
        val speaker = factory.engine.calls.filter { it.startsWith("speaker:") }
        assertEquals(listOf("speaker:true", "speaker:false"), speaker)

        vm.hangUp {}
        advanceUntilIdle()
    }

    @Test
    fun `a second participant is not a second answer, so Telecom is told once`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()

        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "sip_a", name = null)))
        runCurrent()
        advanceTimeBy(TICK * 2 + 1)
        factory.engine.emitParticipants(
            listOf(MediaParticipant(identity = "sip_a", name = null), MediaParticipant(identity = "b", name = null)),
        )
        runCurrent()

        assertEquals(1, telecom.calls.count { it == "active" })
        assertEquals("the duration is not restarted", 2, vm.state.value.call!!.elapsedSeconds)

        vm.hangUp {}
        advanceUntilIdle()
    }

    @Test
    fun `a participant arriving after the hang-up does not answer a call that is over`() = runTest {
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)
        vm.dialTo()
        advanceUntilIdle()
        vm.hangUp {}

        factory.engine.emitParticipants(listOf(MediaParticipant(identity = "sip_late", name = null)))
        advanceUntilIdle()

        assertFalse(vm.state.value.call!!.answered)
        assertFalse(telecom.calls.contains("active"))
    }

    @Test
    fun `dismissing does nothing while there is no call, or while the call is still live`() = runTest {
        val vm = viewModel()
        vm.clearEndedCall()
        assertNull(vm.state.value.call)

        vm.dialTo()
        advanceUntilIdle()
        vm.clearEndedCall()

        assertEquals(CallPhase.RINGING, vm.state.value.call!!.phase)
        vm.hangUp {}
        advanceUntilIdle()
    }

    @Test
    fun `a second call after the first is dismissed shows only the second call's state`() = runTest {
        // ⚠️ The first call's state mirror is replaced, so a late emission from the finished call
        // cannot write over the new one.
        val first = FakeCallEngineFactory()
        val vm = viewModel(factory = first)
        vm.dialTo()
        advanceUntilIdle()
        vm.hangUp {}
        advanceUntilIdle()
        vm.clearEndedCall()

        vm.dialTo("+14165550199")
        advanceUntilIdle()

        assertEquals("+14165550199", vm.state.value.call!!.number)
        assertEquals(CallPhase.RINGING, vm.state.value.call!!.phase)
        vm.hangUp {}
        advanceUntilIdle()
    }

    @Test
    fun `a call placed while the call-back list is read survives the list landing`() = runTest {
        // ⚠️ The two are unrelated reads; a slow call log must not hold the keypad.
        val api = HeldCallsApi().apply {
            dialResult = api().dialResult
            callsResult = ApiResult.Success(listOf(callRow()))
        }
        val vm = viewModel(api = api)
        runCurrent()
        assertTrue(vm.state.value.callbacks is CallbacksState.Loading)

        vm.dialTo()
        runCurrent()
        assertEquals(1, api.dialRequests.size)

        api.gate.complete(Unit)
        runCurrent()
        assertEquals(1, (vm.state.value.callbacks as CallbacksState.Ready).calls.size)
        // ⛔ THE BUG THIS PINS. The read copied the state it saw when it STARTED, so the call
        // placed meanwhile vanished from the screen, and the number with it, when the list landed.
        assertEquals("+14165550100", vm.state.value.call?.number)
        assertEquals("+14165550100", vm.state.value.entry)
        vm.hangUp {}
        advanceUntilIdle()
    }

    /** Holds the call-back read until [gate] opens, as a slow network would. */
    private class HeldCallsApi : TestDistrictApi() {
        val gate = CompletableDeferred<Unit>()

        override suspend fun calls(workspaceId: String, limit: Int, offset: Int): ApiResult<List<CallSummary>> {
            gate.await()
            return super.calls(workspaceId, limit, offset)
        }
    }

    private fun callRow() = CallSummary(
        id = "call-1",
        type = "inbound",
        number = "Ada",
        status = "completed",
        duration = "1m 5s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = false,
        callerName = "Ada",
        from = "+14165550100",
        direction = "inbound",
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private companion object {
        /** ⚠️ Named because detekt's MagicNumber counts a bare status code or interval as one. */
        const val TICK = 1000L
        const val PAYMENT_REQUIRED = 402
        const val CONFLICT = 409
    }
}
