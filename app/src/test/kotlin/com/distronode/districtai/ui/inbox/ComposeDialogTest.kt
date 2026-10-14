package com.distronode.districtai.ui.inbox

import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.MessageThreadTarget
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The "new message" form as drawn: who first, then how and what, and a Send that only works once.
 *
 * ⛔ THE STATE IS HANDED IN WHOLE, so each test pins one rendering of one state; the rules that
 * PRODUCE those states are [ComposeViewModelTest]'s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ComposeDialogTest {

    private val composeRule = createComposeRule()

    /** ⚠️ The drain is OUTER, so it runs after the activity has closed; see [MainLooperDrain]. */
    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(composeRule)

    /** Every call the form made, by name, in order. */
    private class RecordingActions : ComposeActions {
        val calls = mutableListOf<String>()
        override fun open() { calls += "open" }
        override fun dismiss() { calls += "dismiss" }
        override fun onQueryChange(query: String) { calls += "query:$query" }
        override fun choose(contact: Contact) { calls += "choose:${contact.id}" }
        override fun useTypedAddress() { calls += "typed" }
        override fun clearRecipient() { calls += "clear" }
        override fun selectChannel(index: Int) { calls += "channel:$index" }
        override fun onBodyChange(body: String) { calls += "body:$body" }
        override fun onSubjectChange(subject: String) { calls += "subject:$subject" }
        override fun send() { calls += "send" }
    }

    private val actions = RecordingActions()

    private fun contact(id: String, name: String, phone: String? = null, email: String? = null) =
        Contact(
            id = id,
            workspaceId = "ws-1",
            name = name,
            phoneNumber = phone,
            email = email,
            createdAt = "2026-10-01"
        )

    private val ada = contact("c1", "Ada Lovelace", phone = "+14165550100", email = "ada@example.com")
    private val phoneOnly = contact("c2", "Unknown", phone = "+14165550199")
    private val emailOnly = contact("c3", "Grace Hopper", email = "grace@example.com")
    private val nothing = contact("c4", "Bare Row")

    private fun render(state: ComposeUiState) {
        composeRule.setContent {
            DistrictTheme { ComposeDialog(state = state, actions = actions) }
        }
    }

    private fun send() = composeRule.onNodeWithTag(COMPOSE_SEND_DESCRIPTION)

    // ── Who ──────────────────────────────────────────────────────────────────

    @Test
    fun `an untouched form asks who, offers no typed row, and shows the list loading`() {
        render(ComposeUiState(open = true))

        composeRule.onNodeWithText(str(R.string.inbox_compose_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSE_TO_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSE_CONTACTS_LOADING_DESCRIPTION).assertExists()
        composeRule.onNodeWithTag(COMPOSE_USE_TYPED_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSE_MESSAGE_DESCRIPTION).assertDoesNotExist()
        send().assertIsNotEnabled()
    }

    @Test
    fun `typing reports the query, and what is typed can be used as the recipient`() {
        render(ComposeUiState(open = true, query = " +14165550123 "))

        composeRule.onNodeWithTag(COMPOSE_TO_DESCRIPTION).performTextInput("9")
        composeRule.onNodeWithText(str(R.string.inbox_compose_message_address, "+14165550123")).performClick()

        assertEquals("typed", actions.calls.last())
        assertEquals(true, actions.calls.any { it.startsWith("query:") })
    }

    @Test
    fun `suggestions show both addresses where there are both, and a tap chooses the contact`() {
        render(
            ComposeUiState(
                open = true,
                contacts = ComposeContacts.Ready(listOf(ada, phoneOnly, emailOnly, nothing)),
            ),
        )

        assertEquals(4, composeRule.onAllNodesWithTag(COMPOSE_CONTACT_DESCRIPTION).fetchSemanticsNodes().size)
        composeRule.onNodeWithText(
            str(R.string.inbox_compose_contact_detail, "+14165550100", "ada@example.com"),
        ).assertExists()
        // ⚠️ "Unknown" is labelled by its number, so the number is the title and the subtitle.
        assertEquals(
            2,
            composeRule.onAllNodesWithText("+14165550199", useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        composeRule.onNodeWithText("grace@example.com").assertExists()
        composeRule.onNodeWithText("Bare Row").assertExists()
        composeRule.onNodeWithTag(COMPOSE_CONTACTS_CAPTION_DESCRIPTION).assertExists()

        composeRule.onNodeWithText("Ada Lovelace").performClick()
        assertEquals("choose:c1", actions.calls.last())
    }

    @Test
    fun `a failed suggestion list is a caption that keeps the form usable`() {
        render(
            ComposeUiState(
                open = true,
                contacts = ComposeContacts.Failed(FailureText(UiText.Literal("Contacts are unavailable."))),
            ),
        )

        composeRule.onNodeWithTag(COMPOSE_CONTACTS_FAILED_DESCRIPTION).assertExists()
        composeRule.onNodeWithText("Contacts are unavailable.").assertExists()
        composeRule.onNodeWithText(str(R.string.inbox_compose_suggestions_failed)).assertExists()
        composeRule.onNodeWithTag(COMPOSE_TO_DESCRIPTION).assertIsEnabled()
    }

    // ── How ──────────────────────────────────────────────────────────────────

    @Test
    fun `a contact with both addresses offers both channels at rest, and Change goes back`() {
        render(ComposeUiState(open = true, recipient = ComposeRecipient.Picked(ada)))

        composeRule.onNodeWithTag(COMPOSE_RECIPIENT_DESCRIPTION).assertExists()
        composeRule.onNodeWithText("Ada Lovelace").assertExists()
        composeRule.onNodeWithText(str(R.string.inbox_compose_send_on).uppercase()).assertExists()
        composeRule.onNodeWithTag("$COMPOSE_CHANNEL_DESCRIPTION-sms").assertIsDisplayed()
        composeRule.onNodeWithTag("$COMPOSE_CHANNEL_DESCRIPTION-email").performClick()
        assertEquals("channel:1", actions.calls.last())
        composeRule.onNodeWithTag(COMPOSE_ADDRESS_DESCRIPTION).assertExists()
        composeRule.onNodeWithText("+14165550100").assertExists()
        // SMS needs no subject.
        composeRule.onNodeWithTag(COMPOSE_SUBJECT_DESCRIPTION).assertDoesNotExist()

        composeRule.onNodeWithTag(COMPOSE_CHANGE_DESCRIPTION).performClick()
        assertEquals("clear", actions.calls.last())
    }

    @Test
    fun `a single channel is stated rather than offered, and email asks for a subject`() {
        render(ComposeUiState(open = true, recipient = ComposeRecipient.Picked(emailOnly)))

        composeRule.onNodeWithText(str(R.string.inbox_compose_send_on).uppercase()).assertDoesNotExist()
        composeRule.onNodeWithText(str(R.string.inbox_compose_channel_email)).assertExists()
        composeRule.onNodeWithTag(COMPOSE_SUBJECT_DESCRIPTION).performTextInput("Quote")
        composeRule.onNodeWithTag(COMPOSE_MESSAGE_DESCRIPTION).performTextInput("Hello")

        // ⚠️ The state here is fixed, so the field may also report its own reset; what matters is
        // that each box reports through its own callback.
        assertEquals("subject:Quote", actions.calls.first())
        assertEquals("body:Hello", actions.calls.last())
    }

    @Test
    fun `a typed number is stated as SMS`() {
        render(ComposeUiState(open = true, recipient = ComposeRecipient.Typed("+14165550123")))

        composeRule.onNodeWithText(str(R.string.inbox_compose_channel_sms)).assertExists()
        assertEquals(2, composeRule.onAllNodesWithText("+14165550123").fetchSemanticsNodes().size)
    }

    @Test
    fun `a contact with no address says so instead of offering a channel`() {
        render(ComposeUiState(open = true, recipient = ComposeRecipient.Picked(nothing), body = "hi"))

        composeRule.onNodeWithTag(COMPOSE_NO_ADDRESS_DESCRIPTION).assertExists()
        composeRule.onNodeWithText(str(R.string.inbox_compose_no_address)).assertExists()
        send().assertIsNotEnabled()
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    @Test
    fun `a complete form sends, and cancel dismisses`() {
        render(ComposeUiState(open = true, recipient = ComposeRecipient.Picked(ada), body = "On our way"))

        send().assertIsEnabled().performClick()
        composeRule.onNodeWithTag(COMPOSE_CANCEL_DESCRIPTION).performClick()

        assertEquals(listOf("send", "dismiss"), actions.calls)
    }

    @Test
    fun `sending relabels and disables Send, Cancel and Change, and back is ignored`() {
        render(
            ComposeUiState(
                open = true,
                recipient = ComposeRecipient.Picked(ada),
                body = "On our way",
                send = ComposeSendState.Sending,
            ),
        )

        composeRule.onNodeWithText(str(R.string.inbox_compose_sending)).assertExists()
        send().assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSE_CANCEL_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSE_CHANGE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSE_MESSAGE_DESCRIPTION).assertIsNotEnabled()

        pressBackOnDialog()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun `back dismisses when idle`() {
        render(ComposeUiState(open = true))

        pressBackOnDialog()

        assertEquals(listOf("dismiss"), actions.calls)
    }

    @Test
    fun `a refusal is shown in the server's words beside the draft`() {
        render(
            ComposeUiState(
                open = true,
                recipient = ComposeRecipient.Picked(ada),
                body = "On our way",
                send = ComposeSendState.Failed(FailureText(UiText.Literal("Sender not verified."))),
            ),
        )

        composeRule.onNodeWithTag(COMPOSE_FAILURE_DESCRIPTION).assertExists()
        composeRule.onNodeWithText("Sender not verified.").assertExists()
        send().assertIsEnabled()
    }

    // ── Recomposition ────────────────────────────────────────────────────────

    /**
     * ⚠️ THE SAME ACTIONS ACROSS EVERY STEP, as the real screen has them: each control must keep
     * working when the form redraws for a keystroke, not only on its first frame.
     */
    @Test
    fun `the form keeps working as it redraws through every step`() {
        val blankColumns = contact("c5", "Blank Columns", phone = " ", email = " ")
        var state by mutableStateOf(
            ComposeUiState(open = true, contacts = ComposeContacts.Ready(listOf(ada, blankColumns)), query = "a"),
        )
        composeRule.setContent {
            DistrictTheme { ComposeHost(InboxCompose(state, actions) {}) }
        }

        state = state.copy(query = "la")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ada Lovelace").performClick()
        composeRule.onNodeWithText(str(R.string.inbox_compose_message_address, "la")).performClick()
        composeRule.onNodeWithTag(COMPOSE_TO_DESCRIPTION).performTextInput("x")
        composeRule.onNodeWithText("Blank Columns").assertExists()

        state = state.copy(contacts = ComposeContacts.Failed(FailureText(UiText.Literal("Down."))))
        composeRule.waitForIdle()
        state = state.copy(query = "ada")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Down.").assertExists()

        state = state.copy(recipient = ComposeRecipient.Picked(ada), selectedChannel = 1)
        composeRule.waitForIdle()
        state = state.copy(body = "Hi", subject = "Quote")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$COMPOSE_CHANNEL_DESCRIPTION-sms").performClick()
        composeRule.onNodeWithTag(COMPOSE_SUBJECT_DESCRIPTION).performTextInput("!")
        composeRule.onNodeWithTag(COMPOSE_MESSAGE_DESCRIPTION).performTextInput("!")
        // ⚠️ Scrolled back up first: typing in the message box scrolled the form down.
        composeRule.onNodeWithTag(COMPOSE_CHANGE_DESCRIPTION).performScrollTo().performClick()
        send().performClick()

        state = state.copy(recipient = ComposeRecipient.Picked(emailOnly), selectedChannel = 0)
        composeRule.waitForIdle()
        state = state.copy(body = "Hi again")
        composeRule.waitForIdle()

        state = state.copy(send = ComposeSendState.Sending)
        composeRule.waitForIdle()
        pressBackOnDialog()
        state = state.copy(send = ComposeSendState.Failed(FailureText(UiText.Literal("Refused."))))
        composeRule.waitForIdle()
        state = state.copy(body = "Hi once more")
        composeRule.waitForIdle()
        pressBackOnDialog()
        composeRule.onNodeWithTag(COMPOSE_CANCEL_DESCRIPTION).performClick()

        assertEquals(
            listOf("choose:c1", "typed", "channel:0", "clear", "send", "dismiss", "dismiss"),
            actions.calls.filterNot { it.contains(':') && !it.startsWith("choose") && !it.startsWith("channel") },
        )
    }

    /**
     * ⚠️ A NEW ACTIONS INSTANCE MID-FORM, as a recreated screen hands over: every control must call
     * the instance it was last given, never the one it was first drawn with.
     */
    @Test
    fun `controls follow a replaced actions instance`() {
        val later = RecordingActions()
        var current: ComposeActions by mutableStateOf(actions)
        var state by mutableStateOf(
            ComposeUiState(open = true, contacts = ComposeContacts.Ready(listOf(ada)), query = "ada"),
        )
        composeRule.setContent {
            DistrictTheme { ComposeHost(InboxCompose(state, current) {}) }
        }

        current = later
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ada Lovelace").performClick()
        composeRule.onNodeWithText(str(R.string.inbox_compose_message_address, "ada")).performClick()
        composeRule.onNodeWithTag(COMPOSE_TO_DESCRIPTION).performTextInput("x")

        state = state.copy(contacts = ComposeContacts.Failed(FailureText(UiText.Literal("Down."))))
        composeRule.waitForIdle()
        current = actions
        composeRule.waitForIdle()

        state = state.copy(recipient = ComposeRecipient.Picked(ada), selectedChannel = 1, body = "Hi", subject = "Q")
        composeRule.waitForIdle()
        current = later
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$COMPOSE_CHANNEL_DESCRIPTION-sms").performClick()
        composeRule.onNodeWithTag(COMPOSE_SUBJECT_DESCRIPTION).performTextInput("!")
        composeRule.onNodeWithTag(COMPOSE_MESSAGE_DESCRIPTION).performTextInput("!")
        composeRule.onNodeWithTag(COMPOSE_CHANGE_DESCRIPTION).performScrollTo().performClick()
        pressBackOnDialog()

        // ⚠️ A field may echo its fixed value back on redraw; no tap ever reached the old instance.
        assertEquals(emptyList<String>(), actions.calls.filterNot { it.startsWith("query:") })
        assertEquals("choose:c1", later.calls.first())
        assertEquals(true, "typed" in later.calls)
        assertEquals(true, "channel:0" in later.calls)
        assertEquals(listOf("clear", "dismiss"), later.calls.takeLast(2))
    }

    // ── The host ─────────────────────────────────────────────────────────────

    @Test
    fun `the host hands a sent message to the caller once, then closes the form`() {
        val landing = MessageThreadTarget(threadKey = "contact:c1", counterpart = "+14165550100", channel = "sms")
        val landed = mutableListOf<MessageThreadTarget?>()
        var state by mutableStateOf(ComposeUiState(open = true))
        composeRule.setContent {
            DistrictTheme { ComposeHost(InboxCompose(state, actions) { landed += it }) }
        }
        composeRule.onNodeWithTag(COMPOSE_ROOT_DESCRIPTION).assertExists()

        state = state.copy(send = ComposeSendState.Sent(landing))
        composeRule.waitForIdle()
        assertEquals(listOf<MessageThreadTarget?>(landing), landed)
        assertEquals(listOf("dismiss"), actions.calls)

        state = ComposeUiState()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSE_ROOT_DESCRIPTION).assertDoesNotExist()
        assertEquals(1, landed.size)
    }

    /** ⚠️ Through the dialog's own back dispatcher; see `CreateContactDialogTest`. */
    private fun pressBackOnDialog() {
        composeRule.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
    }
}
