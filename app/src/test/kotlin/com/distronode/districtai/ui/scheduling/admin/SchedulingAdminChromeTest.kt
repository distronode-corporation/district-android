package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The pieces every scheduling section is drawn from. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingAdminChromeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val retryable = FailureText(UiText.Literal("Down"), retryable = true)

    @Test
    fun `the section frame carries Back and Refresh, and the read states draw`() {
        var back = 0
        var refresh = 0
        var retried = 0
        composeRule.setContent {
            DistrictTheme {
                SchedulingSectionScaffold("Title", "root", onBack = { back++ }, onRefresh = { refresh++ }) {
                    SchedulingCard(eyebrow = "Eyebrow") { SchedulingReadOnlyRow("Label", "Value") }
                    SchedulingCard(eyebrow = null) { Text("Bare") }
                    SchedulingSectionLoading("loading")
                    SchedulingSectionFailure(retryable, onRetry = { retried++ }, tag = "failure")
                    SchedulingEmptyState("Empty", "Nothing here", "empty")
                    SchedulingStatusBadge("Live", SchedulingStatusKind.SUCCESS)
                    SchedulingHint("A hint")
                    SchedulingRejection(null)
                    SchedulingRejection("Refused")
                }
            }
        }
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        assertEquals(listOf(1, 1, 1), listOf(back, refresh, retried))
        listOf("EYEBROW", "Label", "Value", "Bare", "Nothing here", "Live", "A hint", "Refused").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithTag("loading").assertIsDisplayed()
        composeRule.onNodeWithTag("empty").assertIsDisplayed()
    }

    @Test
    fun `a write dialog confirms, cancels, and shows how the write went`() {
        var state: WriteState by mutableStateOf(WriteState.Idle)
        var confirmed = 0
        var cancelled = 0
        var retried = 0
        composeRule.setContent {
            DistrictTheme {
                SchedulingWriteDialog(
                    title = "Do it?",
                    subtitle = "It happens.",
                    confirmLabel = "Do it",
                    cancelLabel = "Keep",
                    state = state,
                    onConfirm = { confirmed++ },
                    onCancel = { cancelled++ },
                    tag = "dialog",
                    confirmTag = "confirm",
                    destructive = true,
                    onRetry = { retried++ },
                ) { Text("Fields") }
            }
        }
        composeRule.onNodeWithText("It happens.").assertIsDisplayed()
        composeRule.onNodeWithTag("confirm").performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)

        state = WriteState.Working
        composeRule.onNodeWithTag("confirm").assertIsNotEnabled()
            .assertTextContains(str(R.string.scheduling_admin_common_saving))
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).assertIsNotEnabled()

        state = WriteState.Done(UiText.Literal("Done it"))
        composeRule.onNodeWithTag(SCHEDULING_WRITE_NOTICE_TAG).assertIsDisplayed()

        state = WriteState.Failed(retryable)
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `a primary write dialog with no subtitle, and a failure with no retry offered`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingWriteDialog(
                    title = "Save?",
                    subtitle = null,
                    confirmLabel = "Save",
                    cancelLabel = "Cancel",
                    state = WriteState.Failed(retryable),
                    onConfirm = {},
                    onCancel = {},
                    tag = "dialog",
                    confirmTag = "confirm",
                    confirmEnabled = false,
                ) {}
            }
        }
        composeRule.onNodeWithTag("confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_FAILURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).assertDoesNotExist()
    }

    @Test
    fun `a confirm dialog sends or backs out`() {
        var confirmed = 0
        var cancelled = 0
        composeRule.setContent {
            DistrictTheme {
                SchedulingConfirmDialog(
                    "Revoke?",
                    "It stops working.",
                    "Revoke",
                    "Keep",
                    { confirmed++ },
                    { cancelled++ },
                    "c",
                    "go",
                )
            }
        }
        composeRule.onNodeWithText("It stops working.").assertIsDisplayed()
        composeRule.onNodeWithTag("go").performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        assertEquals(1, confirmed)
        assertEquals(1, cancelled)
    }

    @Test
    fun `outcome lines dismiss`() {
        var dismissed = 0
        composeRule.setContent {
            DistrictTheme {
                SchedulingWriteOutcome(WriteState.Done(UiText.Literal("Saved")), onDismiss = { dismissed++ })
                SchedulingFailureLine(FailureText(UiText.Literal("No"), retryable = false), onDismiss = { dismissed++ })
                SchedulingWriteOutcome(WriteState.Idle)
            }
        }
        composeRule.onNodeWithText("Saved").assertIsDisplayed()
        composeRule.onNodeWithText("No").assertIsDisplayed()
        val dismiss = composeRule.onAllNodes(hasTestTag(SCHEDULING_WRITE_DISMISS_TAG))
        dismiss.fetchSemanticsNodes().indices.forEach { dismiss[it].performClick() }
        assertEquals(2, dismissed)
    }

    @Test
    fun `fields take input, show a hint until refused, and choices mark the chosen one`() {
        var text by mutableStateOf("")
        var on by mutableStateOf(false)
        var chosen by mutableStateOf<String?>(null)
        composeRule.setContent {
            DistrictTheme {
                Column {
                    SchedulingTextField("Name", text, { text = it }, "name", hint = "Who")
                    val multiline = SchedulingFieldKind.MULTILINE
                    SchedulingTextField("Notes", "", {}, "notes", error = "Too long", kind = multiline)
                    SchedulingTextField("Secret", "abc", {}, "secret", kind = SchedulingFieldKind.PASSWORD)
                    SchedulingToggleRow("On", on, { on = it }, "toggle")
                    SchedulingToggleRow("Off", false, {}, "locked", enabled = false)
                    val ab = listOf("a" to "A", "b" to "B")
                    SchedulingChoiceRow(ab, chosen, { chosen = it }, "choice", label = "Pick")
                    SchedulingChoiceRow(listOf(1 to "One"), null, {}, "plain", enabled = false)
                }
            }
        }
        composeRule.onNodeWithTag("name").performTextInput("Ana")
        assertEquals("Ana", text)
        composeRule.onNodeWithText("Who").assertIsDisplayed()
        composeRule.onNodeWithText("Too long").assertIsDisplayed()
        composeRule.onNodeWithTag("secret").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("toggle").performClick()
        assertEquals(true, on)
        composeRule.onNodeWithTag("choice-b").performClick()
        assertEquals("b", chosen)
        composeRule.onNodeWithTag("choice-b").assertIsSelected()
        composeRule.onNodeWithText("PICK").assertIsDisplayed()
    }

    @Test
    fun `a pending section is its frame`() {
        var back = 0
        composeRule.setContent { DistrictTheme { SchedulingPendingSection("Later", onBack = { back++ }) } }
        composeRule.onNodeWithTag(SCHEDULING_PENDING_SECTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        assertEquals(1, back)
    }
}
