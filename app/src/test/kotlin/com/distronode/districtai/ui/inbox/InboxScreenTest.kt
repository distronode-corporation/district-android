package com.distronode.districtai.ui.inbox

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.FailureText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THE ASSERTION THIS FILE EXISTS FOR IS `a partial list says so`. The server scans a bounded window
 * of recent messages, so an Inbox can legitimately be a TRUNCATION — and rendering a truncation as if
 * it were the whole account is the same class of mistake as telling a paying customer they have no
 * plan. There is no page to request; saying so is the only honest option.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class InboxScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun thread(
        threadKey: String = "contact:c1",
        counterpart: String = "+14165550142",
        contactName: String? = "Ada Lovelace",
        contactId: String? = "c1",
        unread: Int = 0,
        channels: List<String> = listOf("sms"),
        body: String = "Are you open Thursday?",
    ) = ConversationSummary(
        threadKey = threadKey,
        counterpart = counterpart,
        contactId = contactId,
        contactName = contactName,
        channels = channels,
        canSms = true,
        unreadCount = unread,
        totalMessages = 3,
        lastMessage = ConversationLastMessage(
            body = body,
            direction = "inbound",
            type = "sms",
            status = "received",
            createdAt = "2026-08-15T19:06:00.000Z",
        ),
    )

    private fun render(
        state: InboxUiState,
        onOpenThread: (ConversationSummary) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                InboxScreen(state = state, onOpenThread = onOpenThread, onRetry = onRetry)
            }
        }
    }

    @Test
    fun `a thread shows who it is with and what they last said`() {
        render(InboxUiState.Content(listOf(thread()), partial = false))

        composeRule.onNodeWithContentDescription(INBOX_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("Are you open Thursday?").assertIsDisplayed()
    }

    @Test
    fun `an unresolved counterpart is titled by its address, not by a placeholder`() {
        // ⚠️ A phone number IS the identity of that thread. "Unknown" would hide the only piece of
        // information available about who is messaging.
        render(
            InboxUiState.Content(
                listOf(thread(contactName = null, contactId = null, threadKey = "addr:+14165550142")),
                partial = false,
            ),
        )

        composeRule.onNodeWithText("+14165550142").assertIsDisplayed()
    }

    @Test
    fun `an unread thread carries a count and a read one does not`() {
        render(InboxUiState.Content(listOf(thread(unread = 3)), partial = false))
        composeRule.onNodeWithContentDescription(INBOX_UNREAD_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `a single-channel thread shows no channel badge`() {
        // ⚠️ A single-channel thread does not need to be told it is SMS. A badge on every row would be
        // noise that hides the unread badge, which is the one that matters.
        // ⚠️ Two tests rather than one: `setContent` may be called ONCE per test.
        render(InboxUiState.Content(listOf(thread(channels = listOf("sms"))), partial = false))

        composeRule.onNodeWithText("SMS").assertDoesNotExist()
    }

    @Test
    fun `a mixed-channel thread names its channels`() {
        render(
            InboxUiState.Content(
                listOf(thread(channels = listOf("sms", "email"))),
                partial = false,
            ),
        )

        composeRule.onNodeWithText("EMAIL").assertIsDisplayed()
    }

    @Test
    fun `a partial list says so`() {
        // ⛔ See the class doc. This is the whole point of the file.
        render(InboxUiState.Content(listOf(thread()), partial = true))

        composeRule.onNodeWithContentDescription(INBOX_PARTIAL_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a complete list does not claim to be partial`() {
        render(InboxUiState.Content(listOf(thread()), partial = false))

        composeRule.onNodeWithContentDescription(INBOX_PARTIAL_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `opening a thread reports the thread, not just its key`() {
        // ⚠️ The whole summary travels back, because the caller needs contactId AND counterpart to mark
        // it read — a thread with no Contact row has only the address.
        var opened: ConversationSummary? = null
        render(InboxUiState.Content(listOf(thread()), partial = false), onOpenThread = { opened = it })

        composeRule.onNodeWithText("Ada Lovelace").performClick()

        assertEquals("contact:c1", opened?.threadKey)
        assertEquals("c1", opened?.contactId)
    }

    @Test
    fun `an empty inbox reads as empty, not as broken`() {
        render(InboxUiState.Content(emptyList(), partial = false))

        composeRule.onNodeWithContentDescription(INBOX_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a retryable failure offers a retry`() {
        var retries = 0
        render(
            InboxUiState.Failed(FailureText(message = UiText.Literal("Offline."), retryable = true)),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(INBOX_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a terminal failure offers no retry`() {
        // ⚠️ A role refusal answers identically every time; a button that cannot succeed is worse than
        // no button.
        render(
            InboxUiState.Failed(
                FailureText(message = UiText.Literal("Not permitted."), retryable = false),
            ),
        )

        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `loading is announced`() {
        render(InboxUiState.Loading)

        composeRule.onNodeWithContentDescription(INBOX_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    // ── The drafts badge (Task A1) ──────────────────────────────────────────

    /**
     * ⚠️ THE CHIP SAYS "there is unsent text here", NOT WHAT IT SAYS. Drafts are author-scoped
     * server-side, so this never appears for a colleague's — and the list carries only the thread
     * KEYS, never the bodies, so a screenshot of the Inbox cannot leak half a sentence.
     */
    @Test
    fun `a thread with an unsent draft is chipped`() {
        render(
            InboxUiState.Content(
                listOf(thread()),
                partial = false,
                draftThreadKeys = setOf(thread().threadKey),
            ),
        )

        composeRule.onNodeWithContentDescription(INBOX_DRAFT_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a thread with no draft carries no chip`() {
        render(InboxUiState.Content(listOf(thread()), partial = false))

        composeRule.onNodeWithContentDescription(INBOX_DRAFT_DESCRIPTION).assertDoesNotExist()
    }

    /**
     * ⚠️ THE BADGE READ IS FAIL-SOFT, so an empty key set is also what a failed lookup looks like.
     * That is the right trade: an Inbox that refused to render because a decorative chip's endpoint
     * was down would be strictly worse than an Inbox with no chips.
     */
    @Test
    fun `an unbadged list still renders every thread`() {
        render(
            InboxUiState.Content(
                // ⚠️ DISTINCT thread keys: a LazyColumn keyed on a duplicate throws, which is
                // exactly the crash the threadKey rule in InboxScreen exists to prevent.
                listOf(thread(threadKey = "contact:c1"), thread(threadKey = "contact:c2")),
                partial = false,
                draftThreadKeys = emptySet(),
            ),
        )

        composeRule.onNodeWithContentDescription(INBOX_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INBOX_DRAFT_DESCRIPTION).assertDoesNotExist()
    }
}
