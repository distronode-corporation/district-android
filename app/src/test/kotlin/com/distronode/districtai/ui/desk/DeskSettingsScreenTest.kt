package com.distronode.districtai.ui.desk

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The desk's settings form and the customer-facing logo beneath it.
 *
 * ⛔ THE TWO ASSERTIONS THAT GUARD A WRITE RATHER THAN A LAYOUT: that a clean form cannot be saved
 * (an empty PATCH is a 400, not a no-op), and that a takedown which left the stored bytes behind
 * SAYS SO. The second is the half of the logo DELETE that a 200 does not cover — the column is
 * cleared, the object may survive and answer its old link, and nothing else on this screen would
 * tell the operator.
 *
 * ⚠️ THE WIDE QUALIFIER IS LOAD-BEARING, the same way it is on every workspace-settings test: the
 * form is one scrolling column ending in the logo card, and on the default 320x470dp Robolectric
 * device most of it is composed outside the window, so `assertIsDisplayed` fails on a screen that
 * is rendering correctly.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class DeskSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * ⚠️ A RECORDER RATHER THAN A PARAMETER BUNDLE. The screen takes eight callbacks, and a
     * mirroring constructor would sit on detekt's `LongParameterList` constructor threshold.
     */
    private class Recorder {
        val enabled = mutableListOf<Boolean>()
        val notify = mutableListOf<Boolean>()
        val brand = mutableListOf<String>()
        var saves = 0
        var picks = 0
        var removes = 0
        var retries = 0
        var backs = 0
    }

    private val loaded = DeskSettingsUiState.Content(
        stored = DeskSettings(enabled = true, notifyCustomersByEmail = false),
        enabled = true,
        notifyCustomersByEmail = false,
        brandName = "",
    )

    private fun render(
        state: DeskSettingsUiState,
        canUse: Boolean = true,
        recorder: Recorder = Recorder(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                DeskSettingsScreen(
                    state = state,
                    canUse = canUse,
                    onEnabled = { recorder.enabled += it },
                    onNotify = { recorder.notify += it },
                    onBrandName = { recorder.brand += it },
                    onSave = { recorder.saves++ },
                    onPickLogo = { recorder.picks++ },
                    onRemoveLogo = { recorder.removes++ },
                    onRetry = { recorder.retries++ },
                    onBack = { recorder.backs++ },
                )
            }
        }
    }

    private fun save() = composeRule.onNodeWithContentDescription(DESK_SETTINGS_SAVE_DESCRIPTION)

    @Test
    fun `loading shows skeletons rather than an empty form an operator could save`() {
        render(DeskSettingsUiState.Loading)

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_SETTINGS_LOADING_DESCRIPTION).assertIsDisplayed()
        save().assertDoesNotExist()
    }

    @Test
    fun `a viewer is refused before the state is consulted`() {
        // ⛔ THE REFUSAL OUTRANKS A LOADED FORM. Every desk route excludes `viewer`, and these
        // settings decide what the tenant's customers see, so a read-only seat must not be shown
        // the form at all rather than being walked into a 403 on save.
        render(loaded, canUse = false)

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_REFUSED_DESCRIPTION).assertIsDisplayed()
        save().assertDoesNotExist()
        composeRule.onNodeWithContentDescription(DESK_SETTINGS_ENABLED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a retryable failure offers Try again and the tap reports it`() {
        val recorder = Recorder()
        render(
            DeskSettingsUiState.Failed(
                FailureText(message = UiText.Literal("Offline"), retryable = true),
            ),
            recorder = recorder,
        )

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, recorder.retries)
    }

    @Test
    fun `a role refusal offers no retry, because it answers identically every time`() {
        render(
            DeskSettingsUiState.Failed(
                FailureText(message = UiText.Literal("Forbidden"), retryable = false),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a clean form cannot be saved, because an empty patch is a 400`() {
        // ⛔ THE ROUTE REFINES ON AT LEAST ONE FIELD BEING PRESENT. There is nothing useful a save
        // with nothing to say could do, so the button refuses rather than spending the request.
        render(loaded)

        save().assertIsNotEnabled()
    }

    @Test
    fun `moving a switch makes the form saveable and the tap reports the save`() {
        val recorder = Recorder()
        render(loaded.copy(enabled = false), recorder = recorder)

        save().assertIsEnabled().performClick()

        assertEquals(1, recorder.saves)
    }

    @Test
    fun `an edited name that matches what is stored is not a change`() {
        // ⚠️ Typing a name and undoing it must not send a write, and `""` against a stored null is
        // the same non-change.
        render(loaded.copy(brandName = "", brandNameEdited = true))

        save().assertIsNotEnabled()
    }

    @Test
    fun `an edited name that differs from what is stored is saveable`() {
        render(loaded.copy(brandName = "Barker Plumbing", brandNameEdited = true))

        save().assertIsEnabled()
    }

    @Test
    fun `saving relabels the button and disables it even though the form is still dirty`() {
        render(loaded.copy(enabled = false, saving = true))

        composeRule.onNodeWithText("Saving…").assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertDoesNotExist()
        save().assertIsNotEnabled()
    }

    @Test
    fun `saving disables both switches and the name box, so the form cannot drift under the write`() {
        render(loaded.copy(enabled = false, saving = true))

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_ENABLED_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DESK_SETTINGS_NOTIFY_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DESK_SETTINGS_BRAND_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `each switch reports the value it is moving TO, not the one it held`() {
        val recorder = Recorder()
        render(loaded, recorder = recorder)

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_ENABLED_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(DESK_SETTINGS_NOTIFY_DESCRIPTION).performClick()

        assertEquals(listOf(false), recorder.enabled)
        assertEquals(listOf(true), recorder.notify)
    }

    @Test
    fun `typing a public name reports it`() {
        val recorder = Recorder()
        render(loaded, recorder = recorder)

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_BRAND_DESCRIPTION)
            .performTextInput("Barker Plumbing")

        assertEquals("Barker Plumbing", recorder.brand.firstOrNull())
    }

    @Test
    fun `the empty box is explained, because clearing it is a real write`() {
        // ⛔ An operator who did not know that a blank falls back to the workspace's own name would
        // read the blank as "nothing set" rather than as a value their customers will see.
        render(loaded)

        composeRule.onNodeWithText(
            "Shown to your customers on their ticket page. Leave it empty to use this workspace's " +
                "own name.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a failed save is shown on the form rather than replacing it`() {
        render(
            loaded.copy(
                enabled = false,
                saveFailure = FailureText(message = UiText.Literal("That name is too long.")),
            ),
        )

        composeRule.onNodeWithContentDescription(DESK_SETTINGS_SAVE_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("That name is too long.").assertIsDisplayed()
        // ⚠️ The form survives the failure, so the operator can correct the value and try again.
        save().assertIsEnabled()
    }

    @Test
    fun `with no logo published the card says so and offers nothing to remove`() {
        render(loaded)

        composeRule.onNodeWithContentDescription(DESK_LOGO_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("No logo published.").assertIsDisplayed()
        composeRule.onNodeWithText("Choose an image").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_LOGO_REMOVE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a published logo shows its address rather than a placeholder standing in for it`() {
        // ⚠️ This screen loads no remote images, so showing the URL is the honest rendering of what
        // is published; a placeholder would claim we had fetched it.
        val url = "https://cdn.example.com/desk/logo.png"
        render(loaded.copy(stored = DeskSettings(enabled = true, publicLogoUrl = url)))

        composeRule.onNodeWithText(url).assertIsDisplayed()
        composeRule.onNodeWithText("Replace").assertIsDisplayed()
        composeRule.onNodeWithText("No logo published.").assertDoesNotExist()
    }

    @Test
    fun `choosing and removing report their own callbacks`() {
        val recorder = Recorder()
        render(
            loaded.copy(
                stored = DeskSettings(enabled = true, publicLogoUrl = "https://cdn.example.com/l.png"),
            ),
            recorder = recorder,
        )

        composeRule.onNodeWithContentDescription(DESK_LOGO_CHOOSE_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(DESK_LOGO_REMOVE_DESCRIPTION).performClick()

        assertEquals(1, recorder.picks)
        assertEquals(1, recorder.removes)
    }

    @Test
    fun `a logo request in flight disables both buttons and says it is working`() {
        render(
            loaded.copy(
                stored = DeskSettings(enabled = true, publicLogoUrl = "https://cdn.example.com/l.png"),
                logoBusy = true,
            ),
        )

        composeRule.onNodeWithText("Working…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_LOGO_CHOOSE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(DESK_LOGO_REMOVE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a takedown that left the stored bytes behind says so`() {
        // ⛔ THE SECOND HALF OF THE DELETE, SURFACED RATHER THAN SWALLOWED. A 200 means the column
        // was cleared, which takes the image off the customers' page; `objectRemoved: false` means
        // the file survived and may still answer its old link. Nothing else here would say it.
        render(loaded.copy(logoObjectRetained = true))

        composeRule.onNodeWithContentDescription(DESK_LOGO_RETAINED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the retained notice is absent on an ordinary load`() {
        // ⚠️ It is set only by a DELETE that reported the bytes were left behind; showing it
        // otherwise would warn about a file that was never there.
        render(loaded)

        composeRule.onNodeWithContentDescription(DESK_LOGO_RETAINED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a logo failure is shown in the logo card and not as a save failure`() {
        render(
            loaded.copy(
                logoFailure = FailureText(message = UiText.Literal("That image could not be read.")),
            ),
        )

        composeRule.onNodeWithContentDescription(DESK_LOGO_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_SETTINGS_SAVE_FAILURE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the app bar back action reports the tap`() {
        val recorder = Recorder()
        render(loaded, recorder = recorder)

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, recorder.backs)
    }
}
