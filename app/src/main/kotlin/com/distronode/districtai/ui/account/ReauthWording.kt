// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.account

import com.distronode.districtai.R
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText

/** The service's machine-readable `code` on any failure that can carry one. */
internal val ApiResult.Failure.code: String?
    get() = when (this) {
        is ApiResult.Forbidden -> code
        is ApiResult.NotFound -> code
        is ApiResult.RateLimited -> code
        is ApiResult.HttpFailure -> code
        is ApiResult.Unauthorized,
        is ApiResult.RegionsDegraded,
        is ApiResult.NetworkFailure,
        is ApiResult.DecodeFailure,
        -> null
    }

/** The service's own sentence on any failure that can carry one, else null. */
internal val ApiResult.Failure.serverMessage: String?
    get() = when (this) {
        is ApiResult.Forbidden -> message
        is ApiResult.NotFound -> message
        is ApiResult.RateLimited -> message
        is ApiResult.HttpFailure -> message
        is ApiResult.RegionsDegraded -> message
        is ApiResult.Unauthorized,
        is ApiResult.NetworkFailure,
        is ApiResult.DecodeFailure,
        -> null
    }

/** A 5xx, or the service's own `server_error` code. */
internal val ApiResult.Failure.isServerError: Boolean
    get() = code == "server_error" || (this is ApiResult.HttpFailure && status >= HTTP_SERVER_ERROR)

/**
 * The service's sentence for [failure], in the reader's language: the catalog's for its code, else
 * the service's own words, else [fallback].
 */
internal fun serviceSentence(failure: ApiResult.Failure, fallback: Int): UiText {
    val message = failure.serverMessage?.takeIf { it.isNotBlank() } ?: return UiText.Resource(fallback)
    return UiText.Server(message, failure.code, fallback = fallback)
}

/**
 * What a refused re-authentication says, the same sentences as the iOS app's.
 *
 * ⛔ BRANCHED ON THE CODE, NEVER ON THE MESSAGE. A code this build does not know shows the service's
 * own sentence, and only then a fallback.
 */
internal fun reauthFailure(failure: ApiResult.Failure, sso: Boolean): UiText = when {
    failure is ApiResult.NetworkFailure -> UiText.Resource(R.string.reauth_error_offline_not_confirmed)
    failure.code == "incorrect_password" -> UiText.Resource(R.string.reauth_error_wrong_password)
    failure.code == "mfa_code_invalid" -> UiText.Resource(R.string.reauth_error_wrong_code)
    failure.code == "reauth_failed" ->
        UiText.Resource(if (sso) R.string.reauth_error_wrong_account else R.string.reauth_error_not_confirmed)
    failure.code == "rate_limited" || failure is ApiResult.RateLimited ->
        UiText.Resource(R.string.reauth_error_too_many_attempts)
    failure is ApiResult.Unauthorized -> UiText.Resource(R.string.failure_signed_out)
    failure is ApiResult.DecodeFailure || failure.isServerError -> UiText.Resource(R.string.reauth_error_not_confirmed)
    else -> serviceSentence(failure, R.string.reauth_error_not_confirmed)
}

/** What an SSO attempt that did not end in a proof says, or null for a cancellation (nothing is shown). */
internal fun ssoFailure(outcome: SsoReauthOutcome): UiText? = when (outcome) {
    is SsoReauthOutcome.Proved, SsoReauthOutcome.Cancelled -> null
    SsoReauthOutcome.StateMismatch, SsoReauthOutcome.NoCode -> UiText.Resource(
        R.string.reauth_error_sign_in_not_verified
    )
    SsoReauthOutcome.CouldNotOpen -> UiText.Resource(R.string.reauth_error_sheet_did_not_open)
    is SsoReauthOutcome.Refused -> reauthFailure(outcome.failure, sso = true)
}

private const val HTTP_SERVER_ERROR = 500
