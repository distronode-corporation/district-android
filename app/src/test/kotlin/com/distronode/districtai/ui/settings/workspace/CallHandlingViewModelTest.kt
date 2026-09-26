package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.CallHandlingRepository
import com.distronode.districtai.core.model.AvailabilityReason
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestCallHandlingApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Who answers a call, how long this phone rings, and whether it is rung at all.
 *
 * ⛔ TWO SCOPES BEHIND ONE SCREEN, AND EVERY TEST HERE IS ABOUT KEEPING THEM APART. The mode is a
 * WORKSPACE setting that every member sees; availability is a fact about the CALLER'S OWN
 * membership row, and its route takes no identity at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallHandlingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun api(
        handling: CallHandlingResponse = CallHandlingResponse(
            success = true,
            callHandling = CallHandling.AI_FIRST,
            appRingSeconds = 20,
        ),
        availability: AvailabilityResponse = AvailabilityResponse(
            success = true,
            availableForCalls = true,
        ),
    ) = TestCallHandlingApi().apply {
        handlingResult = ApiResult.Success(handling)
        availabilityResult = ApiResult.Success(availability)
    }

    private fun viewModel(
        api: TestCallHandlingApi,
        role: WorkspaceRole? = WorkspaceRole.AGENCY,
    ) = CallHandlingViewModel(CallHandlingRepository(api), workspaceId = "ws-1", role = role)

    @Test
    fun `both reads land, and either can fail without taking the other with it`() = runTest {
        // ⛔ TWO SCOPES, TWO LOAD STATES. A single one would hide a working availability switch
        // behind a failed workspace read, or show one screen's error over the other's value.
        val api = api().apply { handlingResult = ApiResult.NetworkFailure(IOException("down")) }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.load is CallHandlingLoad.LoadFailed)
        assertTrue(vm.state.value.availability is AvailabilityLoad.Ready)
        assertTrue(vm.state.value.availableForCalls)
    }

    @Test
    fun `an unknown mode is refused before it becomes a draft`() = runTest {
        // ⛔ THE ROUTE ANSWERS 400 FOR IT, which is an error the operator cannot act on. A picker
        // can only offer one through this app's own bug.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.selectMode("ai_maybe")

        assertEquals(CallHandling.AI_FIRST, vm.state.value.mode)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `the ring slider is clamped to the range the server accepts`() = runTest {
        // ⛔ CLAMPED HERE BECAUSE THIS IS THE CONTROL. Letting a position outside the bounds reach
        // the request would be a 400 for something the slider should never have offered. ⚠️ The
        // repository deliberately does NOT clamp, so a value arriving from anywhere else is still
        // reported rather than silently corrected.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.selectRingSeconds(90)
        assertEquals(CallHandling.MAXIMUM_RING_SECONDS, vm.state.value.ringSeconds)

        vm.selectRingSeconds(1)
        assertEquals(CallHandling.MINIMUM_RING_SECONDS, vm.state.value.ringSeconds)
    }

    @Test
    fun `only the changed field is sent`() = runTest {
        // ⛔ SENDING BOTH EVERY TIME WOULD BE HARMLESS TODAY AND STILL WRONG: it would write a ring
        // window the operator never touched.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.selectMode(CallHandling.APP_FIRST)
        vm.save()
        advanceUntilIdle()

        assertEquals(listOf(CallHandling.APP_FIRST to null), api.handlingWrites)
    }

    @Test
    fun `the response is adopted as the new baseline rather than re-read`() = runTest {
        // ⚠️ THE OPPOSITE OF EVERY OTHER FORM IN THIS PACKAGE. This PATCH echoes what it wrote
        // through the same normaliser the read uses, so a second request could only confirm what is
        // already in hand.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        api.handlingResult = ApiResult.Success(
            CallHandlingResponse(
                success = true,
                callHandling = CallHandling.APP_FIRST,
                appRingSeconds = 20,
            ),
        )
        vm.selectMode(CallHandling.APP_FIRST)
        vm.save()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, vm.state.value.save)
        assertEquals(CallHandling.APP_FIRST, vm.state.value.mode)
        assertFalse("the draft is cleared against the echo", vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `a viewer can read both values and change neither`() = runTest {
        // ⛔ BOTH READS ADMIT A VIEWER AND BOTH WRITES EXCLUDE ONE. Hiding the screen would withhold
        // the explanation for a call list they can already see.
        val api = api(
            availability = AvailabilityResponse(
                success = true,
                availableForCalls = false,
                reason = AvailabilityReason.ROLE,
            ),
        )
        val vm = viewModel(api, role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertTrue(vm.state.value.load is CallHandlingLoad.Ready)
        assertFalse(vm.state.value.canMutate)

        vm.selectMode(CallHandling.APP_FIRST)
        vm.setAvailability(true)
        advanceUntilIdle()

        assertFalse(vm.state.value.hasUnsavedChanges)
        assertTrue("no availability write may be attempted", api.availabilityWrites.isEmpty())
        assertTrue(vm.state.value.availabilityBlockedByRole)
    }

    @Test
    fun `an agency member with no membership row cannot move the switch`() = runTest {
        // ⛔ THE ROLE GATE IS NOT ENOUGH ON ITS OWN. `no_member_row` is answered to somebody who
        // holds their role through the OWNER fallback, and the PATCH answers 409 for exactly that
        // person — so a switch enabled on the role alone would fail for the likeliest owner.
        val api = api(
            availability = AvailabilityResponse(
                success = true,
                availableForCalls = false,
                reason = AvailabilityReason.NO_MEMBER_ROW,
            ),
        )
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setAvailability(true)
        advanceUntilIdle()

        assertFalse(vm.state.value.canToggleAvailability)
        assertTrue(vm.state.value.availabilityBlockedByMissingRow)
        assertTrue(api.availabilityWrites.isEmpty())
    }

    @Test
    fun `the availability switch sends on the tap, with no save button in between`() = runTest {
        // ⛔ "I am on call" IS A STATEMENT ABOUT RIGHT NOW. A draft sitting unsent while the phone
        // does not ring is the failure this control exists to prevent.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setAvailability(false)
        advanceUntilIdle()

        assertEquals(listOf(false), api.availabilityWrites)
    }

    @Test
    fun `a failed availability write is reported and the switch does not move`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        api.availabilityResult = ApiResult.HttpFailure(status = 409, message = "no membership row")
        vm.setAvailability(false)
        advanceUntilIdle()

        assertTrue(vm.state.value.availabilitySave is SaveState.Failed)
        // ⚠️ STILL TRUE. Showing it as moved would claim a state the ring fan-out does not share.
        assertTrue(vm.state.value.availableForCalls)
    }

    @Test
    fun `a failed availability read leaves the workspace mode readable and the switch withheld`() =
        runTest {
            // ⚠️ THE MIRROR IMAGE OF THE FIRST TEST. The caller's own row could not be read, so the
            // switch has no known position to show, while the workspace mode still did load.
            val api = api().apply { availabilityResult = ApiResult.NetworkFailure(IOException("down")) }
            val vm = viewModel(api)
            advanceUntilIdle()

            assertTrue(vm.state.value.load is CallHandlingLoad.Ready)
            assertTrue(vm.state.value.availability is AvailabilityLoad.LoadFailed)
            assertFalse(vm.state.value.availableForCalls)
            assertFalse(vm.state.value.canToggleAvailability)
        }

    @Test
    fun `a pick made before the read lands is dropped rather than drafted`() = runTest {
        // ⛔ A DRAFT AGAINST A BASELINE NOBODY HAS READ. The screen shows no picker yet; this is
        // the ViewModel refusing the same thing on its own, whatever called it.
        val vm = viewModel(api())

        vm.selectMode(CallHandling.APP_FIRST)
        vm.selectRingSeconds(30)
        advanceUntilIdle()

        assertEquals(CallHandling.AI_FIRST, vm.state.value.mode)
        assertEquals(20, vm.state.value.ringSeconds)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `a viewer cannot move the ring slider either`() = runTest {
        val vm = viewModel(api(), role = WorkspaceRole.VIEWER)
        advanceUntilIdle()

        vm.selectRingSeconds(30)

        assertEquals(20, vm.state.value.ringSeconds)
        assertFalse(vm.state.value.canSave)
    }

    @Test
    fun `a save with nothing changed sends nothing`() = runTest {
        // ⚠️ The route rejects a body carrying neither field, so an empty save would be a 400.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.save()
        advanceUntilIdle()

        assertTrue(api.handlingWrites.isEmpty())
        assertEquals(SaveState.Idle, vm.state.value.save)
    }

    @Test
    fun `a failed save is reported and keeps the draft`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        api.handlingResult = ApiResult.HttpFailure(status = 500, message = "boom")
        vm.selectRingSeconds(30)
        vm.save()
        advanceUntilIdle()

        assertEquals(listOf(null to 30), api.handlingWrites)
        assertTrue(vm.state.value.save is SaveState.Failed)
        assertEquals("the operator's pick survives the failure", 30, vm.state.value.ringSeconds)
        assertTrue("and can be sent again", vm.state.value.canSave)
    }

    @Test
    fun `tapping the switch to the position it already holds sends nothing`() = runTest {
        // ⚠️ A write that changes nothing still spends a request and stamps the row.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setAvailability(true)
        advanceUntilIdle()

        assertTrue(api.availabilityWrites.isEmpty())
        assertEquals(SaveState.Idle, vm.state.value.availabilitySave)
    }

    @Test
    fun `the factory builds a ViewModel that reads at once and carries the role gate`() = runTest {
        val api = api()
        val vm = CallHandlingViewModel
            .factory(CallHandlingRepository(api), "ws-1", WorkspaceRole.VIEWER)
            .create(CallHandlingViewModel::class.java)
        advanceUntilIdle()

        assertTrue(vm.state.value.load is CallHandlingLoad.Ready)
        assertEquals(CallHandling.AI_FIRST, vm.state.value.mode)
        assertFalse("a viewer's ViewModel offers no controls", vm.state.value.canMutate)
    }
}
