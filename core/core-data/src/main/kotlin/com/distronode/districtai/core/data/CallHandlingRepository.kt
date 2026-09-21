package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.AvailabilityReason
import com.distronode.districtai.core.model.AvailabilityResponse
import com.distronode.districtai.core.model.CallHandling
import com.distronode.districtai.core.model.CallHandlingResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CallHandlingApi

/**
 * Who answers a call, and whether the person asking can be rung at all.
 *
 * ⛔ TWO SCOPES BEHIND ONE SCREEN, AND THE DIFFERENCE IS NOT COSMETIC. [callHandling] is a
 * WORKSPACE setting — every member sees the same value and a mutator changes it for all of them.
 * [availability] is a fact about the CALLER'S OWN membership row, and neither method here takes an
 * identity because neither route does.
 *
 * ⛔ BOTH SAVES ADOPT THEIR OWN RESPONSE RATHER THAN RE-READING, which is the opposite of
 * [WorkspaceConfigRepository]. Each PATCH echoes the values it wrote, through the same normaliser
 * the read uses, so a second request would only confirm what is already in hand. ⚠️ That is a
 * property of these two routes and must not be generalised: every other workspace-settings write
 * answers a bare `{success:true}`.
 */
class CallHandlingRepository(private val api: CallHandlingApi) {

    /**
     * ⚠️ THE VALUES COME BACK NORMALISED and must not be clamped again here. An unrecognised stored
     * mode reads as [CallHandling.DEFAULT] and an out-of-range ring is already inside the bounds; a
     * second opinion would only be able to disagree.
     */
    suspend fun callHandling(workspaceId: String): ApiResult<CallHandlingResponse> =
        when (val result = api.callHandling(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(CALL_HANDLING_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ REFUSED LOCALLY WHEN NEITHER FIELD IS PRESENT, rather than sent and 400ed. The route
     * answers "Nothing to update" for a body carrying only the workspace, and spending a request
     * and a rate-limit token to be told that is a worse answer than declining to make it.
     *
     * ⛔ THE MODE IS CHECKED AGAINST [CallHandling.MODES] AND THE RING IS **NOT** CLAMPED. Those
     * are deliberately different: an unknown mode can only have come from this client's own code,
     * while a ring outside the bounds is a slider the screen should have constrained — and
     * silently clamping it would save a number the operator did not choose. Both are a 400
     * server-side; this makes the first one impossible and lets the second one be reported.
     */
    suspend fun saveCallHandling(
        workspaceId: String,
        callHandling: String? = null,
        appRingSeconds: Int? = null,
    ): ApiResult<CallHandlingResponse> {
        if (callHandling == null && appRingSeconds == null) {
            return refused("saveCallHandling was given nothing to update", "CallHandlingPatch{empty}")
        }
        if (callHandling != null && !CallHandling.isKnown(callHandling)) {
            return refused(
                "saveCallHandling was given a mode the server does not accept",
                "CallHandlingPatch{callHandling=unknown}",
            )
        }
        return when (val result = api.saveCallHandling(workspaceId, callHandling, appRingSeconds)) {
            is ApiResult.Success ->
                rejectedEnvelope(CALL_HANDLING_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }
    }

    /**
     * ⚠️ A 200 HERE DOES NOT MEAN THE VALUE IS TOGGLEABLE. A viewer is answered `false` with
     * [AvailabilityReason.ROLE] and no database read, and a caller with no membership row is
     * answered `false` with [AvailabilityReason.NO_MEMBER_ROW]. Both are real answers rather than
     * refusals, so the reason is carried to the screen instead of being flattened into a boolean.
     */
    suspend fun availability(workspaceId: String): ApiResult<AvailabilityResponse> =
        when (val result = api.availability(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(AVAILABILITY_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * ⛔ THERE IS NO IDENTITY PARAMETER AND THERE MUST NEVER BE ONE. The route writes the caller's
     * own row, located from the session; a `userId` here would be an identity the caller chose.
     *
     * ⚠️ A **409** IS THE "no membership row" ANSWER rather than a fault, and it arrives as
     * `ApiResult.HttpFailure(409)` with the server's own sentence. It is left as a failure
     * deliberately: the toggle did not take effect, and reporting it as success because the reason
     * is understandable would leave a switch showing a state the ring fan-out does not share.
     */
    suspend fun saveAvailability(
        workspaceId: String,
        availableForCalls: Boolean,
    ): ApiResult<AvailabilityResponse> =
        when (val result = api.saveAvailability(workspaceId, availableForCalls)) {
            is ApiResult.Success ->
                rejectedEnvelope(AVAILABILITY_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    private fun refused(message: String, preview: String): ApiResult.DecodeFailure =
        ApiResult.DecodeFailure(cause = IllegalStateException(message), bodyPreview = preview)

    private companion object {
        const val CALL_HANDLING_ENVELOPE = "CallHandlingResponse"
        const val AVAILABILITY_ENVELOPE = "AvailabilityResponse"
    }
}
