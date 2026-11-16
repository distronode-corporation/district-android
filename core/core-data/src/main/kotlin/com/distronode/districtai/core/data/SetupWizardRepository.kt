package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ConnectionsResponse
import com.distronode.districtai.core.model.DistrictSetupResponse
import com.distronode.districtai.core.model.ForwardingCheckResponse
import com.distronode.districtai.core.model.SetupLookupResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SetupWizardApi
import kotlinx.serialization.json.JsonObject

/**
 * The setup wizard, for the workspace's owner: its state, the step saves, the website lookup, the
 * test call and the forwarding check.
 *
 * ⛔ OWNER ONLY. A member or an admin gets 403 on every route under `setup` and a workspace from before
 * the wizard gets 409 `setup_not_available`; both reach the caller with the service's code, unlike
 * [SetupRepository.needsWebSetup], where every failure is "no". Buying a number, choosing a plan and
 * connecting a calendar are not here: they charge a card or need a browser, and stay on the web.
 */
class SetupWizardRepository(private val api: SetupWizardApi) {

    suspend fun load(workspaceId: String): ApiResult<DistrictSetupResponse> = api.districtSetup(workspaceId)

    /**
     * Saves step states and business-facts sections, and answers the state after the write.
     *
     * @param steps step wire name to state; see [SetupWizardApi.districtSetupPatch].
     */
    suspend fun save(
        workspaceId: String,
        steps: Map<String, String> = emptyMap(),
        businessFacts: JsonObject? = null,
    ): ApiResult<DistrictSetupResponse> = api.districtSetupPatch(workspaceId, steps, businessFacts)

    suspend fun lookUp(workspaceId: String, websiteUrl: String, locale: String): ApiResult<SetupLookupResponse> =
        api.setupLookup(workspaceId, websiteUrl, locale)

    /**
     * Has the receptionist call the owner's phone, and answers the call's id.
     *
     * ⛔ THE ENVELOPE IS CHECKED, and an empty id is drift too: a `{}` body decodes into a call that
     * was never placed, and the step would tell the owner to answer a phone that is not ringing.
     */
    suspend fun placeTestCall(workspaceId: String, phone: String): ApiResult<String> =
        when (val result = api.setupTestCall(workspaceId, phone)) {
            is ApiResult.Success ->
                rejectedEnvelope(TEST_CALL_ENVELOPE, result.value.success)
                    ?: emptyCallId(result.value.callId)
                    ?: ApiResult.Success(result.value.callId)
            is ApiResult.Failure -> result
        }

    /** ⚠️ ITS OWN DIAGNOSTIC: a body that affirms success with no call id is not "success=false". */
    private fun emptyCallId(callId: String): ApiResult.DecodeFailure? =
        if (callId.isNotEmpty()) {
            null
        } else {
            ApiResult.DecodeFailure(
                cause = IllegalStateException("$TEST_CALL_ENVELOPE affirmed success with an empty callId"),
                bodyPreview = "$TEST_CALL_ENVELOPE{success=true, callId=\"\"}",
            )
        }

    suspend fun forwardingCheck(workspaceId: String): ApiResult<ForwardingCheckResponse> =
        api.forwardingCheck(workspaceId)

    /** Starts a check of the business line's forwarding. Poll [forwardingCheck] while it is pending. */
    suspend fun startForwardingCheck(
        workspaceId: String,
        businessLine: String,
        mode: String,
    ): ApiResult<ForwardingCheckResponse> = api.forwardingCheckStart(workspaceId, businessLine, mode)

    suspend fun connections(workspaceId: String): ApiResult<ConnectionsResponse> =
        api.workspaceConnections(workspaceId)

    private companion object {
        const val TEST_CALL_ENVELOPE = "SetupTestCallResponse"
    }
}
