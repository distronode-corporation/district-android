package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.A2PRegistrationResponse
import com.distronode.districtai.core.model.NumberConfigureResponse
import com.distronode.districtai.core.model.NumberLookupInfo
import com.distronode.districtai.core.model.NumberLookupResponse
import com.distronode.districtai.core.model.NumberRegistration
import com.distronode.districtai.core.model.NumberRegistrationCreatedResponse
import com.distronode.districtai.core.model.NumberRegistrationSubmitResponse
import com.distronode.districtai.core.model.NumberRegistrationsResponse
import com.distronode.districtai.core.model.NumberReleaseResponse
import com.distronode.districtai.core.model.NumberRequirementsResponse
import com.distronode.districtai.core.model.ProviderStatusResponse
import com.distronode.districtai.core.model.RegistrationDocumentRemovalResponse
import com.distronode.districtai.core.model.RegistrationDocumentResponse
import com.distronode.districtai.core.model.SipTrunk
import com.distronode.districtai.core.model.SipTrunkCreatedResponse
import com.distronode.districtai.core.model.SipTrunksResponse
import com.distronode.districtai.core.model.SubmittedRegistration
import com.distronode.districtai.core.model.TollFreeVerificationResponse
import com.distronode.districtai.core.model.VerifyServiceResponse
import com.distronode.districtai.core.network.A2PRegistrationRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CarrierAccountApi
import com.distronode.districtai.core.network.NumberProvisioningApi
import com.distronode.districtai.core.network.RegulatoryDocumentUpload
import com.distronode.districtai.core.network.TollFreeVerificationRequest
import kotlinx.coroutines.CompletableDeferred

/**
 * A [NumberProvisioningApi] that answers as configured and records every call by name.
 *
 * ⚠️ Every answer defaults to a plain success, so a test sets only what it is about.
 */
class FakeNumberProvisioningApi : NumberProvisioningApi {
    /** Every call, as `name:arguments`, in order. */
    val calls: MutableList<String> = mutableListOf()

    var providerStatusResult: ApiResult<ProviderStatusResponse> = ApiResult.Success(ProviderStatusResponse())
    var requirementsResult: ApiResult<NumberRequirementsResponse> =
        ApiResult.Success(NumberRequirementsResponse(success = true, country = "CA", purchasable = true))
    var registrationsResult: ApiResult<NumberRegistrationsResponse> =
        ApiResult.Success(NumberRegistrationsResponse(success = true))
    var createResult: ApiResult<NumberRegistrationCreatedResponse> =
        ApiResult.Success(
            NumberRegistrationCreatedResponse(success = true, registration = NumberRegistration(id = "BU-new"))
        )
    var uploadResult: ApiResult<RegistrationDocumentResponse> = ApiResult.Success(
        RegistrationDocumentResponse(success = true)
    )
    var removeResult: ApiResult<RegistrationDocumentRemovalResponse> =
        ApiResult.Success(RegistrationDocumentRemovalResponse(success = true))
    var submitResult: ApiResult<NumberRegistrationSubmitResponse> =
        ApiResult.Success(
            NumberRegistrationSubmitResponse(
                success = true,
                registration = SubmittedRegistration(status = "pending-review")
            )
        )
    var configureResult: ApiResult<NumberConfigureResponse> = ApiResult.Success(NumberConfigureResponse(success = true))
    var releaseResult: ApiResult<NumberReleaseResponse> = ApiResult.Success(NumberReleaseResponse(success = true))

    /** When set, a release waits for it: a write still in flight. */
    var releaseGate: CompletableDeferred<Unit>? = null

    override suspend fun providerStatus(workspaceId: String) = providerStatusResult.also {
        calls += "providerStatus:$workspaceId"
    }

    override suspend fun numberRequirements(workspaceId: String, country: String, numberType: String?, endUserType: String?) =
        requirementsResult.also { calls += "numberRequirements:$workspaceId:$country:$numberType:$endUserType" }

    override suspend fun numberRegistrations(workspaceId: String) =
        registrationsResult.also { calls += "numberRegistrations:$workspaceId" }

