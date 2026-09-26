package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pieces every workspace-settings screen shares: the save banner and the not-editable notice.
 *
 * ⚠️ BOTH ARE REDRAWN IN PLACE AS A SAVE MOVES THROUGH ITS STATES, so each is driven through a
 * sequence here rather than rendered once: the banner must follow the state it is given, and a
 * notice whose words change must keep the handle a screen reader and a test find it by.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class WorkspaceSettingsCommonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the save banner is silent while idle and while saving, and follows the outcome`() {
        val save = mutableStateOf<SaveState>(SaveState.Idle)
        val handle = mutableStateOf(NOTICE)
        composeRule.setContent {
            DistrictTheme { SaveNotice(state = save.value, description = handle.value) }
        }
        composeRule.onNodeWithContentDescription(NOTICE).assertDoesNotExist()

        // ⚠️ "Saving" is said by the button, never by a banner that would then have to vanish.
        save.value = SaveState.Saving
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(NOTICE).assertDoesNotExist()

        save.value = SaveState.Saved
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(NOTICE).assertIsDisplayed()

        save.value = SaveState.Failed(FailureText(UiText.Literal("Invalid payload")))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("Invalid payload").assertIsDisplayed()

        // ⚠️ The banner is found by the handle it is GIVEN, so a caller that renames it moves it.
        handle.value = OTHER
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(OTHER).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(NOTICE).assertDoesNotExist()
    }

    @Test
    fun `the not-editable notice keeps its handle when its words change, and takes a new one`() {
        val body = mutableStateOf("First sentence.")
        val handle = mutableStateOf(NOTICE)
        composeRule.setContent {
            DistrictTheme {
                NotEditableNotice(
                    title = "Stored in another shape",
                    body = body.value,
                    description = handle.value,
                )
            }
        }
        composeRule.onNodeWithText("First sentence.").assertIsDisplayed()

        body.value = "Second sentence."
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(NOTICE).assertIsDisplayed()
        composeRule.onNodeWithText("Second sentence.").assertIsDisplayed()

        handle.value = OTHER
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(OTHER).assertIsDisplayed()
    }

    private companion object {
        const val NOTICE = "test-settings-notice"
        const val OTHER = "test-settings-notice-renamed"
    }
}
