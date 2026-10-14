package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.A2PRegistrationResponse
import com.distronode.districtai.core.model.NumberConfigureResponse
import com.distronode.districtai.core.model.NumberLookupResponse
import com.distronode.districtai.core.model.NumberRegistrationCreatedResponse
import com.distronode.districtai.core.model.NumberRegistrationSubmitResponse
import com.distronode.districtai.core.model.NumberRegistrationsResponse
import com.distronode.districtai.core.model.NumberReleaseResponse
import com.distronode.districtai.core.model.NumberRequirementsResponse
import com.distronode.districtai.core.model.ProviderStatusResponse
import com.distronode.districtai.core.model.RegistrationDocumentRemovalResponse
import com.distronode.districtai.core.model.SipTrunkCreatedResponse
import com.distronode.districtai.core.model.SipTrunksResponse
import com.distronode.districtai.core.model.TollFreeVerificationResponse
import com.distronode.districtai.core.model.VerifyServiceResponse
import com.distronode.districtai.core.network.A2PRegistrationRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.CarrierAccountApi
import com.distronode.districtai.core.network.NumberProvisioningApi
import com.distronode.districtai.core.network.TollFreeVerificationRequest

/**
 * Whether sending one of the number writes a second time is the same write a second time
 * (district-core-swift `NumberWriteRepeat`).
 *
 * ⚠️ IT IS NOT "IS THIS SCARY": removing a document sounds consequential and is [IDEMPOTENT];
 * releasing a number sounds like tidying up and is [ONCE].
 */
enum class NumberWriteRepeat {
    /** Safe to send again: the server converges on the same state. */
    IDEMPOTENT,

    /** ⛔ A repeat costs something that cannot be taken back (a fee, a filing, a release). */
    ONCE,
}

/**
 * Whether a failed write may be offered again from the control it failed in.
 *
 * ⛔ THE CONSERVATIVE RULE: A REPEAT IS OFFERED ONLY WHEN THE FAILURE PROVES THE WRITE DID NOT
 * HAPPEN. A 4xx is the server refusing before it did any work; a 5xx can come after the carrier
 * call landed; no answer at all is the lost-response case; and a decode failure comes from a 2xx,
 * so the write certainly happened.
 */
object NumberWriteResubmit {
    private const val CLIENT_ERROR_FLOOR = 400
    private const val SERVER_ERROR_FLOOR = 500

    /** True when the control may be armed again after [failure]. */
    fun allowed(failure: ApiResult.Failure, repeat: NumberWriteRepeat): Boolean {
        if (repeat == NumberWriteRepeat.IDEMPOTENT) return true
        return when (failure) {
            is ApiResult.HttpFailure -> failure.status in CLIENT_ERROR_FLOOR until SERVER_ERROR_FLOOR
            is ApiResult.Unauthorized,
            is ApiResult.Forbidden,
            is ApiResult.NotFound,
            is ApiResult.RateLimited,
            -> true
            // ⚠️ A 503 from the regional databases is a 5xx: it may have happened after work began.
            is ApiResult.RegionsDegraded, is ApiResult.NetworkFailure, is ApiResult.DecodeFailure -> false
        }
    }
}

/**
 * Configuring, releasing, the regulatory filings and the carrier-account paperwork of the
 * workspace's numbers.
 *
 * ⛔ EVERY ANSWER THAT CARRIES `success` MUST AFFIRM IT: a 2xx that says `success:false` is drift,
 * never a quiet success. Provider status alone has no such key.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED: one method per route, as on `DistrictApi`'s sections. The
 * two interfaces behind it are already split; splitting the repository too would split one screen's
 * data layer in two for a lint count.
 */
