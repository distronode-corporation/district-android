package com.distronode.districtai.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.designsystem.DistrictTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `every login status says its own sentence, and a denial carries the server's reason`() {
        // The panel's `when` is exhaustive so a new status cannot compile without copy; this pins
        // WHICH copy each one gets, since two statuses sharing a sentence would compile just fine.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = listOf(
            LoginStatus.NoBrowser to context.getString(R.string.login_status_no_browser),
            LoginStatus.WaitingForBrowser to context.getString(R.string.login_status_waiting),
            LoginStatus.DidNotComplete to context.getString(R.string.login_status_did_not_complete),
            LoginStatus.Completing to context.getString(R.string.login_status_completing),
            LoginStatus.Refused to context.getString(R.string.login_status_refused),
            LoginStatus.Interrupted to context.getString(R.string.login_status_interrupted),
            LoginStatus.Expired to context.getString(R.string.login_status_expired),
            LoginStatus.RateLimited to context.getString(R.string.login_status_rate_limited),
            LoginStatus.Unreachable to context.getString(R.string.login_status_unreachable),
            LoginStatus.Denied("access_denied") to context.getString(R.string.login_status_denied, "access_denied"),
        )
        var status by mutableStateOf<LoginStatus?>(null)
        composeRule.setContent {
            DistrictTheme {
                SignInScreen(status = status, onSignIn = {})
            }
        }

        expected.forEach { (shown, sentence) ->
            composeRule.runOnIdle { status = shown }
            composeRule.onNodeWithContentDescription(SIGN_IN_STATUS_DESCRIPTION).assertTextEquals(sentence)
        }
        assertEquals("no two statuses share a sentence", expected.size, expected.map { it.second }.toSet().size)
        assertTrue(expected.last().second.contains("access_denied"))
    }
}
