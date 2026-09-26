package com.distronode.districtai.ui.desk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.DeskTicketSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the desk queue renders, and the three things it must never render:
 * a disabled desk as an empty one, an empty one as a failure, and a viewer as either.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DeskScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val ticket = DeskTicketSummary(
        id = "tkt_1",
        displayReference = "T-1",
        subject = "Leaking tap",
        status = "open",
        source = "voice-call",
        requesterName = "Ada Lovelace",
    )

    /** ⚠️ Bundled: `DeskScreen` takes eight parameters and a mirroring helper would trip detekt. */
    private class Callbacks(
        val onOpenTicket: (DeskTicketSummary) -> Unit = {},
        val onFilter: (DeskTicketStatus?) -> Unit = {},
        val onCompose: () -> Unit = {},
        val onEnable: () -> Unit = {},
        val onSettings: () -> Unit = {},
        val onRetry: () -> Unit = {},
    )

    private fun render(
        state: DeskUiState,
        canUse: Boolean = true,
        callbacks: Callbacks = Callbacks(),
        // ⚠️ Beside the bundle rather than in it: a seventh field trips detekt's constructor ceiling.
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                DeskScreen(
                    state = state,
                    canUse = canUse,
                    onOpenTicket = callbacks.onOpenTicket,
                    onFilter = callbacks.onFilter,
                    onCompose = callbacks.onCompose,
                    onEnable = callbacks.onEnable,
                    onSettings = callbacks.onSettings,
                    onRetry = callbacks.onRetry,
                    onBack = onBack,
                )
            }
        }
    }

    private fun content(
        tickets: List<DeskTicketSummary> = listOf(ticket),
        filter: DeskTicketStatus? = null,
        refreshing: Boolean = false,
    ) = DeskUiState.Content(
        tickets = tickets,
        settings = DeskSettings(enabled = true),
        filter = filter,
        refreshing = refreshing,
    )

    @Test
    fun `a disabled desk shows its own screen, not the empty one`() {
        render(DeskUiState.Disabled(DeskSettings(enabled = false)))

        composeRule.onNodeWithContentDescription(DESK_DISABLED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("The customer desk is off").assertIsDisplayed()
    }

    @Test
    fun `an enabled desk with no tickets shows the EMPTY screen, which says something different`() {
        render(content(tickets = emptyList()))

        composeRule.onNodeWithContentDescription(DESK_EMPTY_DESCRIPTION).assertIsDisplayed()
        // ⛔ "The desk is on" is the load-bearing half of this sentence. It is what stops an
        // operator concluding their customers' tickets have been lost.
        val onCopy = "The desk is on. Tickets your customers raise, and any the " +
            "receptionist takes during a call, appear here."
        composeRule.onNodeWithText(onCopy).assertIsDisplayed()
    }

    @Test
    fun `a viewer sees why rather than an empty queue`() {
        render(content(), canUse = false)

        composeRule.onNodeWithContentDescription(DESK_REFUSED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failure offers a retry only when retrying could work`() {
        render(
            DeskUiState.Failed(
                FailureText(message = UiText.Literal("Offline"), retryable = true),
            ),
        )

        composeRule.onNodeWithContentDescription(DESK_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun `a role refusal shows no retry, because it answers identically every time`() {
        render(
            DeskUiState.Failed(
                FailureText(message = UiText.Literal("Forbidden"), retryable = false),
            ),
        )

        composeRule.onNodeWithText("Forbidden").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a ticket row shows the subject, the requester and the from-call badge`() {
        render(content())

        composeRule.onNodeWithText("Leaking tap").assertIsDisplayed()
        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("From a call").assertIsDisplayed()
    }

    @Test
    fun `a ticket with no contact details says so rather than showing a blank line`() {
        render(content(tickets = listOf(ticket.copy(requesterName = null, source = "manual"))))

        // ⚠️ A ticket raised during a call may genuinely carry none of the three, and a blank
        // subtitle reads as a rendering fault rather than as the truth.
        composeRule.onNodeWithText("No contact details").assertIsDisplayed()
    }

    @Test
    fun `an unrecognised status renders as itself rather than being hidden`() {
        // ⛔ THE COLUMN IS PLAIN TEXT, so a fourth state added server-side must not make a ticket
        // unreadable on an installed build.
        render(content(tickets = listOf(ticket.copy(status = "escalated"))))

        composeRule.onNodeWithText("escalated").assertIsDisplayed()
    }

    @Test
    fun `the chips carry counts over the WHOLE queue, not the filtered view`() {
        render(
            content(
                tickets = listOf(
                    ticket,
                    ticket.copy(id = "t2", status = "resolved"),
                    ticket.copy(id = "t3", status = "resolved"),
                ),
                filter = DeskTicketStatus.RESOLVED,
            ),
        )

        // ⚠️ The open chip still reads 1 while the resolved filter is applied. Counting the visible
        // rows instead would make every chip but the selected one read zero.
        composeRule.onNodeWithText("Open 1").assertIsDisplayed()
        composeRule.onNodeWithText("Resolved 2").assertIsDisplayed()
    }

    @Test
    fun `a filter that matches nothing says so without claiming the queue is empty`() {
        render(content(filter = DeskTicketStatus.RESOLVED))

        composeRule.onNodeWithContentDescription(DESK_FILTER_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `tapping a chip reports the status`() {
        var picked: DeskTicketStatus? = null
        render(content(), callbacks = Callbacks(onFilter = { picked = it }))

        composeRule
            .onNodeWithContentDescription("$DESK_FILTER_DESCRIPTION-waiting")
            .performClick()

        assertEquals(DeskTicketStatus.WAITING, picked)
    }

    @Test
    fun `tapping a row reports the ticket`() {
        var opened: DeskTicketSummary? = null
        render(content(), callbacks = Callbacks(onOpenTicket = { opened = it }))

        composeRule.onNodeWithText("Leaking tap").performClick()

        assertEquals("tkt_1", opened?.id)
    }

    @Test
    fun `a queue at the route's cap says the list is not complete`() {
        val many = (1..100).map { ticket.copy(id = "tkt_$it") }
        render(content(tickets = many))

        // ⚠️ STATED RATHER THAN IMPLIED. The route caps at 100 with no cursor, so a busy desk
        // silently loses its oldest tickets and nothing else would say so.
        // ⛔ SCROLLED TO FIRST, AND THAT IS NOT TEST CEREMONY. The notice is the LAST item of a
        // `LazyColumn` holding a hundred tickets, so it is never composed at rest and a bare
        // `assertIsDisplayed` fails on a screen that is behaving correctly. The inbox twin passes
        // without this only because its own cap is five and the whole list fits.
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(DESK_CAPPED_DESCRIPTION))
        composeRule.onNodeWithContentDescription(DESK_CAPPED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the app bar back action reports the tap`() {
        var backs = 0
        render(content(), onBack = { backs++ })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `a refresh keeps the queue on screen under a progress bar`() {
        render(content(refreshing = true))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        composeRule.onNodeWithText("Leaking tap").assertIsDisplayed()
    }

    @Test
    fun `a requester known only by email or only by phone is named by what the ticket carries`() {
        render(
            content(
                tickets = listOf(
                    ticket.copy(id = "t-email", requesterName = null, requesterEmail = "ada@example.com"),
                    ticket.copy(id = "t-phone", requesterName = null, requesterPhone = "+14165550142"),
                ),
            ),
        )

        composeRule.onNodeWithText("ada@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("+14165550142").assertIsDisplayed()
        composeRule.onNodeWithText("No contact details").assertDoesNotExist()
    }

    @Test
    fun `the chips follow the filter as it moves, and each still reports its own status`() {
        // ⚠️ One screen with its filter changed underneath it, the way the ViewModel moves it: every
        // chip is redrawn against the new selection and must go on reporting the right status.
        val picked = mutableListOf<DeskTicketStatus?>()
        var state by mutableStateOf<DeskUiState>(content())
        composeRule.setContent {
            DistrictTheme {
                DeskScreen(
                    state = state,
                    canUse = true,
                    onOpenTicket = {},
                    onFilter = { picked += it },
                    onCompose = {},
                    onEnable = {},
                    onSettings = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("$DESK_FILTER_DESCRIPTION-resolved").performClick()
        state = content(filter = DeskTicketStatus.RESOLVED)
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(DESK_FILTER_EMPTY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("$DESK_FILTER_DESCRIPTION-open").performClick()

        assertEquals(listOf(DeskTicketStatus.RESOLVED, DeskTicketStatus.OPEN), picked)
    }

    @Test
    fun `each known status is badged with its own name`() {
        render(
            content(
                tickets = listOf(
                    ticket.copy(id = "t-open", subject = "One"),
                    ticket.copy(id = "t-waiting", subject = "Two", status = "waiting"),
                    ticket.copy(id = "t-resolved", subject = "Three", status = "resolved"),
                ),
            ),
        )

        // ⚠️ Exact matches: the filter chips read "Waiting 1" and "Resolved 1", so only a badge matches.
        composeRule.onNodeWithText("Open").assertIsDisplayed()
        composeRule.onNodeWithText("Waiting").assertIsDisplayed()
        composeRule.onNodeWithText("Resolved").assertIsDisplayed()
    }
}
