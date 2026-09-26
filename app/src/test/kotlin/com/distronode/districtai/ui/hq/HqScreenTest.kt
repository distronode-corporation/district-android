package com.distronode.districtai.ui.hq

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.HqPendingWrite
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the console renders, and the two things it must never render:
 * a blank transcript after a failure, and a proposal that looks like a completed action.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class HqScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val pending = HqPendingWrite(
        tool = "delete_contact",
        args = JsonObject(mapOf("contact" to JsonPrimitive("Ada"))),
        summary = "Permanently DELETE the CRM contact \"Ada\".",
    )

    private val transcript = listOf(
        HqMessage(HqRole.OPERATOR, UiText.Literal("How did we do this week?")),
        HqMessage(HqRole.CONSOLE, UiText.Literal("You had 19 calls this week.")),
    )

    /**
     * ⚠️ The five callbacks are BUNDLED rather than passed individually. `HqScreen` takes eight
     * parameters, and a `render` helper mirroring them plus state, messages and the role flag lands
     * at nine — one over detekt's threshold. The screen itself is exempt (`ignoreAnnotated:
     * Composable`); a plain test helper is not.
     */
    private class Callbacks(
        val onSend: (String) -> Unit = {},
        val onConfirm: () -> Unit = {},
        val onDismiss: () -> Unit = {},
        val onRetry: () -> Unit = {},
        val onBack: () -> Unit = {},
    )

    private fun render(
        state: HqUiState,
        messages: List<HqMessage> = transcript,
        canConfirm: Boolean = true,
        callbacks: Callbacks = Callbacks(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                HqScreen(
                    state = state,
                    messages = messages,
                    canConfirm = canConfirm,
                    onSend = callbacks.onSend,
                    onConfirm = callbacks.onConfirm,
                    onDismiss = callbacks.onDismiss,
                    onRetry = callbacks.onRetry,
                    onBack = callbacks.onBack,
                )
            }
        }
    }

    private fun failure(
        message: String = "We could not reach District HQ.",
        retryable: Boolean = true,
    ) = FailureText(message = UiText.Literal(message), retryable = retryable)

    // ── Transcript ───────────────────────────────────────────────────────────

    @Test
    fun `an empty console invites a question rather than looking broken`() {
        render(HqUiState.Idle, messages = emptyList())

        composeRule.onNodeWithContentDescription(HQ_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HQ_EMPTY_DESCRIPTION).assertIsDisplayed()
        // ⚠️ Names what the tools can actually answer from. A vaguer invitation sets up questions
        // the model is instructed to refuse.
        composeRule.onNodeWithText("Ask about your calls, contacts, the AI receptionist, or your knowledge base.")
            .assertIsDisplayed()
    }

    @Test
    fun `both sides of the conversation are rendered`() {
        render(HqUiState.Idle)

        composeRule.onNodeWithText("How did we do this week?").assertIsDisplayed()
        composeRule.onNodeWithText("You had 19 calls this week.").assertIsDisplayed()
    }

    @Test
    fun `a failure does NOT blank the transcript`() {
        // ⛔ THE CENTRAL GUARANTEE OF THIS SCREEN. The server keeps no conversation, so a screen
        // that cleared on failure would destroy the only copy of it.
        render(HqUiState.Failed(failure(), "How did we do?"))

        composeRule.onNodeWithText("How did we do this week?").assertIsDisplayed()
        composeRule.onNodeWithText("You had 19 calls this week.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HQ_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("We could not reach District HQ.").assertIsDisplayed()
    }

    @Test
    fun `a retryable failure offers retry and calls back`() {
        var retried = 0
        render(HqUiState.Failed(failure(), "How did we do?"), callbacks = Callbacks(onRetry = { retried++ }))

        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `a non-retryable failure offers no retry`() {
        // ⚠️ Contract drift and a role refusal produce the identical failure on every attempt, so a
        // retry button there is a control that cannot succeed.
        render(HqUiState.Failed(failure(retryable = false), "How did we do?"))

        composeRule.onNodeWithContentDescription(HQ_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    // ── Thinking ─────────────────────────────────────────────────────────────

    @Test
    fun `a turn in flight says so and closes the composer`() {
        // ⚠️ The send control is disabled while a turn runs, so something has to explain why.
        render(HqUiState.Thinking)

        composeRule.onNodeWithContentDescription(HQ_THINKING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HQ_SEND_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `an idle console shows no thinking indicator`() {
        render(HqUiState.Idle)

        composeRule.onNodeWithContentDescription(HQ_THINKING_DESCRIPTION).assertDoesNotExist()
    }

    // ── The confirm gate ─────────────────────────────────────────────────────

    @Test
    fun `a proposal renders the SUMMARY and says nothing has changed yet`() {
        // ⛔ THE TWO THINGS THIS CARD EXISTS FOR. The summary is the only description of the change
        // the operator ever reads — the tool name would be an identifier, not a decision — and
        // without the "nothing has changed" line a proposal reads as a completed action.
        render(HqUiState.Confirming(pending))

        composeRule.onNodeWithContentDescription(HQ_CONFIRM_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Permanently DELETE the CRM contact \"Ada\".").assertIsDisplayed()
        composeRule.onNodeWithText("Nothing has been changed yet.").assertIsDisplayed()
        composeRule.onNodeWithText("delete_contact").assertDoesNotExist()
    }

    @Test
    fun `confirming and dismissing both call back`() {
        var confirmed = 0
        var dismissed = 0
        render(
            HqUiState.Confirming(pending),
            callbacks = Callbacks(onConfirm = { confirmed++ }, onDismiss = { dismissed++ }),
        )

        composeRule.onNodeWithContentDescription(HQ_CONFIRM_ACCEPT_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(HQ_CONFIRM_DISMISS_DESCRIPTION).performClick()

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `a role that cannot write is offered no confirm control, only a dismiss`() {
        // ⚠️ Should be unreachable — the server never surfaces a proposal to a viewer — but a
        // control that can only 403 is worse than an absent one.
        render(HqUiState.Confirming(pending), canConfirm = false)

        composeRule.onNodeWithContentDescription(HQ_CONFIRM_ACCEPT_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(HQ_CONFIRM_DISMISS_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `an applying write disables the button that performs it`() {
        // ⛔ THIS IS THE TAP THAT DELETES A CONTACT, and the server has no idempotency key — so the
        // disabled state is the only thing between a double tap and a repeated write.
        render(HqUiState.Applying(pending))

        composeRule.onNodeWithContentDescription(HQ_CONFIRM_ACCEPT_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(HQ_CONFIRM_DISMISS_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithText("Applying…").assertIsDisplayed()
    }

    @Test
    fun `a failed confirm keeps the card, so what was attempted stays visible`() {
        // ⛔ The write may already have taken effect, so the decision to try again is the
        // operator's — and they can only make it if they can still see what it was.
        render(HqUiState.ConfirmFailed(pending, failure("The connection dropped.")))

        composeRule.onNodeWithContentDescription(HQ_CONFIRM_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Permanently DELETE the CRM contact \"Ada\".").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HQ_CONFIRM_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(HQ_CONFIRM_ACCEPT_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `an idle console shows no confirm card`() {
        render(HqUiState.Idle)

        composeRule.onNodeWithContentDescription(HQ_CONFIRM_DESCRIPTION).assertDoesNotExist()
    }

    // ── The composer ─────────────────────────────────────────────────────────

    @Test
    fun `typing a question and sending it hands over the text`() {
        var sent: String? = null
        render(HqUiState.Idle, callbacks = Callbacks(onSend = { sent = it }))

        composeRule.onNodeWithContentDescription(HQ_PROMPT_FIELD_DESCRIPTION)
            .performTextInput("What did Ada want?")
        composeRule.onNodeWithContentDescription(HQ_SEND_DESCRIPTION).performClick()

        assertEquals("What did Ada want?", sent)
    }

    @Test
    fun `send is disabled until something is typed`() {
        render(HqUiState.Idle)

        composeRule.onNodeWithContentDescription(HQ_SEND_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the composer stays open for a role that cannot write`() {
        // ⛔ Reads admit viewers. Hiding the input would remove the half of the feature they are
        // entitled to.
        render(HqUiState.Idle, canConfirm = false)

        composeRule.onNodeWithContentDescription(HQ_PROMPT_FIELD_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the app bar back action is present`() {
        var backs = 0
        render(HqUiState.Idle, callbacks = Callbacks(onBack = { backs++ }))

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `the newest-line handle moves to an answer as it arrives`() {
        // ⚠️ The same bubble is redrawn with `isLast` flipped, which is the case a test asserting
        // "the answer" depends on: the handle must leave the question when the answer lands.
        var lines by mutableStateOf(transcript.take(1))
        composeRule.setContent {
            DistrictTheme {
                HqScreen(
                    state = HqUiState.Idle,
                    messages = lines,
                    canConfirm = true,
                    onSend = {},
                    onConfirm = {},
                    onDismiss = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription(HQ_LATEST_MESSAGE_DESCRIPTION).onChild()
            .assertTextEquals("How did we do this week?")

        lines = transcript
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(HQ_LATEST_MESSAGE_DESCRIPTION).onChild()
            .assertTextEquals("You had 19 calls this week.")
    }

    @Test
    fun `a theme change keeps the newest-line handle on the newest line`() {
        val theme = ThemeFlip(composeRule)
        theme.setContent {
            HqScreen(
                state = HqUiState.Idle,
                messages = transcript,
                canConfirm = true,
                onSend = {},
                onConfirm = {},
                onDismiss = {},
                onRetry = {},
                onBack = {},
            )
        }

        theme.flip()

        composeRule.onNodeWithContentDescription(HQ_LATEST_MESSAGE_DESCRIPTION).onChild()
            .assertTextEquals("You had 19 calls this week.")
    }
}
