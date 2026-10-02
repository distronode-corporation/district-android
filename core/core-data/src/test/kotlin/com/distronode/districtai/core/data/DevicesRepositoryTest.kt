package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.DeviceRevokeResponse
import com.distronode.districtai.core.model.NativeDevice
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * ⛔ THE ONE THING THIS LAYER MUST NOT DO IS TURN A NON-ANSWER INTO AN ANSWER. Every field of
 * `DeviceListResponse` has a default, so an HTTP 200 whose body is `{}` decodes cleanly into "you
 * have no devices signed in" — which on this screen is the wrong answer to "is my lost phone still
 * live". The envelope check is what makes that a failure instead, and these tests pin it.
 *
 * ⛔ AND `revoked: 0` MUST SURVIVE UNTOUCHED. The server returns it deliberately for a device id
 * the account does not own, so the route cannot be used as a membership oracle over an opaque id
 * space — a repository that promoted zero to a failure would be reintroducing that oracle from the
 * client side, and one that hid it would claim a revocation that never happened.
 */
class DevicesRepositoryTest {

    private val device = NativeDevice(
        deviceId = "device-1",
        deviceName = "Google Pixel 9",
        platform = "android",
        lastUsedAt = "2026-08-17T09:15:00.000Z",
        createdAt = "2026-08-01T09:15:00.000Z",
    )

    private fun api() = FakeDistrictApi().apply {
        devicesResult = ApiResult.Success(DeviceListResponse(success = true, devices = listOf(device)))
    }

    @Test
    fun `the list is account-scoped, taking no workspace at all`() = runTest {
        // ⚠️ Stated as a test because it is a property of the ROUTE, not of this class: a native
        // session belongs to a user, and there is no parameter that could narrow or widen it.
        val api = api()

        val result = DevicesRepository(api).devices()

        assertEquals(listOf("list"), api.deviceCalls)
        assertEquals(
            listOf("device-1"),
            (result as ApiResult.Success).value.devices.map { it.deviceId },
        )
    }

    @Test
    fun `an empty list is a success, because a rotating chain is briefly invisible`() = runTest {
        // ⛔ The server filters on `rotatedAt: null`, so a single-device account genuinely sees
        // zero rows for a moment on a perfectly good session.
        val api = api().apply {
            devicesResult = ApiResult.Success(DeviceListResponse(success = true))
        }

        val result = DevicesRepository(api).devices()

        assertTrue(result is ApiResult.Success)
        assertEquals(emptyList<NativeDevice>(), (result as ApiResult.Success).value.devices)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not an empty account`() = runTest {
        // ⛔ THE `{}` BODY. It decodes into a well-formed "no devices", which is exactly the answer
        // an operator would act on by concluding their lost phone is already signed out.
        val api = api().apply {
            devicesResult = ApiResult.Success(DeviceListResponse(success = false))
        }

        val result = DevicesRepository(api).devices()

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a failed read stays a failure`() = runTest {
        val api = api().apply { devicesResult = ApiResult.HttpFailure(500, "Server error") }

        assertTrue(DevicesRepository(api).devices() is ApiResult.HttpFailure)
    }

    @Test
    fun `revoking a device sends the id and returns the count`() = runTest {
        val api = api()

        val result = DevicesRepository(api).revokeDevice("device-2")

        assertEquals(listOf("revoke:device-2"), api.deviceCalls)
        assertEquals(1, (result as ApiResult.Success).value.revoked)
    }

    @Test
    fun `revoked zero is carried through as a success, never promoted to a failure`() = runTest {
        // ⛔ THE NON-ORACLE CONTRACT. Zero is what the server says for a device id that is not
        // yours, and equally for a row another device already revoked. This layer cannot tell them
        // apart and neither can the server, so the count travels untouched to the screen.
        val api = api().apply {
            deviceRevokeResult = ApiResult.Success(DeviceRevokeResponse(success = true, revoked = 0))
        }

        val result = DevicesRepository(api).revokeDevice("device-not-mine")

        assertTrue("zero must remain a success", result is ApiResult.Success)
        assertEquals(0, (result as ApiResult.Success).value.revoked)
    }

    @Test
    fun `a revoke that does not affirm success is contract drift`() = runTest {
        // ⚠️ The dangerous direction here is the opposite of the list's: a `{}` body decodes into
        // `revoked: 0`, which would render as the perfectly ordinary "already signed out" notice
        // for a write that may not have happened at all.
        val api = api().apply {
            deviceRevokeResult = ApiResult.Success(DeviceRevokeResponse(success = false))
        }

        assertTrue(DevicesRepository(api).revokeDevice("device-2") is ApiResult.DecodeFailure)
    }

    @Test
    fun `revoke-all sends no id and returns the count`() = runTest {
        val api = api().apply {
            deviceRevokeResult = ApiResult.Success(DeviceRevokeResponse(success = true, revoked = 3))
        }

        val result = DevicesRepository(api).revokeAllDevices()

        assertEquals(listOf("revoke-all"), api.deviceCalls)
        assertEquals(3, (result as ApiResult.Success).value.revoked)
    }

    @Test
    fun `a failed revoke-all is a failure, never a reported sign-out`() = runTest {
        // ⛔ THE FAIL-OPEN THE SERVER REFUSES TO SHIP AND THIS LAYER MUST NOT REINTRODUCE. A caller
        // told "signed out everywhere" while the rows are untouched stops looking for the problem.
        val api = api().apply {
            deviceRevokeResult = ApiResult.HttpFailure(500, "Could not sign out your devices.")
        }

        assertTrue(DevicesRepository(api).revokeAllDevices() is ApiResult.HttpFailure)
    }
}
