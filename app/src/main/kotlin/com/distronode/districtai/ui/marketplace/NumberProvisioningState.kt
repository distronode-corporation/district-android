package com.distronode.districtai.ui.marketplace

import com.distronode.districtai.core.model.CountryRequirements
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.NumberLookupInfo
import com.distronode.districtai.core.model.NumberRegistration
import com.distronode.districtai.core.model.ProviderStatusEntry
import com.distronode.districtai.core.model.SipTrunk
import com.distronode.districtai.core.network.A2PRegistrationRequest
import com.distronode.districtai.core.network.TollFreeVerificationRequest
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText

/**
 * Where one family of number writes stands (iOS `NumberWriteState`).
 *
 * ⛔ A FAILURE CARRIES WHETHER THE CONTROL MAY BE PRESSED AGAIN: a write that may have gone through
 * (see `NumberWriteResubmit`) is not offered again, and the notice says so.
 */
sealed interface NumberWriteState {
    data object Idle : NumberWriteState
    data object Running : NumberWriteState

    /**
     * Done, with the sentence that says so, and any [details] the server added (a release's
     * warnings), shown after it as they were sent.
     */
    data class Done(val notice: UiText, val details: List<String> = emptyList()) : NumberWriteState
    data class Failed(val failure: FailureText, val resubmitAllowed: Boolean) : NumberWriteState

    /** Whether the control that starts this write may be pressed. */
    val isArmed: Boolean
        get() = when (this) {
            Idle, is Done -> true
            Running -> false
            is Failed -> resubmitAllowed
        }

    /** A failure that may have gone through: the control stays disarmed until a fresh read. */
    val refusedRepeat: Boolean
        get() = this is Failed && !resubmitAllowed
}

/** A section's read: loading, its data, or why it could not be read. */
sealed interface SectionState<out T> {
    data object Loading : SectionState<Nothing>
    data class Ready<T>(val value: T) : SectionState<T>
    data class Failed(val failure: FailureText) : SectionState<Nothing>
}

/** The carriers, connected first then refused, by name. */
data class CarrierAccounts(
    val entries: Map<String, ProviderStatusEntry>,
    val connected: List<String>,
    val refused: List<String>,
) {
    /** ⚠️ An empty map is "no carrier connected", a state of its own, not an empty list. */
    val notConnected: Boolean get() = entries.isEmpty()
}

/** The workspace's filings, and the countries already open to it. */
data class Registrations(
    val registrations: List<NumberRegistration>,
    val approvedCountries: List<String>,
    val platformCountries: List<String>,
)

/** The one-time passcode sender. */
data class VerifyService(val enabled: Boolean)

/** What a country's regulator asks for, once asked. */
sealed interface RequirementsState {
    data object Idle : RequirementsState
    data object Loading : RequirementsState
    data class Ready(val regulation: CountryRequirements, val purchasable: Boolean) : RequirementsState

    /** The country publishes no regulation for this number type: nothing to file. */
    data class NoRegulation(val country: String, val purchasable: Boolean) : RequirementsState
    data class Failed(val failure: FailureText) : RequirementsState
}

/**
 * What the billed lookup found, as lines to show.
 *
 * ⚠️ AN INVALID NUMBER IS AN ANSWER THE CARRIER BILLED, not an error: it shows as one line saying so.
 */
data class LookupSummary(val valid: Boolean, val lines: List<String>) {
    companion object {
        fun of(info: NumberLookupInfo): LookupSummary =
            if (!info.valid) {
                LookupSummary(valid = false, lines = emptyList())
            } else {
                LookupSummary(
                    valid = true,
                    lines = listOf(info.phoneNumber) +
                        listOfNotNull(info.country, info.type, info.carrier)
                            .map(String::trim)
                            .filter(String::isNotEmpty),
                )
            }
    }
}

/** A write that is confirmed first, because it is billed, filed or irreversible. */
sealed interface NumberConfirmation {
    data class Release(val phoneNumber: String) : NumberConfirmation
    data class SubmitRegistration(val bundleId: String, val country: String) : NumberConfirmation
    data object A2P : NumberConfirmation
    data object TollFree : NumberConfirmation
    data object Lookup : NumberConfirmation
}

