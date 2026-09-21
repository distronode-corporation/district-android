package com.distronode.districtai.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.designsystem.DistrictTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Compose UI test running on the JVM under Robolectric.
 *
 * ⛔ ROBOLECTRIC, NOT AN EMULATOR, AND THAT IS THE POINT. An `androidTest` variant would need a
 * booted device, so the CI verify stage would need KVM and a Gradle-managed device — minutes per
 * run on a shared runner, for a test that touches no real hardware. Keeping the Compose harness
 * in the unit-test lane is what lets the CI pipeline stay a plain JVM job.
 *
 * This also asserts the Compose compiler plugin actually ran: a misconfigured
 * `district.android.compose` convention produces code that compiles and then fails at runtime,
 * so `assembleDebug` alone would not catch it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SignInScreenTest {

    /**
     * ⚠️ The `v2` rule. The v1 `createComposeRule` is deprecated; v2 drives the clock with
     * `StandardTestDispatcher` instead of `UnconfinedTestDispatcher`, so coroutines QUEUE rather
     * than running eagerly. Tests involving async state need explicit synchronisation
     * (`waitForIdle`, `waitUntil`).
     */
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `renders the sign-in affordance and is reachable by its semantics handle`() {
        composeRule.setContent {
            DistrictTheme {
                SignInScreen(status = null, onSignIn = {})
            }
        }

        composeRule.onNodeWithContentDescription(SIGN_IN_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("District AI").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in").assertIsDisplayed()
    }

    @Test
    fun `tapping sign in invokes the callback exactly once`() {
        var invocations = 0
        composeRule.setContent {
            DistrictTheme {
                SignInScreen(status = null, onSignIn = { invocations += 1 })
            }
        }

        composeRule.onNodeWithText("Sign in").performClick()

        // Exactly once matters: a double-fired sign-in would start two PKCE attempts, and the
        // second would overwrite the first's verifier — so the callback for attempt one would
        // fail its state check and look like an injection attempt.
        assertEquals(1, invocations)
    }

    @Test
    fun `status text is surfaced when present`() {
        composeRule.setContent {
            DistrictTheme {
                SignInScreen(status = LoginStatus.WaitingForBrowser, onSignIn = {})
            }
        }

        composeRule.onNodeWithContentDescription(SIGN_IN_STATUS_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Waiting for sign-in to complete in the browser…")
            .assertIsDisplayed()
    }
}
