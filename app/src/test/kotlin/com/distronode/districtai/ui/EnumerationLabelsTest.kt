package com.distronode.districtai.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.AvailableNumber
import com.distronode.districtai.core.model.WorkflowActionResult
import com.distronode.districtai.core.model.WorkflowRun
import com.distronode.districtai.str
import com.distronode.districtai.ui.marketplace.SearchResults
import com.distronode.districtai.ui.marketplace.SearchState
import com.distronode.districtai.ui.workflows.RunHistory
import com.distronode.districtai.ui.workflows.RunHistoryPanel
import com.distronode.districtai.ui.workflows.actionReasonCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A workflow run's actions and a number search result read their enumerations through the wire
 * vocabulary: an action's type, outcome and skip reason, a number's capabilities and type. A value the
 * vocabulary does not list reads through the category's generic label.
 *
 * ⚠️ AN OUTCOME AND A CAPABILITY HAVE NO PORTAL LABEL, so their English is the value as sent ("ok",
 * "sms") and only the pseudolocale tells the label from the raw value.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class EnumerationLabelsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val run = WorkflowRun(
        id = "run-1",
        workflowId = "wf-1",
        trigger = "call_ended_unanswered",
        status = "partial",
        startedAt = "2026-10-14T13:05:00.000Z",
        finishedAt = "2026-10-14T13:05:01.000Z",
        actionResults = listOf(
            WorkflowActionResult(type = "send_sms", outcome = "ok"),
            WorkflowActionResult(type = "post_to_slack", outcome = "retrying"),
        ),
    )

    private fun renderRun() = composeRule.setContent {
        DistrictTheme { RunHistoryPanel(RunHistory(runs = listOf(run), total = 1), onLoadMore = {}) }
    }

    private fun renderNumber(number: AvailableNumber) = composeRule.setContent {
        DistrictTheme { SearchResults(SearchState.Ready("twilio", listOf(number))) }
    }

    private val number = AvailableNumber(
        phoneNumber = "+15145550123",
        capabilities = listOf("sms", "voice"),
        type = "local",
    )

    @Test
    fun `an action's type reads as the portal names it, and an unlisted one generically`() {
        renderRun()
        assertEquals("Send SMS", str(R.string.wire_workflow_action_send_sms))
        composeRule.onNodeWithText(str(R.string.wire_workflow_action_send_sms)).assertExists()
        composeRule.onNodeWithText("send_sms").assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.wire_workflow_action_unknown, "post_to_slack")).assertExists()
    }

    @Test
    @Config(qualifiers = "+en-rXA")
    fun `an action's outcome reads through its label`() {
        assertNotEquals("ok", str(R.string.wire_workflow_action_outcome_ok))
        renderRun()
        composeRule.onNodeWithText(str(R.string.wire_workflow_action_outcome_ok)).assertExists()
        composeRule.onNodeWithText(str(R.string.wire_workflow_action_outcome_unknown, "retrying")).assertExists()
        composeRule.onNodeWithText("ok").assertDoesNotExist()
    }

    private fun renderReasons(vararg reasons: String) = composeRule.setContent {
        DistrictTheme {
            RunHistoryPanel(
                RunHistory(
                    runs = listOf(
                        run.copy(
                            actionResults = reasons.map {
                                WorkflowActionResult(type = "send_sms", outcome = "skipped", reason = it)
                            },
                        ),
                    ),
                    total = 1,
                ),
                onLoadMore = {},
            )
        }
    }

    @Test
    fun `a skip reason reads as its code's label, never the engine's sentence or its detail`() {
        // Until 3.3.0 the card showed the engine's English sentence, in French too.
        renderReasons("fallback-failed: email bounced for +15145550123", "recipient-number-held")
        composeRule.onNodeWithText(str(R.string.wire_workflow_action_reason_fallback_failed)).assertExists()
        composeRule.onNodeWithText(str(R.string.wire_workflow_action_reason_recipient_number_held)).assertExists()
        composeRule.onNodeWithText("+15145550123", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("fallback-failed", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `a skip reason reads in French`() {
        renderReasons("loop-paused: +15145550123 replied automatically 3 times")
        val label = str(R.string.wire_workflow_action_reason_loop_paused)
        assertTrue(label, label.startsWith("Texto non envoyé"))
        composeRule.onNodeWithText(label).assertExists()
        composeRule.onNodeWithText("replied automatically", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a reason whose code this build does not know shows the whole reason through the unknown label`() {
        // The fixture's reasons predate the codes; English reads them exactly as before.
        renderReasons("contact has no email address", "brand-new-code: detail")
        composeRule.onNodeWithText(
            str(R.string.wire_workflow_action_reason_unknown, "contact has no email address"),
        ).assertExists()
        composeRule.onNodeWithText(
            str(R.string.wire_workflow_action_reason_unknown, "brand-new-code: detail"),
        ).assertExists()
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `an unknown reason is named as unknown in French and keeps what the engine said`() {
        renderReasons("contact has no email address")
        val label = str(R.string.wire_workflow_action_reason_unknown, "contact has no email address")
        assertNotEquals("contact has no email address", label)
        composeRule.onNodeWithText(label).assertExists()
    }

    @Test
    fun `an http status reason reads as HTTP and its number, the vocabulary's http-{status}`() {
        // Seer on #116: the engine writes the status, so `http-502` never matched `http-{status}`.
        renderReasons("http-502: ops webhook returned 502 Bad Gateway")
        composeRule.onNodeWithText("HTTP 502").assertExists()
        composeRule.onNodeWithText("Bad Gateway", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("{status}", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun `an http status reason reads the same in French, never as an unknown value`() {
        renderReasons("http-404: ops webhook returned 404 Not Found", "http-50x: not a status")
        composeRule.onNodeWithText("HTTP 404").assertExists()
        composeRule.onNodeWithText("Not Found", substring = true).assertDoesNotExist()
        // Not three digits, so not a status: the whole reason, named as unknown.
        composeRule.onNodeWithText(
            str(R.string.wire_workflow_action_reason_unknown, "http-50x: not a status"),
        ).assertExists()
    }

    @Test
    fun `the code is everything before the first colon, as the portal reads it`() {
        assertEquals("quiet-hours", actionReasonCode("quiet-hours: it was 22:30"))
        assertEquals("dnc", actionReasonCode(" dnc "))
        assertEquals("", actionReasonCode(": nothing"))
    }

    @Test
    fun `a number with no capabilities reads its type as the portal names it`() {
        renderNumber(number.copy(capabilities = emptyList(), type = "tollFree"))
        assertEquals("Toll-Free", str(R.string.wire_number_type_toll_free))
        composeRule.onNodeWithText(str(R.string.wire_number_type_toll_free)).assertExists()
        composeRule.onNodeWithText("tollFree").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "+en-rXA")
    fun `a number's capabilities read through their labels`() {
        assertNotEquals("sms", str(R.string.wire_number_capability_sms))
        renderNumber(number)
        val shown = str(R.string.wire_number_capability_sms) + " · " + str(R.string.wire_number_capability_voice)
        composeRule.onNodeWithText(shown).assertExists()
        composeRule.onNodeWithText("sms · voice").assertDoesNotExist()
    }
}
