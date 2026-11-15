package com.distronode.districtai.ui.account

import com.distronode.districtai.R
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText

/** Which change the service refused. */
internal enum class TwoStepChange(val intent: TwoStepIntent) {
    ENROLL(TwoStepIntent.TURN_ON),
    CONFIRM(TwoStepIntent.TURN_ON),
    DISABLE(TwoStepIntent.TURN_OFF),
}

/** Where a refused change leaves the screen. */
internal enum class TwoStepNext {
    /** Stay on this step and try again. */
    STAY,

    /** The proof is gone: confirm it is you again, for this change. */
    REAUTH,

    /** What is on is no longer known, or no longer what the screen showed: read it again. */
    REREAD,
}

private val RE_READ_CODES = setOf("MFA_ALREADY_ENABLED", "MFA_NOT_STARTED", "MFA_NOT_PENDING")

private const val PNG_DATA_URL_PREFIX = "data:image/png;base64,"

/** The catalog's sentence for the code, else the service's, else a fallback. */
internal fun twoStepSentence(failure: ApiResult.Failure): UiText =
    serviceSentence(failure, R.string.two_step_error_try_again)

/**
 * What a refused change says and where it leaves the screen, the iOS app's rules.
 *
 * ⛔ BRANCHED ON THE CODE, NEVER ON THE MESSAGE. ⛔ A CONFIRM OR A DISABLE WHOSE ANSWER IS LOST MAY HAVE
 * HAPPENED, so it never claims either way and the status is read again.
 */
internal fun twoStepRefusal(failure: ApiResult.Failure, change: TwoStepChange): Pair<UiText, TwoStepNext> {
    val unknown = when (change) {
        TwoStepChange.ENROLL -> null
        TwoStepChange.CONFIRM -> UiText.Resource(R.string.two_step_error_outcome_unknown_on) to TwoStepNext.REREAD
        TwoStepChange.DISABLE -> UiText.Resource(R.string.two_step_error_outcome_unknown_off) to TwoStepNext.REREAD
    }
    return when {
        failure is ApiResult.NetworkFailure ->
            unknown ?: (UiText.Resource(R.string.two_step_error_offline_nothing_changed) to TwoStepNext.STAY)
        failure is ApiResult.DecodeFailure ->
            unknown ?: (UiText.Resource(R.string.two_step_error_try_again) to TwoStepNext.STAY)
        // ⚠️ A NEW PROOF MEANS A NEW SECRET for turning on: the old one is dropped.
        failure.code == "reauth_required" ->
            UiText.Resource(R.string.two_step_error_proof_expired) to TwoStepNext.REAUTH
        failure.code in RE_READ_CODES -> twoStepSentence(failure) to TwoStepNext.REREAD
        failure.isServerError -> UiText.Resource(R.string.two_step_error_try_again) to TwoStepNext.STAY
        failure is ApiResult.Unauthorized -> UiText.Resource(R.string.failure_signed_out) to TwoStepNext.STAY
        else -> twoStepSentence(failure) to TwoStepNext.STAY
    }
}

/** What a status read that failed says. */
internal fun twoStepLoadFailure(failure: ApiResult.Failure): UiText = when {
    failure is ApiResult.NetworkFailure -> UiText.Resource(R.string.two_step_error_offline_load)
    failure is ApiResult.Unauthorized -> UiText.Resource(R.string.failure_signed_out)
    failure is ApiResult.DecodeFailure || failure.isServerError -> UiText.Resource(R.string.two_step_error_load)
    else -> serviceSentence(failure, R.string.two_step_error_load)
}

/**
 * Where a refused password re-authentication leaves the screen.
 *
 * ⚠️ `MFA_REQUIRED` IS NOT A FAILURE: two-step verification is on, and its code goes with the
 * password on the next try. `NO_PASSWORD_SET` goes back to the providers.
 */
internal fun passwordRefused(state: TwoStepUiState, failure: ApiResult.Failure, intent: TwoStepIntent): TwoStepUiState =
    when (failure.code) {
        "MFA_REQUIRED" -> state.copy(step = TwoStepStep.Password(intent, needsCode = true))
        "NO_PASSWORD_SET" -> state.copy(
            step = TwoStepStep.Reauth(intent),
            failure = UiText.Resource(R.string.reauth_error_no_password),
        )
        "mfa_code_invalid" -> state.copy(code = "", failure = reauthFailure(failure, sso = false))
        else -> state.copy(failure = reauthFailure(failure, sso = false))
    }

/** The base64 inside `data:image/png;base64,...`, or null for anything else. */
internal fun pngBase64(dataUrl: String): String? =
    dataUrl.takeIf { it.startsWith(PNG_DATA_URL_PREFIX) }?.removePrefix(PNG_DATA_URL_PREFIX)

/** The key in groups of four, as authenticator apps print it. ⚠️ Display only: Copy uses the key as sent. */
internal fun groupedKey(secret: String): String = secret.chunked(KEY_GROUP).joinToString(" ")

private const val KEY_GROUP = 4
