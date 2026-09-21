package com.distronode.districtai.ui.dialer

import com.distronode.districtai.core.data.CallControlRepository
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.DialRepository
import com.distronode.districtai.core.data.HangUpOutcome
import com.distronode.districtai.core.model.CallHangUpResponse
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestCallControlApi
import com.distronode.districtai.ui.TestDistrictApi
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Ending the CARRIER leg, which is the half `Room.disconnect()` cannot do.
 *
 * ⛔ THE FAILURE THIS EXISTS FOR IS A BILL, NOT AN ERROR. An End button that disconnects only the
 * operator's own LiveKit participant leaves the SIP leg up, so the room outlives the operator and
 * the carrier leg runs on: carrier records show calls ended in under a second each billed roughly
 * 90 seconds, with nothing failing and nothing logged. Against a real callee the phone keeps
 * RINGING after the app has said "call ended".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialerServerHangUpTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var engineScope: CoroutineScope

    private val telecom = FakeTelecomBridge()

    private val callControlApi = TestCallControlApi()

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

    private val placed = DialResponse(
        success = true,
        callId = "CA0123456789abcdef0123456789abcdef",
        roomName = "direct_ws-1-CA0123456789abcdef0123456789abcdef",
        token = "jwt-dial",
        url = "wss://livekit.test",
    )

    private fun api(dial: ApiResult<DialResponse> = ApiResult.Success(placed)) =
        TestDistrictApi().apply {
            dialResult = dial
            callsResult = ApiResult.Success(emptyList())
        }

    private fun viewModel(
        api: TestDistrictApi = api(),
        factory: FakeCallEngineFactory = FakeCallEngineFactory(),
    ) = DialerViewModel(
        dialRepository = DialRepository(api),
        callsRepository = CallsRepository(api),
        callControl = CallControlRepository(callControlApi),
        sessions = SoftphoneSessionFactory(
            engineFactory = factory,
            telecom = telecom,
            engineScopeFactory = { engineScope },
            tickMillis = TICK,
        ),
        workspaceId = "ws-1",
        role = WorkspaceRole.AGENCY,
        serverHangUpScope = engineScope,
    )

    private fun DialerViewModel.dial() {
        onEntryChange("+14165550100")
        onDial()
        onMicrophonePermissionResult(granted = true)
    }

    @Test
    fun `hanging up a live call asks the server to end the carrier leg`() = runTest {
        val vm = viewModel()
        vm.dial()
        advanceUntilIdle()

        vm.hangUp {}
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to placed.callId), callControlApi.hangUps)
    }

    @Test
    fun `the call id comes from the dial response and is not derived from the room name`() =
        runTest {
            // ⛔ `Call.callSid`, NOT A ROOM NAME. The room is `direct_<workspaceId>-<callSid>` and
            // the route matches on the callSid alone; sending the room name would 404 on every
            // hang-up while the app reported a clean end.
            val vm = viewModel()
            vm.dial()
            advanceUntilIdle()

            vm.hangUp {}
            advanceUntilIdle()

            assertEquals(placed.callId, callControlApi.hangUps.single().second)
        }

    @Test
    fun `a second hang-up spends no second request`() = runTest {
        // ⚠️ THE ROUTE IS IDEMPOTENT, so a second send would be SAFE — and it would still be wrong:
        // it would spend a request on behalf of a call nobody is on.
        val vm = viewModel()
        vm.dial()
        advanceUntilIdle()

        vm.hangUp {}
        vm.hangUp {}
        advanceUntilIdle()

        assertEquals(1, callControlApi.hangUps.size)
    }

    @Test
    fun `a hang-up before any call was placed sends nothing`() = runTest {
        // ⚠️ THERE IS NO `callId` TO SEND. The keypad's End is reachable with no call in flight.
        val vm = viewModel()
        advanceUntilIdle()

        vm.hangUp {}
        advanceUntilIdle()

        assertTrue(callControlApi.hangUps.isEmpty())
    }

    @Test
    fun `a dial that lands AFTER the operator hung up still ends the carrier leg`() = runTest {
        // ⛔ THE CASE THE WHOLE DESIGN IS SHAPED AROUND, AND IT IS THE COMMON ONE. `calls/dial`
        // writes the row and instructs the carrier BEFORE it answers, so a response that has not
        // arrived may already be ringing somebody — and an operator who misdials hangs up in well
        // under a second. Discarding that response would throw away the only callId this client
        // will ever hold, and the phone at the far end would go on ringing.
        val factory = FakeCallEngineFactory()
        val vm = viewModel(factory = factory)

        vm.dial()
        // ⚠️ NOT advanced: the dial is still in flight, which is the whole point.
        vm.hangUp {}
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to placed.callId), callControlApi.hangUps)
        // ⛔ AND NO SESSION IS BUILT. A call screen back on top of a call the operator already
        // ended would be worse than the bill.
        assertEquals(null, vm.state.value.call)
    }

    @Test
    fun `a refused dial leaves nothing to hang up`() = runTest {
        // ⚠️ ONLY A PLACED CALL CARRIES AN ID. A refusal means no call reached the carrier, so
        // there is nothing to end.
        val vm = viewModel(api(dial = ApiResult.Forbidden("viewers may not dial")))
        vm.dial()
        advanceUntilIdle()

        vm.hangUp {}
        advanceUntilIdle()

        assertTrue(callControlApi.hangUps.isEmpty())
    }

    @Test
    fun `a failed carrier hang-up is recorded and never shown`() = runTest {
        // ⛔ BY THE TIME THIS IS SENT THE CALL IS OVER LOCALLY and the operator is looking at a
        // summary. An error over it would report a failure for a call that ended correctly from
        // their point of view — so the outcome is a diagnostic, not a refusal on screen.
        callControlApi.result = ApiResult.NetworkFailure(IOException("offline"))
        val vm = viewModel()
        vm.dial()
        advanceUntilIdle()

        vm.hangUp {}
        advanceUntilIdle()

        assertTrue(vm.lastServerHangUp is HangUpOutcome.NotEnded)
        assertEquals(null, vm.state.value.refusal)
    }

    @Test
    fun `an already-ended call is a success rather than a fault`() = runTest {
        // ⚠️ `ended: false` MEANS NO DEPLOYMENT HELD THE ROOM: the callee hung up, the room emptied,
        // or a previous hangup already ran.
        callControlApi.result =
            ApiResult.Success(CallHangUpResponse(success = true, ended = false))
        val vm = viewModel()
        vm.dial()
        advanceUntilIdle()

        vm.hangUp {}
        advanceUntilIdle()

        assertEquals(HangUpOutcome.AlreadyEnded, vm.lastServerHangUp)
    }

    private companion object {
        const val TICK = 1000L
    }
}
