package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.AccountDeleteResponse
import com.distronode.districtai.core.model.MfaConfirmResponse
import com.distronode.districtai.core.model.MfaDisableResponse
import com.distronode.districtai.core.model.MfaEnrollResponse
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.model.NativeReauthResponse
import kotlinx.serialization.json.JsonPrimitive

/**
 * The signed-in account's own security: a fresh re-authentication for one action, and two-step
 * verification. The same routes and wire values as district-core-swift's `nativeReauth*` and
 * `mfa*` endpoints.
 *
 * ⛔ EVERY CHANGE NEEDS A FRESH `2fa-change` PROOF FIRST ([nativeReauthSso] or
 * [nativeReauthPassword] with [NativeReauthAction.TWO_FACTOR_CHANGE]). The proof lives on the
 * service and is keyed on this installation's bearer, so nothing here carries it; without one the
 * service answers 403 `reauth_required`. [mfaEnroll] checks it without spending it; [mfaConfirm] and
 * [mfaDisable] spend it.
 *
 * ⚠️ UNDER `/api/auth/`, A PUBLIC PREFIX IN THE SERVICE'S PROXY, and every route here is
 * `requireAuth`: each takes this app's bearer like any district route, so they ride
 * [DistrictApiClient] rather than the unauthenticated `NativeAuthApi`.
 */
interface AccountSecurityApi {

    /**
     * Exchanges the code the re-authentication page handed back for a proof. ⚠️ The service also
     * checks the code was issued to this installation (by the SHA-256 of its id) and this account.
     */
    suspend fun nativeReauthSso(
        action: NativeReauthAction,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): ApiResult<NativeReauthResponse>

    /**
     * A proof from the account's password, and its authenticator code when two-step verification is
     * on.
     *
     * ⛔ THE FIRST TRY WITHOUT A CODE IS ANSWERED 401 `MFA_REQUIRED`, AND THAT 401 IS NOT THE SESSION.
     * It comes back as [ApiResult.HttpFailure] with that code, and the password is not sent twice.
     */
    suspend fun nativeReauthPassword(
        action: NativeReauthAction,
        password: String,
        code: String?,
    ): ApiResult<NativeReauthResponse>

    /** Whether the factor is on and how many recovery codes are left. ⚠️ The one route here that needs no proof. */
    suspend fun mfaStatus(): ApiResult<MfaStatusResponse>

    /** Mints a new authenticator secret. ⚠️ No body: the route reads none. */
    suspend fun mfaEnroll(): ApiResult<MfaEnrollResponse>

    /** Proves the authenticator works with one of its codes, which turns the factor on. */
    suspend fun mfaConfirm(totp: String): ApiResult<MfaConfirmResponse>

    /**
     * Turns the factor off. ⛔ NO BODY AND NO PASSWORD: for an app the `2fa-change` proof takes the
     * password's place, which is also how an account without a password turns it off.
     */
    suspend fun mfaDisable(): ApiResult<MfaDisableResponse>

    /**
     * Deletes the signed-in account, spending a `delete-account` proof.
     *
     * ⛔ [confirmation] IS THE ACCOUNT'S EMAIL ADDRESS, TYPED: the service compares it without case
     * before it spends the proof, so a typo costs nothing (`confirmation_mismatch`). ⚠️ A DELETE WITH A
     * JSON BODY, the website's own route.
     */
    suspend fun deleteAccount(confirmation: String): ApiResult<AccountDeleteResponse>
}

/** [AccountSecurityApi] over [DistrictApiClient]. */
class HttpAccountSecurityApi(private val client: DistrictApiClient) : AccountSecurityApi {

    override suspend fun nativeReauthSso(
        action: NativeReauthAction,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): ApiResult<NativeReauthResponse> =
        client.send(
            method = "POST",
            segments = AccountSecurityPaths.REAUTH_SSO,
            serializer = NativeReauthResponse.serializer(),
            body = jsonObjectOf(
                "action" to JsonPrimitive(action.wire),
                "code" to JsonPrimitive(code),
                "codeVerifier" to JsonPrimitive(codeVerifier),
                "redirectUri" to JsonPrimitive(redirectUri),
            ),
        )

    override suspend fun nativeReauthPassword(
        action: NativeReauthAction,
        password: String,
        code: String?,
    ): ApiResult<NativeReauthResponse> =
        client.send(
            method = "POST",
            segments = AccountSecurityPaths.REAUTH_PASSWORD,
            serializer = NativeReauthResponse.serializer(),
            body = jsonObjectOf(
                "action" to JsonPrimitive(action.wire),
                "password" to JsonPrimitive(password),
                "code" to code?.let(::JsonPrimitive),
            ),
            challenges = MFA_CHALLENGE,
        )

    override suspend fun mfaStatus(): ApiResult<MfaStatusResponse> =
        client.get(segments = AccountSecurityPaths.MFA, serializer = MfaStatusResponse.serializer())

    override suspend fun mfaEnroll(): ApiResult<MfaEnrollResponse> =
        client.send(
            method = "POST",
            segments = AccountSecurityPaths.MFA_ENROLL,
            serializer = MfaEnrollResponse.serializer()
        )

    override suspend fun mfaConfirm(totp: String): ApiResult<MfaConfirmResponse> =
        client.send(
            method = "POST",
            segments = AccountSecurityPaths.MFA_CONFIRM,
            serializer = MfaConfirmResponse.serializer(),
            body = jsonObjectOf("totp" to JsonPrimitive(totp)),
        )

    override suspend fun mfaDisable(): ApiResult<MfaDisableResponse> =
        client.send(
            method = "POST",
            segments = AccountSecurityPaths.MFA_DISABLE,
            serializer = MfaDisableResponse.serializer()
        )

    override suspend fun deleteAccount(confirmation: String): ApiResult<AccountDeleteResponse> =
        client.send(
            method = "DELETE",
            segments = AccountSecurityPaths.DELETE_ACCOUNT,
            serializer = AccountDeleteResponse.serializer(),
            body = jsonObjectOf("confirmation" to JsonPrimitive(confirmation)),
        )

    private companion object {
        /** The one 401 on these routes that answers the request rather than the session. */
        val MFA_CHALLENGE = setOf("MFA_REQUIRED")
    }
}

private object AccountSecurityPaths {
    private val AUTH = listOf("api", "auth")
    private val REAUTH = AUTH + listOf("native", "reauth")

    val REAUTH_SSO = REAUTH + "sso"
    val REAUTH_PASSWORD = REAUTH + "password"

    /** ⚠️ Under `auth/mfa`, not `auth/native`: the website's own routes, which take a native proof. */
    val MFA = AUTH + "mfa"
    val MFA_ENROLL = MFA + "enroll"
    val MFA_CONFIRM = MFA + "confirm"
    val MFA_DISABLE = MFA + "disable"

    /** ⚠️ Under `auth`, not `auth/native`: the website's own route, which takes a native proof. */
    val DELETE_ACCOUNT = AUTH + "delete-account"
}
