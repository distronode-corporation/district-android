package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

// Everything that happens to a phone number other than buying one, and the carrier-account
// surfaces a number needs before it can send or receive anything (district-core-swift
// 7.0.0-beta.1 `NumberProvisioningResponses.swift` and `CarrierAccountResponses.swift`).
//
// ⛔ NO PURCHASE TYPE BELONGS HERE. Buying a number stays on the web on every platform (Play's
// Payments policy here, App Store 3.1.1 on iOS); `workspace/numbers/purchase` has no type, no
// endpoint and no path in this client.
//
// ⚠️ NONE OF THESE HAS A CONTRACT FIXTURE YET: the monorepo publishes none for these routes. The
// shapes are the routes' own (`distronode-website/src/app/api/district/workspace/...`), pinned by
// the request tests in :core:core-network. ⚠️ Every timestamp is an ISO-8601 string.

/** One carrier as `workspace/provider/status` reports it. */
@Serializable
data class ProviderStatusEntry(
    val connected: Boolean = false,
    /** True for an account Distronode holds; its details are not the workspace's to see. */
    val managed: Boolean? = null,
    val accountName: String? = null,
    val status: String? = null,
    /** A decimal string, as the carrier sends it. */
    val balance: String? = null,
    val currency: String? = null,
    val numberCount: Int? = null,
    val error: String? = null,
)

/**
 * `GET workspace/provider/status`.
 *
 * ⚠️ NO `success` KEY: the route answers `{connected:false, provider:null}` for a workspace with no
 * carrier, and `{connected, providers, provider}` otherwise.
 */
@Serializable
data class ProviderStatusResponse(
    val connected: Boolean = false,
    val providers: Map<String, ProviderStatusEntry>? = null,
    val provider: String? = null,
) {
    /** The connected carriers, by name. */
    val connectedProviders: List<String>
        get() = providers.orEmpty().filterValues { it.connected }.keys.sorted()

    /** The carriers whose credentials were refused, by name. */
    val refusedProviders: List<String>
        get() = providers.orEmpty().filterValues { !it.connected }.keys.sorted()
}

/** A document a regulation accepts for one requirement. */
@Serializable
data class RegulatoryAcceptedDocument(
    val name: String = "",
    val type: String = "",
    val fields: List<String> = emptyList(),
)

/** One thing a country's regulator asks for: a detail (`end_user`) or a document. */
@Serializable
data class RegulatoryRequirement(
    val kind: String = "",
    val name: String = "",
    val requirementName: String = "",
    val type: String = "",
    val fields: List<String> = emptyList(),
    val acceptedDocuments: List<RegulatoryAcceptedDocument>? = null,
)

/** The regulation of one country, number type and end-user type. */
@Serializable
data class CountryRequirements(
    val isoCountry: String = "",
    val numberType: String = "",
    val endUserType: String = "",
    val regulationSid: String = "",
    val friendlyName: String = "",
    val requirements: List<RegulatoryRequirement> = emptyList(),
)

/** `GET workspace/numbers/requirements`. [requirements] is null where no regulation applies. */
@Serializable
data class NumberRequirementsResponse(
    val success: Boolean = false,
    val country: String = "",
    val numberType: String = "",
    val purchasable: Boolean = false,
    val requirements: CountryRequirements? = null,
)

/** One document on a filing. */
@Serializable
data class RegistrationDocument(
    val id: String = "",
    val requirementName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val stored: Boolean = false,
    val submitted: Boolean = false,
    val updatedAt: String = "",
)

/**
 * One regulatory filing (a carrier "bundle").
 *
 * ⚠️ [status] is a wire value: `draft`, `pending-review`, `in-review`, `twilio-approved`,
 * `twilio-rejected`. Only `draft` may be edited or filed.
 */
@Serializable
data class NumberRegistration(
    val id: String = "",
    val isoCountry: String = "",
    val numberType: String = "",
    val endUserType: String = "",
    val friendlyName: String? = null,
    val status: String = "",
    val rejectionReasons: List<String> = emptyList(),
    val submittedAt: String? = null,
    val reviewedAt: String? = null,
    val createdAt: String = "",
    val documents: List<RegistrationDocument> = emptyList(),
)

