package com.distronode.districtai.ui.incoming

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.call.IncomingCallPhase
import com.distronode.districtai.call.IncomingCallUiState
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.dialer.ActiveCallUiState
import com.distronode.districtai.ui.dialer.IN_CALL_HANG_UP_DESCRIPTION
import com.distronode.districtai.ui.dialer.IN_CALL_NUMBER_DESCRIPTION
import com.distronode.districtai.ui.dialer.IN_CALL_ROOT_DESCRIPTION
import com.distronode.districtai.ui.dialer.IN_CALL_TIMER_DESCRIPTION
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The ringing screen, and the hand-off to the shared in-call surface.
 *
 * ⛔ THE FAILURE THAT IS ONLY VISIBLE IN THE RENDERING IS THE **DECLINE BUTTON SURVIVING INTO THE
 * ANSWER**. Once the answer round trip is out, a decline would tear down a call whose credential has
 * already been spent and whose rendezvous the server has already read — so the control has to be
 * absent, not merely disabled, and only a rendering can say which it is.
 *
 * ⛔ THE SECOND IS THE CALLER LABEL. This client never learns who is calling: the push carries
 * identifiers only, and the answer route returns a join credential rather than a caller. The label
 * is therefore the whole of what is knowable, and it has to pass through `InCallScreen`'s number
 * slot without being regrouped into nonsense by the dial formatter.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class IncomingCallScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var answers = 0
    private var declines = 0
    private var hangUps = 0
    private var dismisses = 0
    private var micTaps = 0

    private fun render(state: IncomingCallUiState, modifier: Modifier = Modifier) {
        composeRule.setContent {
            DistrictTheme {
                IncomingCallScreen(
                    modifier = modifier,
                    state = state,
                    handlers = IncomingCallHandlers(
                        onAnswer = { answers++ },
                        onDecline = { declines++ },
                        onToggleMicrophone = { micTaps++ },
                        onToggleSpeaker = {},
                        onHangUp = { hangUps++ },
                        onDismiss = { dismisses++ },
                    ),
                )
            }
        }
    }

    private fun ringing(phase: IncomingCallPhase = IncomingCallPhase.RINGING) = IncomingCallUiState(
        workspaceId = "ws-1",
        callId = "CA1",
        phase = phase,
    )

    @Test
    fun `a ringing call offers Answer and Decline`() {
        render(ringing())

        composeRule.onNodeWithContentDescription(INCOMING_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_DECLINE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_PHASE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `Answer and Decline reach their handlers`() {
        render(ringing())

        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(INCOMING_DECLINE_DESCRIPTION).performClick()

        assertEquals(1, answers)
        assertEquals(1, declines)
    }

    @Test
    fun `the ringing screen says the microphone is about to be used`() {
        // ⚠️ THE ONE PIECE OF COPY THAT IS NOT DECORATION. Answering puts this operator's microphone
        // into a live customer conversation, and the notice is where that is said before the tap
        // rather than after it.
        render(ringing())

        composeRule.onNodeWithContentDescription(INCOMING_NOTICE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `once answering, neither button is drawn at all`() {
        // ⛔ ABSENT, NOT DISABLED, AND THE DIFFERENCE IS BOTH SAFETY AND LEGIBILITY. A decline landing
        // mid-join would tear down a call whose credential has already been spent; and a disabled
        // Answer button on a call that is already connecting looks momentarily broken, where an
        // absent one is a state change the user can read.
        render(ringing(IncomingCallPhase.ANSWERING))

        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(INCOMING_DECLINE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(INCOMING_PHASE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `ANSWERING keeps the connecting screen even once a session exists`() {
        // ⛔ THE CONTROLLER STARTS MIRRORING THE SESSION BEFORE THE JOIN RESOLVES, so `call` is
        // non-null while the phase is still ANSWERING. A screen that branched on nullability alone
        // would flip to the in-call surface at that instant and draw "Calling…" with a live hang-up
        // button over a call that has not been joined yet — skipping the connecting state entirely,
        // and offering a control whose media does not exist.
        render(
            ringing(IncomingCallPhase.ANSWERING).copy(
                call = ActiveCallUiState(number = ""),
            ),
        )

        composeRule.onNodeWithContentDescription(INCOMING_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_ROOT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a call that ended before connecting shows its message and a way out`() {
        render(
            ringing(IncomingCallPhase.ENDED).copy(
                message = FailureText(UiText.Literal("That call ended."), retryable = false),
            ),
        )

        composeRule.onNodeWithContentDescription(INCOMING_MESSAGE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_DISMISS_DESCRIPTION).performClick()

        assertEquals(1, dismisses)
    }

    @Test
    fun `a connected call hands over to the shared in-call surface`() {
        // ⛔ REUSED RATHER THAN REBUILT. Mute, speaker, hang up, the duration, the reconnecting
        // banner and the ended summary are properties of a live audio call rather than of how it
        // started, and a second copy would be the thing that drifts.
        render(
            ringing(IncomingCallPhase.IN_CALL).copy(
                call = ActiveCallUiState(
                    number = "",
                    connection = CallConnectionState.Connected,
                    answered = true,
                    elapsedSeconds = 42,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(IN_CALL_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_ROOT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the caller slot shows a label rather than an empty line`() {
        // ⛔ THE PUSH CARRIES NO NUMBER AND NO NAME, so the session's `number` is empty — and an
        // empty headline on a live call reads as a broken screen. The label is substituted in
        // composition, where `stringResource` can reach it.
        render(
            ringing(IncomingCallPhase.IN_CALL).copy(
                call = ActiveCallUiState(number = "", answered = true),
            ),
        )

        composeRule.onNodeWithContentDescription(IN_CALL_NUMBER_DESCRIPTION)
            .assertTextEquals("Caller")
    }

    @Test
    fun `hanging up from the connected surface reaches the incoming handler`() {
        // ⚠️ DISTINCT FROM Decline EVEN THOUGH BOTH END THE CALL: decline refuses a ring, hang up
        // ends a conversation, and only one of them is reachable at a time.
        render(
            ringing(IncomingCallPhase.IN_CALL).copy(
                call = ActiveCallUiState(number = "", answered = true),
            ),
        )

        composeRule.onNodeWithContentDescription(IN_CALL_HANG_UP_DESCRIPTION).performClick()

        assertEquals(1, hangUps)
        assertEquals("a hang-up is not a decline", 0, declines)
    }

    @Test
    fun `the caller's modifier reaches the ringing screen`() {
        render(ringing(), Modifier.testTag("host-ring"))
        composeRule.onNodeWithTag("host-ring").assertIsDisplayed()
    }

    @Test
    fun `the caller's modifier reaches the in-call surface`() {
        render(
            ringing(IncomingCallPhase.IN_CALL).copy(call = ActiveCallUiState(number = "", answered = true)),
            Modifier.testTag("host-call"),
        )
        composeRule.onNodeWithTag("host-call").assertIsDisplayed()
    }

    @Test
    fun `in call with no session yet offers no ring buttons`() {
        // ⚠️ REACHABLE for a frame: the controller marks the call IN_CALL once media is up, and the
        // session's first state arrives through its mirror a dispatch later. Answer and Decline
        // must not flash back in that gap.
        render(ringing(IncomingCallPhase.IN_CALL))

        composeRule.onNodeWithContentDescription(INCOMING_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(INCOMING_DECLINE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(INCOMING_DISMISS_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the design preview draws a ringing call`() {
        composeRule.setContent { IncomingCallScreenPreview() }

        composeRule.onNodeWithContentDescription(INCOMING_ANSWER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(INCOMING_DECLINE_DESCRIPTION).assertIsDisplayed()
    }
}
