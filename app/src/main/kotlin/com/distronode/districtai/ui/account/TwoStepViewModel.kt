package com.distronode.districtai.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.NativeReauthRepository
import com.distronode.districtai.core.data.TwoStepSettingsRepository
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a re-authentication on the two-step screen is for. */
enum class TwoStepIntent { TURN_ON, TURN_OFF }

/** The screen's three text fields. */
enum class TwoStepField { PASSWORD, CODE, TOTP }

/** Where the two-step screen is. */
sealed interface TwoStepStep {
    /** The first read of the status. */
    data object Loading : TwoStepStep

    /** The status could not be read; the failure says why, and Retry reads it again. */
    data object LoadFailed : TwoStepStep

    /** On or off, since when, and how many recovery codes are left. */
    data object Overview : TwoStepStep

    /** The ways to confirm it is you, before the change. */
    data class Reauth(val intent: TwoStepIntent) : TwoStepStep

    /** The password, and once the service asks for it, the authenticator code. */
    data class Password(val intent: TwoStepIntent, val needsCode: Boolean) : TwoStepStep

    /** The new secret as a QR code and a key, and the code that proves the app has it. */
    data object Scan : TwoStepStep

    /** The recovery codes, shown this once. */
    data object RecoveryCodes : TwoStepStep
}

/**
 * A new secret, held only while [TwoStepStep.Scan] is on screen.
 *
 * ⛔ ALL THREE FIELDS ARE THE SECRET, so [toString] prints none of them.
 */
class TwoStepEnrollment(
    val secret: String,
    val otpauthUri: String,
    /** The base64 PNG inside the service's `data:` URL, or null when it is not one. */
    val qrPngBase64: String?,
) {
    override fun toString(): String = "TwoStepEnrollment(<redacted>)"
}

/**
 * The two-step screen's state.
 *
 * ⛔ IT HOLDS THE SECRET, THE RECOVERY CODES AND A TYPED PASSWORD, so [toString] prints none of them.
 */
data class TwoStepUiState(
    val step: TwoStepStep = TwoStepStep.Loading,
    val busy: Boolean = false,
    val status: MfaStatusResponse? = null,
    /** A refusal or a failure, as one sentence saying what to do. */
    val failure: UiText? = null,
    /** What the last change did, until the next action. */
    val notice: UiText? = null,
    val enrollment: TwoStepEnrollment? = null,
    val recoveryCodes: List<String> = emptyList(),
    val password: String = "",
    val code: String = "",
    /** The authenticator's code that confirms enrolment ([TwoStepStep.Scan]). */
    val totp: String = "",
) {
    /**
     * ⚠️ THE STATUS'S OWN FLAG IS THE WEBSITE'S RULE, NOT THE APP'S: an account with no password turns
     * it off here after a fresh provider sign-in.
     */
    val signsInWithoutPassword: Boolean get() = status?.let { it.enrolled && !it.canDisable } ?: false

    val canSubmitPassword: Boolean
        get() {
            val at = step as? TwoStepStep.Password ?: return false
            return !busy && password.isNotEmpty() && (!at.needsCode || code.isNotBlank())
        }

    val canConfirm: Boolean get() = !busy && step == TwoStepStep.Scan && totp.isNotBlank()

    /** ⛔ The steps that hold a credential: covered from screenshots and the app switcher. */
    val holdsSecret: Boolean get() = step == TwoStepStep.Scan || step == TwoStepStep.RecoveryCodes

    override fun toString(): String = "TwoStepUiState(step=$step, busy=$busy, status=$status, <secrets redacted>)"
}

/** What the two-step screen can ask its model to do. [TwoStepViewModel] is the real one. */
interface TwoStepActions {
    fun onField(field: TwoStepField, value: String)
    fun load()
    fun begin(intent: TwoStepIntent)
    fun usePassword()
    fun back()
    fun reauthenticate(provider: ReauthProvider)
    fun submitPassword()
    fun confirm()
    fun finishRecoveryCodes()
}

