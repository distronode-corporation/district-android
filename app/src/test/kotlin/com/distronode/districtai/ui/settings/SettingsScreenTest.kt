package com.distronode.districtai.ui.settings

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.core.designsystem.DistrictTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THIS SCREEN IS A PLAY REQUIREMENT, SO ITS TEST GUARDS AN UPLOAD RATHER THAN A FEATURE. Google's
 * User Data policy requires an in-app route to initiate account deletion, and reviewers check for
 * sign-out in the same pass. Before this screen existed `TokenRefreshCoordinator.forget()` had zero
 * non-test callers — a signed-in user had no way to stop being signed in. Losing either row is a
 * store rejection, which is a failure mode no other test in this module covers.
 *
 * ⚠️ Plain UI with callbacks, no container and no ViewModel: the screen holds no state of its own,
 * so everything worth asserting is "is the row there" and "did the callback fire".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        onBack: () -> Unit = {},
        onSignOut: () -> Unit = {},
        onDeleteAccount: () -> Unit = {},
        onOpenDevices: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                SettingsScreen(
                    onBack = onBack,
                    onSignOut = onSignOut,
                    onDeleteAccount = onDeleteAccount,
                    onOpenDevices = onOpenDevices,
                )
            }
        }
    }

    @Test
    fun `the deletion row stays reachable by scrolling`() {
        // ⛔ SCROLLED TO, AND THE NEED FOR IT IS THE POINT RATHER THAN A TEST DETAIL. On a phone in
        // landscape or at a large font scale the ACCOUNT card can sit past the bottom of the
        // screen, and the only row it holds is the Play-mandated deletion entry point. The screen
        // scrolls so it stays REACHABLE; asserting it without scrolling would only prove it fits
        // on this particular Robolectric window.
        var deletions = 0
        render(onDeleteAccount = { deletions += 1 })

        composeRule.onNodeWithContentDescription(SETTINGS_DELETE_ACCOUNT_DESCRIPTION)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, deletions)
    }

    @Test
    fun `offers both account actions`() {
        // ⛔ The rejection guard: an upload missing either of these is refused, not niggled.
        render()

        composeRule.onNodeWithContentDescription(SETTINGS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SETTINGS_SIGN_OUT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SETTINGS_DELETE_ACCOUNT_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `offers the device list, which is the only route to it`() {
        // ⛔ THE DEVICE-MANAGEMENT ROUTES ARE UNREACHABLE WITHOUT THIS ROW. A `deviceId` is
        // client-generated and opaque, so the list screen is the only place a user learns which
        // ids exist — losing this entry point makes both revoke routes unusable rather than
        // merely inconvenient.
        var opened = 0
        render(onOpenDevices = { opened += 1 })

        composeRule.onNodeWithContentDescription(SETTINGS_DEVICES_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SETTINGS_DEVICES_DESCRIPTION).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `signing out is reported once per tap`() {
        var signOuts = 0
        render(onSignOut = { signOuts += 1 })

        composeRule.onNodeWithContentDescription(SETTINGS_SIGN_OUT_DESCRIPTION).performClick()

        assertEquals(1, signOuts)
    }

    @Test
    fun `deletion hands off rather than deleting in place`() {
        // ⚠️ A LINK, NOT A DESTRUCTIVE BUTTON. The flow is identity-verified and irreversible and the
        // web owns it; the policy asks only that the app let the user START it. So the contract this
        // asserts is "the tap is forwarded", and deliberately not "something was deleted".
        var deletions = 0
        render(onDeleteAccount = { deletions += 1 })

        composeRule.onNodeWithContentDescription(SETTINGS_DELETE_ACCOUNT_DESCRIPTION).performClick()

        assertEquals(1, deletions)
    }

    @Test
    fun `back is offered and reported`() {
        var backs = 0
        render(onBack = { backs += 1 })

        composeRule.onNodeWithText("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `the caption tells the user deletion leaves the app`() {
        // ⚠️ Tapping it opens a browser, so saying so up front stops that reading as a crash.
        render()

        composeRule
            .onNodeWithText("Request deletion of your account and its data. Opens in your browser.")
            .assertIsDisplayed()
    }

    @Test
    fun `the deletion url is the live public page named in the store listing`() {
        // ⛔ THIS LITERAL IS COUPLED TO THE PLAY STORE LISTING'S DATA-DELETION FIELD AND TO A PAGE A
        // REVIEWER OPENS WITH NO SESSION. Changing one side alone is a review failure that no build
        // or runtime check would catch, so the coupling is asserted here instead.
        assertEquals("https://www.distronode.com/privacy/account-deletion", ACCOUNT_DELETION_URL)
    }

    @Test
    fun `a row calls the callback the screen was last given, not the first one`() {
        // ⚠️ THE NAV HOST HANDS THIS SCREEN NEW LAMBDAS WHENEVER IT RECOMPOSES. A row that kept the
        // first one would sign out, or open devices, through a callback bound to a stale graph.
        val generation = mutableIntStateOf(1)
        val signOuts = mutableListOf<Int>()
        val devices = mutableListOf<Int>()
        composeRule.setContent {
            val current = generation.intValue
            DistrictTheme {
                SettingsScreen(
                    onBack = {},
                    onSignOut = { signOuts += current },
                    onDeleteAccount = {},
                    onOpenDevices = { devices += current },
                )
            }
        }

        generation.intValue = 2
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(SETTINGS_SIGN_OUT_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(SETTINGS_DEVICES_DESCRIPTION).performClick()

        assertEquals(listOf(2), signOuts)
        assertEquals(listOf(2), devices)
    }

    @Test
    fun `the design preview composes the whole screen`() {
        composeRule.setContent { SettingsScreenPreview() }

        composeRule.onNodeWithContentDescription(SETTINGS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SETTINGS_SIGN_OUT_DESCRIPTION).assertIsDisplayed()
    }
}
