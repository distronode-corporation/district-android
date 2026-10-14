package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.A2PRegistrationResponse
import com.distronode.districtai.core.model.NumberLookupResponse
import com.distronode.districtai.core.model.SipTrunkCreatedResponse
import com.distronode.districtai.core.model.SipTrunksResponse
import com.distronode.districtai.core.model.TollFreeVerificationResponse
import com.distronode.districtai.core.model.VerifyServiceResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The carrier-account surfaces a number needs before it can send or receive anything: SIP trunks,
 * the one-time passcode (Verify) sender, A2P 10DLC, toll-free verification, and the billed lookup.
 *
 * ⛔ MOST WRITES HERE COST SOMETHING THAT CANNOT BE TAKEN BACK, and none is retried: a SIP trunk
 * adds a monthly charge, an A2P registration pays a brand fee, a toll-free verification enters a
 * manual review, enabling Verify mints a billable sender, and every lookup is billed.
 *
 * ⛔ EVERY WRITE EXCLUDES `viewer` SERVER-SIDE.
 */
interface CarrierAccountApi {

    /** `GET workspace/sip`. */
    suspend fun sipTrunks(workspaceId: String): ApiResult<SipTrunksResponse>

    /** `POST workspace/sip`. ⛔ Adds a monthly charge, and cannot be removed in the app. */
    suspend fun createSipTrunk(
        workspaceId: String,
        name: String,
        domain: String,
        ipAccessControlList: List<String>,
    ): ApiResult<SipTrunkCreatedResponse>

    /** `GET workspace/verify`. */
    suspend fun verifyService(workspaceId: String): ApiResult<VerifyServiceResponse>

    /** `POST workspace/verify`. ⚠️ [enabled] goes out as a JSON boolean; a string is a 400. */
    suspend fun setVerifyServiceEnabled(workspaceId: String, enabled: Boolean): ApiResult<VerifyServiceResponse>

    /** `POST workspace/a2p`. ⛔ Once: a carrier brand fee, and no status read anywhere. */
    suspend fun submitA2PRegistration(
        workspaceId: String,
        registration: A2PRegistrationRequest,
    ): ApiResult<A2PRegistrationResponse>

    /** `POST workspace/tfv`. ⛔ Once: a manual review queue, and no status read anywhere. */
    suspend fun submitTollFreeVerification(
        workspaceId: String,
        verification: TollFreeVerificationRequest,
    ): ApiResult<TollFreeVerificationResponse>

    /** `GET workspace/lookup`. ⛔ Billed by the carrier for every call, an invalid answer included. */
    suspend fun lookupNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberLookupResponse>
}

/**
 * An A2P 10DLC registration.
 *
 * ⛔ A TYPE, NOT EIGHT STRING ARGUMENTS: at that shape a transposition at a call site compiles and
 * files a website where the business name belongs.
 */
data class A2PRegistrationRequest(
    val businessName: String,
    val campaignType: String,
    val campaignDescription: String,
    val sampleMessage1: String,
    val sampleMessage2: String,
    val ein: String? = null,
    val website: String? = null,
    val vertical: String? = null,
)

/** A toll-free verification. See [A2PRegistrationRequest] for why it is a type. */
data class TollFreeVerificationRequest(
    val phoneNumber: String,
    val businessName: String,
    val useCase: String,
    val optInImageUrls: List<String>,
    val website: String? = null,
    val messageContent: String? = null,
    val optInFlow: String? = null,
)

/** [CarrierAccountApi] over [DistrictApiClient]. ⚠️ Every write here takes the workspace in the body. */
class HttpCarrierAccountApi(private val client: DistrictApiClient) : CarrierAccountApi {

    override suspend fun sipTrunks(workspaceId: String): ApiResult<SipTrunksResponse> =
        client.get(NumberPaths.SIP, SipTrunksResponse.serializer(), workspaceQuery(workspaceId))

    override suspend fun createSipTrunk(
        workspaceId: String,
        name: String,
        domain: String,
        ipAccessControlList: List<String>,
    ): ApiResult<SipTrunkCreatedResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.SIP,
            serializer = SipTrunkCreatedResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "name" to JsonPrimitive(name),
                "domain" to JsonPrimitive(domain),
                "ipAccessControlList" to JsonArray(ipAccessControlList.map(::JsonPrimitive)),
            ),
        )

    override suspend fun verifyService(workspaceId: String): ApiResult<VerifyServiceResponse> =
        client.get(NumberPaths.VERIFY, VerifyServiceResponse.serializer(), workspaceQuery(workspaceId))

    override suspend fun setVerifyServiceEnabled(workspaceId: String, enabled: Boolean): ApiResult<VerifyServiceResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.VERIFY,
            serializer = VerifyServiceResponse.serializer(),
            body = jsonObjectOf("workspaceId" to JsonPrimitive(workspaceId), "enabled" to JsonPrimitive(enabled)),
        )

    override suspend fun submitA2PRegistration(
        workspaceId: String,
        registration: A2PRegistrationRequest,
    ): ApiResult<A2PRegistrationResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.A2P,
            serializer = A2PRegistrationResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "businessName" to JsonPrimitive(registration.businessName),
                "ein" to registration.ein?.let(::JsonPrimitive),
                "website" to registration.website?.let(::JsonPrimitive),
                "vertical" to registration.vertical?.let(::JsonPrimitive),
                "campaignType" to JsonPrimitive(registration.campaignType),
                "campaignDescription" to JsonPrimitive(registration.campaignDescription),
                "sampleMessage1" to JsonPrimitive(registration.sampleMessage1),
                "sampleMessage2" to JsonPrimitive(registration.sampleMessage2),
            ),
        )

    /**
     * ⚠️ `optInImageUrls` IS ALWAYS SENT, EVEN EMPTY, so a refusal is the route's own sentence about
     * the opt-in evidence rather than a generic missing-field 400.
     */
    override suspend fun submitTollFreeVerification(
        workspaceId: String,
        verification: TollFreeVerificationRequest,
    ): ApiResult<TollFreeVerificationResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.TFV,
            serializer = TollFreeVerificationResponse.serializer(),
            body = jsonObjectOf(
                "workspaceId" to JsonPrimitive(workspaceId),
                "phoneNumber" to JsonPrimitive(verification.phoneNumber),
                "businessName" to JsonPrimitive(verification.businessName),
                "website" to verification.website?.let(::JsonPrimitive),
                "useCase" to JsonPrimitive(verification.useCase),
                "messageContent" to verification.messageContent?.let(::JsonPrimitive),
                "optInFlow" to verification.optInFlow?.let(::JsonPrimitive),
                "optInImageUrls" to JsonArray(verification.optInImageUrls.map(::JsonPrimitive)),
            ),
        )

    override suspend fun lookupNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberLookupResponse> =
        client.get(
            segments = NumberPaths.LOOKUP,
            serializer = NumberLookupResponse.serializer(),
            query = mapOf("workspaceId" to workspaceId, "phoneNumber" to phoneNumber),
        )
}
