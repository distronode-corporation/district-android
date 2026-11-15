package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.MfaConfirmResponse
import com.distronode.districtai.core.model.MfaDisableResponse
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.model.NativeReauthResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeAccountSecurityApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The re-authentication and two-step repositories: what they accept from a 200 and what they pass
 * through untouched.
 */
class AccountSecurityRepositoriesTest {

    private val api = FakeAccountSecurityApi()
    private val reauth = NativeReauthRepository(api)
    private val twoStep = TwoStepSettingsRepository(api)

    private fun proof(success: Boolean = true, action: String = "2fa-change") =
        ApiResult.Success(NativeReauthResponse(success = success, action = action, expiresInSeconds = 120))

    // ── Re-authentication ────────────────────────────────────────────────────

    @Test
    fun `a password proof for this action answers how long it stands`() = runTest {
        api.passwordResults += proof()

        assertEquals(ApiResult.Success(120), reauth.reauthenticate(NativeReauthAction.TWO_FACTOR_CHANGE, "pw"))
        assertEquals(Triple(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", null), api.passwordAttempts.single())
    }

    @Test
    fun `an sso proof passes the code, verifier and redirect through`() = runTest {
        api.ssoResult = proof(action = "delete-account")

        val result = reauth.reauthenticate(NativeReauthAction.DELETE_ACCOUNT, "c", "v", "districtai://auth")

        assertEquals(ApiResult.Success(120), result)
        assertEquals(listOf("delete-account", "c", "v", "districtai://auth"), api.ssoExchanges.single())
    }

    @Test
    fun `a proof for another action is refused, not trusted`() = runTest {
        api.passwordResults += proof(action = "delete-account")

        val result = reauth.reauthenticate(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", "123456")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a 200 that does not affirm success is refused`() = runTest {
        api.passwordResults += proof(success = false)

        assertTrue(reauth.reauthenticate(NativeReauthAction.TWO_FACTOR_CHANGE, "pw") is ApiResult.DecodeFailure)
    }

    @Test
    fun `a refusal keeps the service's code`() = runTest {
        val refusal = ApiResult.HttpFailure(401, "Enter the code", "MFA_REQUIRED")
        api.passwordResults += refusal

        assertEquals(refusal, reauth.reauthenticate(NativeReauthAction.TWO_FACTOR_CHANGE, "pw"))
    }

    // ── Two-step settings ────────────────────────────────────────────────────

    @Test
    fun `the status is passed through when it agrees with itself`() = runTest {
        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_ON)
        assertEquals(ApiResult.Success(FakeAccountSecurityApi.STATUS_ON), twoStep.status())

        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_OFF)
        assertEquals(ApiResult.Success(FakeAccountSecurityApi.STATUS_OFF), twoStep.status())
    }

    @Test
    fun `a status that is on without a date, or off with one, is a decoding failure`() = runTest {
        api.statusResult = ApiResult.Success(MfaStatusResponse(true, null, 3, true))
        assertTrue(twoStep.status() is ApiResult.DecodeFailure)

        api.statusResult = ApiResult.Success(MfaStatusResponse(false, "2026-01-01T00:00:00.000Z", 0, true))
        assertTrue(twoStep.status() is ApiResult.DecodeFailure)
    }

    @Test
    fun `a failed status read is passed through`() = runTest {
        val failure = ApiResult.HttpFailure(500, "x")
        api.statusResult = failure

        assertEquals(failure, twoStep.status())
    }

    @Test
    fun `enrolling passes the secret through`() = runTest {
        assertEquals(ApiResult.Success(FakeAccountSecurityApi.ENROLLMENT), twoStep.enroll())
    }

    @Test
    fun `a confirmation must say it is on`() = runTest {
        assertTrue(twoStep.confirm("123456") is ApiResult.Success)
        assertEquals(listOf("123456"), api.confirmCodes)

        api.confirmResult = ApiResult.Success(MfaConfirmResponse(enrolled = false, backupCodes = emptyList()))
        assertTrue(twoStep.confirm("123456") is ApiResult.DecodeFailure)

        val refusal = ApiResult.HttpFailure(400, "bad", "mfa_code_invalid")
        api.confirmResult = refusal
        assertEquals(refusal, twoStep.confirm("000000"))
    }

    @Test
    fun `turning it off must say it is off`() = runTest {
        assertEquals(ApiResult.Success(Unit), twoStep.disable())

        api.disableResult = ApiResult.Success(MfaDisableResponse(enrolled = true))
        assertTrue(twoStep.disable() is ApiResult.DecodeFailure)

        val refusal = ApiResult.Forbidden("again", "reauth_required")
        api.disableResult = refusal
        assertEquals(refusal, twoStep.disable())
    }
}
