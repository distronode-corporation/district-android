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
import com.distronode.districtai.core.model.MessageSearchHit
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the Inbox draws once a search is active.
 *
 * ⛔ SEARCH REPLACES THE LIST RATHER THAN FILTERING IT, because the two answer different questions:
 * the list holds a bounded window of recent messages grouped into threads, and the route queries
 * every `Message` row in the workspace. Merging them would present a whole-history answer as if it
 * were a page of the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class InboxSearchScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val conversations = InboxUiState.Content(
        conversations = listOf(
            ConversationSummary(
                threadKey = "contact:c9",
                counterpart = "+14165550159",
                contactId = "c9",
                contactName = "Someone Else",
                lastMessage = ConversationLastMessage(body = "unrelated", direction = "inbound"),
            ),
        ),
        partial = false,
    )

    private fun hit(id: String) = MessageSearchHit(
        messageId = id,
        key = "phone:+14165550142",
        threadKey = "contact:c1",
        counterpart = "+14165550142",
        kind = "phone",
        contactId = "c1",
        contactName = "Ada",
        body = "Our refund policy is 30 days.",
        direction = "inbound",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun render(
        searchState: InboxSearchState,
        onOpenHit: (MessageSearchHit) -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                InboxScreen(
                    state = conversations,
                    onOpenThread = {},
                    onRetry = {},
                    onBack = {},
                    searchState = searchState,
                    onSearchQueryChanged = {},
                    onOpenHit = onOpenHit,
                )
            }
        }
    }

    @Test
    fun `the field is offered before anything is typed, and the list is untouched`() {
        render(InboxSearchState())

        composeRule.onNodeWithContentDescription(INBOX_SEARCH_FIELD_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Someone Else").assertIsDisplayed()
    }

    @Test
    fun `an active search replaces the conversation list with its hits`() {
        render(InboxSearchState(query = "refund", hits = listOf(hit("m1"))))

        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText("Our refund policy is 30 days.").assertIsDisplayed()
        composeRule.onNodeWithText("Someone Else").assertDoesNotExist()
    }

    @Test
    fun `a capped page says so rather than presenting itself as every match`() {
        // ⛔ THE SERVER STOPPED AT ITS CEILING, so older matches exist and are not on this screen.
        // There is no offset to page on, so this is a note rather than a control.
        render(InboxSearchState(query = "refund", hits = listOf(hit("m1")), truncated = true))

        composeRule.onNodeWithContentDescription(INBOX_SEARCH_CAPPED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `no matches is worded as a search result, not as an empty inbox`() {
        render(InboxSearchState(query = "refund"))

        composeRule.onNodeWithContentDescription(INBOX_SEARCH_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INBOX_EMPTY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a failed search is reported instead of reading as no matches`() {
        // ⛔ "No matches" AND "I could not look" ARE DIFFERENT ANSWERS, and the first is the one
        // somebody acts on.
        render(
            InboxSearchState(
                query = "refund",
                failure = FailureText(UiText.Literal("The search did not run.")),
            ),
        )

        composeRule.onNodeWithContentDescription(INBOX_SEARCH_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("The search did not run.").assertIsDisplayed()
    }

    @Test
    fun `tapping a hit reports the hit, so the caller can mark it read and resolve a target`() {
        var opened: MessageSearchHit? = null
        render(InboxSearchState(query = "refund", hits = listOf(hit("m1"))), onOpenHit = { opened = it })

        composeRule.onNodeWithText("Our refund policy is 30 days.").performClick()

        assertEquals("m1", opened?.messageId)
    }

    @Test
    fun `an email hit shows its subject line, and a blank subject draws no empty line`() {
        render(
            InboxSearchState(
                query = "refund",
                hits = listOf(
                    hit("m1").copy(
                        kind = "email",
                        key = "email:ada@example.com",
                        counterpart = "ada@example.com",
                        subject = "Refund request #1042",
                    ),
                    hit("m2").copy(contactName = "Grace", body = "Refund please", subject = " "),
                ),
            ),
        )

        composeRule.onNodeWithText("Refund request #1042").assertIsDisplayed()
        // The blank subject is dropped rather than drawn as an empty line.
        composeRule.onNodeWithText("Grace").assertIsDisplayed()
        composeRule.onNodeWithText(" ").assertDoesNotExist()
    }
}
