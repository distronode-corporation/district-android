package com.distronode.districtai.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The shared failure presentation every screen now draws through.
 *
 * ⛔ THE SIGN-IN BRANCH IS THE ONE THE COPIES HAD LOST. Before these were shared, only the call log
 * and contacts list turned a dead session into a "sign in again" button; every other screen showed
 * the signed-out sentence and nothing to press. These tests pin both halves of that: the button
 * appears when a screen wires a sign-in route, and nothing is invented when it does not.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class FailureStatesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val signedOut = FailureText(
        message = UiText.Literal("Signed out"),
        signedOutCause = SignedOutCause.SESSION_INVALID,
        retryable = false,
    )

    private val degraded = FailureText(
        message = UiText.Literal("Some regions are unreachable"),
        degradedRegions = listOf("eu", "ca"),
    )

    private fun signInAgain() = context.getString(R.string.overview_sign_in_again)

    private fun retry() = context.getString(R.string.overview_retry)

    private fun regions() = context.getString(R.string.overview_degraded_regions, "eu, ca")

    @Test
    fun `a full-screen signed-out failure offers sign-in when the screen wires it`() {
        var signIns = 0
        var retries = 0
        composeRule.setContent {
            DistrictTheme {
                FailureState(
                    failure = signedOut,
                    onRetry = { retries++ },
                    onSignIn = { signIns++ },
                    description = "handle",
                )
            }
        }

        composeRule.onNodeWithContentDescription("handle").assertIsDisplayed()
        composeRule.onNodeWithText(signInAgain()).performClick()

        assertEquals(1, signIns)
        assertEquals(0, retries)
        composeRule.onAllNodesWithText(retry()).assertCountEquals(0)
    }

    @Test
    fun `a full-screen signed-out failure invents nothing when no sign-in is wired`() {
        composeRule.setContent {
            DistrictTheme {
                FailureState(failure = signedOut, onRetry = {}, onSignIn = null, description = "handle")
            }
        }

        composeRule.onNodeWithText("Signed out").assertIsDisplayed()
        composeRule.onAllNodesWithText(signInAgain()).assertCountEquals(0)
        composeRule.onAllNodesWithText(retry()).assertCountEquals(0)
    }

    @Test
    fun `a full-screen failure names its regions, carries its title, and retries by its handle`() {
        var retries = 0
        composeRule.setContent {
            DistrictTheme {
                FailureState(
                    failure = degraded,
                    onRetry = { retries++ },
                    onSignIn = {},
                    description = "handle",
                    title = "Inbox",
                    retryDescription = "retry-handle",
                )
            }
        }

        composeRule.onNodeWithText("INBOX").assertIsDisplayed()
        composeRule.onNodeWithText(regions()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("retry-handle").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `a full-screen failure that cannot be retried offers no button`() {
        composeRule.setContent {
            DistrictTheme {
                FailureState(
                    failure = FailureText(message = UiText.Literal("Forbidden"), retryable = false),
                    onRetry = {},
                    onSignIn = {},
                    description = "handle",
                )
            }
        }

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
        composeRule.onAllNodesWithText(retry()).assertCountEquals(0)
        composeRule.onAllNodesWithText(signInAgain()).assertCountEquals(0)
    }

    @Test
    fun `an inline failure offers sign-in when wired, with no title`() {
        var signIns = 0
        composeRule.setContent {
            DistrictTheme {
                InlineFailure(
                    title = null,
                    failure = signedOut,
                    onRetry = {},
                    description = "card",
                    onSignIn = { signIns++ },
                )
            }
        }

        composeRule.onNodeWithContentDescription("card").assertIsDisplayed()
        composeRule.onNodeWithText(signInAgain()).performClick()

        assertEquals(1, signIns)
    }

    @Test
    fun `an inline failure names its regions and retries by its handle`() {
        var retries = 0
        composeRule.setContent {
            DistrictTheme {
                InlineFailure(
                    title = "History",
                    failure = degraded,
                    onRetry = { retries++ },
                    description = "card",
                    retryDescription = "retry-handle",
                )
            }
        }

        composeRule.onNodeWithText("HISTORY").assertIsDisplayed()
        composeRule.onNodeWithText(regions()).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("retry-handle").performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `an inline failure follows a new failure and a new handle`() {
        var failure by mutableStateOf(FailureText(message = UiText.Literal("First")))
        var description by mutableStateOf("first-handle")
        composeRule.setContent {
            DistrictTheme {
                InlineFailure(title = "Title", failure = failure, onRetry = {}, description = description)
            }
        }

        failure = FailureText(message = UiText.Literal("Second"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("first-handle").assertIsDisplayed()
        composeRule.onNodeWithText("Second").assertIsDisplayed()

        description = "second-handle"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("second-handle").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("first-handle").assertDoesNotExist()
    }

    @Test
    fun `the footer states and the retry button follow a new handle`() {
        var handle by mutableStateOf("first")
        composeRule.setContent {
            DistrictTheme {
                Column {
                    PagedAppendLoading(description = "$handle-loading")
                    PagedAppendFailure(
                        failure = FailureText(message = UiText.Literal("Footer")),
                        onRetry = {},
                        onSignIn = null,
                        description = "$handle-footer",
                    )
                    FailureState(
                        failure = FailureText(message = UiText.Literal("Screen")),
                        onRetry = {},
                        onSignIn = null,
                        description = "screen",
                        retryDescription = "$handle-retry",
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("first-footer").assertExists()

        handle = "second"
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("second-loading").assertExists()
        composeRule.onNodeWithContentDescription("second-footer").assertExists()
        composeRule.onNodeWithContentDescription("second-retry").assertExists()
        composeRule.onNodeWithContentDescription("first-footer").assertDoesNotExist()
    }

    @Test
    fun `a footer failure offers sign-in when wired and names its regions`() {
        var signIns = 0
        composeRule.setContent {
            DistrictTheme {
                PagedAppendFailure(
                    failure = signedOut.copy(degradedRegions = listOf("eu", "ca")),
                    onRetry = {},
                    onSignIn = { signIns++ },
                    description = "footer",
                )
            }
        }

        composeRule.onNodeWithContentDescription("footer").assertIsDisplayed()
        composeRule.onNodeWithText(regions()).assertIsDisplayed()
        composeRule.onNodeWithText(signInAgain()).performClick()

        assertEquals(1, signIns)
    }
}
