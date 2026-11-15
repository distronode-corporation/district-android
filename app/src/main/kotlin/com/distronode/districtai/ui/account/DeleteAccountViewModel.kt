package com.distronode.districtai.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.NativeReauthRepository
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the deletion screen is. */
sealed interface DeleteAccountStep {
    /** What is deleted and kept, and the ways to confirm it is you. */
    data object Explain : DeleteAccountStep

    /** The password, and once the service asks for it, the authenticator code. */
    data class Password(val needsCode: Boolean) : DeleteAccountStep

    /** The proof stands: the typed email and the final button. */
    data object Confirm : DeleteAccountStep
}

/** A refusal: an optional headline (that nothing was deleted), then what went wrong and what to do. */
data class DeleteAccountFailure(val title: UiText?, val message: UiText)

/** The screen's three text fields. */
enum class DeleteAccountField { PASSWORD, CODE, EMAIL }

/**
 * The deletion screen's state. ⛔ It holds a typed password, so [toString] prints none of the fields.
 */
data class DeleteAccountUiState(
    val step: DeleteAccountStep = DeleteAccountStep.Explain,
    val busy: Boolean = false,
    val failure: DeleteAccountFailure? = null,
    val password: String = "",
    val code: String = "",
    val confirmation: String = "",
) {
    val canSubmitPassword: Boolean
        get() {
            val at = step as? DeleteAccountStep.Password ?: return false
            return !busy && password.isNotEmpty() && (!at.needsCode || code.isNotBlank())
        }

    val canDelete: Boolean get() = !busy && step == DeleteAccountStep.Confirm && confirmation.isNotBlank()

    override fun toString(): String = "DeleteAccountUiState(step=$step, busy=$busy, <fields redacted>)"
}

/** What the deletion screen can ask its model to do. [DeleteAccountViewModel] is the real one. */
interface DeleteAccountActions {
    fun onField(field: DeleteAccountField, value: String)
    fun usePassword()
    fun back()
    fun reauthenticate(provider: ReauthProvider)
    fun submitPassword()
    fun delete()
}

/**
 * Deleting the signed-in account from the app (Google Play's account deletion requirement). The
 * behaviour of the iOS app's `DeleteAccountModel`.
 *
 * ⛔ THREE STEPS, AND THE SERVICE ENFORCES EVERY ONE OF THEM. A fresh re-authentication (a fresh
 * sign-in at the person's provider, or their password and, with two-step verification, its code)
 * earns a proof bound to this installation and this one action; the typed email then spends it.
 * Nothing here decides who may delete: a workspace owner, a refused subscription cancellation or any
 * other block is the service's answer, shown as it comes.
 *
 * ⛔ SINGLE-FLIGHT ACROSS ALL THREE CALLS. `busy` is checked and set before the first suspension, so
 * two taps dispatched before a re-render send one request. A second delete would only be refused
 * (the proof is single-use), but a second password attempt costs one of the few a minute the
 * service allows.
 *
 * ⚠️ THE PASSWORD IS NOT KEPT. It is cleared after every attempt, and the typed email after a
 * deletion; nothing is held once the screen leaves the back stack.
 */
class DeleteAccountViewModel(
    private val repository: NativeReauthRepository,
    private val sso: suspend (ReauthProvider) -> SsoReauthOutcome,
    private val deleted: (externalErasurePending: Boolean) -> Unit,
) : ViewModel(), DeleteAccountActions {

    private val _state = MutableStateFlow(DeleteAccountUiState())
    val state: StateFlow<DeleteAccountUiState> = _state.asStateFlow()

    override fun onField(field: DeleteAccountField, value: String) = _state.update {
        when (field) {
            DeleteAccountField.PASSWORD -> it.copy(password = value)
            DeleteAccountField.CODE -> it.copy(code = value)
            DeleteAccountField.EMAIL -> it.copy(confirmation = value)
        }
    }

    override fun usePassword() {
        if (_state.value.busy) return
        _state.update {
            it.copy(failure = null, password = "", code = "", step = DeleteAccountStep.Password(needsCode = false))
        }
    }

    override fun back() {
        if (_state.value.busy) return
        _state.update { it.copy(failure = null, password = "", code = "", step = DeleteAccountStep.Explain) }
    }

    override fun reauthenticate(provider: ReauthProvider) {
        if (_state.value.step != DeleteAccountStep.Explain) return
        flight {
            when (val outcome = sso(provider)) {
                is SsoReauthOutcome.Proved -> _state.update { it.copy(step = DeleteAccountStep.Confirm) }
                else -> _state.update { state -> state.copy(failure = ssoFailure(outcome)?.let { DeleteAccountFailure(null, it) }) }
            }
        }
    }

    override fun submitPassword() {
        val current = _state.value
        if (!current.canSubmitPassword) return
        val needsCode = (current.step as DeleteAccountStep.Password).needsCode
        val typedCode = if (needsCode) current.code.trim() else null
        flight {
            val result = repository.reauthenticate(NativeReauthAction.DELETE_ACCOUNT, current.password, typedCode)
            _state.update { it.copy(password = "") }
            when (result) {
                is ApiResult.Success -> _state.update { it.copy(code = "", step = DeleteAccountStep.Confirm) }
                is ApiResult.Failure -> _state.update { deletePasswordRefused(it, result) }
            }
        }
    }

    override fun delete() {
        val current = _state.value
        if (!current.canDelete) return
        val typed = current.confirmation.trim()
        flight {
            when (val result = repository.deleteAccount(typed)) {
                is ApiResult.Success -> {
                    _state.update { it.copy(confirmation = "") }
                    deleted(result.value)
                }
                is ApiResult.Failure -> _state.update {
                    if (result.code == "reauth_required") {
                        // ⛔ THE PROOF IS GONE (expired, or spent): back to the start, said plainly.
                        it.copy(
                            step = DeleteAccountStep.Explain,
                            failure = DeleteAccountFailure(null, UiText.Resource(R.string.delete_account_error_proof_expired)),
                        )
                    } else {
                        it.copy(failure = deleteFailure(result))
                    }
                }
            }
        }
    }

    override fun onCleared() = _state.update { it.copy(password = "", code = "", confirmation = "") }

    /** ⛔ The single-flight gate: checked and set before the first suspension. */
    private fun flight(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            block()
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        fun factory(
            repository: NativeReauthRepository,
            sso: suspend (ReauthProvider) -> SsoReauthOutcome,
            deleted: (Boolean) -> Unit,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeleteAccountViewModel(repository, sso, deleted) }
        }
    }
}
