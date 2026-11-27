// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.account

import com.distronode.districtai.R
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText

/**
 * Where a refused password re-authentication leaves the deletion screen.
 *
 * ⚠️ `MFA_REQUIRED` IS NOT A FAILURE: the account has two-step verification, and the code goes with
 * the password on the next try. `NO_PASSWORD_SET` goes back to the providers.
 */
internal fun deletePasswordRefused(state: DeleteAccountUiState, failure: ApiResult.Failure): DeleteAccountUiState =
    when (failure.code) {
        "MFA_REQUIRED" -> state.copy(step = DeleteAccountStep.Password(needsCode = true))
        "NO_PASSWORD_SET" -> state.copy(
            step = DeleteAccountStep.Explain,
            failure = DeleteAccountFailure(null, UiText.Resource(R.string.reauth_error_no_password)),
        )
        "mfa_code_invalid" -> state.copy(code = "", failure = DeleteAccountFailure(null, reauthFailure(failure, false)))
        else -> state.copy(failure = DeleteAccountFailure(null, reauthFailure(failure, sso = false)))
    }

/**
 * What a refused deletion says, the iOS app's rules: a headline that says nothing was deleted, then
 * why, exactly as the web's delete does.
 *
 * ⛔ A TIMEOUT, A DROPPED CONNECTION OR AN UNREADABLE 2xx CAN COME AFTER THE SERVICE DELETED THE
 * ACCOUNT, so those never claim either way. ⛔ NOR DOES A 401: a retry after a delete lost in
 * transport is answered 401 when the first one did delete the account (the session went with it).
 */
internal fun deleteFailure(failure: ApiResult.Failure): DeleteAccountFailure {
    val notDeleted = UiText.Resource(R.string.delete_account_not_deleted)
    return when {
        failure is ApiResult.NetworkFailure ->
            DeleteAccountFailure(null, UiText.Resource(R.string.delete_account_error_offline_outcome_unknown))
        failure is ApiResult.DecodeFailure ->
            DeleteAccountFailure(null, UiText.Resource(R.string.delete_account_error_outcome_unknown))
        failure is ApiResult.Unauthorized -> DeleteAccountFailure(null, UiText.Resource(R.string.failure_signed_out))
        failure.code == "confirmation_mismatch" ->
            DeleteAccountFailure(null, UiText.Resource(R.string.delete_account_error_email_mismatch))
        failure.code == "rate_limited" || failure is ApiResult.RateLimited ->
            DeleteAccountFailure(notDeleted, UiText.Resource(R.string.reauth_error_too_many_attempts))
        // ⛔ A 5xx CAN COME AFTER THE ACCOUNT WAS DELETED: the route goes on after the delete
        // (revoking provider grants, erasing support data), and a gateway can time out after it
        // committed. So it never claims either way, unlike the iOS app. The one 5xx the service
        // sends before deleting anything is `service_unavailable`, with its own sentence (below).
        failure.isServerError && failure.code != "service_unavailable" ->
            DeleteAccountFailure(null, UiText.Resource(R.string.delete_account_error_server_outcome_unknown))
        else -> DeleteAccountFailure(
            notDeleted,
            serviceSentence(failure, R.string.delete_account_error_try_again_shortly),
        )
    }
}
