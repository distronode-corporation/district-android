package com.distronode.districtai.ui.dialer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ WHAT THIS SCREEN GETS WRONG IS ONLY VISIBLE IN THE RENDERING, WHICH IS WHY IT IS TESTED HERE
 * AND NOT ONLY AT THE STATE. Four failures live entirely in the drawing: offering a dial control to
 * a role the server refuses (a button that 403s), drawing a duration while the callee's phone is
 * still ringing (counting ringing as conversation on the number an operator checks against an
 * invoice), showing a camera control on a call that publishes no video, and rendering a refused
 * dial as a call in progress.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DialerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var entries = mutableListOf<String>()
    private var callBacks = mutableListOf<String>()
    private var dialTaps = 0
    private var micTaps = 0
    private var speakerTaps = 0
    private var hangUpTaps = 0
    private var dismissTaps = 0

    private fun render(state: DialerUiState) {
        composeRule.setContent {
            DistrictTheme {
                DialerScreen(
                    state = state,
                    handlers = DialerHandlers(
                        onEntryChange = { entries += it },
                        onCallBack = { callBacks += it },
                        onDial = { dialTaps++ },
                        onToggleMicrophone = { micTaps++ },
                        onToggleSpeaker = { speakerTaps++ },
                        onHangUp = { hangUpTaps++ },
                        onDismissEndedCall = { dismissTaps++ },
                        onBack = {},
                    ),
                )
            }
        }
    }

    private fun idle(
        canDial: Boolean = true,
        entry: String = "",
        callbacks: CallbacksState = CallbacksState.Ready(emptyList()),
        refusal: FailureText? = null,
    ) = DialerUiState(canDial = canDial, entry = entry, callbacks = callbacks, refusal = refusal)

    // ── The keypad ───────────────────────────────────────────────────────────

    @Test
    fun `the entry card and the call control are drawn for a role that may dial`() {
        render(idle(entry = "+14165550100"))

        composeRule.onNodeWithContentDescription(DIALER_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DIALER_ENTRY_CARD_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(DIALER_FIELD_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(DIALER_CALL_DESCRIPTION).assertIsEnabled()
        // ⚠️ THE MICROPHONE NOTICE IS SHOWN BEFORE THE DIALOG, so the permission request has a
        // visible reason. A request with no explanation is the one users deny permanently.
        composeRule.onNodeWithContentDescription(DIALER_MIC_NOTICE_DESCRIPTION).assertExists()
    }

    @Test
    fun `a viewer gets no dial control at all, and is told why`() {
        // ⛔ PRESENCE, NOT WORDING. `POST calls/dial` excludes `viewer` server-side, so a control
        // here would be one that 403s on the screen's only action.
        render(idle(canDial = false, entry = "+14165550100"))

        composeRule.onNodeWithContentDescription(DIALER_CALL_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(DIALER_VIEWER_NOTICE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a number too short to dial leaves the control disabled`() {
        // ⚠️ Counted on DIGITS, so this thirteen-character five-digit entry is short. A client
        // counting characters would enable the button for a 400 that reads as a server fault.
        render(idle(entry = "(416) 5"))

        composeRule.onNodeWithContentDescription(DIALER_CALL_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `the field holds the raw entry and the formatted form sits beside it`() {
        // ⛔ THE PREVIEW NEVER REWRITES THE FIELD. The server normalises its own copy and dials
        // against that, so a client-side rewrite could place a call the DNC check never saw — and
        // rewriting as someone types also moves the cursor. The wording of the grouping itself is
        // `DialFormatTest`'s property; what matters here is that both are drawn.
        render(idle(entry = "+14165550100"))

        composeRule.onNodeWithContentDescription(DIALER_PREVIEW_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("+14165550100").assertExists()
    }

    @Test
    fun `pressing call fires once`() {
        render(idle(entry = "+14165550100"))

        composeRule.onNodeWithContentDescription(DIALER_CALL_DESCRIPTION).performClick()

        assertEquals(1, dialTaps)
    }

    @Test
    fun `a refusal is shown on the keypad, where the call would have been`() {
        // ⚠️ A REFUSED DIAL PRODUCED NO CALL, so there is no call screen to show it over.
        render(
            idle(
                entry = "+14165550100",
                refusal = FailureText(
                    message = UiText.Resource(R.string.dialer_subscription_inactive),
                    retryable = false,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(DIALER_REFUSAL_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_ROOT_DESCRIPTION).assertDoesNotExist()
    }

    // ── The call-back list ───────────────────────────────────────────────────

    @Test
    fun `a call-back row fills the field rather than dialling`() {
        // ⛔ ONE TAP MUST NEVER PLACE A CALL, least of all from a scrolling list.
        render(idle(callbacks = CallbacksState.Ready(listOf(callRow()))))

        composeRule.onNodeWithContentDescription(callbackRowDescription("call-1")).performClick()

        assertEquals(listOf("+14165550100"), callBacks)
        assertEquals(0, dialTaps)
    }

    @Test
    fun `an empty call-back list explains itself rather than showing nothing`() {
        render(idle())

        composeRule.onNodeWithContentDescription(DIALER_CALLBACKS_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed call-back read leaves the keypad usable`() {
        // ⛔ THE TWO ARE UNRELATED SERVER SURFACES. An outage of the call log must not become an
        // inability to place a call.
        render(
            idle(
                entry = "+14165550100",
                callbacks = CallbacksState.Failed(FailureText(message = UiText.Literal("offline"))),
            ),
        )

        composeRule.onNodeWithContentDescription(DIALER_CALLBACKS_FAILED_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(DIALER_CALL_DESCRIPTION).assertIsEnabled()
    }

    // ── The call takes over the same destination ─────────────────────────────

    @Test
    fun `a live call replaces the keypad rather than opening a destination`() {
        // ⛔ ONE DESTINATION, BECAUSE A CALL DESTINATION WOULD BE RESTORED AFTER PROCESS DEATH and
        // its start effect would place a second billable call with no user action.
        render(
            idle().copy(
                call = ActiveCallUiState(
                    number = "+14165550100",
                    connection = CallConnectionState.Connected,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(IN_CALL_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(DIALER_ROOT_DESCRIPTION).assertDoesNotExist()
    }

    private fun callRow() = CallSummary(
        id = "call-1",
        type = "inbound",
        number = "Ada",
        status = "completed",
        duration = "1m 5s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = false,
        callerName = "Ada",
        from = "+14165550100",
        direction = "inbound",
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
    )
}
