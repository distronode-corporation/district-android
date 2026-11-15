package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.model.NativeReauthResponse
import com.distronode.districtai.core.network.AccountSecurityApi
import com.distronode.districtai.core.network.ApiResult

/**
 * A fresh re-authentication for one action, and deleting the account with it. The Kotlin mirror of district-core-swift's
 * `NativeReauthRepository`.
 *
 * ⛔ EVERY ACCOUNT TYPE RE-AUTHENTICATES THE SAME WAY: a fresh sign-in at its provider, or its
 * password (with the authenticator code once the service asks for it). A refusal keeps the
 * service's `code` (`MFA_REQUIRED`, `incorrect_password`, `reauth_failed`, `NO_PASSWORD_SET`,
 * `mfa_code_invalid`, `rate_limited`) for the caller to act on.
 *
 * Each success answers how many seconds the proof stands.
 */
class NativeReauthRepository(private val api: AccountSecurityApi) {

    /** Exchanges the re-authentication page's code for a proof. */
    suspend fun reauthenticate(
        action: NativeReauthAction,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): ApiResult<Int> = proof(action, api.nativeReauthSso(action, code, codeVerifier, redirectUri))

    /** Re-authenticates with the account's password, and its authenticator code once asked. */
    suspend fun reauthenticate(
        action: NativeReauthAction,
        password: String,
        code: String? = null,
    ): ApiResult<Int> = proof(action, api.nativeReauthPassword(action, password, code))

    /**
     * Deletes the signed-in account with the typed email address. Answers whether vendor-held
     * support data is still being erased (the account itself is gone either way). ⚠️ A 200 that does
     * not say the account was deleted is a decoding failure, never a deletion.
     */
    suspend fun deleteAccount(confirmation: String): ApiResult<Boolean> =
        when (val result = api.deleteAccount(confirmation)) {
            is ApiResult.Success -> {
                val gone = result.value.success && result.value.accountDeleted
                rejectedEnvelope("AccountDeleteResponse", gone) ?: ApiResult.Success(result.value.externalErasurePending)
            }
            is ApiResult.Failure -> result
        }

    /** ⚠️ A PROOF FOR ANOTHER ACTION IS NOT THIS ONE'S, so it is refused rather than trusted. */
    private fun proof(action: NativeReauthAction, result: ApiResult<NativeReauthResponse>): ApiResult<Int> =
        when (result) {
            is ApiResult.Success -> rejectedEnvelope(ENVELOPE, result.value.success)
                ?: if (result.value.action == action.wire) {
                    ApiResult.Success(result.value.expiresInSeconds)
                } else {
                    ApiResult.DecodeFailure(
                        cause = IllegalStateException("$ENVELOPE is for another action"),
                        bodyPreview = "$ENVELOPE{action!=${action.wire}}",
                    )
                }
            is ApiResult.Failure -> result
        }

    private companion object {
        const val ENVELOPE = "NativeReauthResponse"
    }
}
