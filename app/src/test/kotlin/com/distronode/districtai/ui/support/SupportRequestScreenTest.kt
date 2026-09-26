package com.distronode.districtai.ui.support

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.SupportMessage
import com.distronode.districtai.core.model.SupportRequestDetail
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One support request's thread, rendered in each of its states.
 *
 * WHAT HAD NO TEST. The list screen has [SupportScreenTest], and the navigation test reaches this
 * destination only signed out. Nothing rendered a loaded thread, so nothing checked the three things
 * a customer acts on here: who said what (the label comes from `role`, never from a name the payload
 * does not carry), whether the request can be closed from the phone, and that a failed send or a
 * failed close is said beside the control rather than lost.
 */
@RunWith(AndroidJUnit4::class)
// A TALL VIEWPORT: the thread is a LazyColumn, which composes only what fits, and on a phone-sized
// display the second message and the close-failure row sit below the fold.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w480dp-h2400dp")
class SupportRequestScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val request = SupportRequestDetail(
        issueKey = "DA-42",
        id = "row_1",
        subject = "Calls drop after ten seconds",
        statusName = "In Progress",
        statusCategory = "INDETERMINATE",
        messages = listOf(
            SupportMessage(id = "m1", role = "customer", author = "Customer", body = "Calls drop.", createdAt = "t1"),
            SupportMessage(id = "m2", role = "agent", author = "Agent", body = "Looking now.", createdAt = "t2"),
        ),
        closeable = true,
    )

    private var sends = 0
    private var closes = 0
    private var retries = 0
    private var backs = 0
    private val drafts = mutableListOf<String>()

    private fun render(state: SupportRequestUiState, draft: String = "", canUse: Boolean = true) {
        composeRule.setContent {
            DistrictTheme {
                SupportRequestScreen(
                    state = state,
                    draft = draft,
                    canUse = canUse,
                    onDraftChange = { drafts += it },
                    onSend = { sends += 1 },
                    onClose = { closes += 1 },
                    onRetry = { retries += 1 },
                    onBack = { backs += 1 },
                )
            }
        }
    }

    @Test
    fun `a loaded thread is titled by its key and attributes each message by role`() {
        render(SupportRequestUiState.Content(request))

        // The key is both the top bar's title and the header's eyebrow.
        val keyed = composeRule.onAllNodesWithText("DA-42")
        keyed.assertCountEquals(2)
        keyed[0].assertIsDisplayed()
        keyed[1].assertIsDisplayed()
        composeRule.onNodeWithText("Calls drop after ten seconds").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_STATUS_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Distronode Support").assertIsDisplayed()
        composeRule.onNodeWithText("You").assertIsDisplayed()
        composeRule.onNodeWithText("Looking now.").assertIsDisplayed()
    }

    @Test
    fun `an open, closeable request can be closed from the phone`() {
        render(SupportRequestUiState.Content(request))

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION)
            .assertIsEnabled()
            .performClick()

        assertEquals(1, closes)
    }

    @Test
    fun `a request the desk will not close says so instead of offering the button`() {
        render(SupportRequestUiState.Content(request.copy(closeable = false)))

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a resolved request offers neither, and still takes a reply`() {
        // There is no reopen endpoint; replying on a closed request is the supported path.
        val resolved = request.copy(statusName = "Done", statusCategory = "DONE")
        render(SupportRequestUiState.Content(resolved), draft = "One more thing")

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_SEND_DESCRIPTION)
            .assertIsEnabled()
            .performClick()

        assertEquals(1, sends)
    }

    @Test
    fun `an unfiled request is titled generically and its header says it is being opened`() {
        render(SupportRequestUiState.Content(request.copy(issueKey = null)))

        composeRule.onNodeWithText("Request").assertIsDisplayed()
        composeRule.onNodeWithText("BEING OPENED").assertIsDisplayed()
    }

    @Test
    fun `typing reaches the host, and an empty draft cannot be sent`() {
        render(SupportRequestUiState.Content(request))

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_REPLY_DESCRIPTION).performTextInput("Thanks")

        assertEquals(listOf("Thanks"), drafts)
    }

    @Test
    fun `while a reply is sending the box and the button are both held`() {
        render(SupportRequestUiState.Content(request, sending = true), draft = "Thanks")

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_SEND_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithText("Sending…").assertIsDisplayed()
    }

    @Test
    fun `while a close is in flight the button says so and cannot be pressed again`() {
        // The button stays, disabled and labelled as closing. The not-closeable sentence belongs to
        // a request the desk will not close, and this one is being closed.
        render(SupportRequestUiState.Content(request, closing = true))

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_DESCRIPTION)
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Closing…").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_NOT_CLOSEABLE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a failed send and a failed close are each said beside their own control`() {
        render(
            SupportRequestUiState.Content(
                request,
                sendFailure = FailureText(UiText.Literal("The reply did not send.")),
                closeFailure = FailureText(UiText.Literal("The request could not be closed.")),
            ),
            draft = "Thanks",
        )

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_SEND_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("The reply did not send.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_CLOSE_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("The request could not be closed.").assertIsDisplayed()
    }

    @Test
    fun `a retryable failure offers a retry that reaches the host`() {
        render(SupportRequestUiState.Failed(FailureText(UiText.Literal("Could not load."))))

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a failure that cannot be retried is shown as the server sent it, with no button`() {
        render(SupportRequestUiState.Failed(FailureText(UiText.Literal("Not found."), retryable = false)))

        composeRule.onNodeWithText("Not found.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a viewer is refused the thread, whatever loaded`() {
        render(SupportRequestUiState.Content(request), canUse = false)
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_REFUSED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Calls drop after ten seconds").assertDoesNotExist()
    }

    @Test
    fun `a loading request draws its skeleton and no thread`() {
        render(SupportRequestUiState.Loading)

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_REPLY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the thread's back action reaches the host`() {
        render(SupportRequestUiState.Loading)

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `a second failed send replaces the first sentence in the same place`() {
        var state by mutableStateOf<SupportRequestUiState>(
            SupportRequestUiState.Content(request, sendFailure = FailureText(UiText.Literal("First failure."))),
        )
        composeRule.setContent {
            DistrictTheme {
                SupportRequestScreen(
                    state = state,
                    draft = "Thanks",
                    canUse = true,
                    onDraftChange = {},
                    onSend = {},
                    onClose = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("First failure.").assertIsDisplayed()

        state = SupportRequestUiState.Content(request, sendFailure = FailureText(UiText.Literal("Second failure.")))
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(SUPPORT_REQUEST_SEND_FAILURE_DESCRIPTION)
            .assertTextEquals("Second failure.")
        composeRule.onNodeWithText("First failure.").assertDoesNotExist()
    }
}
