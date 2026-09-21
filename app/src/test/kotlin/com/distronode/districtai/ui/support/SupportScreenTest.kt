package com.distronode.districtai.ui.support

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the support surface renders.
 *
 * ⛔ THE ASSERTION THAT MATTERS MOST HERE IS THE ONE ABOUT AN EMPTY LIST. The web collapsed every
 * list failure into `[]` and told a customer with three open tickets that they had none.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SupportScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val open = SupportRequestSummary(
        issueKey = "DA-42",
        id = "row_1",
        subject = "Calls drop",
        statusName = "In Progress",
        statusCategory = "INDETERMINATE",
    )

    private val unfiled = SupportRequestSummary(
        issueKey = null,
        id = "row_2",
        subject = "Billing question",
        statusName = "Received",
        statusCategory = "NEW",
    )

    private fun renderList(
        state: SupportUiState,
        canUse: Boolean = true,
        onOpenRequest: (SupportRequestSummary) -> Unit = {},
        onCompose: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                SupportScreen(
                    state = state,
                    canUse = canUse,
                    onOpenRequest = onOpenRequest,
                    onCompose = onCompose,
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun `an empty list shows the empty state rather than a failure`() {
        renderList(SupportUiState.Content(requests = emptyList()))

        composeRule.onNodeWithContentDescription(SUPPORT_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SUPPORT_FAILURE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a failure shows the failure state, NOT an empty list`() {
        renderList(
            SupportUiState.Failed(FailureText(message = UiText.Literal("Offline"))),
        )

        composeRule.onNodeWithContentDescription(SUPPORT_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SUPPORT_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a viewer sees why rather than an empty list`() {
        renderList(SupportUiState.Content(requests = listOf(open)), canUse = false)

        composeRule.onNodeWithContentDescription(SUPPORT_REFUSED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `an unfiled request shows "being opened" rather than a blank reference`() {
        // ⚠️ A request with no issue key is not broken: we hold it and it is addressable by its own
        // row id, Atlassian simply does not have it yet.
        renderList(SupportUiState.Content(requests = listOf(unfiled)))

        composeRule.onNodeWithText("Billing question").assertIsDisplayed()
        composeRule.onNodeWithText("Being opened").assertIsDisplayed()
    }

    @Test
    fun `the status badge shows the desk's own word, not a translated one`() {
        renderList(
            SupportUiState.Content(
                requests = listOf(open.copy(statusName = "Terminé", statusCategory = "DONE")),
            ),
        )

        // ⛔ SUBSTITUTING "Closed" WOULD PRINT ENGLISH over a status Atlassian spells in another
        // language, on a screen whose whole point is telling a customer where their request stands.
        composeRule.onNodeWithText("Terminé").assertIsDisplayed()
    }

    @Test
    fun `open and resolved are shown under their own headings`() {
        renderList(
            SupportUiState.Content(
                requests = listOf(
                    open,
                    open.copy(id = "row_0", subject = "Old one", statusCategory = "DONE"),
                ),
            ),
        )

        composeRule.onNodeWithText("OPEN").assertIsDisplayed()
        composeRule.onNodeWithText("RESOLVED").assertIsDisplayed()
    }

    @Test
    fun `tapping a row reports the request`() {
        var opened: SupportRequestSummary? = null
        renderList(SupportUiState.Content(requests = listOf(open)), onOpenRequest = { opened = it })

        composeRule.onNodeWithText("Calls drop").performClick()

        assertEquals("row_1", opened?.id)
    }

    // ── The detail ───────────────────────────────────────────────────────────

    private val detail = SupportRequestDetail(
        issueKey = "DA-42",
        id = "row_1",
        subject = "Calls drop",
        statusName = "In Progress",
        statusCategory = "INDETERMINATE",
        closeable = true,
        messages = listOf(
            SupportMessage(id = "c1", role = "customer", author = "You", body = "It drops."),
            SupportMessage(
                id = "c2",
                role = "agent",
                author = "Distronode Support",
                body = "Looking now.",
            ),
        ),
    )

    private fun renderDetail(
        state: SupportRequestUiState,
        canUse: Boolean = true,
        onClose: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                SupportRequestScreen(
                    state = state,
                    draft = "",
                    canUse = canUse,
                    onDraftChange = {},
                    onSend = {},
                    onClose = onClose,
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun `the thread shows both roles with the labels the route synthesises`() {
        renderDetail(SupportRequestUiState.Content(detail))

        // ⛔ SCROLLED TO, BECAUSE THE HEADER IS TALLER THAN THE TEST VIEWPORT. The thread is a
        // `LazyColumn` whose first item is the whole request header (subject, status, close
        // affordance and its explanation), so the first message is not composed at rest and a bare
        // `assertIsDisplayed` fails on a screen that is rendering correctly.
        listOf("It drops.", "Looking now.", "Distronode Support").forEach { text ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            composeRule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun `close is offered when the server said it could be`() {
        var closed = false
        renderDetail(SupportRequestUiState.Content(detail), onClose = { closed = true })

        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION)
            .performClick()

        assertEquals(true, closed)
    }

    @Test
    fun `a not-closeable open request explains why instead of showing nothing`() {
        // ⛔ SILENCE WOULD READ AS A MISSING FEATURE rather than as the desk's own answer.
        renderDetail(SupportRequestUiState.Content(detail.copy(closeable = false)))

        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `an already-resolved request shows neither the close button nor the explanation`() {
        // ⚠️ There is nothing to say: it is already resolved, and "cannot be closed from here"
        // would read as a refusal of something the operator did not ask for.
        renderDetail(SupportRequestUiState.Content(detail.copy(statusCategory = "DONE")))

        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION)
            .assertDoesNotExist()
        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `the reply box stays available on a resolved request, because there is no reopen`() {
        // ⛔ THE SERVER EXPOSES NO REOPEN, so replying on a closed request is the supported path.
        renderDetail(
            SupportRequestUiState.Content(
                detail.copy(statusCategory = "DONE", closeable = false),
            ),
        )

        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_REPLY_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun `a viewer sees the refusal on the detail too`() {
        renderDetail(SupportRequestUiState.Content(detail), canUse = false)

        composeRule
            .onNodeWithContentDescription(SUPPORT_REQUEST_REFUSED_DESCRIPTION)
            .assertIsDisplayed()
    }
}
