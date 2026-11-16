package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.AccountDeleteResponse
import com.distronode.districtai.core.model.MfaConfirmResponse
import com.distronode.districtai.core.model.MfaDisableResponse
import com.distronode.districtai.core.model.MfaEnrollResponse
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.model.NativeReauthResponse
import com.distronode.districtai.core.network.AccountSecurityApi
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

/**
 * A scripted [AccountSecurityApi] that records what it was asked.
 *
 * ⚠️ [gate], WHEN SET, HOLDS EVERY CALL until it completes, so a test can tap twice while the first
 * request is still in flight and see one call.
 */
class FakeAccountSecurityApi : AccountSecurityApi {

    var statusResult: ApiResult<MfaStatusResponse> = ApiResult.Success(STATUS_OFF)
    var enrollResult: ApiResult<MfaEnrollResponse> = ApiResult.Success(ENROLLMENT)
    var confirmResult: ApiResult<MfaConfirmResponse> =
        ApiResult.Success(MfaConfirmResponse(enrolled = true, backupCodes = listOf("aaaa-bbbb", "cccc-dddd")))
    var disableResult: ApiResult<MfaDisableResponse> = ApiResult.Success(MfaDisableResponse(enrolled = false))
    var deleteResult: ApiResult<AccountDeleteResponse> = ApiResult.Success(
        AccountDeleteResponse(success = true, accountDeleted = true, externalErasurePending = false),
    )

    /** Answered to every password attempt in turn; the last one repeats. */
    val passwordResults: MutableList<ApiResult<NativeReauthResponse>> = mutableListOf()
    var ssoResult: ApiResult<NativeReauthResponse>? = null

    var gate: CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()
    val passwordAttempts = mutableListOf<Triple<NativeReauthAction, String, String?>>()
    val ssoExchanges = mutableListOf<List<String>>()
    val confirmCodes = mutableListOf<String>()
    val deleteConfirmations = mutableListOf<String>()

    private suspend fun <T> answer(name: String, result: ApiResult<T>): ApiResult<T> {
        calls += name
        gate?.await()
        return result
    }

    override suspend fun nativeReauthSso(
        action: NativeReauthAction,
        code: String,
        codeVerifier: String,
        redirectUri: String,
    ): ApiResult<NativeReauthResponse> {
        ssoExchanges += listOf(action.wire, code, codeVerifier, redirectUri)
        return answer("reauthSso", ssoResult ?: proof(action))
    }

    override suspend fun nativeReauthPassword(
        action: NativeReauthAction,
        password: String,
        code: String?,
    ): ApiResult<NativeReauthResponse> {
        passwordAttempts += Triple(action, password, code)
        val result = when {
            passwordResults.isEmpty() -> proof(action)
            passwordResults.size == 1 -> passwordResults.first()
            else -> passwordResults.removeAt(0)
        }
        return answer("reauthPassword", result)
    }

    override suspend fun mfaStatus() = answer("status", statusResult)

    override suspend fun mfaEnroll() = answer("enroll", enrollResult)

    override suspend fun mfaConfirm(totp: String): ApiResult<MfaConfirmResponse> {
        confirmCodes += totp
        return answer("confirm", confirmResult)
    }

    override suspend fun mfaDisable() = answer("disable", disableResult)

    override suspend fun deleteAccount(confirmation: String): ApiResult<AccountDeleteResponse> {
        deleteConfirmations += confirmation
        return answer("delete", deleteResult)
    }

    companion object {
        val STATUS_OFF =
            MfaStatusResponse(enrolled = false, enrolledAt = null, backupCodesRemaining = 0, canDisable = true)
        val STATUS_ON = MfaStatusResponse(
            enrolled = true,
            enrolledAt = "2026-10-01T12:00:00.000Z",
            backupCodesRemaining = 7,
            canDisable = true,
        )

        /** ⚠️ A made-up key in base32's alphabet, never a real one. */
        val ENROLLMENT = MfaEnrollResponse(
            secret = "AAAABBBBCCCCDDDD",
            otpauthUri = "otpauth://totp/Distronode:test?secret=AAAABBBBCCCCDDDD&issuer=Distronode",
            qrDataUrl = "data:image/png;base64,iVBORw0KGgo=",
        )

        fun proof(action: NativeReauthAction): ApiResult<NativeReauthResponse> =
            ApiResult.Success(NativeReauthResponse(success = true, action = action.wire, expiresInSeconds = 300))
    }
}
