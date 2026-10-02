package com.distronode.districtai.ui.devices

import com.distronode.districtai.core.data.DevicesRepository
import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.DeviceRevokeResponse
import com.distronode.districtai.core.model.NativeDevice
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DeviceRevokeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The device list's state machine.
 *
 * ⛔ THE THREE THINGS THIS SCREEN MUST NEVER DO. It must not stay signed in after revoking the
 * session it is holding — the credential is dead server-side and the user would discover that as
 * a 401 on some later screen. It must not render `revoked: 0` as a failure, because the server
 * returns it deliberately (for a device id that is not yours, so the route is not a membership
 * oracle) and for two ordinary races besides. And it must not render an empty list as a sign-out:
 * the server hides a chain that is mid-rotation, so a single-device account genuinely sees zero
 * rows for a moment while perfectly signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val thisDevice = NativeDevice(
        deviceId = THIS_DEVICE_ID,
        deviceName = "Google Pixel 9",
        platform = "android",
        lastUsedAt = "2026-08-17T09:15:00.000Z",
        createdAt = "2026-08-01T09:15:00.000Z",
    )

    /** ⚠️ Both nullable fields absent, which is the shape a just-signed-in device has. */
    private val otherDevice = NativeDevice(
        deviceId = "device-other-phone",
        platform = "ios",
        createdAt = "2026-07-20T11:00:00.000Z",
    )

    private fun api(devices: List<NativeDevice> = listOf(thisDevice, otherDevice)) =
        FakeDistrictApi().apply {
            devicesResult = ApiResult.Success(DeviceListResponse(success = true, devices = devices))
        }

    private fun viewModel(api: FakeDistrictApi) =
        DevicesViewModel(DevicesRepository(api), thisDeviceId = THIS_DEVICE_ID)

    // ── The read ─────────────────────────────────────────────────────────────

    @Test
    fun `the list loads on entry`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals(1, api.deviceListRequests.size)
        assertEquals(
            listOf(THIS_DEVICE_ID, "device-other-phone"),
            (vm.state.value.devices as DevicesListState.Ready).devices.map { it.deviceId },
        )
    }

    @Test
    fun `an empty list is a success, not a sign-out`() = runTest {
        // ⛔ THE SERVER FILTERS ON `rotatedAt: null`, so a chain caught mid-refresh is invisible.
        // On a single-device account that is an empty list on a perfectly good session. Mapping it
        // to a failure would tell someone they had been signed out while they had not.
        val vm = viewModel(api(devices = emptyList()))
        advanceUntilIdle()

        val ready = vm.state.value.devices
        assertTrue("an empty list must stay a Ready", ready is DevicesListState.Ready)
        assertEquals(emptyList<NativeDevice>(), (ready as DevicesListState.Ready).devices)
    }

    @Test
    fun `a failed read is a failure, not an empty device list`() = runTest {
        // ⛔ "We could not look" must never be drawn as "nothing is signed in" — on this screen
        // that is the difference between finding a lost phone's session and believing it is gone.
        val api = api().apply { devicesResult = ApiResult.HttpFailure(500, "Server error") }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.devices is DevicesListState.Failed)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not an empty account`() = runTest {
        // ⚠️ Every field of the response has a default, so a `{}` body decodes into a well-formed
        // "you have no devices". The repository's envelope check is what stops that being served
        // as an answer; this pins that the ViewModel keeps it a failure.
        val api = api().apply {
            devicesResult = ApiResult.Success(DeviceListResponse(success = false))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.devices is DevicesListState.Failed)
    }

    @Test
    fun `a refresh keeps the rows on screen while it runs`() = runTest {
        // ⚠️ The re-read after a revoke must not blank a list the user is reading in order to
        // redraw almost the same thing.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.load(refreshing = true)

        assertTrue(
            "a refreshing load must not fall back to Loading",
            vm.state.value.devices is DevicesListState.Ready,
        )
    }

    // ── Revoking another device ──────────────────────────────────────────────

    @Test
    fun `revoking another device does not sign this one out`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeDevice("device-other-phone") { signOuts += 1 }
        advanceUntilIdle()

        assertEquals(listOf("revoke:device-other-phone"), api.deviceWrites)
        assertEquals("the session in the user's hand is untouched", 0, signOuts)
    }

    @Test
    fun `revoking another device re-reads the list`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.revokeDevice("device-other-phone") {}
        advanceUntilIdle()

        assertEquals("the row that vanished has to stop being shown", 2, api.deviceListRequests.size)
    }

    @Test
    fun `revoked zero is a neutral notice and a refresh, never an error`() = runTest {
        // ⛔ THE NON-ORACLE CONTRACT, ASSERTED FROM THE CLIENT SIDE. The server answers
        // `{success:true, revoked:0}` for a device id the account does not own — deliberately, so
        // the route cannot be used to probe an opaque id space — and identically for a row another
        // device already revoked. A client that painted this red would report a fault that did not
        // happen; one that said "signed out" would claim a revocation that did not happen either.
        val api = api().apply {
            deviceRevokeResult = ApiResult.Success(DeviceRevokeResponse(success = true, revoked = 0))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.revokeDevice("device-other-phone") {}
        advanceUntilIdle()

        assertTrue("zero must raise the neutral notice", vm.state.value.nothingRevoked)
        assertNull("and must NOT raise a failure", vm.state.value.mutationFailure)
        assertEquals("and must still refresh the list", 2, api.deviceListRequests.size)
    }

    @Test
    fun `a failed revoke keeps the list on screen and offers the reason`() = runTest {
        // ⚠️ Shown ALONGSIDE the rows: a failed write does not invalidate a correct list already
        // in hand, and blanking it would lose exactly what the user was about to act on.
        val api = api().apply {
            deviceRevokeResult = ApiResult.HttpFailure(500, "Could not sign out that device.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeDevice("device-other-phone") { signOuts += 1 }
        advanceUntilIdle()

        assertTrue(vm.state.value.devices is DevicesListState.Ready)
        assertNotNullFailure(vm)
        assertFalse(vm.state.value.busy)
        assertEquals("a failed revoke must not sign anyone out", 0, signOuts)
    }

    @Test
    fun `notices are dismissible without a re-read`() = runTest {
        val api = api().apply {
            deviceRevokeResult = ApiResult.HttpFailure(500, "Could not sign out that device.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()
        vm.revokeDevice("device-other-phone") {}
        advanceUntilIdle()
        val readsBefore = api.deviceListRequests.size

        vm.dismissNotices()

        assertNull(vm.state.value.mutationFailure)
        assertFalse(vm.state.value.nothingRevoked)
        assertEquals(readsBefore, api.deviceListRequests.size)
    }

    // ── Revoking THIS device ─────────────────────────────────────────────────

    @Test
    fun `revoking this device signs it out locally`() = runTest {
        // ⛔ THE SERVER CANNOT TELL THIS PROCESS ITS CREDENTIAL JUST DIED — it would simply 401 on
        // the next request, on whatever screen the user happened to be looking at. So the local
        // half has to be driven from the response, which is what the callback is for.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeDevice(THIS_DEVICE_ID) { signOuts += 1 }
        advanceUntilIdle()

        assertEquals(1, signOuts)
    }

    @Test
    fun `revoking this device signs out even when the server revoked nothing`() = runTest {
        // ⚠️ A zero here means the row was already gone — revoked from another device, or rotated
        // out from under the list. In every one of those cases the credential this app holds is
        // dead or about to be, so staying signed in would leave the user looking at a device list
        // that no longer contains them.
        val api = api().apply {
            deviceRevokeResult = ApiResult.Success(DeviceRevokeResponse(success = true, revoked = 0))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeDevice(THIS_DEVICE_ID) { signOuts += 1 }
        advanceUntilIdle()

        assertEquals(1, signOuts)
        assertFalse(
            "the notice is pointless on a screen that is being torn down",
            vm.state.value.nothingRevoked,
        )
    }

    @Test
    fun `revoking this device does not re-read a list nobody will see`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.revokeDevice(THIS_DEVICE_ID) {}
        advanceUntilIdle()

        assertEquals(1, api.deviceListRequests.size)
    }

    // ── Revoking everything ──────────────────────────────────────────────────

    @Test
    fun `revoke-all signs this device out too`() = runTest {
        // ⛔ THE SERVER'S "ALL" GENUINELY MEANS ALL: its route header states that sparing the
        // caller would be a control nobody could reason about. A client that stayed signed in
        // would be holding a credential the server has already retired.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeAllDevices { signOuts += 1 }
        advanceUntilIdle()

        assertEquals(listOf("revoke-all"), api.deviceWrites)
        assertEquals(1, signOuts)
    }

    @Test
    fun `revoke-all signs out on a second press, which revokes nothing`() = runTest {
        // ⚠️ Zero is a legitimate success here: everything was already revoked. It is still this
        // device's sign-out.
        val api = api().apply {
            deviceRevokeResult = ApiResult.Success(DeviceRevokeResponse(success = true, revoked = 0))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeAllDevices { signOuts += 1 }
        advanceUntilIdle()

        assertEquals(1, signOuts)
    }

    @Test
    fun `a failed revoke-all does not sign anyone out`() = runTest {
        // ⛔ THE FAIL-OPEN TO AVOID. Reporting "signed out everywhere" while the rows are untouched
        // stops the user looking for the problem — which is exactly why the route refuses to
        // downgrade its own 500 into a success.
        val api = api().apply {
            deviceRevokeResult = ApiResult.HttpFailure(500, "Could not sign out your devices.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        var signOuts = 0
        vm.revokeAllDevices { signOuts += 1 }
        advanceUntilIdle()

        assertEquals(0, signOuts)
        assertNotNullFailure(vm)
    }

    // ── Concurrency ──────────────────────────────────────────────────────────

    @Test
    fun `a second write while one is in flight is dropped`() = runTest {
        // ⛔ DROPPED, NOT QUEUED. Both writes end sessions and both are followed by a re-read; a
        // queued second one could revoke a row the refreshed list no longer shows, or race the
        // local sign-out the first is about to trigger.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.revokeDevice("device-other-phone") {}
        vm.revokeDevice("device-other-phone") {}
        advanceUntilIdle()

        assertEquals(listOf("revoke:device-other-phone"), api.deviceWrites)
    }

    @Test
    fun `the busy flag clears so a later write is possible`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.revokeDevice("device-other-phone") {}
        advanceUntilIdle()
        vm.revokeAllDevices {}
        advanceUntilIdle()

        assertEquals(listOf("revoke:device-other-phone", "revoke-all"), api.deviceWrites)
    }

    @Test
    fun `a list read landing mid-write keeps the write's lock, so a second tap is still dropped`() = runTest {
        // ⛔ THE BUG THIS PINS. The read copied the state it saw when it STARTED, so a revoke
        // begun while the list was loading had its `busy` flag cleared by the read landing, and a
        // second tap then started a second revoke while the first was still in flight.
        val api = HeldDevicesApi().apply {
            devicesResult = api().devicesResult
        }
        val vm = viewModel(api)
        runCurrent()

        vm.revokeDevice(OTHER_DEVICE_ID) {}
        runCurrent()
        api.listGate.complete(Unit)
        runCurrent()

        assertTrue("the write is still in flight", vm.state.value.busy)
        vm.revokeDevice(OTHER_DEVICE_ID) {}
        api.writeGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("revoke:$OTHER_DEVICE_ID"), api.deviceWrites)
    }

    /** Holds the first list read and every write until their gates open, as a slow network would. */
    private class HeldDevicesApi : FakeDistrictApi() {
        val listGate = CompletableDeferred<Unit>()
        val writeGate = CompletableDeferred<Unit>()

        override suspend fun devices(): ApiResult<DeviceListResponse> {
            listGate.await()
            return super.devices()
        }

        override suspend fun revokeDevice(request: DeviceRevokeRequest): ApiResult<DeviceRevokeResponse> {
            writeGate.await()
            return super.revokeDevice(request)
        }
    }

    private fun assertNotNullFailure(vm: DevicesViewModel) {
        assertTrue(
            "a genuine server fault must surface as a mutation failure",
            vm.state.value.mutationFailure != null,
        )
    }

    private companion object {
        /** ⛔ The opaque installation id — the ONLY trustworthy way to tell which row is ours. */
        const val THIS_DEVICE_ID = "device-this-installation"
        const val OTHER_DEVICE_ID = "device-other-phone"
    }
}
