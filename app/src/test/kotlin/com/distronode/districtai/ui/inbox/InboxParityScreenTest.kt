package com.distronode.districtai.ui.inbox

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.str
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Inbox's owner line, its "Assigned to me" filter and the Meta channel chips. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class InboxParityScreenTest {

    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    private fun thread(
        key: String,
        name: String,
        assignee: String? = null,
        mine: Boolean = false,
        channels: List<String> = listOf("sms"),
    ) = ConversationSummary(
        threadKey = key,
        counterpart = key.removePrefix("addr:"),
        contactName = name,
        channels = channels,
        assigneeEmail = assignee,
        assignedToMe = mine,
        lastMessage = ConversationLastMessage(body = "Message from $name"),
    )

    private val threads = listOf(
        thread(
            "addr:fb:5550101",
            "Bea",
            assignee = "me@example.com",
            mine = true,
            channels = listOf("messenger", "instagram"),
        ),
        thread("addr:14165550177", "Cy", assignee = "teammate@example.com"),
        thread("addr:ig:5550102", "Di"),
    )

    private fun render(conversations: List<ConversationSummary> = threads, startOn: Boolean = false) {
        composeRule.setContent {
            var on by remember { mutableStateOf(startOn) }
            DistrictTheme {
                InboxScreen(
                    state = InboxUiState.Content(conversations, partial = false),
                    onOpenThread = {},
                    onRetry = {},
                    onSignIn = {},
                    onBack = {},
                    searchState = InboxSearchState(),
                    onSearchQueryChanged = {},
                    onOpenHit = {},
                    assignedToMe = AssignedToMeFilter(on) { on = it },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `an assigned row names its owner, an unassigned one says nothing about it`() {
        render()

        composeRule.onNodeWithText(str(R.string.inbox_assigned_to, "teammate@example.com")).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.inbox_assigned_to, "me@example.com")).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.thread_unassigned)).assertDoesNotExist()
    }

    @Test
    fun `Messenger and Instagram rows wear their product names`() {
        render()

        composeRule.onNodeWithText(str(R.string.inbox_channel_messenger)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.inbox_channel_instagram)).assertIsDisplayed()
    }

    @Test
    fun `Assigned to me keeps only the rows the server marked, and a second tap brings the rest back`() {
        render()

        composeRule.onNodeWithTag(INBOX_ASSIGNED_TO_ME_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bea").assertIsDisplayed()
        // ⛔ Assigned, but to someone else: the server said it is not mine.
        composeRule.onNodeWithText("Cy").assertDoesNotExist()
        composeRule.onNodeWithText("Di").assertDoesNotExist()

        composeRule.onNodeWithTag(INBOX_ASSIGNED_TO_ME_DESCRIPTION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cy").assertIsDisplayed()
    }

    @Test
    fun `a filter that leaves nothing says so, and keeps its chip`() {
        render(conversations = threads.drop(1), startOn = true)

        composeRule.onNodeWithTag(INBOX_ASSIGNED_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.inbox_assigned_to_me_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag(INBOX_EMPTY_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(INBOX_ASSIGNED_TO_ME_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `an empty Inbox is still the empty Inbox with the filter off`() {
        render(conversations = emptyList())

        composeRule.onNodeWithTag(INBOX_EMPTY_DESCRIPTION).assertIsDisplayed()
    }
}
