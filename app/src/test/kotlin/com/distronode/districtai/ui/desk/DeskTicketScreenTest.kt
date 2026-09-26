package com.distronode.districtai.ui.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.DeskMessage
import com.distronode.districtai.core.model.DeskTicketDetail
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * One ticket, its thread and the two writes on it.
 *
 * ⛔ THREE ASSERTIONS HERE GUARD SOMETHING A CUSTOMER SEES. That a viewer gets no reply box at all;
 * that a reply in flight disables the SEND control and not the status controls; and that an unknown
 * notification outcome says NOTHING. The last is the sharpest: `lastNotified` is null on a degraded
 * replay, and rendering that as "your customer was not emailed" is a claim about someone's inbox
 * that the reply route never made.
 *
 * ⚠️ THE WIDE QUALIFIER IS LOAD-BEARING. The thread is a `LazyColumn` whose first item is the whole
 * ticket header (reference, subject, three contact lines and the three status controls), so on the
 * default 320x470dp Robolectric device the first message is never composed and the third status
 * control is pushed past the right edge — both of which fail on a screen that is behaving
 * correctly. This is the same qualifier every workspace-settings test carries.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class DeskTicketScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** ⚠️ A recorder rather than a parameter bundle, the same as its sibling settings test. */
    private class Recorder {
        val drafts = mutableListOf<String>()
        val statuses = mutableListOf<DeskTicketStatus>()
        var sends = 0
        var retries = 0
        var backs = 0
    }

    private val ticket = DeskTicketDetail(
        id = "tkt_1",
        displayReference = "T-42",
        subject = "Leaking tap",
        status = "open",
        source = "voice-call",
        requesterName = "Ada Lovelace",
        requesterEmail = "ada@example.com",
        requesterPhone = "+14165550142",
        messages = listOf(
            DeskMessage(id = "m1", authorType = "customer", body = "The tap drips."),
            DeskMessage(id = "m2", authorType = "team", body = "We will come Thursday."),
        ),
    )

    private val loaded = DeskTicketUiState.Content(ticket = ticket)

    private fun render(
        state: DeskTicketUiState,
        draft: String = "",
        canUse: Boolean = true,
        recorder: Recorder = Recorder(),
    ) {
        composeRule.setContent {
            DistrictTheme {
                DeskTicketScreen(
                    state = state,
                    draft = draft,
                    canUse = canUse,
                    onDraftChange = { recorder.drafts += it },
                    onSend = { recorder.sends++ },
                    onSetStatus = { recorder.statuses += it },
                    onRetry = { recorder.retries++ },
                    onBack = { recorder.backs++ },
                )
            }
        }
    }

    private fun statusButton(status: DeskTicketStatus) =
        composeRule.onNodeWithContentDescription("$DESK_TICKET_SET_STATUS_DESCRIPTION-${status.wire}")

    @Test
    fun `loading shows skeletons under the generic title, because there is no reference yet`() {
        render(DeskTicketUiState.Loading)

        composeRule.onNodeWithContentDescription(DESK_TICKET_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_TICKET_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Ticket").assertIsDisplayed()
    }

    @Test
    fun `a viewer is refused and is offered no reply box at all`() {
        // ⛔ THE PAYLOAD IS THE CUSTOMER'S NAME, EMAIL AND PHONE IN THE CLEAR PLUS THE
        // CORRESPONDENCE ABOUT THEM. A read-only seat must be shown the refusal rather than a
        // captioned thread, and must not be given a control that can write into it.
        render(loaded, canUse = false)

        composeRule.onNodeWithContentDescription(DESK_TICKET_REFUSED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_TICKET_REPLY_DESCRIPTION).assertDoesNotExist()
        statusButton(DeskTicketStatus.RESOLVED).assertDoesNotExist()
    }

    @Test
    fun `a retryable failure offers Try again and the tap reports it`() {
        val recorder = Recorder()
        render(
            DeskTicketUiState.Failed(
                FailureText(message = UiText.Literal("Offline"), retryable = true),
            ),
            recorder = recorder,
        )

        composeRule.onNodeWithContentDescription(DESK_TICKET_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, recorder.retries)
    }

    @Test
    fun `a role refusal offers no retry, because it answers identically every time`() {
        render(
            DeskTicketUiState.Failed(
                FailureText(message = UiText.Literal("Forbidden"), retryable = false),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a loaded ticket is titled by its reference rather than by the generic word`() {
        // ⚠️ The reference is what an operator says out loud to a customer, so it is what the bar
        // carries once it is known. It appears twice: in the bar and as the card's eyebrow.
        render(loaded)

        composeRule.onAllNodesWithText("T-42").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Ticket").assertDoesNotExist()
    }

    @Test
    fun `the header shows the subject and every contact detail the ticket carries`() {
        render(loaded)

        composeRule.onNodeWithText("Leaking tap").assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("ada@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("+14165550142").assertIsDisplayed()
    }

    @Test
    fun `a ticket with no contact details invents none`() {
        // ⚠️ A ticket raised during a call may genuinely carry none of the three, and the header
        // shows what is there rather than a placeholder line per missing field.
        render(
            loaded.copy(
                ticket = ticket.copy(
                    requesterName = null,
                    requesterEmail = null,
                    requesterPhone = null,
                ),
            ),
        )

        composeRule.onNodeWithText("Leaking tap").assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertDoesNotExist()
        composeRule.onNodeWithText("ada@example.com").assertDoesNotExist()
    }

    @Test
    fun `all three states are offered and the tap reports the one picked`() {
        // ⚠️ NOT CONFIRM-GATED, DELIBERATELY. Nothing here is irreversible or billable: the three
        // states are values an operator moves between freely and the server echoes the result, so a
        // confirmation on each would be friction charged for no risk.
        val recorder = Recorder()
        render(loaded, recorder = recorder)

        statusButton(DeskTicketStatus.OPEN).assertIsDisplayed()
        statusButton(DeskTicketStatus.WAITING).assertIsDisplayed()
        statusButton(DeskTicketStatus.RESOLVED).performClick()

        assertEquals(listOf(DeskTicketStatus.RESOLVED), recorder.statuses)
    }

    @Test
    fun `the status controls are disabled while a status change is in flight`() {
        render(loaded.copy(statusChanging = true))

        statusButton(DeskTicketStatus.OPEN).assertIsNotEnabled()
        statusButton(DeskTicketStatus.WAITING).assertIsNotEnabled()
        statusButton(DeskTicketStatus.RESOLVED).assertIsNotEnabled()
    }

    @Test
    fun `a reply in flight leaves the status controls alone`() {
        // ⛔ TWO FLAGS, NOT ONE SHARED BUSY. An operator may reasonably resolve a ticket while a
        // reply is in flight, and one shared flag would disable a control because of the other's
        // request.
        render(loaded.copy(sending = true), draft = "On our way")

        statusButton(DeskTicketStatus.RESOLVED).assertIsEnabled()
        composeRule.onNodeWithContentDescription(DESK_TICKET_REPLY_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a failed status change is shown in the thread rather than discarded`() {
        render(
            loaded.copy(
                statusFailure = FailureText(message = UiText.Literal("Could not move that ticket.")),
            ),
        )

        composeRule.onNodeWithContentDescription(DESK_TICKET_STATUS_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Could not move that ticket.").assertIsDisplayed()
    }

    @Test
    fun `each message is attributed to the author the server fixed`() {
        // ⛔ THE ATTRIBUTION IS NOT THIS SCREEN'S TO CHOOSE. An operator's reply is the team's, and
        // a ticket an operator raised on a customer's behalf is the CUSTOMER'S, because it is their
        // problem. Relabelling either would make the thread read as us talking to ourselves.
        render(loaded)

        composeRule.onNodeWithText("The tap drips.").assertIsDisplayed()
        composeRule.onNodeWithText("Customer").assertIsDisplayed()
        composeRule.onNodeWithText("We will come Thursday.").assertIsDisplayed()
        composeRule.onNodeWithText("Your team").assertIsDisplayed()
    }

    @Test
    fun `a receptionist message is labelled as the receptionist`() {
        render(
            loaded.copy(
                ticket = ticket.copy(
                    messages = listOf(
                        DeskMessage(id = "m3", authorType = "assistant", body = "Booked for Thursday."),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Receptionist").assertIsDisplayed()
        composeRule.onNodeWithText("Booked for Thursday.").assertIsDisplayed()
    }

    @Test
    fun `an unrecognised author is attributed to the customer rather than to us`() {
        // ⛔ THE COLUMN IS PLAIN TEXT so a fourth author type can arrive without a migration, and
        // Customer is the conservative reading: it is the only one of the three labels that does
        // not claim the message came from us.
        render(
            loaded.copy(
                ticket = ticket.copy(
                    messages = listOf(
                        DeskMessage(id = "m4", authorType = "sms-gateway", body = "Hello."),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Hello.").assertIsDisplayed()
        composeRule.onNodeWithText("Customer").assertIsDisplayed()
        composeRule.onNodeWithText("Your team").assertDoesNotExist()
    }

    @Test
    fun `a reply the server confirmed was emailed says so`() {
        render(loaded.copy(lastNotified = true))

        composeRule.onNodeWithContentDescription(DESK_TICKET_NOTIFIED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_TICKET_NOT_NOTIFIED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a reply the server said was not emailed says that instead`() {
        render(loaded.copy(lastNotified = false))

        composeRule.onNodeWithContentDescription(DESK_TICKET_NOT_NOTIFIED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DESK_TICKET_NOTIFIED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an unknown notification outcome says nothing at all`() {
        // ⛔ NULL IS "WE DO NOT KNOW". The reply route omits `notified` entirely on a degraded
        // replay, and asserting either sentence there would be a claim about the customer's inbox
        // that nothing supports.
        render(loaded.copy(lastNotified = null))

        composeRule.onNodeWithContentDescription(DESK_TICKET_NOTIFIED_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(DESK_TICKET_NOT_NOTIFIED_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `send is refused on an empty draft`() {
        render(loaded, draft = "")

        composeRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun `whitespace is not a reply`() {
        render(loaded, draft = "   ")

        composeRule.onNodeWithText("Send").assertIsNotEnabled()
    }

    @Test
    fun `a filled draft sends, and the tap reports it once`() {
        val recorder = Recorder()
        render(loaded, draft = "We will come Thursday.", recorder = recorder)

        composeRule.onNodeWithText("Send").assertIsEnabled().performClick()

        assertEquals(1, recorder.sends)
    }

    @Test
    fun `typing in the reply box reports the draft`() {
        val recorder = Recorder()
        render(loaded, recorder = recorder)

        composeRule.onNodeWithContentDescription(DESK_TICKET_REPLY_DESCRIPTION)
            .performTextInput("On our way")

        assertEquals("On our way", recorder.drafts.firstOrNull())
    }

    @Test
    fun `sending relabels the button and disables it, so a double tap cannot email twice`() {
        render(loaded.copy(sending = true), draft = "We will come Thursday.")

        composeRule.onNodeWithText("Sending…").assertIsNotEnabled()
        composeRule.onNodeWithText("Send").assertDoesNotExist()
    }

    @Test
    fun `a failed send is shown above the box, with the draft still in hand`() {
        val recorder = Recorder()
        render(
            loaded.copy(
                sendFailure = FailureText(message = UiText.Literal("That reply was not sent.")),
            ),
            draft = "We will come Thursday.",
            recorder = recorder,
        )

        composeRule.onNodeWithContentDescription(DESK_TICKET_SEND_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("That reply was not sent.").assertIsDisplayed()
        // ⚠️ Still sendable: the operator's words survive the failure and one tap retries them.
        composeRule.onNodeWithText("Send").assertIsEnabled().performClick()

        assertEquals(1, recorder.sends)
    }

    @Test
    fun `the app bar back action reports the tap`() {
        val recorder = Recorder()
        render(loaded, recorder = recorder)

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, recorder.backs)
    }

    @Test
    fun `the thread follows an echoed status and a new message without losing the controls`() {
        // ⚠️ One screen, its ticket replaced the way the ViewModel adopts the server's echo: the
        // header redraws the new status as selected and the controls still report taps.
        val statuses = mutableListOf<DeskTicketStatus>()
        var state by mutableStateOf<DeskTicketUiState>(loaded)
        composeRule.setContent {
            DistrictTheme {
                DeskTicketScreen(
                    state = state,
                    draft = "",
                    canUse = true,
                    onDraftChange = {},
                    onSend = {},
                    onSetStatus = { statuses += it },
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        state = DeskTicketUiState.Content(
            ticket = ticket.copy(
                status = "waiting",
                messages = ticket.messages + DeskMessage(id = "m3", authorType = "team", body = "On our way."),
            ),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("On our way.").assertIsDisplayed()
        statusButton(DeskTicketStatus.RESOLVED).performClick()
        assertEquals(listOf(DeskTicketStatus.RESOLVED), statuses)
    }
}
