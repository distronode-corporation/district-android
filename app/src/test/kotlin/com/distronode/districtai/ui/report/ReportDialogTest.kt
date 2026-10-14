package com.distronode.districtai.ui.report

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.network.testing.FakeSupportApi
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ReportDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the host files the desk's English for the call, and shows the confirmation until Done`() {
        val api = FakeSupportApi()
        var dismissed = 0
        composeRule.setContent {
            DistrictTheme {
                ReportDialogHost(ReportTarget.Call("call-1"), "ws-1", SupportRepository(api)) { dismissed++ }
            }
        }
        composeRule.onNodeWithText(str(R.string.report_headline_call)).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("It invented a price.")
        composeRule.onNodeWithTag(REPORT_SUBMIT_TAG).performClick()
        composeRule.waitForIdle()

        val draft = api.createDrafts.single()
        assertEquals("Report: AI-generated content", draft.subject)
        assertEquals(
            "AI-generated content was reported from the District AI Android app.\nCall: call-1\n\nIt invented a price.",
            draft.message,
        )
        assertTrue(api.createKeys.single()!!.isNotBlank())
        composeRule.onNodeWithTag(REPORT_CONFIRMATION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_DONE_TAG).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a conversation heading names the person, and the desk gets the contact id only`() {
        val api = FakeSupportApi()
        composeRule.setContent {
            DistrictTheme {
                ReportDialogHost(ReportTarget.Conversation("contact:c7", "Ada"), "ws-1", SupportRepository(api)) {}
            }
        }
        composeRule.onNodeWithText(str(R.string.report_headline_conversation_with, "Ada")).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_SUBMIT_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(
            "AI-generated content was reported from the District AI Android app.\nConversation\nContact: c7\n\n" +
                "No note was added.",
            api.createDrafts.single().message,
        )
    }

    @Test
    fun `an HQ answer is named by its kind`() {
        val api = FakeSupportApi()
        composeRule.setContent {
            DistrictTheme { ReportDialogHost(ReportTarget.HqAnswer, "ws-1", SupportRepository(api)) {} }
        }
        composeRule.onNodeWithText(str(R.string.report_headline_hq)).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_SUBMIT_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue(api.createDrafts.single().message.contains("\nDistrict HQ answer\n"))
    }

    @Test
    fun `a failure shows above the form, which stays armed, and Cancel closes`() {
        var dismissed = 0
        composeRule.setContent {
            DistrictTheme {
                ReportDialog(
                    target = ReportTarget.Conversation("addr:+1", null),
                    state = ReportState.Failed(FailureText(UiText.Literal("Too many requests."))),
                    onSubmit = { _, _ -> },
                    onDismiss = { dismissed++ },
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.report_headline_conversation)).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_FAILURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_NOTE_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_CANCEL_TAG).performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `while sending, nothing can be pressed or dismissed`() {
        composeRule.setContent {
            DistrictTheme {
                ReportDialog(ReportTarget.HqAnswer, ReportState.Sending, onSubmit = { _, _ -> }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText(str(R.string.report_sending)).assertIsDisplayed()
        composeRule.onNodeWithTag(REPORT_SUBMIT_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(REPORT_CANCEL_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the note is clamped as it is typed`() {
        var sent = ""
        composeRule.setContent {
            DistrictTheme {
                ReportDialog(
                    ReportTarget.HqAnswer,
                    ReportState.Idle,
                    onSubmit = { _, message -> sent = message },
                    onDismiss = {}
                )
            }
        }
        composeRule.onNodeWithTag(REPORT_NOTE_TAG).performTextInput("y".repeat(ContentReport.NOTE_LIMIT + 5))
        composeRule.onNodeWithTag(REPORT_SUBMIT_TAG).performClick()
        assertTrue(sent.endsWith("\n\n" + "y".repeat(ContentReport.NOTE_LIMIT)))
    }
}
