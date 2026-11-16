package com.distronode.districtai.ui.account

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.distronode.districtai.R
import com.distronode.districtai.core.data.NativeReauthRepository
import com.distronode.districtai.core.model.AccountDeleteResponse
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeAccountSecurityApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.UiText
import java.io.IOException
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
 * The deletion model's three steps, its refusals, and the one call that deletes: the iOS app's
 * `DeleteAccountModel` rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val api = FakeAccountSecurityApi()
    private var ssoOutcome: SsoReauthOutcome = SsoReauthOutcome.Proved(300)
    private val deletions = mutableListOf<Boolean>()

    private fun model() = DeleteAccountViewModel(
        repository = NativeReauthRepository(api),
        sso = { ssoOutcome },
        deleted = { deletions += it },
    )

    private fun res(id: Int) = UiText.Resource(id)

    private fun failure(id: Int, title: Int? = null) = DeleteAccountFailure(title?.let(::res), res(id))

    private val offline = ApiResult.NetworkFailure(IOException("offline"))

    /** A model on the last step, the proof earned with a provider. */
    private fun kotlinx.coroutines.test.TestScope.confirming(): DeleteAccountViewModel {
        val vm = model()
        vm.reauthenticate(ReauthProvider.GOOGLE)
        advanceUntilIdle()
        assertEquals(DeleteAccountStep.Confirm, vm.state.value.step)
        return vm
    }

    @Test
    fun `a provider sign-in leads to the typed email, and the email deletes`() = runTest(dispatcher) {
        val vm = confirming()
        assertFalse("no email yet", vm.state.value.canDelete)
        vm.onField(DeleteAccountField.EMAIL, "  owner@example.test ")

        vm.delete()
        advanceUntilIdle()

        assertEquals(listOf("owner@example.test"), api.deleteConfirmations)
        assertEquals(listOf(false), deletions)
        assertEquals("", vm.state.value.confirmation)
    }

    @Test
    fun `a deletion with support data still being erased says so to the session`() = runTest(dispatcher) {
        api.deleteResult = ApiResult.Success(
            AccountDeleteResponse(success = true, accountDeleted = true, externalErasurePending = true),
        )
        val vm = confirming()
        vm.onField(DeleteAccountField.EMAIL, "owner@example.test")

        vm.delete()
        advanceUntilIdle()

        assertEquals(listOf(true), deletions)
    }

    @Test
    fun `each way a provider sign-in can fail says so on the first step, and a cancellation says nothing`() =
        runTest(dispatcher) {
            val cases = listOf(
                SsoReauthOutcome.Cancelled to null,
                SsoReauthOutcome.StateMismatch to failure(R.string.reauth_error_sign_in_not_verified),
                SsoReauthOutcome.CouldNotOpen to failure(R.string.reauth_error_sheet_did_not_open),
                SsoReauthOutcome.Refused(ApiResult.Forbidden("x", "reauth_failed")) to
                    failure(R.string.reauth_error_wrong_account),
            )
            for ((outcome, expected) in cases) {
                ssoOutcome = outcome
                val vm = model()
                vm.reauthenticate(ReauthProvider.APPLE)
                advanceUntilIdle()
                assertEquals(DeleteAccountStep.Explain, vm.state.value.step)
                assertEquals(expected, vm.state.value.failure)
            }
        }

    @Test
    fun `a provider sign-in works only from the first step`() = runTest(dispatcher) {
        val vm = model()
        vm.usePassword()
        vm.reauthenticate(ReauthProvider.GOOGLE)
        advanceUntilIdle()
        assertEquals(DeleteAccountStep.Password(needsCode = false), vm.state.value.step)
    }

    @Test
    fun `the password asks for the code when the service does, then leads to the email`() = runTest(dispatcher) {
        val vm = model()
        vm.usePassword()
        assertFalse(vm.state.value.canSubmitPassword)
        vm.onField(DeleteAccountField.PASSWORD, "pw")
        api.passwordResults += ApiResult.HttpFailure(401, "Enter the code", "MFA_REQUIRED")
        api.passwordResults += FakeAccountSecurityApi.proof(NativeReauthAction.DELETE_ACCOUNT)

        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(DeleteAccountStep.Password(needsCode = true), vm.state.value.step)
        assertNull(vm.state.value.failure)
        assertEquals("", vm.state.value.password)

        vm.onField(DeleteAccountField.PASSWORD, "pw")
        assertFalse(vm.state.value.canSubmitPassword)
        vm.onField(DeleteAccountField.CODE, " abcd-efgh ")
        vm.submitPassword()
        advanceUntilIdle()

        assertEquals(Triple(NativeReauthAction.DELETE_ACCOUNT, "pw", "abcd-efgh"), api.passwordAttempts.last())
        assertEquals(DeleteAccountStep.Confirm, vm.state.value.step)
        assertEquals("", vm.state.value.code)
    }

    @Test
    fun `password refusals, a wrong code and an account with no password`() = runTest(dispatcher) {
        val vm = model()
        vm.usePassword()
        vm.onField(DeleteAccountField.PASSWORD, "pw")
        api.passwordResults += ApiResult.Forbidden("no", "incorrect_password")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(failure(R.string.reauth_error_wrong_password), vm.state.value.failure)

        api.passwordResults.clear()
        api.passwordResults += ApiResult.HttpFailure(400, "bad", "mfa_code_invalid")
        vm.onField(DeleteAccountField.PASSWORD, "pw")
        vm.onField(DeleteAccountField.CODE, "123")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals("", vm.state.value.code)
        assertEquals(failure(R.string.reauth_error_wrong_code), vm.state.value.failure)

        api.passwordResults.clear()
        api.passwordResults += ApiResult.HttpFailure(409, "none", "NO_PASSWORD_SET")
        vm.onField(DeleteAccountField.PASSWORD, "pw")
        vm.submitPassword()
        advanceUntilIdle()
        assertEquals(DeleteAccountStep.Explain, vm.state.value.step)
        assertEquals(failure(R.string.reauth_error_no_password), vm.state.value.failure)
    }

    @Test
    fun `back from the password returns to the first step and forgets what was typed`() = runTest(dispatcher) {
        val vm = model()
        vm.usePassword()
        vm.onField(DeleteAccountField.PASSWORD, "pw")
        vm.back()

        assertEquals(DeleteAccountStep.Explain, vm.state.value.step)
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun `nothing moves while a call is in flight, and two taps delete once`() = runTest(dispatcher) {
        val vm = confirming()
        vm.onField(DeleteAccountField.EMAIL, "owner@example.test")
        api.gate = CompletableDeferred()

        vm.delete()
        vm.delete()
        vm.back()
        vm.usePassword()
        runCurrent()
        assertTrue(vm.state.value.busy)
        assertEquals(DeleteAccountStep.Confirm, vm.state.value.step)
        api.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, api.deleteConfirmations.size)
    }

    @Test
    fun `an expired proof goes back to the start, said plainly`() = runTest(dispatcher) {
        api.deleteResult = ApiResult.Forbidden("again", "reauth_required")
        val vm = confirming()
        vm.onField(DeleteAccountField.EMAIL, "owner@example.test")

        vm.delete()
        advanceUntilIdle()

        assertEquals(DeleteAccountStep.Explain, vm.state.value.step)
        assertEquals(failure(R.string.delete_account_error_proof_expired), vm.state.value.failure)
        assertTrue(deletions.isEmpty())
    }

    @Test
    fun `a refused deletion says whether anything was deleted, and never claims it when it cannot tell`() =
        runTest(dispatcher) {
            val cases = listOf(
                offline to failure(R.string.delete_account_error_offline_outcome_unknown),
                ApiResult.DecodeFailure(IllegalStateException(), "") to
                    failure(R.string.delete_account_error_outcome_unknown),
                ApiResult.Unauthorized(null) to failure(R.string.failure_signed_out),
                ApiResult.HttpFailure(400, "Type it", "confirmation_mismatch") to
                    failure(R.string.delete_account_error_email_mismatch),
                ApiResult.RateLimited("slow", code = "rate_limited") to
                    failure(R.string.reauth_error_too_many_attempts, R.string.delete_account_not_deleted),
                ApiResult.HttpFailure(500, "Internal server error", "server_error") to
                    failure(R.string.delete_account_error_try_again_shortly, R.string.delete_account_not_deleted),
                ApiResult.HttpFailure(502, "Bad gateway") to
                    failure(R.string.delete_account_error_try_again_shortly, R.string.delete_account_not_deleted),
                ApiResult.HttpFailure(409, "The subscription could not be cancelled.", "subscription_cancel_failed") to
                    DeleteAccountFailure(
                        res(R.string.delete_account_not_deleted),
                        UiText.Server(
                            "The subscription could not be cancelled.",
                            "subscription_cancel_failed",
                            fallback = R.string.delete_account_error_try_again_shortly,
                        ),
                    ),
            )
            for ((refusal, expected) in cases) {
                api.deleteResult = refusal
                val vm = confirming()
                vm.onField(DeleteAccountField.EMAIL, "owner@example.test")
                vm.delete()
                advanceUntilIdle()
                assertEquals(refusal.toString(), expected, vm.state.value.failure)
                assertEquals(DeleteAccountStep.Confirm, vm.state.value.step)
            }
            assertTrue(deletions.isEmpty())
        }

    @Test
    fun `a 200 that does not say the account is gone is an unknown outcome, not a deletion`() = runTest(dispatcher) {
        api.deleteResult = ApiResult.Success(
            AccountDeleteResponse(success = true, accountDeleted = false, externalErasurePending = false),
        )
        val vm = confirming()
        vm.onField(DeleteAccountField.EMAIL, "owner@example.test")

        vm.delete()
        advanceUntilIdle()

        assertEquals(failure(R.string.delete_account_error_outcome_unknown), vm.state.value.failure)
        assertTrue(deletions.isEmpty())
    }

    @Test
    fun `what the state allows follows from its fields`() {
        val password = DeleteAccountStep.Password(needsCode = false)
        assertFalse("not on the password step", DeleteAccountUiState(password = "pw").canSubmitPassword)
        assertFalse("busy", DeleteAccountUiState(step = password, password = "pw", busy = true).canSubmitPassword)
        assertTrue(DeleteAccountUiState(step = password, password = "pw").canSubmitPassword)

        val confirm = DeleteAccountStep.Confirm
        assertFalse("busy", DeleteAccountUiState(step = confirm, confirmation = "x", busy = true).canDelete)
        assertFalse("not confirming", DeleteAccountUiState(confirmation = "x").canDelete)
        assertFalse("blank", DeleteAccountUiState(step = DeleteAccountStep.Confirm, confirmation = " ").canDelete)
    }

    @Test
    fun `nothing is sent before it can be`() = runTest(dispatcher) {
        val vm = model()
        vm.submitPassword()
        vm.delete()
        advanceUntilIdle()
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a throttle without a code, and an outage with its own code, still say nothing was deleted`() {
        assertEquals(
            failure(R.string.reauth_error_too_many_attempts, R.string.delete_account_not_deleted),
            deleteFailure(ApiResult.RateLimited("slow")),
        )
        assertEquals(
            DeleteAccountFailure(
                res(R.string.delete_account_not_deleted),
                UiText.Server(
                    "Try again",
                    "service_unavailable",
                    fallback = R.string.delete_account_error_try_again_shortly,
                ),
            ),
            deleteFailure(ApiResult.HttpFailure(503, "Try again", "service_unavailable")),
        )
    }

    @Test
    fun `nothing typed prints or outlives the screen`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val vm = ViewModelProvider(
            store,
            DeleteAccountViewModel.factory(NativeReauthRepository(api), { ssoOutcome }, { deletions += it }),
        )[DeleteAccountViewModel::class.java]
        vm.usePassword()
        vm.onField(DeleteAccountField.PASSWORD, "hunter2")
        vm.onField(DeleteAccountField.EMAIL, "owner@example.test")

        assertFalse(vm.state.value.toString().contains("hunter2"))
        store.clear()
        assertEquals("", vm.state.value.password)
        assertEquals("", vm.state.value.confirmation)
    }
}