    override suspend fun createNumberRegistration(
        workspaceId: String,
        isoCountry: String,
        numberType: String?,
        endUserType: String?,
        friendlyName: String?,
    ) = createResult.also {
        calls += "createNumberRegistration:$workspaceId:$isoCountry:$numberType:$endUserType:$friendlyName"
    }

    override suspend fun uploadRegistrationDocument(workspaceId: String, upload: RegulatoryDocumentUpload) =
        uploadResult.also { calls += "uploadRegistrationDocument:$workspaceId:${upload.bundleId}" }

    override suspend fun deleteRegistrationDocument(workspaceId: String, bundleId: String, documentId: String) =
        removeResult.also { calls += "deleteRegistrationDocument:$workspaceId:$bundleId:$documentId" }

    override suspend fun submitNumberRegistration(workspaceId: String, bundleId: String, endUserAttributes: Map<String, String>) =
        submitResult.also { calls += "submitNumberRegistration:$workspaceId:$bundleId:$endUserAttributes" }

    override suspend fun configureNumber(workspaceId: String, phoneNumber: String) =
        configureResult.also { calls += "configureNumber:$workspaceId:$phoneNumber" }

    override suspend fun releaseNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberReleaseResponse> {
        calls += "releaseNumber:$workspaceId:$phoneNumber"
        releaseGate?.await()
        return releaseResult
    }
}

/** A [CarrierAccountApi] that answers as configured and records every call by name. */
class FakeCarrierAccountApi : CarrierAccountApi {
    val calls: MutableList<String> = mutableListOf()
    val a2pRequests: MutableList<A2PRegistrationRequest> = mutableListOf()
    val tollFreeRequests: MutableList<TollFreeVerificationRequest> = mutableListOf()

    var sipResult: ApiResult<SipTrunksResponse> = ApiResult.Success(SipTrunksResponse(success = true))
    var createSipResult: ApiResult<SipTrunkCreatedResponse> =
        ApiResult.Success(SipTrunkCreatedResponse(success = true, trunk = SipTrunk(id = "T-new")))
    var verifyResult: ApiResult<VerifyServiceResponse> = ApiResult.Success(VerifyServiceResponse(success = true))
    var setVerifyResult: ApiResult<VerifyServiceResponse>? = null
    var a2pResult: ApiResult<A2PRegistrationResponse> =
        ApiResult.Success(A2PRegistrationResponse(success = true, status = "PENDING"))
    var tollFreeResult: ApiResult<TollFreeVerificationResponse> =
        ApiResult.Success(TollFreeVerificationResponse(success = true, status = "PENDING_REVIEW"))
    var lookupResult: ApiResult<NumberLookupResponse> =
        ApiResult.Success(
            NumberLookupResponse(success = true, info = NumberLookupInfo(valid = true, phoneNumber = "+14165550142"))
        )

    override suspend fun sipTrunks(workspaceId: String) = sipResult.also { calls += "sipTrunks:$workspaceId" }

    override suspend fun createSipTrunk(workspaceId: String, name: String, domain: String, ipAccessControlList: List<String>) =
        createSipResult.also { calls += "createSipTrunk:$workspaceId:$name:$domain:$ipAccessControlList" }

    override suspend fun verifyService(workspaceId: String) = verifyResult.also { calls += "verifyService:$workspaceId" }

    /** Answers [setVerifyResult], or by default the state asked for. */
    override suspend fun setVerifyServiceEnabled(
        workspaceId: String,
        enabled: Boolean
    ): ApiResult<VerifyServiceResponse> {
        calls += "setVerifyServiceEnabled:$workspaceId:$enabled"
        return setVerifyResult ?: ApiResult.Success(VerifyServiceResponse(success = true, enabled = enabled))
    }

    override suspend fun submitA2PRegistration(workspaceId: String, registration: A2PRegistrationRequest) =
        a2pResult.also {
            calls += "submitA2PRegistration:$workspaceId"
            a2pRequests += registration
        }

    override suspend fun submitTollFreeVerification(workspaceId: String, verification: TollFreeVerificationRequest) =
        tollFreeResult.also {
            calls += "submitTollFreeVerification:$workspaceId"
            tollFreeRequests += verification
        }

    override suspend fun lookupNumber(workspaceId: String, phoneNumber: String) =
        lookupResult.also { calls += "lookupNumber:$workspaceId:$phoneNumber" }
}
