package com.distronode.districtai.ui.support

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.core.model.SupportRequestSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
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
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                SupportScreen(
                    state = state,
                    canUse = canUse,
                    onOpenRequest = onOpenRequest,
                    onCompose = onCompose,
                    onRetry = onRetry,
                    onBack = onBack,
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
                    onBack = {},
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

    @Test
    fun `a refresh over a loaded list draws its bar above the rows it keeps`() {
        renderList(SupportUiState.Content(requests = listOf(open), refreshing = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        composeRule.onNodeWithText("Calls drop").assertIsDisplayed()
    }

    @Test
    fun `a list at the route's cap says older requests are not shown`() {
        // ⚠️ The route caps at 100 with no cursor; staying silent would present a partial list as
        // the whole history.
        val many = (1..100).map { open.copy(issueKey = "DA-$it", id = "row_$it", subject = "Request $it") }
        renderList(SupportUiState.Content(requests = many))

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(SUPPORT_CAPPED_DESCRIPTION))
        composeRule.onNodeWithContentDescription(SUPPORT_CAPPED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a list under the cap says nothing about older requests`() {
        renderList(SupportUiState.Content(requests = listOf(open)))

        composeRule.onNodeWithContentDescription(SUPPORT_CAPPED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a failed read offers a retry that reaches the host, and only when it could work`() {
        var retries = 0
        renderList(SupportUiState.Failed(FailureText(message = UiText.Literal("Offline"))), onRetry = { retries += 1 })
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a failure that cannot be retried offers no button`() {
        renderList(SupportUiState.Failed(FailureText(message = UiText.Literal("Forbidden"), retryable = false)))

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `the list's back action reaches the host`() {
        var backs = 0
        renderList(SupportUiState.Loading, onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(SUPPORT_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `a theme change redraws the list with its rows still opening their request`() {
        val opened = mutableListOf<SupportRequestSummary>()
        var composes = 0
        val theme = ThemeFlip(composeRule)
        theme.setContent {
            SupportScreen(
                state = SupportUiState.Content(requests = listOf(open, unfiled)),
                canUse = true,
                onOpenRequest = { opened += it },
                onCompose = { composes += 1 },
                onRetry = {},
                onBack = {},
            )
        }

        theme.flip()

        composeRule.onNodeWithText("Calls drop").performClick()
        composeRule.onNodeWithContentDescription(SUPPORT_NEW_DESCRIPTION).performClick()
        assertEquals(listOf(open), opened)
        assertEquals(1, composes)
    }

    @Test
    fun `rows and the new-request button reach the handlers the host holds now`() {
        // ⚠️ The host may hand the list new handlers (a recreated ViewModel); a tap must reach
        // the live ones rather than the first ones the list was drawn with.
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        var onOpen by mutableStateOf<(SupportRequestSummary) -> Unit>({ first += "open" })
        var onCompose by mutableStateOf({ first += "compose" })
        composeRule.setContent {
            DistrictTheme {
                SupportScreen(
                    state = SupportUiState.Content(requests = listOf(open)),
                    canUse = true,
                    onOpenRequest = onOpen,
                    onCompose = onCompose,
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        onOpen = { second += "open" }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Calls drop").performClick()
        onCompose = { second += "compose" }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(SUPPORT_NEW_DESCRIPTION).performClick()

        assertEquals(emptyList<String>(), first)
        assertEquals(listOf("open", "compose"), second)
    }
}
