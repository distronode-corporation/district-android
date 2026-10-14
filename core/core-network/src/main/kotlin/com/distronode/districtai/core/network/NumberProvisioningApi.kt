package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.NumberConfigureResponse
import com.distronode.districtai.core.model.NumberRegistrationCreatedResponse
import com.distronode.districtai.core.model.NumberRegistrationSubmitResponse
import com.distronode.districtai.core.model.NumberRegistrationsResponse
import com.distronode.districtai.core.model.NumberReleaseResponse
import com.distronode.districtai.core.model.NumberRequirementsResponse
import com.distronode.districtai.core.model.ProviderStatusResponse
import com.distronode.districtai.core.model.RegistrationDocumentRemovalResponse
import com.distronode.districtai.core.model.RegistrationDocumentResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything that happens to a phone number except buying it: which carriers are connected, a
 * country's regulatory filings, and configuring or releasing a number the workspace has.
 *
 * ⛔ THERE IS NO PURCHASE CALL, AND THAT STAYS A DECISION. Buying a number is a recurring carrier
 * charge sold on the web (Play's Payments policy); the marketplace hands off to the web for it. The
 * rest of a number's life is here, as on iOS.
 *
 * ⚠️ A SIBLING OF `DistrictApi` for the reason [ExtraPaths] gives; [CarrierAccountApi] holds the
 * carrier-account half (SIP, Verify, A2P, toll-free, lookup), split to stay under detekt's
 * function ceiling.
 *
 * ⛔ EVERY WRITE EXCLUDES `viewer` SERVER-SIDE.
 */
interface NumberProvisioningApi {

    /** `GET workspace/provider/status`: which carriers are connected. */
    suspend fun providerStatus(workspaceId: String): ApiResult<ProviderStatusResponse>

    /**
     * `GET workspace/numbers/requirements`: what [country]'s regulator asks for.
     *
     * ⚠️ A null [numberType] or [endUserType] is left out, so the route's own defaults apply.
     */
    suspend fun numberRequirements(
        workspaceId: String,
        country: String,
        numberType: String?,
        endUserType: String?,
    ): ApiResult<NumberRequirementsResponse>

    /** `GET workspace/numbers/registrations`: this workspace's filings. */
    suspend fun numberRegistrations(workspaceId: String): ApiResult<NumberRegistrationsResponse>

    /** `POST workspace/numbers/registrations`: start a draft filing. Idempotent in effect. */
    suspend fun createNumberRegistration(
        workspaceId: String,
        isoCountry: String,
        numberType: String?,
        endUserType: String?,
        friendlyName: String?,
    ): ApiResult<NumberRegistrationCreatedResponse>

    /**
     * `POST workspace/numbers/registrations/documents` (multipart): one document for one
     * requirement. ⚠️ The route REPLACES the document for that requirement, so it is idempotent.
     */
    suspend fun uploadRegistrationDocument(
        workspaceId: String,
        upload: RegulatoryDocumentUpload,
    ): ApiResult<RegistrationDocumentResponse>

    /** `DELETE workspace/numbers/registrations/documents`, with a JSON body. Draft filings only. */
    suspend fun deleteRegistrationDocument(
        workspaceId: String,
        bundleId: String,
        documentId: String,
    ): ApiResult<RegistrationDocumentRemovalResponse>

    /**
     * `POST workspace/numbers/registrations/submit`: file a draft with the carrier.
     *
     * ⛔ ONCE: it files a regulated application in the business's name.
     */
    suspend fun submitNumberRegistration(
        workspaceId: String,
        bundleId: String,
        endUserAttributes: Map<String, String>,
    ): ApiResult<NumberRegistrationSubmitResponse>

    /** `POST workspace/numbers/configure`: point a number's calls and messages back here. Idempotent. */
    suspend fun configureNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberConfigureResponse>

    /**
     * `POST workspace/numbers/release`.
     *
     * ⛔ IRREVERSIBLE, AND A REPEAT IS NOT EVIDENCE OF ANYTHING: after a release the ownership row
     * is gone, so a second attempt answers 403 whether or not the first one worked.
     */
    suspend fun releaseNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberReleaseResponse>
}

/** One regulatory document to upload, held in memory (the route caps a file at 10 MiB). */
class RegulatoryDocumentUpload(
    val bundleId: String,
    val requirementName: String,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** [NumberProvisioningApi] over [DistrictApiClient]. */
class HttpNumberProvisioningApi(private val client: DistrictApiClient) : NumberProvisioningApi {

    override suspend fun providerStatus(workspaceId: String): ApiResult<ProviderStatusResponse> =
        client.get(NumberPaths.PROVIDER_STATUS, ProviderStatusResponse.serializer(), workspaceQuery(workspaceId))

    override suspend fun numberRequirements(
        workspaceId: String,
        country: String,
        numberType: String?,
        endUserType: String?,
    ): ApiResult<NumberRequirementsResponse> =
        client.get(
            segments = NumberPaths.REQUIREMENTS,
            serializer = NumberRequirementsResponse.serializer(),
            query = mapOf(
                "workspaceId" to workspaceId,
                "country" to country,
                "type" to numberType,
                "endUserType" to endUserType,
            ),
        )

    override suspend fun numberRegistrations(workspaceId: String): ApiResult<NumberRegistrationsResponse> =
        client.get(NumberPaths.REGISTRATIONS, NumberRegistrationsResponse.serializer(), workspaceQuery(workspaceId))

    /** ⚠️ THE WORKSPACE IS IN THE QUERY on every registration route, not the body. */
    override suspend fun createNumberRegistration(
        workspaceId: String,
        isoCountry: String,
        numberType: String?,
        endUserType: String?,
        friendlyName: String?,
    ): ApiResult<NumberRegistrationCreatedResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.REGISTRATIONS,
            serializer = NumberRegistrationCreatedResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = jsonObjectOf(
                "isoCountry" to JsonPrimitive(isoCountry),
                "numberType" to numberType?.let(::JsonPrimitive),
                "endUserType" to endUserType?.let(::JsonPrimitive),
                "friendlyName" to friendlyName?.let(::JsonPrimitive),
            ),
        )

    /** ⛔ TWO FORM FIELDS, AND NEITHER IS THE WORKSPACE: it travels in the query. */
    override suspend fun uploadRegistrationDocument(
        workspaceId: String,
        upload: RegulatoryDocumentUpload,
    ): ApiResult<RegistrationDocumentResponse> =
        client.sendMultipart(
            segments = NumberPaths.REGISTRATION_DOCUMENTS,
            serializer = RegistrationDocumentResponse.serializer(),
            fields = mapOf("bundleId" to upload.bundleId, "requirementName" to upload.requirementName),
            fileName = upload.fileName,
            contentType = upload.mimeType,
            bytes = upload.bytes,
            query = workspaceQuery(workspaceId),
        )

    override suspend fun deleteRegistrationDocument(
        workspaceId: String,
        bundleId: String,
        documentId: String,
    ): ApiResult<RegistrationDocumentRemovalResponse> =
        client.send(
            method = "DELETE",
            segments = NumberPaths.REGISTRATION_DOCUMENTS,
            serializer = RegistrationDocumentRemovalResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = jsonObjectOf("bundleId" to JsonPrimitive(bundleId), "documentId" to JsonPrimitive(documentId)),
        )

    /** ⚠️ `endUserAttributes` is sent even when empty, so a captured request says so. */
    override suspend fun submitNumberRegistration(
        workspaceId: String,
        bundleId: String,
        endUserAttributes: Map<String, String>,
    ): ApiResult<NumberRegistrationSubmitResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.REGISTRATION_SUBMIT,
            serializer = NumberRegistrationSubmitResponse.serializer(),
            query = workspaceQuery(workspaceId),
            body = jsonObjectOf(
                "bundleId" to JsonPrimitive(bundleId),
                "endUserAttributes" to JsonObject(endUserAttributes.mapValues { JsonPrimitive(it.value) }),
            ),
        )

    /**
     * ⚠️ THE WORKSPACE IS IN THE BODY HERE, unlike the registration routes: the route reads the body
     * before its role guard, and answers 400 without it.
     */
    override suspend fun configureNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberConfigureResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.CONFIGURE,
            serializer = NumberConfigureResponse.serializer(),
            body = numberBody(workspaceId, phoneNumber),
        )

    override suspend fun releaseNumber(workspaceId: String, phoneNumber: String): ApiResult<NumberReleaseResponse> =
        client.send(
            method = "POST",
            segments = NumberPaths.RELEASE,
            serializer = NumberReleaseResponse.serializer(),
            body = numberBody(workspaceId, phoneNumber),
        )

    private fun numberBody(workspaceId: String, phoneNumber: String): JsonObject =
        jsonObjectOf("workspaceId" to JsonPrimitive(workspaceId), "phoneNumber" to JsonPrimitive(phoneNumber))
}

/** The number-provisioning and carrier-account paths. */
internal object NumberPaths {
    private val WORKSPACE: List<String> = ApiRoots.DISTRICT + "workspace"
    private val NUMBERS: List<String> = WORKSPACE + "numbers"

    val PROVIDER_STATUS: List<String> = WORKSPACE + "provider" + "status"
    val REQUIREMENTS: List<String> = NUMBERS + "requirements"
    val REGISTRATIONS: List<String> = NUMBERS + "registrations"
    val REGISTRATION_DOCUMENTS: List<String> = REGISTRATIONS + "documents"
    val REGISTRATION_SUBMIT: List<String> = REGISTRATIONS + "submit"
    val CONFIGURE: List<String> = NUMBERS + "configure"
    val RELEASE: List<String> = NUMBERS + "release"
    val A2P: List<String> = WORKSPACE + "a2p"
    val TFV: List<String> = WORKSPACE + "tfv"
    val SIP: List<String> = WORKSPACE + "sip"
    val VERIFY: List<String> = WORKSPACE + "verify"
    val LOOKUP: List<String> = WORKSPACE + "lookup"
}