/** The registration form. */
data class RegistrationDraft(
    val country: String = "",
    val numberType: String = NUMBER_TYPE_LOCAL,
    val friendlyName: String = "",
) {
    val trimmedCountry: String get() = country.trim().uppercase()
    val trimmedName: String? get() = friendlyName.trim().ifEmpty { null }
}

/** The SIP trunk form. ⚠️ One address or range per line. */
data class SipTrunkDraft(val name: String = "", val domain: String = "", val ipAddresses: String = "") {
    val addresses: List<String> get() = ipAddresses.lines().map(String::trim).filter(String::isNotEmpty)
    val isSubmittable: Boolean
        get() = name.isNotBlank() && domain.isNotBlank() && addresses.isNotEmpty()
}

/** The A2P 10DLC form. */
data class A2PDraft(
    val businessName: String = "",
    val ein: String = "",
    val website: String = "",
    val vertical: String = "",
    val campaignType: String = A2P_CAMPAIGN_TYPES.first(),
    val campaignDescription: String = "",
    val sampleOne: String = "",
    val sampleTwo: String = "",
) {
    val isSubmittable: Boolean
        get() = listOf(businessName, campaignDescription, sampleOne, sampleTwo).all(String::isNotBlank)

    val request: A2PRegistrationRequest
        get() = A2PRegistrationRequest(
            businessName = businessName.trim(),
            campaignType = campaignType,
            campaignDescription = campaignDescription,
            sampleMessage1 = sampleOne,
            sampleMessage2 = sampleTwo,
            ein = optional(ein),
            website = optional(website),
            vertical = optional(vertical),
        )
}

/** The toll-free verification form. ⚠️ The opt-in evidence link is required: a reviewer opens it. */
data class TollFreeDraft(
    val phoneNumber: String = "",
    val businessName: String = "",
    val useCase: String = TOLL_FREE_USE_CASES.first(),
    val messageContent: String = "",
    val optInImageUrl: String = "",
) {
    val optInImageUrls: List<String> get() = listOfNotNull(optional(optInImageUrl))
    val isSubmittable: Boolean
        get() = phoneNumber.isNotBlank() && businessName.isNotBlank() && optInImageUrls.isNotEmpty()

    val request: TollFreeVerificationRequest
        get() = TollFreeVerificationRequest(
            phoneNumber = phoneNumber.trim(),
            businessName = businessName.trim(),
            useCase = useCase,
            optInImageUrls = optInImageUrls,
            messageContent = optional(messageContent),
        )
}

/** Everything the number-management tabs and dialogs draw. */
data class NumberProvisioningUiState(
    val carrier: SectionState<CarrierAccounts> = SectionState.Loading,
    val verify: SectionState<VerifyService> = SectionState.Loading,
    val sip: SectionState<List<SipTrunk>> = SectionState.Loading,
    val registrations: SectionState<Registrations> = SectionState.Loading,
    val requirements: RequirementsState = RequirementsState.Idle,
    /** The owned number whose actions are open. */
    val selected: ListedNumber? = null,
    val confirmation: NumberConfirmation? = null,
    val numberWrite: NumberWriteState = NumberWriteState.Idle,
    val carrierWrite: NumberWriteState = NumberWriteState.Idle,
    val registrationWrite: NumberWriteState = NumberWriteState.Idle,
    val lookupWrite: NumberWriteState = NumberWriteState.Idle,
    val lookupResult: LookupSummary? = null,
    val lookupNumber: String = "",
    val registrationDraft: RegistrationDraft = RegistrationDraft(),
    val sipDraft: SipTrunkDraft = SipTrunkDraft(),
    val a2pDraft: A2PDraft = A2PDraft(),
    val tollFreeDraft: TollFreeDraft = TollFreeDraft(),
)

/** The A2P campaign types the route accepts. Wire values, shown as they are (iOS does the same). */
val A2P_CAMPAIGN_TYPES: List<String> = listOf("CUSTOMER_CARE", "LOW_VOLUME", "MARKETING", "2FA")

/** The toll-free use cases the route accepts. Wire values; each has its own label. */
val TOLL_FREE_USE_CASES: List<String> = listOf("Customer Support", "Marketing", "Notifications", "2FA / OTP")

/** A registration's editable status. Every other status is with the carrier. */
const val REGISTRATION_DRAFT: String = "draft"

private fun optional(value: String): String? = value.trim().ifEmpty { null }
