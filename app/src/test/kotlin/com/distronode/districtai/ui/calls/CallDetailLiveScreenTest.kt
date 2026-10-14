package com.distronode.districtai.ui.calls

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_CALL_ENDED
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.live.FinalTranscriptState
import com.distronode.districtai.core.network.live.LiveTranscriptPhase
import com.distronode.districtai.core.network.testing.testCall
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.calls.LiveTranscriptController.Connection
import com.distronode.districtai.ui.calls.LiveTranscriptController.State
import com.distronode.districtai.ui.calls.LiveTranscriptFrames.line
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the live transcript draws on a call's screen, state by state. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CallDetailLiveScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(live: State?) {
        composeRule.setContent {
            DistrictTheme {
                CallDetailScreen(
                    state = CallDetailUiState.Content(testCall(status = "in-progress")),
                    onBack = {},
                    onRetry = {},
                    onSignIn = {},
                    onShowTranscript = {},
                    live = live,
                )
            }
        }
    }

    private fun shows(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `live lines are labelled by speaker, with interim and interrupted lines marked`() {
        render(
            State(
                lines = listOf(
                    line("a", 0, 1, speaker = "agent", speakerName = "Ava", text = "Northside Dental."),
                    line("b", 1, 2, speaker = "agent", speakerName = " ", text = "Unnamed.", interrupted = true),
                    line("c", 2, 3, speaker = "caller", text = "A cleaning, please.", isFinal = false),
                    line("d", 3, 4, speaker = "supervisor", text = "Joining."),
                ),
                phase = LiveTranscriptPhase.Live,
                connection = Connection.OPEN,
            ),
        )
        composeRule.onNodeWithTag(CALL_DETAIL_LIVE_TRANSCRIPT_TAG).performScrollTo()
        shows(str(R.string.live_transcript_heading))
        // The call's own status line reads "Live" too; the transcript's status is the second.
        composeRule.onAllNodesWithText(str(R.string.live_transcript_live)).assertCountEquals(2)
        shows("Ava")
        shows(str(R.string.live_transcript_speaker_assistant))
        shows(str(R.string.live_transcript_interrupted))
        shows(str(R.string.live_transcript_speaker_caller))
        shows("A cleaning, please.")
        shows(str(R.string.live_transcript_speaker_other))
        // The on-demand transcript gives its place to the live one.
        composeRule.onNodeWithTag(CALL_DETAIL_SHOW_TRANSCRIPT_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `live with nothing said yet, and an incomplete buffer, say so`() {
        render(State(phase = LiveTranscriptPhase.Live, complete = false, connection = Connection.OPEN))
        shows(str(R.string.live_transcript_waiting))
        composeRule.onNodeWithTag(CALL_DETAIL_LIVE_INCOMPLETE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the full transcript loads in place once the call ended`() {
        render(
            State(
                phase = LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_CALL_ENDED),
                finalTranscript = FinalTranscriptState.Loaded("The whole call."),
            ),
        )
        shows(str(R.string.live_transcript_ended))
        shows(str(R.string.live_transcript_full_transcript))
        shows("The whole call.")
    }

    @Test
    fun `a full transcript on its way, absent, or failed each say so`() {
        render(State(phase = LiveTranscriptPhase.Reconnecting, finalTranscript = FinalTranscriptState.Fetching(1)))
        shows(str(R.string.live_transcript_reconnecting))
        shows(str(R.string.live_transcript_loading_full))
    }

    @Test
    fun `an empty full transcript says there is none`() {
        render(State(phase = LiveTranscriptPhase.Ended("handed_off"), finalTranscript = FinalTranscriptState.Empty))
        shows(str(R.string.live_transcript_no_transcript))
    }

    @Test
    fun `a failed full transcript shows the failure`() {
        render(
            State(
                phase = LiveTranscriptPhase.Ended("handed_off"),
                finalTranscript = FinalTranscriptState.Failed(ApiResult.NotFound("Call not found")),
            ),
        )
        shows("Call not found")
    }

    @Test
    fun `a transcript that falls back offers the on-demand one`() {
        render(State(phase = LiveTranscriptPhase.Unavailable("not_live")))
        composeRule.onNodeWithTag(CALL_DETAIL_LIVE_TRANSCRIPT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(CALL_DETAIL_SHOW_TRANSCRIPT_DESCRIPTION).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the status reads connecting until the socket is open and live`() {
        assertEquals(R.string.live_transcript_connecting, liveStatus(State()))
        assertEquals(R.string.live_transcript_connecting, liveStatus(State(phase = LiveTranscriptPhase.Live)))
        assertEquals(
            R.string.live_transcript_connecting,
            liveStatus(State(phase = LiveTranscriptPhase.Subscribing, connection = Connection.OPEN)),
        )
    }
}
