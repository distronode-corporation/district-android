package com.distronode.districtai.ui.contacts

import androidx.activity.ComponentDialog
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
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import com.distronode.districtai.ui.MainLooperDrain
import org.junit.rules.RuleChain

/**
 * The one place in the app that holds user input.
 *
 * ⛔ THE SUBMIT RULE IS "A NAME PLUS EITHER A PHONE OR AN EMAIL" — not both. Contacts became
 * email-first and the database deliberately allows any number of phone-less rows per workspace, so
 * demanding a number here would refuse legitimate input. (`contacts/bulk-create` disagrees and
 * requires a phone per row; that inconsistency is server-side and is deliberately not smoothed over
 * on this screen.)
 *
 * ⚠️ Saving disables everything, including dismissal. A request is already in flight and the row
 * either lands or does not; letting the dialog close mid-flight makes the outcome unobservable.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CreateContactDialogTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private fun render(
        state: CreateContactUiState = CreateContactUiState.Idle,
        onCreate: (String, String, String) -> Unit = { _, _, _ -> },
        onDismiss: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                CreateContactDialog(state = state, onCreate = onCreate, onDismiss = onDismiss)
            }
        }
    }

    private fun type(description: String, text: String) {
        composeRule.onNodeWithContentDescription(description).performTextInput(text)
    }

    private fun submit() = composeRule.onNodeWithContentDescription(CONTACT_CREATE_SUBMIT_DESCRIPTION)

    @Test
    fun `opens with all three fields and nothing submittable`() {
        render()

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_NAME_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_PHONE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_EMAIL_DESCRIPTION).assertIsDisplayed()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `a name alone is not enough, and the hint says why`() {
        render()

        type(CONTACT_CREATE_NAME_DESCRIPTION, "Ada Lovelace")

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_HINT_DESCRIPTION).assertIsDisplayed()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `the hint is guidance, not an error, so it is absent before a name is typed`() {
        // ⚠️ An empty form is the normal starting state; telling the user off for it is noise.
        render()

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_HINT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a name plus a phone submits`() {
        var captured: Triple<String, String, String>? = null
        render(onCreate = { n, p, e -> captured = Triple(n, p, e) })

        type(CONTACT_CREATE_NAME_DESCRIPTION, "Ada Lovelace")
        type(CONTACT_CREATE_PHONE_DESCRIPTION, "+14165550142")
        submit().assertIsEnabled().performClick()

        assertEquals(Triple("Ada Lovelace", "+14165550142", ""), captured)
    }

    @Test
    fun `a name plus an email submits, with no phone at all`() {
        // ⛔ THE EMAIL-FIRST CASE. Demanding a number here would refuse a legitimate row.
        var captured: Triple<String, String, String>? = null
        render(onCreate = { n, p, e -> captured = Triple(n, p, e) })

        type(CONTACT_CREATE_NAME_DESCRIPTION, "Bob Barker")
        type(CONTACT_CREATE_EMAIL_DESCRIPTION, "bob@example.com")
        submit().assertIsEnabled().performClick()

        assertEquals(Triple("Bob Barker", "", "bob@example.com"), captured)
    }

    @Test
    fun `a contact method without a name does not submit`() {
        render()

        type(CONTACT_CREATE_PHONE_DESCRIPTION, "+14165550142")

        submit().assertIsNotEnabled()
        // The hint is keyed to "named but unreachable", so it stays away here.
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_HINT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `whitespace is not a name and not a contact method`() {
        render()

        type(CONTACT_CREATE_NAME_DESCRIPTION, "   ")
        type(CONTACT_CREATE_PHONE_DESCRIPTION, "  ")

        submit().assertIsNotEnabled()
    }

    @Test
    fun `saving disables submission and relabels the button`() {
        render(CreateContactUiState.Saving)

        composeRule.onNodeWithText("Adding…").assertIsDisplayed()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `saving disables cancel, because the request is already in flight`() {
        var dismissed = 0
        render(CreateContactUiState.Saving, onDismiss = { dismissed++ })

        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()

        assertEquals(0, dismissed)
    }

    @Test
    fun `cancel dismisses when idle`() {
        var dismissed = 0
        render(onDismiss = { dismissed++ })

        composeRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun `a failure is shown in the dialog rather than closing it`() {
        // ⚠️ A 409 lands here as "already exists" rather than as a server fault — the database
        // enforces one contact per phone and per lowercased email per workspace.
        render(
            CreateContactUiState.Failed(
                FailureText(message = UiText.Literal("A contact with that phone number already exists.")),
            ),
        )

        composeRule.onNodeWithContentDescription(CONTACT_CREATE_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("A contact with that phone number already exists.")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed attempt stays submittable so the user can correct and retry`() {
        render(
            CreateContactUiState.Failed(
                FailureText(message = UiText.Literal("Could not add that contact.")),
            ),
        )

        type(CONTACT_CREATE_NAME_DESCRIPTION, "Ada Lovelace")
        type(CONTACT_CREATE_EMAIL_DESCRIPTION, "ada@example.com")

        submit().assertIsEnabled()
    }

    @Test
    fun `the idle button reads Add rather than Adding`() {
        render()

        composeRule.onNodeWithText("Add").assertIsDisplayed()
        composeRule.onNodeWithText("Adding…").assertDoesNotExist()
    }

    /**
     * The system back gesture, delivered to the dialog's own window the way the platform does.
     *
     * ⚠️ THROUGH THE DIALOG'S BACK DISPATCHER, not a key event on a node: Compose's dialog listens
     * for back on its window, which a semantics key press never reaches.
     */
    private fun pressBackOnDialog() {
        composeRule.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
    }

    @Test
    fun `back dismisses when idle`() {
        var dismissed = 0
        render(onDismiss = { dismissed++ })

        pressBackOnDialog()

        assertEquals(1, dismissed)
    }

    @Test
    fun `back is ignored while saving, because the request is already in flight`() {
        var dismissed = 0
        render(CreateContactUiState.Saving, onDismiss = { dismissed++ })

        pressBackOnDialog()

        assertEquals(0, dismissed)
        composeRule.onNodeWithContentDescription(CONTACT_CREATE_DESCRIPTION).assertIsDisplayed()
    }
}
