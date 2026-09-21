package com.distronode.districtai.ui.dialer

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THE THREE FAILURES THAT ARE ONLY VISIBLE IN THE RENDERING.
 *  1. A duration drawn while the callee's phone is still ringing. `Connected` means "in the room",
 *     not "on the phone", and the number on this screen is what an operator compares against an
 *     invoice — so the timer must not exist during the ring.
 *  2. A camera control on a call that publishes no video. The token the dial route mints would
 *     PERMIT video, so the absence of the control is the only thing stopping a phone call becoming
 *     a billed video upload nobody can see.
 *  3. `Reconnecting` drawn as a failure. A phone walking out of wifi does this routinely and the
 *     SDK recovers by itself; a failure screen teaches people to hang up on a call that was about
 *     to come back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class InCallScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var micTaps = 0
    private var speakerTaps = 0
    private var hangUpTaps = 0
    private var dismissTaps = 0

    private fun render(call: ActiveCallUiState) {
        composeRule.setContent {
            DistrictTheme {
                InCallScreen(
                    call = call,
                    handlers = DialerHandlers(
                        onEntryChange = {},
                        onCallBack = {},
                        onDial = {},
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

    private fun call(
        connection: CallConnectionState = CallConnectionState.Connected,
        answered: Boolean = false,
        elapsedSeconds: Int = 0,
        micEnabled: Boolean = true,
        speakerOn: Boolean = false,
        ended: Boolean = false,
    ) = ActiveCallUiState(
        number = "+14165550100",
        connection = connection,
        answered = answered,
        elapsedSeconds = elapsedSeconds,
        micEnabled = micEnabled,
        speakerOn = speakerOn,
        ended = ended,
    )

    // ── The four phases ──────────────────────────────────────────────────────

    @Test
    fun `placing the call says so and shows no duration`() {
        render(call(connection = CallConnectionState.Connecting))

        composeRule.onNodeWithContentDescription(IN_CALL_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_DIALING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `being in the room while the far end rings says Calling and shows no duration`() {
        // ⛔ THE ONE THAT MATTERS. The dial route returns before the callee's phone rings, so a
        // screen that started a timer on `Connected` would count the ringing as conversation.
        render(call(connection = CallConnectionState.Connected, answered = false))

        composeRule.onNodeWithContentDescription(IN_CALL_RINGING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an answered call shows the duration counting up`() {
        render(call(answered = true, elapsedSeconds = 75))

        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("01:15").assertExists()
        composeRule.onNodeWithContentDescription(IN_CALL_RINGING_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an ended call keeps its duration and says where the record is`() {
        // ⚠️ THE SCREEN STAYS, rather than popping. The operator needs the duration — and needs to
        // be told the record is the call log, because this app never writes the call's outcome.
        render(call(answered = true, elapsedSeconds = 75, ended = true))

        composeRule.onNodeWithContentDescription(IN_CALL_ENDED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_LOG_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertIsDisplayed()
        // ⛔ THE LIVE CONTROLS ARE GONE. A mute button on a finished call is a control that cannot
        // work, and a hang-up on one invites a second press that would race the first's teardown.
        composeRule.onNodeWithContentDescription(IN_CALL_HANG_UP_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(IN_CALL_DONE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed connection says so instead of showing a running call`() {
        render(call(connection = CallConnectionState.Failed("unreachable")))

        composeRule.onNodeWithContentDescription(IN_CALL_FAILED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `reconnecting is a banner over the call, not a replacement for it`() {
        render(call(connection = CallConnectionState.Reconnecting, answered = true, elapsedSeconds = 30))

        composeRule.onNodeWithContentDescription(IN_CALL_RECONNECTING_DESCRIPTION).assertIsDisplayed()
        // ⛔ THE CALL IS STILL DRAWN, timer and controls included. Replacing it with an error would
        // tear down a call that was about to recover.
        composeRule.onNodeWithContentDescription(IN_CALL_TIMER_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(IN_CALL_HANG_UP_DESCRIPTION).assertIsDisplayed()
    }

    // ── Controls ─────────────────────────────────────────────────────────────

    @Test
    fun `there is no camera control anywhere on a call`() {
        // ⛔ THE SOFTPHONE PUBLISHES NO VIDEO, and the token would permit it — so the absence of a
        // control is the entire enforcement. The room screen's handles are used deliberately: if
        // somebody ever copies that row of controls across, this fails.
        render(call(answered = true))

        composeRule.onNodeWithContentDescription("district-room-camera").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("district-room-flip").assertDoesNotExist()
    }

    @Test
    fun `mute, speaker and hang up each fire once`() {
        render(call(answered = true))

        composeRule.onNodeWithContentDescription(IN_CALL_MIC_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(IN_CALL_SPEAKER_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(IN_CALL_HANG_UP_DESCRIPTION).performClick()

        assertEquals(1, micTaps)
        assertEquals(1, speakerTaps)
        assertEquals(1, hangUpTaps)
    }

    @Test
    fun `done dismisses the ended call`() {
        render(call(ended = true, elapsedSeconds = 10))

        composeRule.onNodeWithContentDescription(IN_CALL_DONE_DESCRIPTION).performClick()

        assertEquals(1, dismissTaps)
    }
}
