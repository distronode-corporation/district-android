package com.distronode.districtai.ui.dialer

import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallControlRepository
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.DialRepository
import com.distronode.districtai.core.data.NumbersRepository
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.DialResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.rooms.FakeCallEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeCallControlApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.OwnedNumbersResponse

/**
 * Dialler design A in the ViewModel: the field read in the country on the button, the E.164 number
 * that is sent, when a wrong length is said, and where the home country comes from.
 *
 * ⚠️ ITS OWN CLASS BECAUSE `DialerViewModelTest` CROSSED detekt's `LargeClass` WITH THESE IN IT. The
 * fixture is the same one, so a dial here goes through the same engine fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DialerCountryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

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
        engineScope = CoroutineScope(dispatcher)
    }

    @After
    fun tearDown() {
        engineScope.cancel()
    }

    private val telecom = FakeTelecomBridge()

    /** ⛔ Recorded per test: the carrier hang-up must be sent exactly once and never retried. */
    private val callControlApi = FakeCallControlApi()

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
    ) = FakeDistrictApi().apply {
        dialResult = dial
        callsResult = ApiResult.Success(callbacks)
    }

    private fun viewModel(
        api: FakeDistrictApi = api(),
        factory: FakeCallEngineFactory = FakeCallEngineFactory(),
        role: WorkspaceRole? = WorkspaceRole.AGENCY,
        memory: DialerCountryMemory = FakeDialerCountryMemory(),
    ) = DialerViewModel(
        dialRepository = DialRepository(api),
        callsRepository = CallsRepository(api),
        numbers = NumbersRepository(api),
        countryMemory = memory,
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

    // ── Dialler design A ─────────────────────────────────────────────────────

    private fun owned(vararg numbers: String) = ApiResult.Success(
        OwnedNumbersResponse(success = true, numbers = numbers.map { ListedNumber(phoneNumber = it) }),
    )

    @Test
    fun `a local number is formatted in the field and dialled as E164`() = runTest {
        val api = api()
        val vm = viewModel(api = api, memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA")))
        advanceUntilIdle()

        vm.dialTo("4165550199")
        advanceUntilIdle()

        assertEquals("(416) 555-0199", vm.state.value.entry)
        // ⛔ THE NUMBER THE LINE UNDER THE FIELD SHOWED, NOT THE FIELD'S TEXT.
        assertEquals("+14165550199", api.dialRequests.single().to)
        vm.hangUp {}
    }

    @Test
    fun `a pasted plus forty four number moves the flag and the field shows the national form`() = runTest {
        val vm = viewModel(memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA")))
        advanceUntilIdle()

        vm.onEntryChange("+44 20 7946 0958")

        assertEquals("GB", vm.state.value.country)
        assertEquals("020 7946 0958", vm.state.value.entry)
        assertEquals("+442079460958", vm.state.value.reading.e164)
        assertTrue(vm.state.value.canPlaceCall)
    }

    /**
     * ⛔ THE BILLED CALL. `+4165550123` moves the flag to Switzerland and can never be dialled; the
     * sentence waits until the operator stops, and the next key clears it.
     */
    @Test
    fun `the Switzerland misdial is never dialled, and a refused tap says why`() = runTest {
        val api = api()
        val vm = viewModel(api = api, memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA")))
        advanceUntilIdle()

        vm.onEntryChange("+4165550123")
        assertEquals("CH", vm.state.value.country)
        assertFalse(vm.state.value.canPlaceCall)
        assertNull("nothing said while typing", vm.state.value.entryProblem)

        vm.onDial()
        vm.onMicrophonePermissionResult(granted = true)
        advanceUntilIdle()

        assertTrue(api.dialRequests.isEmpty())
        assertEquals(0, vm.state.value.microphoneRequest)
        assertEquals(R.string.dialer_entry_wrong_length, vm.state.value.entryProblem)

        // The next key clears it, and makes a whole Swiss number.
        vm.onEntryChange(vm.state.value.entry + "4")
        assertNull(vm.state.value.entryProblem)
        assertEquals("+41655501234", vm.state.value.reading.e164)
    }

    @Test
    fun `stopping on a wrong length says so, and an edit clears it`() = runTest {
        val vm = viewModel(memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA")))
        vm.onEntryChange("416555")
        vm.checkLength()
        assertEquals(R.string.dialer_entry_wrong_length, vm.state.value.entryProblem)
        vm.onEntryChange("4165550")
        assertNull(vm.state.value.entryProblem)
        vm.onEntryChange("416555019999")
        assertEquals(DialPlan.Status.TOO_LONG, vm.state.value.reading.status)
        assertNull(vm.state.value.entryProblem)
        vm.checkLength()
        assertEquals(R.string.dialer_entry_wrong_length, vm.state.value.entryProblem)
    }

    @Test
    fun `a lone plus or trunk is not a wrong length`() = runTest {
        val vm = viewModel(memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "GB")))
        vm.onEntryChange("+")
        vm.checkLength()
        assertNull(vm.state.value.entryProblem)
        vm.onEntryChange("0")
        vm.checkLength()
        assertNull(vm.state.value.entryProblem)
    }

    @Test
    fun `emergency numbers are refused whatever the country`() = runTest {
        val vm = viewModel(memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA")))
        for (number in listOf("911", "112", "999", "+1911")) {
            vm.onEntryChange(number)
            assertFalse(number, vm.state.value.canPlaceCall)
            assertEquals(number, R.string.api_error_emergency_number, vm.state.value.entryProblem)
        }
    }

    @Test
    fun `with no home country, digits ask for a country and a plus number still dials`() = runTest {
        val api = api()
        val vm = viewModel(api = api)
        advanceUntilIdle()

        assertNull(vm.state.value.country)
        vm.onEntryChange("4165550199")
        assertFalse(vm.state.value.canPlaceCall)
        assertEquals(R.string.dialer_entry_hint_none, vm.state.value.entryProblem)

        vm.dialTo("+33 6 39 98 01 23")
        advanceUntilIdle()
        assertEquals("+33639980123", api.dialRequests.single().to)
        vm.hangUp {}
    }

    @Test
    fun `the home country comes from the workspace's numbers and is remembered`() = runTest {
        val api = api().apply { ownedResult = owned("+442079460100", "+442079460101", "+14165550100") }
        val memory = FakeDialerCountryMemory()
        val vm = viewModel(api = api, memory = memory)
        advanceUntilIdle()

        assertEquals("GB", vm.state.value.country)
        assertEquals("GB", vm.state.value.homeCountry)
        assertEquals("GB", memory.homes["ws-1"])
        assertEquals(listOf("ws-1"), api.ownedRequests)
    }

    @Test
    fun `the cached home country is on the button before the numbers load`() = runTest {
        val vm = viewModel(memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "FR")))
        assertEquals("FR", vm.state.value.country)
        assertEquals("FR", vm.state.value.homeCountry)
    }

    /** ⛔ THE ANSWER NEVER OVERWRITES WHAT THE OPERATOR DID WHILE IT WAS IN FLIGHT. */
    @Test
    fun `a home country arriving late moves neither a pick nor a started number`() = runTest {
        val picked = viewModel(api = api().apply { ownedResult = owned("+442079460100") })
        picked.pickCountry("DE")
        advanceUntilIdle()
        assertEquals("DE", picked.state.value.country)
        assertEquals("GB", picked.state.value.homeCountry)

        val typing = viewModel(
            api = api().apply { ownedResult = owned("+442079460100") },
            memory = FakeDialerCountryMemory(picks = mutableMapOf("ws-1" to "FR")),
        )
        typing.onEntryChange("06")
        advanceUntilIdle()
        assertEquals("FR", typing.state.value.country)
    }

    @Test
    fun `a failed numbers read changes nothing`() = runTest {
        val api = api().apply { ownedResult = ApiResult.NetworkFailure(java.io.IOException("offline")) }
        val memory = FakeDialerCountryMemory(picks = mutableMapOf("ws-1" to "FR"))
        val vm = viewModel(api = api, memory = memory)
        advanceUntilIdle()

        assertEquals("FR", vm.state.value.country)
        assertNull(vm.state.value.homeCountry)
        assertTrue(memory.homes.isEmpty())
    }

    @Test
    fun `numbers that name no country leave the button alone`() = runTest {
        val vm = viewModel(api = api().apply { ownedResult = owned("+18005550100") })
        advanceUntilIdle()
        assertNull(vm.state.value.country)
        assertNull(vm.state.value.homeCountry)
    }

    @Test
    fun `a pick on a workspace with no home is remembered, and the digits are read again`() = runTest {
        val memory = FakeDialerCountryMemory()
        val vm = viewModel(memory = memory)
        advanceUntilIdle()

        vm.onEntryChange("02079460958")
        assertEquals(DialPlan.Status.NEEDS_COUNTRY, vm.state.value.reading.status)
        vm.pickCountry("GB")

        assertEquals("GB", memory.picks["ws-1"])
        assertEquals("020 7946 0958", vm.state.value.entry)
        assertEquals("+442079460958", vm.state.value.reading.e164)
        // And the next keypad on this workspace opens there.
        assertEquals("GB", viewModel(memory = memory).state.value.country)
    }

    @Test
    fun `a pick on a workspace with a home country is not remembered`() = runTest {
        val memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA"))
        val vm = viewModel(memory = memory)
        vm.pickCountry("US")
        assertEquals("US", vm.state.value.country)
        assertTrue(memory.picks.isEmpty())
    }

    @Test
    fun `a pick counts as an edit and clears the length sentence`() = runTest {
        val vm = viewModel(memory = FakeDialerCountryMemory(homes = mutableMapOf("ws-1" to "CA")))
        vm.onEntryChange("416555")
        vm.checkLength()
        vm.pickCountry("US")
        assertNull(vm.state.value.entryProblem)
        assertFalse(vm.state.value.lengthChecked)
    }

    private companion object {
        const val TICK = 1000L
    }
}