/** `GET workspace/numbers/registrations`. */
@Serializable
data class NumberRegistrationsResponse(
    val success: Boolean = false,
    val registrations: List<NumberRegistration> = emptyList(),
    /** Countries this workspace's own filings have unlocked. */
    val approvedCountries: List<String> = emptyList(),
    /** Countries Distronode's own filings cover: purchasable today without one. */
    val platformCountries: List<String> = emptyList(),
)

/** `POST workspace/numbers/registrations`. */
@Serializable
data class NumberRegistrationCreatedResponse(
    val success: Boolean = false,
    val registration: NumberRegistration = NumberRegistration(),
)

/** A document as the upload route answers it. */
@Serializable
data class UploadedRegistrationDocument(
    val id: String = "",
    val requirementName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val createdAt: String = "",
)

/** `POST workspace/numbers/registrations/documents` (multipart). */
@Serializable
data class RegistrationDocumentResponse(
    val success: Boolean = false,
    val document: UploadedRegistrationDocument = UploadedRegistrationDocument(),
)

/** `DELETE workspace/numbers/registrations/documents`. */
@Serializable
data class RegistrationDocumentRemovalResponse(
    val success: Boolean = false,
    val documentId: String = "",
)

/** The filing as the submit route answers it. */
@Serializable
data class SubmittedRegistration(
    val id: String = "",
    val status: String = "",
    val submittedAt: String = "",
)

/** `POST workspace/numbers/registrations/submit`. */
@Serializable
data class NumberRegistrationSubmitResponse(
    val success: Boolean = false,
    val registration: SubmittedRegistration = SubmittedRegistration(),
)

/** `POST workspace/numbers/configure`: `{success:true}` and nothing else. */
@Serializable
data class NumberConfigureResponse(val success: Boolean = false)

/**
 * `POST workspace/numbers/release`.
 *
 * ⚠️ A SUCCESS MAY CARRY [warnings]: the number is released, and something around it (a routing
 * rule, a cached reference) could not be tidied. Show them; they are not a failure.
 */
@Serializable
data class NumberReleaseResponse(
    val success: Boolean = false,
    val warnings: List<String>? = null,
)

/** One SIP trunk. */
@Serializable
data class SipTrunk(
    val id: String = "",
    val name: String = "",
    val domain: String = "",
    val ipAccessControlList: List<String> = emptyList(),
    /** Null when the carrier resources were never recorded. */
    val domainSid: String? = null,
    val ipAclSid: String? = null,
    val status: String = "",
)

/** `GET workspace/sip`. */
@Serializable
data class SipTrunksResponse(
    val success: Boolean = false,
    val trunks: List<SipTrunk> = emptyList(),
)

/** `POST workspace/sip`. */
@Serializable
data class SipTrunkCreatedResponse(
    val success: Boolean = false,
    val trunk: SipTrunk = SipTrunk(),
)

/** `GET` and `POST workspace/verify`: whether the one-time passcode sender is on. */
@Serializable
data class VerifyServiceResponse(
    val success: Boolean = false,
    val enabled: Boolean = false,
    val verifyServiceSid: String? = null,
)

/**
 * `POST workspace/a2p`.
 *
 * ⛔ THE ONLY REPORT THIS FLOW WILL EVER PRODUCE: there is no status read, so a screen adopts
 * [status] rather than re-reading anything.
 */
@Serializable
data class A2PRegistrationResponse(
    val success: Boolean = false,
    val status: String = "",
    val brandSid: String = "",
    val messagingServiceSid: String = "",
    val campaignSid: String = "",
)

/** `POST workspace/tfv`. ⛔ Like [A2PRegistrationResponse], the only report there is. */
@Serializable
data class TollFreeVerificationResponse(
    val success: Boolean = false,
    val status: String = "",
    val tfvSid: String = "",
)

/** What the carrier knows about a number. */
@Serializable
data class NumberLookupInfo(
    val valid: Boolean = false,
    val phoneNumber: String = "",
    val country: String? = null,
    val type: String? = null,
    val carrier: String? = null,
)

/** `GET workspace/lookup`. ⛔ Billed per call by the carrier, including an invalid answer. */
@Serializable
data class NumberLookupResponse(
    val success: Boolean = false,
    val info: NumberLookupInfo = NumberLookupInfo(),
)
