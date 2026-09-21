package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.DeviceListResponse
import com.distronode.districtai.core.model.DeviceRevokeResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DeviceRevokeRequest
import com.distronode.districtai.core.network.DistrictApi

/**
 * The account's signed-in devices, and the two ways to end one.
 *
 * ⛔ NO CACHE, AND HERE THAT IS A SAFETY PROPERTY RATHER THAN TIDINESS. This list is what
 * someone consults after losing a phone. A cached row would answer "is my lost phone still
 * signed in" with a value from before they asked — and both directions of that error are bad:
 * a stale live row invites a revoke that already happened, and a stale absent row says the
 * device is gone when it is not.
 *
 * ⛔ ACCOUNT-SCOPED. Nothing here takes a `workspaceId`, because a native session belongs to a
 * user rather than to a tenant. See `DevicesApi` for why that scope cannot be widened by any
 * parameter.
 *
 * ⛔ THIS LAYER DOES NOT SIGN THE USER OUT AND MUST NOT. Revoking THIS device, or revoking all,
 * ends this installation's own session server-side — but the local wipe is `AppContainer`'s
 * job, it involves a scope this layer has no business holding, and the ORDER matters (the epoch
 * bump has to come last). So these functions report what the server did and nothing more; the
 * screen wires the consequence.
 */
class DevicesRepository(private val api: DistrictApi) {

    /**
     * Read the device list.
     *
     * ⚠️ AN EMPTY LIST IS A LEGITIMATE SUCCESS AND NOT A SIGN-OUT. The server filters on
     * `rotatedAt: null`, so a chain caught mid-refresh is briefly invisible — on a
     * single-device account that is an empty list arriving on a perfectly good session. It is
     * an explanatory empty state, never a failure and never a reason to re-authenticate.
     */
    suspend fun devices(): ApiResult<DeviceListResponse> =
        when (val result = api.devices()) {
            // ⚠️ Envelope first, for the reason `ResponseEnvelope` documents: every field of
            // the response has a default, so a `{}` body decodes into a well-formed "you have
            // no devices" — which is exactly the wrong answer to render on this screen.
            is ApiResult.Success -> rejectedEnvelope(LIST_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value)
            is ApiResult.Failure -> result
        }

    /**
     * Sign out one device.
     *
     * ⛔ `revoked == 0` IS NOT AN ERROR AND MUST NOT BE PROMOTED TO ONE HERE. The server
     * answers it for a device id that is not yours — deliberately, so the route is not a
     * membership oracle over an opaque id space — and equally for the ordinary races: a row
     * already revoked from another device, or a chain that rotated between the list read and
     * the tap. This layer cannot tell those apart and neither can the server, so the count is
     * carried through untouched and the screen decides what to say.
     */
    suspend fun revokeDevice(deviceId: String): ApiResult<DeviceRevokeResponse> =
        revokeResult(api.revokeDevice(DeviceRevokeRequest(deviceId)))

    /**
     * Sign out every device, INCLUDING THIS ONE.
     *
     * ⛔ THE CALLER IS NOT SPARED — the server's own header says so, and a caller must treat
     * success as its own sign-out. ⚠️ `revoked: 0` is a legitimate success here too: a second
     * press, after the first revoked everything.
     */
    suspend fun revokeAllDevices(): ApiResult<DeviceRevokeResponse> =
        revokeResult(api.revokeAllDevices())

    private fun revokeResult(
        result: ApiResult<DeviceRevokeResponse>,
    ): ApiResult<DeviceRevokeResponse> = when (result) {
        is ApiResult.Success -> rejectedEnvelope(REVOKE_ENVELOPE, result.value.success)
            ?: ApiResult.Success(result.value)
        is ApiResult.Failure -> result
    }

    private companion object {
        const val LIST_ENVELOPE = "DeviceListResponse"
        const val REVOKE_ENVELOPE = "DeviceRevokeResponse"
    }
}
