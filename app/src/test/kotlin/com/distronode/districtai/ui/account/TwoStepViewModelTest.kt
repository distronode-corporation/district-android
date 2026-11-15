package com.distronode.districtai.ui.account

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.distronode.districtai.R
import com.distronode.districtai.core.data.NativeReauthRepository
import com.distronode.districtai.core.data.TwoStepSettingsRepository
import com.distronode.districtai.core.model.MfaConfirmResponse
import com.distronode.districtai.core.model.MfaStatusResponse
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeAccountSecurityApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The two-step model's steps, refusals and secrets: the iOS app's `TwoStepModel` rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoStepViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeAccountSecurityApi()
    private var ssoOutcome: SsoReauthOutcome = SsoReauthOutcome.Proved(300)
    private val ssoProviders = mutableListOf<ReauthProvider>()

    private fun model(): TwoStepViewModel = TwoStepViewModel(
        settings = TwoStepSettingsRepository(api),
        reauth = NativeReauthRepository(api),
        sso = { provider ->
            ssoProviders += provider
            ssoOutcome
        },
        now = { Instant.parse("2026-11-15T12:00:00.123456Z") },
    )

    private fun res(id: Int) = UiText.Resource(id)

    private val offline = ApiResult.NetworkFailure(IOException("offline"))
    private val undecodable = ApiResult.DecodeFailure(IllegalStateException("x"), "")

    /** A model on the overview, with the factor off (or on). */
    private fun kotlinx.coroutines.test.TestScope.overview(on: Boolean = false): TwoStepViewModel {
        val status = if (on) FakeAccountSecurityApi.STATUS_ON else FakeAccountSecurityApi.STATUS_OFF
        api.statusResult = ApiResult.Success(status)
        return model().also { advanceUntilIdle() }
    }

    /** A model on the scan step with a new secret. */
    private fun kotlinx.coroutines.test.TestScope.scanning(): TwoStepViewModel {
        val vm = overview()
        vm.begin(TwoStepIntent.TURN_ON)
        vm.reauthenticate(ReauthProvider.GOOGLE)
        advanceUntilIdle()
        assertEquals(TwoStepStep.Scan, vm.state.value.step)
        return vm
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    @Test
    fun `the first read lands on the overview`() = runTest(dispatcher) {
        val vm = overview(on = true)

        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertEquals(FakeAccountSecurityApi.STATUS_ON, vm.state.value.status)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun `a first read that fails lands on retry, with a sentence per kind of failure`() = runTest(dispatcher) {
        val cases = listOf(
            offline to res(R.string.two_step_error_offline_load),
            ApiResult.Unauthorized(null) to res(R.string.failure_signed_out),
            undecodable to res(R.string.two_step_error_load),
            ApiResult.HttpFailure(503, "down") to res(R.string.two_step_error_load),
            ApiResult.HttpFailure(400, "Internal", "server_error") to res(R.string.two_step_error_load),
            ApiResult.NotFound("Account not found", "account_not_found") to
                UiText.Server("Account not found", "account_not_found", fallback = R.string.two_step_error_load),
            ApiResult.RegionsDegraded("", emptyList()) to res(R.string.two_step_error_load),
        )
        for ((failure, expected) in cases) {
            api.statusResult = failure
            val vm = model()
            advanceUntilIdle()
            assertEquals(TwoStepStep.LoadFailed, vm.state.value.step)
            assertEquals(expected, vm.state.value.failure)
        }
    }

    @Test
    fun `retry reads again, once however often it is tapped`() = runTest(dispatcher) {
        api.statusResult = offline
        val vm = model()
        advanceUntilIdle()

        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_OFF)
        api.gate = CompletableDeferred()
        vm.load()
        vm.load()
        runCurrent()
        assertTrue(vm.state.value.busy)
        api.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("status", "status"), api.calls)
        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertNull(vm.state.value.failure)
    }

    // ── Starting a change ───────────────────────────────────────────────────

    @Test
    fun `the status decides which change is offered`() = runTest(dispatcher) {
        val vm = overview(on = false)

        vm.begin(TwoStepIntent.TURN_OFF)
        assertEquals(TwoStepStep.Overview, vm.state.value.step)

        vm.begin(TwoStepIntent.TURN_ON)
        assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_ON), vm.state.value.step)

        // Not on the overview any more: a second begin does nothing.
        vm.begin(TwoStepIntent.TURN_ON)
        assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_ON), vm.state.value.step)
    }

    @Test
    fun `nothing starts before the status is read, or while a read is in flight`() = runTest(dispatcher) {
        api.statusResult = offline
        val failed = model()
        advanceUntilIdle()
        failed.begin(TwoStepIntent.TURN_ON)
        assertEquals(TwoStepStep.LoadFailed, failed.state.value.step)

        val vm = overview()
        api.gate = CompletableDeferred()
        vm.load()
        runCurrent()
        vm.begin(TwoStepIntent.TURN_ON)
        vm.usePassword()
        vm.back()
        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        api.gate!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `back walks one step at a time and drops an unconfirmed secret`() = runTest(dispatcher) {
        val vm = overview()
        vm.back()
        assertEquals(TwoStepStep.Overview, vm.state.value.step)

        vm.begin(TwoStepIntent.TURN_ON)
        vm.usePassword()
        vm.onField(TwoStepField.PASSWORD, "pw")
        assertEquals(TwoStepStep.Password(TwoStepIntent.TURN_ON, needsCode = false), vm.state.value.step)
        vm.back()
        assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_ON), vm.state.value.step)
        assertEquals("", vm.state.value.password)
        vm.back()
        assertEquals(TwoStepStep.Overview, vm.state.value.step)

        val scan = scanning()
        scan.onField(TwoStepField.TOTP, "12")
        scan.back()
        assertEquals(TwoStepStep.Overview, scan.state.value.step)
        assertNull(scan.state.value.enrollment)
        assertEquals("", scan.state.value.totp)
    }

    @Test
    fun `use my password works from the ways to confirm only`() = runTest(dispatcher) {
        val vm = overview()
        vm.usePassword()
        assertEquals(TwoStepStep.Overview, vm.state.value.step)
    }

    // ── Signing in again with a provider ────────────────────────────────────

    @Test
    fun `a provider sign-in that proves it is you starts the enrolment`() = runTest(dispatcher) {
        val vm = scanning()

        assertEquals(listOf(ReauthProvider.GOOGLE), ssoProviders)
        val enrollment = vm.state.value.enrollment!!
        assertEquals(FakeAccountSecurityApi.ENROLLMENT.secret, enrollment.secret)
        assertEquals(FakeAccountSecurityApi.ENROLLMENT.otpauthUri, enrollment.otpauthUri)
        assertEquals("iVBORw0KGgo=", enrollment.qrPngBase64)
        assertTrue(vm.state.value.holdsSecret)
    }

    @Test
    fun `a provider sign-in only works from the ways to confirm`() = runTest(dispatcher) {
        val vm = overview()
        vm.reauthenticate(ReauthProvider.APPLE)
        advanceUntilIdle()
        assertTrue(ssoProviders.isEmpty())
    }

    @Test
    fun `each way a provider sign-in can fail says so, and a cancellation says nothing`() = runTest(dispatcher) {
        val cases = listOf(
            SsoReauthOutcome.Cancelled to null,
            SsoReauthOutcome.StateMismatch to res(R.string.reauth_error_sign_in_not_verified),
            SsoReauthOutcome.NoCode to res(R.string.reauth_error_sign_in_not_verified),
            SsoReauthOutcome.CouldNotOpen to res(R.string.reauth_error_sheet_did_not_open),
            SsoReauthOutcome.Refused(ApiResult.Forbidden("x", "reauth_failed")) to
                res(R.string.reauth_error_wrong_account),
        )
        for ((outcome, expected) in cases) {
            ssoOutcome = outcome
            val vm = overview()
            vm.begin(TwoStepIntent.TURN_ON)
            vm.reauthenticate(ReauthProvider.MICROSOFT)
            advanceUntilIdle()
            assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_ON), vm.state.value.step)
            assertEquals(expected, vm.state.value.failure)
        }
    }

    // ── The password ────────────────────────────────────────────────────────

    @Test
    fun `the password asks for the code when the service does, then sends both`() = runTest(dispatcher) {
        val vm = overview(on = true)
        vm.begin(TwoStepIntent.TURN_OFF)
        vm.usePassword()
        assertFalse("no password yet", vm.state.value.canSubmitPassword)
        vm.onField(TwoStepField.PASSWORD, "pw")
        api.passwordResults += ApiResult.HttpFailure(401, "Enter the code", "MFA_REQUIRED")
        api.passwordResults += FakeAccountSecurityApi.proof(NativeReauthAction.TWO_FACTOR_CHANGE)

        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(TwoStepStep.Password(TwoStepIntent.TURN_OFF, needsCode = true), vm.state.value.step)
        assertNull("asking for the code is not a failure", vm.state.value.failure)
        assertEquals("", vm.state.value.password)

        vm.onField(TwoStepField.PASSWORD, "pw")
        assertFalse("the code is now needed too", vm.state.value.canSubmitPassword)
        vm.onField(TwoStepField.CODE, " 123456 ")
        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_OFF)
        vm.submitPassword()
        advanceUntilIdle()

        assertEquals(Triple(NativeReauthAction.TWO_FACTOR_CHANGE, "pw", "123456"), api.passwordAttempts.last())
        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertEquals(res(R.string.two_step_turned_off), vm.state.value.notice)
        assertFalse(vm.state.value.status!!.enrolled)
        assertEquals(listOf("status", "reauthPassword", "reauthPassword", "disable", "status"), api.calls)
    }

    @Test
    fun `a password refusal keeps the step and says why`() = runTest(dispatcher) {
        val cases = listOf(
            ApiResult.Forbidden("no", "incorrect_password") to res(R.string.reauth_error_wrong_password),
            ApiResult.HttpFailure(400, "bad", "mfa_code_invalid") to res(R.string.reauth_error_wrong_code),
            ApiResult.Forbidden("no", "reauth_failed") to res(R.string.reauth_error_not_confirmed),
            ApiResult.RateLimited("slow", code = "rate_limited") to res(R.string.reauth_error_too_many_attempts),
            ApiResult.RateLimited("slow") to res(R.string.reauth_error_too_many_attempts),
            offline to res(R.string.reauth_error_offline_not_confirmed),
            ApiResult.Unauthorized(null) to res(R.string.failure_signed_out),
            undecodable to res(R.string.reauth_error_not_confirmed),
            ApiResult.HttpFailure(500, "Internal server error", "server_error") to
                res(R.string.reauth_error_not_confirmed),
            ApiResult.HttpFailure(400, "Something new", "brand_new") to
                UiText.Server("Something new", "brand_new", fallback = R.string.reauth_error_not_confirmed),
            ApiResult.HttpFailure(400, " ") to res(R.string.reauth_error_not_confirmed),
        )
        for ((failure, expected) in cases) {
            api.passwordResults.clear()
            api.passwordResults += failure
            val vm = overview()
            vm.begin(TwoStepIntent.TURN_ON)
            vm.usePassword()
            vm.onField(TwoStepField.PASSWORD, "pw")
            vm.onField(TwoStepField.CODE, "999")
            vm.submitPassword()
            advanceUntilIdle()
            assertEquals(failure.toString(), expected, vm.state.value.failure)
            assertEquals(TwoStepStep.Password(TwoStepIntent.TURN_ON, needsCode = false), vm.state.value.step)
            assertEquals("", vm.state.value.password)
        }
    }

    @Test
    fun `a wrong code is cleared, and an account with no password goes back to the providers`() = runTest(dispatcher) {
        val vm = overview(on = true)
        vm.begin(TwoStepIntent.TURN_OFF)
        vm.usePassword()
        vm.onField(TwoStepField.PASSWORD, "pw")
        api.passwordResults += ApiResult.HttpFailure(401, "code", "MFA_REQUIRED")
        vm.submitPassword()
        advanceUntilIdle()

        api.passwordResults.clear()
        api.passwordResults += ApiResult.HttpFailure(400, "bad", "mfa_code_invalid")
        vm.onField(TwoStepField.PASSWORD, "pw")
        vm.onField(TwoStepField.CODE, "111111")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("", vm.state.value.code)

        api.passwordResults.clear()
        api.passwordResults += ApiResult.HttpFailure(409, "none", "NO_PASSWORD_SET")
        vm.onField(TwoStepField.PASSWORD, "pw")
        vm.onField(TwoStepField.CODE, "222222")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_OFF), vm.state.value.step)
        assertEquals(res(R.string.reauth_error_no_password), vm.state.value.failure)
    }

    @Test
    fun `two taps on continue send the password once`() = runTest(dispatcher) {
        val vm = overview()
        vm.begin(TwoStepIntent.TURN_ON)
        vm.usePassword()
        vm.onField(TwoStepField.PASSWORD, "pw")
        api.gate = CompletableDeferred()

        vm.submitPassword()
        vm.submitPassword()
        runCurrent()
        api.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, api.passwordAttempts.size)
    }

    // ── Enrolling, confirming and the codes ─────────────────────────────────

    @Test
    fun `a refused enrolment says why and where it leaves the screen`() = runTest(dispatcher) {
        data class Case(val failure: ApiResult.Failure, val message: UiText, val step: TwoStepStep)
        val reauth = TwoStepStep.Reauth(TwoStepIntent.TURN_ON)
        val cases = listOf(
            Case(offline, res(R.string.two_step_error_offline_nothing_changed), reauth),
            Case(undecodable, res(R.string.two_step_error_try_again), reauth),
            Case(ApiResult.Forbidden("again", "reauth_required"), res(R.string.two_step_error_proof_expired), reauth),
            Case(ApiResult.HttpFailure(500, "x"), res(R.string.two_step_error_try_again), reauth),
            Case(ApiResult.Unauthorized(null), res(R.string.failure_signed_out), reauth),
            Case(
                ApiResult.Forbidden("Only a signed-in app", "browser_session_required"),
                UiText.Server(
                    "Only a signed-in app",
                    "browser_session_required",
                    fallback = R.string.two_step_error_try_again
                ),
                reauth,
            ),
            Case(
                ApiResult.HttpFailure(409, "Already on", "MFA_ALREADY_ENABLED"),
                UiText.Server("Already on", "MFA_ALREADY_ENABLED", fallback = R.string.two_step_error_try_again),
                TwoStepStep.Overview,
            ),
        )
        for (case in cases) {
            api.enrollResult = case.failure
            val vm = overview()
            vm.begin(TwoStepIntent.TURN_ON)
            vm.reauthenticate(ReauthProvider.GOOGLE)
            advanceUntilIdle()
            assertEquals(case.failure.toString(), case.message, vm.state.value.failure)
            assertEquals(case.failure.toString(), case.step, vm.state.value.step)
            assertNull(vm.state.value.enrollment)
        }
    }

    @Test
    fun `a QR code that is not a PNG data URL is left out, and the key still shows`() = runTest(dispatcher) {
        val notPng = FakeAccountSecurityApi.ENROLLMENT.copy(qrDataUrl = "https://example.test/qr")
        api.enrollResult = ApiResult.Success(notPng)
        val vm = scanning()

        assertNull(vm.state.value.enrollment!!.qrPngBase64)
    }

    @Test
    fun `confirming shows the codes once, then reads the status again`() = runTest(dispatcher) {
        val vm = scanning()
        assertFalse(vm.state.value.canConfirm)
        vm.onField(TwoStepField.TOTP, " 654321 ")
        assertTrue(vm.state.value.canConfirm)

        vm.confirm()
        advanceUntilIdle()

        assertEquals(listOf("654321"), api.confirmCodes)
        assertEquals(TwoStepStep.RecoveryCodes, vm.state.value.step)
        assertEquals(listOf("aaaa-bbbb", "cccc-dddd"), vm.state.value.recoveryCodes)
        assertNull("the secret is dropped once confirmed", vm.state.value.enrollment)
        assertEquals(
            MfaStatusResponse(true, "2026-11-15T12:00:00.123Z", 2, true),
            vm.state.value.status,
        )
        assertTrue(vm.state.value.holdsSecret)

        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_ON)
        vm.finishRecoveryCodes()
        advanceUntilIdle()
        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertTrue(vm.state.value.recoveryCodes.isEmpty())
        assertEquals(res(R.string.two_step_turned_on), vm.state.value.notice)
        assertFalse(vm.state.value.holdsSecret)
    }

    @Test
    fun `done with the codes works only on the codes`() = runTest(dispatcher) {
        val vm = overview()
        vm.finishRecoveryCodes()
        advanceUntilIdle()
        assertEquals(listOf("status"), api.calls)
    }

    @Test
    fun `a wrong authenticator code stays on the scan step`() = runTest(dispatcher) {
        val vm = scanning()
        api.confirmResult = ApiResult.HttpFailure(400, "That code is not valid", "mfa_code_invalid")
        vm.onField(TwoStepField.TOTP, "000000")

        vm.confirm()
        advanceUntilIdle()

        assertEquals(TwoStepStep.Scan, vm.state.value.step)
        assertEquals("", vm.state.value.totp)
        assertEquals(
            UiText.Server("That code is not valid", "mfa_code_invalid", fallback = R.string.two_step_error_try_again),
            vm.state.value.failure,
        )
    }

    @Test
    fun `a confirm whose answer is lost never claims either way and reads the status`() = runTest(dispatcher) {
        val vm = scanning()
        api.confirmResult = offline
        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_ON)
        vm.onField(TwoStepField.TOTP, "123456")

        vm.confirm()
        advanceUntilIdle()

        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertEquals(res(R.string.two_step_error_outcome_unknown_on), vm.state.value.failure)
        assertEquals(FakeAccountSecurityApi.STATUS_ON, vm.state.value.status)
    }

    @Test
    fun `when that read fails too, its own failure is the one shown over the last status`() = runTest(dispatcher) {
        val vm = scanning()
        api.confirmResult = undecodable
        api.statusResult = offline
        vm.onField(TwoStepField.TOTP, "123456")

        vm.confirm()
        advanceUntilIdle()

        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertEquals(res(R.string.two_step_error_offline_load), vm.state.value.failure)
    }

    @Test
    fun `an expired proof at confirm goes back to confirming it is you, without the old secret`() = runTest(
        dispatcher
    ) {
        val vm = scanning()
        api.confirmResult = ApiResult.Forbidden("again", "reauth_required")
        vm.onField(TwoStepField.TOTP, "123456")

        vm.confirm()
        advanceUntilIdle()

        assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_ON), vm.state.value.step)
        assertNull(vm.state.value.enrollment)
    }

    @Test
    fun `a confirm that does not say it is on is an unknown outcome`() = runTest(dispatcher) {
        val vm = scanning()
        api.confirmResult = ApiResult.Success(MfaConfirmResponse(enrolled = false, backupCodes = emptyList()))
        vm.onField(TwoStepField.TOTP, "123456")

        vm.confirm()
        advanceUntilIdle()

        assertEquals(res(R.string.two_step_error_outcome_unknown_on), vm.state.value.failure)
    }

    // ── Turning it off ──────────────────────────────────────────────────────

    @Test
    fun `a disable whose answer is lost reads the status again`() = runTest(dispatcher) {
        val vm = overview(on = true)
        api.disableResult = offline
        vm.begin(TwoStepIntent.TURN_OFF)
        vm.reauthenticate(ReauthProvider.GOOGLE)
        advanceUntilIdle()

        assertEquals(TwoStepStep.Overview, vm.state.value.step)
        assertEquals(res(R.string.two_step_error_outcome_unknown_off), vm.state.value.failure)
    }

    @Test
    fun `an expired proof at disable goes back to confirming it is you for turning off`() = runTest(dispatcher) {
        val vm = overview(on = true)
        api.disableResult = ApiResult.Forbidden("again", "reauth_required")
        vm.begin(TwoStepIntent.TURN_OFF)
        vm.reauthenticate(ReauthProvider.GOOGLE)
        advanceUntilIdle()

        assertEquals(TwoStepStep.Reauth(TwoStepIntent.TURN_OFF), vm.state.value.step)
    }

    @Test
    fun `an account with no password is told it turns this off with its provider`() = runTest(dispatcher) {
        api.statusResult = ApiResult.Success(FakeAccountSecurityApi.STATUS_ON.copy(canDisable = false))
        val vm = model()
        advanceUntilIdle()

        assertTrue(vm.state.value.signsInWithoutPassword)
        vm.begin(TwoStepIntent.TURN_OFF)
        vm.reauthenticate(ReauthProvider.APPLE)
        advanceUntilIdle()
        assertEquals("off now, and the flag carried over", false, vm.state.value.status!!.canDisable)
    }

    // ── Secrets ─────────────────────────────────────────────────────────────

    @Test
    fun `nothing secret prints, and nothing secret outlives the screen`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            TwoStepViewModel.factory(TwoStepSettingsRepository(api), NativeReauthRepository(api)) { ssoOutcome },
        )[TwoStepViewModel::class.java]
        advanceUntilIdle()
        vm.begin(TwoStepIntent.TURN_ON)
        vm.reauthenticate(ReauthProvider.GOOGLE)
        advanceUntilIdle()
        vm.onField(TwoStepField.TOTP, "123456")

        val printed = vm.state.value.toString() + vm.state.value.enrollment.toString()
        assertFalse(printed.contains(FakeAccountSecurityApi.ENROLLMENT.secret))
        assertFalse(printed.contains("otpauth://"))
        assertFalse(printed.contains("123456"))

        store.clear()
        assertNull(vm.state.value.enrollment)
        assertEquals("", vm.state.value.totp)
        assertTrue(vm.state.value.recoveryCodes.isEmpty())
    }

    @Test
    fun `what the state allows follows from its fields`() {
        assertFalse("no status, no note", TwoStepUiState().signsInWithoutPassword)
        val offNoPassword = FakeAccountSecurityApi.STATUS_OFF.copy(canDisable = false)
        assertFalse(TwoStepUiState(status = offNoPassword).signsInWithoutPassword)
        assertFalse(TwoStepUiState(status = FakeAccountSecurityApi.STATUS_ON).signsInWithoutPassword)

        val password = TwoStepStep.Password(TwoStepIntent.TURN_ON, needsCode = false)
        assertFalse("not on the password step", TwoStepUiState(password = "pw").canSubmitPassword)
        assertFalse("busy", TwoStepUiState(step = password, password = "pw", busy = true).canSubmitPassword)
        assertTrue(TwoStepUiState(step = password, password = "pw").canSubmitPassword)

        assertFalse("busy", TwoStepUiState(step = TwoStepStep.Scan, totp = "1", busy = true).canConfirm)
        assertFalse("not scanning", TwoStepUiState(step = TwoStepStep.Overview, totp = "1").canConfirm)
        assertFalse("blank", TwoStepUiState(step = TwoStepStep.Scan, totp = " ").canConfirm)
    }

    @Test
    fun `the key is grouped in fours for reading only`() {
        assertEquals("AAAA BBBB CCCC DDDD", groupedKey("AAAABBBBCCCCDDDD"))
        assertEquals("ABCD E", groupedKey("ABCDE"))
    }
}