/**
 * Two-step verification on the signed-in account: whether it is on, turning it on with an
 * authenticator app, and turning it off. The behaviour of the iOS app's `TwoStepModel`.
 *
 * ⛔ A FRESH `2fa-change` RE-AUTHENTICATION BEFORE EVERY CHANGE, AND THE SERVICE ENFORCES IT. A fresh
 * sign-in at the person's provider, or their password and (when two-step verification is already on)
 * its code, earns a proof bound to this installation and this action; `enroll` checks it, `confirm`
 * and `disable` spend it. No passkeys.
 *
 * ⛔ THE SECRET, ITS URI, ITS QR CODE AND THE RECOVERY CODES ARE CREDENTIALS. They live in this
 * state only, are never logged, and are dropped when the screen leaves the back stack ([onCleared]).
 *
 * ⛔ SINGLE-FLIGHT ACROSS EVERY CALL. `busy` is checked and set before the first suspension, on the
 * main thread, so two taps dispatched before a re-render send one request. A second password attempt
 * would cost one of the few a minute the service allows, and a second confirm would answer
 * `MFA_NOT_PENDING` over codes already shown.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED: one small function per action the screen offers, around one
 * state, as iOS's model has them. The wording lives beside it in `TwoStepWording.kt`.
 */
@Suppress("TooManyFunctions")
class TwoStepViewModel(
    private val settings: TwoStepSettingsRepository,
    private val reauth: NativeReauthRepository,
    private val sso: suspend (ReauthProvider) -> SsoReauthOutcome,
    private val now: () -> Instant = Instant::now,
) : ViewModel(), TwoStepActions {

    private val _state = MutableStateFlow(TwoStepUiState())
    val state: StateFlow<TwoStepUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** A keystroke in one of the screen's three fields. */
    override fun onField(field: TwoStepField, value: String) = _state.update {
        when (field) {
            TwoStepField.PASSWORD -> it.copy(password = value)
            TwoStepField.CODE -> it.copy(code = value)
            TwoStepField.TOTP -> it.copy(totp = value)
        }
    }

    override fun load() = flight { read() }

    override fun begin(intent: TwoStepIntent) {
        val current = _state.value
        val status = current.status
        if (current.busy || current.step != TwoStepStep.Overview || status == null) return
        // ⚠️ THE STATUS DECIDES WHICH CHANGE IS OFFERED: a stale button never starts the other.
        if (status.enrolled != (intent == TwoStepIntent.TURN_OFF)) return
        _state.update { it.copy(failure = null, notice = null, step = TwoStepStep.Reauth(intent)) }
    }

    override fun usePassword() {
        val current = _state.value
        val at = current.step as? TwoStepStep.Reauth ?: return
        if (current.busy) return
        _state.update {
            it.copy(failure = null, password = "", code = "", step = TwoStepStep.Password(at.intent, needsCode = false))
        }
    }

    /**
     * One step back: from the password to the ways to confirm, from there to the overview, and from
     * the new secret to the overview (the unconfirmed secret is dropped).
     */
    override fun back() {
        val current = _state.value
        if (current.busy) return
        val next = when (val at = current.step) {
            is TwoStepStep.Password -> TwoStepStep.Reauth(at.intent)
            is TwoStepStep.Reauth, TwoStepStep.Scan -> TwoStepStep.Overview
            else -> return
        }
        _state.update {
            val moved = it.copy(failure = null, password = "", code = "", step = next)
            if (next == TwoStepStep.Overview) moved.copy(enrollment = null, totp = "") else moved
        }
    }

    override fun reauthenticate(provider: ReauthProvider) {
        val at = _state.value.step as? TwoStepStep.Reauth ?: return
        flight {
            when (val outcome = sso(provider)) {
                is SsoReauthOutcome.Proved -> change(at.intent)
                else -> _state.update { it.copy(failure = ssoFailure(outcome)) }
            }
        }
    }

    override fun submitPassword() {
        val current = _state.value
        if (!current.canSubmitPassword) return
        val at = current.step as TwoStepStep.Password
        val typedCode = if (at.needsCode) current.code.trim() else null
        flight {
            val result = reauth.reauthenticate(NativeReauthAction.TWO_FACTOR_CHANGE, current.password, typedCode)
            _state.update { it.copy(password = "") }
            when (result) {
                is ApiResult.Success -> {
                    _state.update { it.copy(code = "") }
                    change(at.intent)
                }
                is ApiResult.Failure -> _state.update { passwordRefused(it, result, at.intent) }
            }
        }
    }

    /** Turns the factor on with the authenticator's code, and shows the recovery codes. */
    override fun confirm() {
        val current = _state.value
        if (!current.canConfirm) return
        val typed = current.totp.trim()
        flight {
            val result = settings.confirm(typed)
            _state.update { it.copy(totp = "") }
            when (result) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        enrollment = null,
                        recoveryCodes = result.value.backupCodes,
                        // ⚠️ ON NOW, WHATEVER THE NEXT READ SAYS: a read that fails after this must
                        // not show it off.
                        status = MfaStatusResponse(
                            enrolled = true,
                            enrolledAt = now().truncatedTo(ChronoUnit.MILLIS).toString(),
                            backupCodesRemaining = result.value.backupCodes.size,
                            canDisable = it.status?.canDisable ?: true,
                        ),
                        step = TwoStepStep.RecoveryCodes,
                    )
                }
                is ApiResult.Failure -> if (result.code == "mfa_code_invalid") {
                    _state.update { it.copy(failure = twoStepSentence(result)) }
                } else {
                    refused(result, TwoStepChange.CONFIRM)
                }
            }
        }
    }

    /** The recovery codes are saved: drop them and read the status again. */
    override fun finishRecoveryCodes() {
        if (_state.value.step != TwoStepStep.RecoveryCodes) return
        flight {
            _state.update {
                it.copy(
                    recoveryCodes = emptyList(),
                    notice = UiText.Resource(R.string.two_step_turned_on)
                )
            }
            read()
        }
    }

    /**
     * ⛔ THE SCREEN HAS LEFT THE BACK STACK: nothing secret outlives it. An unconfirmed secret stays
     * pending on the service, which replaces it on the next enrolment.
     */
    override fun onCleared() = _state.update {
        it.copy(enrollment = null, recoveryCodes = emptyList(), totp = "", password = "", code = "")
    }

    // ── Changes ─────────────────────────────────────────────────────────────

    /** The proof stands: make the change it was earned for. Runs inside the caller's flight. */
    private suspend fun change(intent: TwoStepIntent) {
        if (intent == TwoStepIntent.TURN_ON) {
            when (val result = settings.enroll()) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        enrollment = TwoStepEnrollment(
                            secret = result.value.secret,
                            otpauthUri = result.value.otpauthUri,
                            qrPngBase64 = pngBase64(result.value.qrDataUrl),
                        ),
                        totp = "",
                        step = TwoStepStep.Scan,
                    )
                }
                is ApiResult.Failure -> refused(result, TwoStepChange.ENROLL)
            }
            return
        }
        when (val result = settings.disable()) {
            is ApiResult.Success -> {
                _state.update {
                    it.copy(
                        notice = UiText.Resource(R.string.two_step_turned_off),
                        // ⚠️ OFF NOW, WHATEVER THE NEXT READ SAYS, as after a confirm.
                        status = MfaStatusResponse(false, null, 0, it.status?.canDisable ?: true),
                    )
                }
                read()
            }
            is ApiResult.Failure -> refused(result, TwoStepChange.DISABLE)
        }
    }

    /** Where a refused change leaves the screen, and the sentence for it. */
    private suspend fun refused(failure: ApiResult.Failure, change: TwoStepChange) {
        val (message, next) = twoStepRefusal(failure, change)
        _state.update { it.copy(failure = message) }
        when (next) {
            TwoStepNext.STAY -> Unit
            TwoStepNext.REAUTH -> _state.update {
                it.copy(enrollment = null, totp = "", step = TwoStepStep.Reauth(change.intent))
            }
            TwoStepNext.REREAD -> {
                _state.update { it.copy(enrollment = null, totp = "") }
                // ⚠️ THE READ'S OWN FAILURE, IF ANY, IS THE MORE URGENT ONE; otherwise the refusal stays.
                if (read()) _state.update { it.copy(failure = message) }
            }
        }
    }

    /** The status, into the state and the overview, or the reason it could not be read. Answers whether it was read. */
    private suspend fun read(): Boolean = when (val result = settings.status()) {
        is ApiResult.Success -> {
            _state.update { it.copy(status = result.value, failure = null, step = TwoStepStep.Overview) }
            true
        }
        is ApiResult.Failure -> {
            _state.update {
                // ⚠️ A STATUS ALREADY SHOWN STAYS ON SCREEN under the failure; only a first read fails
                // to the Retry step.
                it.copy(
                    failure = twoStepLoadFailure(result),
                    step = if (it.status == null) TwoStepStep.LoadFailed else TwoStepStep.Overview,
                )
            }
            false
        }
    }

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
            settings: TwoStepSettingsRepository,
            reauth: NativeReauthRepository,
            sso: suspend (ReauthProvider) -> SsoReauthOutcome,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TwoStepViewModel(settings, reauth, sso) }
        }
    }
}
