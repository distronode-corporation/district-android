// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MfaConfirmResponse
import com.distronode.districtai.core.model.MfaEnrollResponse
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.core.network.AccountSecurityApi
import com.distronode.districtai.core.network.ApiResult

/**
 * Reading two-step verification, and turning it on and off, from the app. The Kotlin mirror of
 * district-core-swift's `TwoStepSettingsRepository`.
 *
 * ⛔ A FRESH `2fa-change` PROOF COMES BEFORE EVERY CHANGE ([status] needs none), from
 * [NativeReauthRepository]. [enroll] checks it without spending it, so a restarted enrolment needs
 * no second re-authentication; [confirm] and [disable] spend it. Without one each answers 403
 * `reauth_required`; a credential that is not this app's bearer answers 403
 * `browser_session_required`. Other refusals keep the service's `code` (`MFA_ALREADY_ENABLED`,
 * `MFA_NOT_STARTED`, `MFA_NOT_PENDING`, `mfa_code_invalid`, `rate_limited`).
 */
class TwoStepSettingsRepository(private val api: AccountSecurityApi) {

    /**
     * Whether the factor is on and how many recovery codes are left. ⚠️ A 200 whose `enrolled`
     * disagrees with whether `enrolledAt` is set is a decoding failure, as on iOS.
     */
    suspend fun status(): ApiResult<MfaStatusResponse> = when (val result = api.mfaStatus()) {
        is ApiResult.Success -> affirm("MfaStatusResponse", result.value.enrolled == (result.value.enrolledAt != null))
            ?: result
        is ApiResult.Failure -> result
    }

    /**
     * Starts enrolment: a new secret, its `otpauth://` URI and a QR code of it. The factor is not on
     * until [confirm]; starting again replaces an unconfirmed secret.
     */
    suspend fun enroll(): ApiResult<MfaEnrollResponse> = api.mfaEnroll()

    /** Turns the factor on with a code from the authenticator. Answers the recovery codes, shown once. */
    suspend fun confirm(totp: String): ApiResult<MfaConfirmResponse> = when (val result = api.mfaConfirm(totp)) {
        is ApiResult.Success -> affirm("MfaConfirmResponse", result.value.enrolled) ?: result
        is ApiResult.Failure -> result
    }

    /** Turns the factor off and drops its recovery codes. */
    suspend fun disable(): ApiResult<Unit> = when (val result = api.mfaDisable()) {
        is ApiResult.Success -> affirm("MfaDisableResponse", !result.value.enrolled) ?: ApiResult.Success(Unit)
        is ApiResult.Failure -> result
    }

    private fun affirm(envelope: String, holds: Boolean): ApiResult.DecodeFailure? =
        if (holds) {
            null
        } else {
            ApiResult.DecodeFailure(
                cause = IllegalStateException("$envelope contradicts itself"),
                bodyPreview = "$envelope{inconsistent}",
            )
        }
}