@Suppress("TooManyFunctions")
class NumberProvisioningRepository(
    private val numbers: NumberProvisioningApi,
    private val carrier: CarrierAccountApi,
) {
    suspend fun providerStatus(workspaceId: String): ApiResult<ProviderStatusResponse> =
        numbers.providerStatus(workspaceId)

    suspend fun requirements(
        workspaceId: String,
        country: String,
        numberType: String? = null,
    ): ApiResult<NumberRequirementsResponse> =
        numbers.numberRequirements(workspaceId, country, numberType, endUserType = null)
            .affirm("NumberRequirementsResponse") { it.success }

    suspend fun registrations(workspaceId: String): ApiResult<NumberRegistrationsResponse> =
        numbers.numberRegistrations(workspaceId).affirm("NumberRegistrationsResponse") { it.success }

    /** [NumberWriteRepeat.IDEMPOTENT]. */
    suspend fun createRegistration(
        workspaceId: String,
        isoCountry: String,
        numberType: String?,
        friendlyName: String?,
    ): ApiResult<NumberRegistrationCreatedResponse> =
        numbers.createNumberRegistration(workspaceId, isoCountry, numberType, endUserType = null, friendlyName)
            .affirm("NumberRegistrationCreatedResponse") { it.success }

    /** [NumberWriteRepeat.IDEMPOTENT]. */
    suspend fun removeDocument(
        workspaceId: String,
        bundleId: String,
        documentId: String,
    ): ApiResult<RegistrationDocumentRemovalResponse> =
        numbers.deleteRegistrationDocument(workspaceId, bundleId, documentId)
            .affirm("RegistrationDocumentRemovalResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: a regulated filing. */
    suspend fun submitRegistration(
        workspaceId: String,
        bundleId: String,
        endUserAttributes: Map<String, String>,
    ): ApiResult<NumberRegistrationSubmitResponse> =
        numbers.submitNumberRegistration(workspaceId, bundleId, endUserAttributes)
            .affirm("NumberRegistrationSubmitResponse") { it.success }

    /** [NumberWriteRepeat.IDEMPOTENT]. */
    suspend fun configure(workspaceId: String, phoneNumber: String): ApiResult<NumberConfigureResponse> =
        numbers.configureNumber(workspaceId, phoneNumber).affirm("NumberConfigureResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: irreversible. */
    suspend fun release(workspaceId: String, phoneNumber: String): ApiResult<NumberReleaseResponse> =
        numbers.releaseNumber(workspaceId, phoneNumber).affirm("NumberReleaseResponse") { it.success }

    suspend fun sipTrunks(workspaceId: String): ApiResult<SipTrunksResponse> =
        carrier.sipTrunks(workspaceId).affirm("SipTrunksResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: a monthly charge. */
    suspend fun createSipTrunk(
        workspaceId: String,
        name: String,
        domain: String,
        addresses: List<String>,
    ): ApiResult<SipTrunkCreatedResponse> =
        carrier.createSipTrunk(workspaceId, name, domain, addresses).affirm("SipTrunkCreatedResponse") { it.success }

    suspend fun verifyService(workspaceId: String): ApiResult<VerifyServiceResponse> =
        carrier.verifyService(workspaceId).affirm("VerifyServiceResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: enabling mints a billable sender. */
    suspend fun setVerifyEnabled(workspaceId: String, enabled: Boolean): ApiResult<VerifyServiceResponse> =
        carrier.setVerifyServiceEnabled(workspaceId, enabled).affirm("VerifyServiceResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: a brand fee. */
    suspend fun submitA2P(workspaceId: String, request: A2PRegistrationRequest): ApiResult<A2PRegistrationResponse> =
        carrier.submitA2PRegistration(workspaceId, request).affirm("A2PRegistrationResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: a manual review. */
    suspend fun submitTollFree(
        workspaceId: String,
        request: TollFreeVerificationRequest,
    ): ApiResult<TollFreeVerificationResponse> =
        carrier.submitTollFreeVerification(workspaceId, request).affirm("TollFreeVerificationResponse") { it.success }

    /** [NumberWriteRepeat.ONCE]: billed per call. */
    suspend fun lookup(workspaceId: String, phoneNumber: String): ApiResult<NumberLookupResponse> =
        carrier.lookupNumber(workspaceId, phoneNumber).affirm("NumberLookupResponse") { it.success }

    private inline fun <T> ApiResult<T>.affirm(envelope: String, success: (T) -> Boolean): ApiResult<T> =
        when (this) {
            is ApiResult.Success -> rejectedEnvelope(envelope, success(value)) ?: this
            is ApiResult.Failure -> this
        }
}
