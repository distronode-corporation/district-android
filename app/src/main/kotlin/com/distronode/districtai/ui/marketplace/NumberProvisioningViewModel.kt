package com.distronode.districtai.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.NumberProvisioningRepository
import com.distronode.districtai.core.data.NumberWriteRepeat
import com.distronode.districtai.core.data.NumberWriteResubmit
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Number management: a number's actions, the regulatory filings and the carrier account (iOS
 * `NumberProvisioningModel`).
 *
 * ⛔ EVERY WRITE IS GATED THREE WAYS: the role ([canProvision]; the routes exclude `viewer`), the
 * write family's state (one at a time, `isArmed`), and for the billed, filed or irreversible ones a
 * confirmation the screen shows first ([requestConfirmation]). A failed write that may have gone
 * through is not offered again (`NumberWriteResubmit`).
 *
 * ⛔ BUYING A NUMBER IS NOT HERE AND MUST NOT BE: it stays on the web.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED: one small function per action the screens offer, around one
 * state, as iOS's model has them.
 */
@Suppress("TooManyFunctions")
class NumberProvisioningViewModel(
    private val repository: NumberProvisioningRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    /** Whether this member may change numbers and carrier settings. */
    val canProvision: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow(NumberProvisioningUiState())
    val state: StateFlow<NumberProvisioningUiState> = _state.asStateFlow()

    // ── Reads ───────────────────────────────────────────────────────────────────────────────────

    /** The carrier tab's three reads. */
    fun loadCarrierTab() {
        loadCarrier()
        loadVerify()
        loadSip()
    }

    fun loadCarrier() {
        _state.update { it.copy(carrier = SectionState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.providerStatus(workspaceId)) {
                is ApiResult.Success -> SectionState.Ready(
                    CarrierAccounts(
                        entries = result.value.providers.orEmpty(),
                        connected = result.value.connectedProviders,
                        refused = result.value.refusedProviders,
                    ),
                )
                is ApiResult.Failure -> SectionState.Failed(result.toFailureText())
            }
            _state.update { it.copy(carrier = next) }
        }
    }

    fun loadVerify() {
        _state.update { it.copy(verify = SectionState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.verifyService(workspaceId)) {
                is ApiResult.Success -> SectionState.Ready(VerifyService(result.value.enabled))
                is ApiResult.Failure -> SectionState.Failed(result.toFailureText())
            }
            _state.update { it.copy(verify = next) }
        }
    }

    fun loadSip() {
        _state.update { it.copy(sip = SectionState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.sipTrunks(workspaceId)) {
                is ApiResult.Success -> SectionState.Ready(result.value.trunks)
                is ApiResult.Failure -> SectionState.Failed(result.toFailureText())
            }
            _state.update { it.copy(sip = next) }
        }
    }

    /**
     * ⚠️ A ROLE THAT MAY NOT FILE IS NOT SENT TO READ THE FILINGS: the tab shows it why instead.
     */
    fun loadRegistrations() {
        if (!canProvision) {
            _state.update {
                it.copy(
                    registrations = SectionState.Ready(Registrations(emptyList(), emptyList(), emptyList()))
                )
            }
            return
        }
        _state.update { it.copy(registrations = SectionState.Loading) }
        viewModelScope.launch { readRegistrations() }
    }

    private suspend fun readRegistrations() {
        val next = when (val result = repository.registrations(workspaceId)) {
            is ApiResult.Success -> SectionState.Ready(
                Registrations(
                    registrations = result.value.registrations,
                    approvedCountries = result.value.approvedCountries,
                    platformCountries = result.value.platformCountries,
                ),
            )
            is ApiResult.Failure -> SectionState.Failed(result.toFailureText())
        }
        _state.update { it.copy(registrations = next) }
    }

    /** What the draft's country asks for. */
    fun loadRequirements() {
        val draft = _state.value.registrationDraft
        val country = draft.trimmedCountry
        if (country.isEmpty()) return
        _state.update { it.copy(requirements = RequirementsState.Loading) }
        viewModelScope.launch {
            val next = when (val result = repository.requirements(workspaceId, country, draft.numberType)) {
                is ApiResult.Success -> {
                    val regulation = result.value.requirements
                    if (regulation != null) {
                        RequirementsState.Ready(regulation, result.value.purchasable)
                    } else {
                        RequirementsState.NoRegulation(result.value.country, result.value.purchasable)
                    }
                }
                is ApiResult.Failure -> RequirementsState.Failed(result.toFailureText())
            }
            _state.update { it.copy(requirements = next) }
        }
    }

    // ── Forms ───────────────────────────────────────────────────────────────────────────────────

    fun updateRegistrationDraft(draft: RegistrationDraft) = _state.update { it.copy(registrationDraft = draft) }

    fun updateSipDraft(draft: SipTrunkDraft) = _state.update { it.copy(sipDraft = draft) }

    fun updateA2PDraft(draft: A2PDraft) = _state.update { it.copy(a2pDraft = draft) }

    fun updateTollFreeDraft(draft: TollFreeDraft) = _state.update { it.copy(tollFreeDraft = draft) }

    fun updateLookupNumber(number: String) = _state.update { it.copy(lookupNumber = number) }

    // ── A number's actions ──────────────────────────────────────────────────────────────────────

    /**
     * Open [number]'s actions. ⚠️ THE LAST NUMBER WRITE'S NOTICE IS CLEARED unless one is running: a
     * "Released" left over from another number would read as this one's.
     */
    fun openNumber(number: ListedNumber) = _state.update {
        it.copy(
            selected = number,
            numberWrite = if (it.numberWrite == NumberWriteState.Running) it.numberWrite else NumberWriteState.Idle
        )
    }

    fun closeNumber() = _state.update { it.copy(selected = null) }

    /** Reapply routing. [NumberWriteRepeat.IDEMPOTENT], so it needs no confirmation. */
    fun configure(phoneNumber: String) = write(NumberWriteFamily.NUMBER, NumberWriteRepeat.IDEMPOTENT) {
        when (val result = repository.configure(workspaceId, phoneNumber)) {
            is ApiResult.Success -> ApiResult.Success(
                NumberWriteState.Done(UiText.Resource(R.string.numbers_configure_done))
            )
            is ApiResult.Failure -> result
        }
    }

    // ── Confirmations ───────────────────────────────────────────────────────────────────────────

    /** Ask the screen to confirm [next]. Refused for a role that may not, or while its write is not armed. */
    fun requestConfirmation(next: NumberConfirmation) {
        if (!canProvision || !family(next).state(_state.value).isArmed) return
        _state.update { it.copy(confirmation = next) }
    }

    fun dismissConfirmation() = _state.update { it.copy(confirmation = null) }

    /** Perform the confirmed write. */
    fun confirm() {
        when (val confirmation = _state.value.confirmation ?: return) {
            is NumberConfirmation.Release -> release(confirmation.phoneNumber)
            is NumberConfirmation.SubmitRegistration -> submitRegistration(confirmation.bundleId)
            NumberConfirmation.A2P -> submitA2P()
            NumberConfirmation.TollFree -> submitTollFree()
            NumberConfirmation.Lookup -> lookup()
        }
    }

    /** ⛔ Irreversible, [NumberWriteRepeat.ONCE]. A release that worked may carry warnings. */
    private fun release(phoneNumber: String) = write(NumberWriteFamily.NUMBER, NumberWriteRepeat.ONCE) {
        when (val result = repository.release(workspaceId, phoneNumber)) {
            is ApiResult.Success -> {
                val warnings = result.value.warnings.orEmpty()
                ApiResult.Success(
                    if (warnings.isEmpty()) {
                        NumberWriteState.Done(UiText.Resource(R.string.numbers_release_done))
                    } else {
                        NumberWriteState.Done(UiText.Resource(R.string.numbers_release_warning_eyebrow), warnings)
                    },
                )
            }
            is ApiResult.Failure -> result
        }
    }

    /** ⛔ Billed per call, [NumberWriteRepeat.ONCE]. */
    private fun lookup() {
        val number = _state.value.lookupNumber.trim()
        if (number.isEmpty()) return dismissConfirmation()
        write(NumberWriteFamily.LOOKUP, NumberWriteRepeat.ONCE) {
            when (val result = repository.lookup(workspaceId, number)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(lookupResult = LookupSummary.of(result.value.info)) }
                    ApiResult.Success(NumberWriteState.Idle)
                }
                is ApiResult.Failure -> result
            }
        }
    }

    /** ⛔ A brand fee, [NumberWriteRepeat.ONCE]. The carrier's status is adopted: there is no read. */
    private fun submitA2P() {
        val draft = _state.value.a2pDraft
        if (!draft.isSubmittable) return dismissConfirmation()
        write(NumberWriteFamily.CARRIER, NumberWriteRepeat.ONCE) {
            when (val result = repository.submitA2P(workspaceId, draft.request)) {
                is ApiResult.Success ->
                    ApiResult.Success(
                        NumberWriteState.Done(UiText.Resource(R.string.numbers_a2p_submitted, result.value.status))
                    )
                is ApiResult.Failure -> result
            }
        }
    }

    /** ⛔ A manual review, [NumberWriteRepeat.ONCE]. */
    private fun submitTollFree() {
        val draft = _state.value.tollFreeDraft
        if (!draft.isSubmittable) return dismissConfirmation()
        write(NumberWriteFamily.CARRIER, NumberWriteRepeat.ONCE) {
            when (val result = repository.submitTollFree(workspaceId, draft.request)) {
                is ApiResult.Success ->
                    ApiResult.Success(
                        NumberWriteState.Done(UiText.Resource(R.string.numbers_tfv_submitted, result.value.status))
                    )
                is ApiResult.Failure -> result
            }
        }
    }

    // ── Carrier account ─────────────────────────────────────────────────────────────────────────

    /** ⛔ A monthly charge, [NumberWriteRepeat.ONCE]. Confirmed by the caption above the button. */
    fun createSipTrunk() {
        val draft = _state.value.sipDraft
        if (!draft.isSubmittable) return
        write(NumberWriteFamily.CARRIER, NumberWriteRepeat.ONCE) {
            when (
                val result = repository.createSipTrunk(
                    workspaceId,
                    draft.name.trim(),
                    draft.domain.trim(),
                    draft.addresses
                )
            ) {
                is ApiResult.Success -> {
                    _state.update { it.copy(sipDraft = SipTrunkDraft()) }
                    readSip()
                    ApiResult.Success(NumberWriteState.Idle)
                }
                is ApiResult.Failure -> result
            }
        }
    }

    private suspend fun readSip() {
        val next = when (val result = repository.sipTrunks(workspaceId)) {
            is ApiResult.Success -> SectionState.Ready(result.value.trunks)
            is ApiResult.Failure -> SectionState.Failed(result.toFailureText())
        }
        _state.update { it.copy(sip = next) }
    }

    /** ⛔ Enabling mints a billable sender, [NumberWriteRepeat.ONCE]. The reply is adopted. */
    fun setVerify(enabled: Boolean) = write(NumberWriteFamily.CARRIER, NumberWriteRepeat.ONCE) {
        when (val result = repository.setVerifyEnabled(workspaceId, enabled)) {
            is ApiResult.Success -> {
                _state.update { it.copy(verify = SectionState.Ready(VerifyService(result.value.enabled))) }
                ApiResult.Success(NumberWriteState.Idle)
            }
            is ApiResult.Failure -> result
        }
    }

    // ── Registrations ───────────────────────────────────────────────────────────────────────────

    /** Start a draft filing. [NumberWriteRepeat.IDEMPOTENT]. */
    fun createRegistration() {
        val draft = _state.value.registrationDraft
        if (draft.trimmedCountry.isEmpty()) return
        write(NumberWriteFamily.REGISTRATION, NumberWriteRepeat.IDEMPOTENT) {
            when (
                val result = repository.createRegistration(
                    workspaceId,
                    draft.trimmedCountry,
                    draft.numberType,
                    draft.trimmedName
                )
            ) {
                is ApiResult.Success -> {
                    _state.update { it.copy(registrationDraft = RegistrationDraft()) }
                    readRegistrations()
                    ApiResult.Success(NumberWriteState.Idle)
                }
                is ApiResult.Failure -> result
            }
        }
    }

    /** Remove a draft's document. [NumberWriteRepeat.IDEMPOTENT]. */
    fun removeDocument(bundleId: String, documentId: String) = write(
        NumberWriteFamily.REGISTRATION,
        NumberWriteRepeat.IDEMPOTENT,
    ) {
        when (val result = repository.removeDocument(workspaceId, bundleId, documentId)) {
            is ApiResult.Success -> {
                readRegistrations()
                ApiResult.Success(NumberWriteState.Idle)
            }
            is ApiResult.Failure -> result
        }
    }

    /**
     * ⛔ Files a regulated application, [NumberWriteRepeat.ONCE]. The list is read again either way:
     * a refusal changes the filing's status and reasons too.
     */
    private fun submitRegistration(bundleId: String) = write(NumberWriteFamily.REGISTRATION, NumberWriteRepeat.ONCE) {
        val result = repository.submitRegistration(workspaceId, bundleId, endUserAttributes = emptyMap())
        readRegistrations()
        when (result) {
            is ApiResult.Success -> ApiResult.Success(
                NumberWriteState.Done(UiText.Resource(R.string.numbers_submit_filed))
            )
            is ApiResult.Failure -> result
        }
    }

    // ── The write runner ────────────────────────────────────────────────────────────────────────

    /**
     * Run one write of [family]: refused for a role that may not or while the family is not armed,
     * then Running, then what [work] answers (a state, or a failure to word and gate).
     *
     * ⛔ A CONFIRMATION CLOSES AS THE WRITE STARTS: its outcome shows where the control was.
     */
    private fun write(
        family: NumberWriteFamily,
        repeat: NumberWriteRepeat,
        work: suspend () -> ApiResult<NumberWriteState>
    ) {
        if (!canProvision || !family.state(_state.value).isArmed) return
        _state.update { family.with(it.copy(confirmation = null), NumberWriteState.Running) }
        viewModelScope.launch {
            val next = when (val outcome = work()) {
                is ApiResult.Success -> outcome.value
                is ApiResult.Failure -> NumberWriteState.Failed(
                    failure = failureFor(outcome, family),
                    resubmitAllowed = NumberWriteResubmit.allowed(outcome, repeat),
                )
            }
            _state.update { family.with(it, next) }
        }
    }

    /**
     * ⚠️ A 422 ON A FILING IS THE CARRIER'S REFUSAL, worded with its own sentence (or, without one, a
     * prompt to read the list again for its reasons).
     */
    private fun failureFor(failure: ApiResult.Failure, family: NumberWriteFamily): FailureText {
        val text = failure.toFailureText()
        if (family != NumberWriteFamily.REGISTRATION || (failure as? ApiResult.HttpFailure)?.status != UNPROCESSABLE) {
            return text
        }
        val stated = failure.message.trim()
        return FailureText(
            message = if (stated.isEmpty()) {
                UiText.Resource(R.string.numbers_submit_refused_reread)
            } else {
                UiText.Resource(R.string.numbers_submit_refused_because, stated)
            },
            retryable = false,
        )
    }

    private fun family(confirmation: NumberConfirmation): NumberWriteFamily = when (confirmation) {
        is NumberConfirmation.Release -> NumberWriteFamily.NUMBER
        is NumberConfirmation.SubmitRegistration -> NumberWriteFamily.REGISTRATION
        NumberConfirmation.A2P, NumberConfirmation.TollFree -> NumberWriteFamily.CARRIER
        NumberConfirmation.Lookup -> NumberWriteFamily.LOOKUP
    }

    companion object {
        private const val UNPROCESSABLE = 422

        fun factory(
            repository: NumberProvisioningRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { NumberProvisioningViewModel(repository, workspaceId, role) }
        }
    }
}

/** The four families of number writes; one of each may run at a time. */
internal enum class NumberWriteFamily {
    NUMBER,
    CARRIER,
    REGISTRATION,
    LOOKUP,
    ;

    fun state(state: NumberProvisioningUiState): NumberWriteState = when (this) {
        NUMBER -> state.numberWrite
        CARRIER -> state.carrierWrite
        REGISTRATION -> state.registrationWrite
        LOOKUP -> state.lookupWrite
    }

    fun with(state: NumberProvisioningUiState, write: NumberWriteState): NumberProvisioningUiState = when (this) {
        NUMBER -> state.copy(numberWrite = write)
        CARRIER -> state.copy(carrierWrite = write)
        REGISTRATION -> state.copy(registrationWrite = write)
        LOOKUP -> state.copy(lookupWrite = write)
    }
}
