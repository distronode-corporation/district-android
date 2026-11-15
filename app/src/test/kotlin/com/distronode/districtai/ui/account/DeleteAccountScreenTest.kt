package com.distronode.districtai.ui.account

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The deletion screen: what each step draws, and what each control asks of the model. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w411dp-h2400dp")
class DeleteAccountScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class RecordingActions : DeleteAccountActions {
        val calls = mutableListOf<String>()
        override fun onField(field: DeleteAccountField, value: String) {
            calls += "field:$field=$value"
        }
        override fun usePassword() {
            calls += "usePassword"
        }
        override fun back() {
            calls += "back"
        }
        override fun reauthenticate(provider: ReauthProvider) {
            calls += "sso:$provider"
        }
        override fun submitPassword() {
            calls += "submit"
        }
        override fun delete() {
            calls += "delete"
        }
    }

    private val actions = RecordingActions()
    private var backs = 0
    private var activity: ComponentActivity? = null
    private var state by mutableStateOf(DeleteAccountUiState())

    private fun render(initial: DeleteAccountUiState) {
        state = initial
        composeRule.setContent {
            activity = LocalActivity.current as ComponentActivity
            DistrictTheme {
                DeleteAccountScreen(state = state, actions = actions, onBack = { backs += 1 })
            }
        }
    }

    private fun pressBack() {
        composeRule.runOnUiThread { activity!!.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun `the first step says what goes and what stays, and offers each way to confirm it is you`() {
        render(DeleteAccountUiState())

        composeRule.onNodeWithText(str(R.string.delete_account_cannot_be_undone)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.delete_account_what_is_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.delete_account_subscriptions_cancelled)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.delete_account_what_is_kept)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.delete_account_confirm_intro)).assertIsDisplayed()
        ReauthProvider.entries.forEach { composeRule.onNodeWithTag(reauthProviderTag(it)).performClick() }
        composeRule.onNodeWithTag(REAUTH_USE_PASSWORD_TAG).performClick()
        composeRule.onNodeWithTag(REAUTH_BACK_TAG).assertDoesNotExist()

        assertEquals(listOf("sso:GOOGLE", "sso:MICROSOFT", "sso:APPLE", "usePassword"), actions.calls)
    }

    @Test
    fun `the password step types into the model, and back goes to the first step`() {
        render(DeleteAccountUiState(step = DeleteAccountStep.Password(needsCode = true), password = "pw", code = "1"))

        composeRule.onNodeWithTag(REAUTH_PASSWORD_TAG).performTextInput("x")
        composeRule.onNodeWithTag(REAUTH_CODE_TAG).performTextInput("2")
        composeRule.onNodeWithTag(REAUTH_PASSWORD_CONTINUE_TAG).assertIsEnabled().performClick()
        pressBack()

        assertEquals("field:PASSWORD=xpw", actions.calls.first())
        assertTrue("field:CODE=21" in actions.calls)
        assertEquals(listOf("submit", "back"), actions.calls.takeLast(2))
        assertEquals(0, backs)
    }

    @Test
    fun `the last step takes the typed email and deletes`() {
        render(DeleteAccountUiState(step = DeleteAccountStep.Confirm, confirmation = "owner@example.test"))

        composeRule.onNodeWithText(str(R.string.delete_account_type_email)).assertIsDisplayed()
        composeRule.onNodeWithTag(DELETE_ACCOUNT_EMAIL_TAG).performTextInput("x")
        composeRule.onNodeWithTag(DELETE_ACCOUNT_SUBMIT_TAG).assertIsEnabled().performClick()

        assertEquals(listOf("field:EMAIL=xowner@example.test", "delete"), actions.calls.filter { it != "field:EMAIL=owner@example.test" })
    }

    @Test
    fun `a refusal shows its headline above its reason`() {
        render(
            DeleteAccountUiState(
                step = DeleteAccountStep.Confirm,
                failure = DeleteAccountFailure(
                    UiText.Resource(R.string.delete_account_not_deleted),
                    UiText.Resource(R.string.delete_account_error_try_again_shortly),
                ),
            ),
        )

        composeRule.onNodeWithText(str(R.string.delete_account_not_deleted)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.delete_account_error_try_again_shortly)).assertIsDisplayed()
        composeRule.onNodeWithTag(DELETE_ACCOUNT_SUBMIT_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a refusal without a headline shows its reason only`() {
        render(
            DeleteAccountUiState(
                failure = DeleteAccountFailure(null, UiText.Resource(R.string.delete_account_error_proof_expired)),
            ),
        )

        composeRule.onNodeWithTag(DELETE_ACCOUNT_MESSAGE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.delete_account_not_deleted)).assertDoesNotExist()
    }

    @Test
    fun `while deleting, the button says so and there is no way out`() {
        render(DeleteAccountUiState(step = DeleteAccountStep.Confirm, confirmation = "x", busy = true))

        composeRule.onNodeWithText(str(R.string.delete_account_deleting)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(str(R.string.top_bar_back)).assertDoesNotExist()
        pressBack()

        assertTrue(actions.calls.isEmpty())
        assertEquals(0, backs)
    }

    @Test
    fun `the top bar leaves the screen when nothing is in flight`() {
        render(DeleteAccountUiState(step = DeleteAccountStep.Confirm))

        composeRule.onNodeWithContentDescription(str(R.string.top_bar_back)).performClick()

        assertEquals(1, backs)
    }
}
