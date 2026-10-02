package com.distronode.districtai.ui.calls

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.CallAnalysis
import com.distronode.districtai.core.model.CallFollowUp
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.distronode.districtai.core.network.testing.testCall

/**
 * What one call renders, and — more usefully — what it does NOT.
 *
 * ⛔ THE ABSENCE CASES ARE THE POINT. Nearly every field on this screen is optional, and the failure
 * mode of an optional field is not a crash: it is a card that says something untrue. Two are already
 * recorded as fixed bugs in the source and are pinned here so they cannot come back —
 * a missed call must not read "· 0s" (the server always formats a duration string, so the append is
 * guarded), and a live call must read "Live" rather than the raw wire status, via the same mapper
 * the log uses.
 *
 * ⚠️ `performScrollTo` before asserting anything below the fold. The content column is a
 * `verticalScroll`, so a composed-but-offscreen node fails `assertIsDisplayed` even though the
 * screen is correct.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class CallDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun render(
        state: CallDetailUiState,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onShowTranscript: () -> Unit = {},
        onPlayRecording: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                CallDetailScreen(
                    state = state,
                    onBack = onBack,
                    onRetry = onRetry,
                    onSignIn = {},
                    onShowTranscript = onShowTranscript,
                    onPlayRecording = onPlayRecording,
                )
            }
        }
    }

    private fun content(
        call: CallSummary = testCall(),
        transcript: TranscriptState = TranscriptState.Idle,
        recording: RecordingState = RecordingState.Idle,
    ) = CallDetailUiState.Content(call = call, transcript = transcript, recording = recording)

    private fun failure(
        message: String = "We could not load that call.",
        retryable: Boolean = true,
    ) = FailureText(message = UiText.Literal(message), retryable = retryable)

    @Test
    fun `loading shows a spinner under the app bar`() {
        render(CallDetailUiState.Loading)

        composeRule.onNodeWithContentDescription(CALL_DETAIL_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_DETAIL_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a retryable failure offers retry and calls back`() {
        var retried = 0
        render(CallDetailUiState.Failed(failure()), onRetry = { retried++ })

        composeRule.onNodeWithContentDescription(CALL_DETAIL_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("We could not load that call.").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()

        assertEquals(1, retried)
    }

    @Test
    fun `a non-retryable failure offers no retry`() {
        // ⚠️ A contract mismatch and a role refusal both produce the identical failure on every
        // attempt, so a retry button there is a control that cannot succeed.
        render(CallDetailUiState.Failed(failure(retryable = false)))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `the app bar back action is present on a LOADED call, not only on the failure`() {
        // ⚠️ The regression this pins: back used to be a text button inside the failure state, so a
        // call that loaded successfully had no visible way back at all.
        var backs = 0
        render(content(), onBack = { backs++ })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun `renders the header, direction, time and duration`() {
        render(content(testCall().copy(number = "Ada Lovelace")))

        composeRule.onNodeWithText("Ada Lovelace").assertIsDisplayed()
        composeRule.onNodeWithText("Inbound · Aug 15, 02:30 PM · 1m 5s").assertIsDisplayed()
    }

    @Test
    fun `a missed call does not read as a zero-second call`() {
        // ⛔ THE FIXED BUG. The server always formats a duration string, so appending it
        // unconditionally rendered "· 0s" for a call that never connected, while the call log
        // correctly showed nothing. The append is guarded on the mapper's durationLabel.
        render(content(testCall(status = "no-answer").copy(durationRaw = 0, duration = "0s")))

        composeRule.onNodeWithText("Inbound · Aug 15, 02:30 PM").assertIsDisplayed()
    }

    @Test
    fun `a live call reads Live rather than the wire status`() {
        // ⚠️ Through the same mapper the log uses, so the two screens cannot disagree.
        render(content(testCall(status = "in-progress")))

        composeRule.onNodeWithText("Live").assertIsDisplayed()
    }

    @Test
    fun `a call with no caller id says so instead of printing an empty name`() {
        render(content(testCall().copy(number = "", callerName = "", from = null)))

        composeRule.onNodeWithText("No caller ID").assertIsDisplayed()
    }

    @Test
    fun `optional analysis cards appear only when the field has content`() {
        render(
            content(
                testCall().copy(
                    sentiment = "Positive",
                    disposition = "Booked",
                    transferStatus = "completed",
                    transferReason = "asked for a human",
                    analysis = CallAnalysis(
                        keyPoints = listOf("Wants a Tuesday slot"),
                        objections = listOf("Price"),
                        topics = listOf("Scheduling"),
                        actionItems = listOf("Send confirmation"),
                    ),
                ),
            ),
        )

        composeRule.onNodeWithText("Positive").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Booked").performScrollTo().assertIsDisplayed()
        // ⚠️ Status and reason are joined, so a transfer that failed says why on the same card.
        composeRule.onNodeWithText("completed · asked for a human").performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("• Wants a Tuesday slot").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("• Price").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("• Scheduling").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("• Send confirmation").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an empty analysis renders no list cards at all`() {
        // ⚠️ Every analysis field defaults to an empty list because the column is unstructured
        // JSON. An empty list must produce no card rather than a labelled blank.
        render(content(testCall().copy(analysis = CallAnalysis())))

        composeRule.onNodeWithText("Key points").assertDoesNotExist()
        composeRule.onNodeWithText("Objections").assertDoesNotExist()
        composeRule.onNodeWithText("Topics").assertDoesNotExist()
        composeRule.onNodeWithText("Action items").assertDoesNotExist()
    }

    @Test
    fun `a blank summary renders no summary card`() {
        // LabelledCard returns early on a blank value, so a call the model never summarised does not
        // get a card with a heading and nothing under it.
        render(content(testCall().copy(aiSummary = "")))

        composeRule.onNodeWithText("AI summary").assertDoesNotExist()
    }

    @Test
    fun `a follow-up joins the email and sms that were actually sent`() {
        render(
            content(
                testCall().copy(
                    followUp = CallFollowUp(email = "Sent a recap", sms = "Sent a link"),
                ),
            ),
        )

        composeRule.onNodeWithText("Sent a recap\nSent a link").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a follow-up with neither channel renders an em dash rather than an empty card`() {
        // ⚠️ `ifBlank { "—" }` matters because LabelledCard would otherwise drop the card entirely,
        // and a follow-up row that exists on the wire but shows nothing reads as data loss.
        render(content(testCall().copy(followUp = CallFollowUp())))

        composeRule.onNodeWithText("—").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the play button is offered only when the row hints at a recording`() {
        render(content(testCall(recordingUrl = "https://example.invalid/rec.mp3")))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `no play button when the row has no recording url`() {
        render(content(testCall()))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `playing a recording calls back`() {
        var plays = 0
        render(
            content(testCall(recordingUrl = "https://example.invalid/rec.mp3")),
            onPlayRecording = { plays++ },
        )

        composeRule.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).performScrollTo()
            .performClick()

        assertEquals(1, plays)
    }

    @Test
    fun `an absent recording reads as a fact, not a failure`() {
        // Ordinary for a missed call.
        render(content(recording = RecordingState.Absent))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_NO_RECORDING_DESCRIPTION)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failed recording resolution shows why`() {
        render(content(recording = RecordingState.Failed(failure("That recording has expired."))))

        composeRule.onNodeWithText("That recording has expired.").performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `a recording no app could play says so and offers play again`() {
        var plays = 0
        render(content(recording = RecordingState.NoPlayer), onPlayRecording = { plays++ })

        composeRule.onNodeWithText(string(R.string.call_detail_recording_no_player)).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).performScrollTo()
            .performClick()
        assertEquals(1, plays)
    }

    @Test
    fun `a refused hand-off says so and offers play again`() {
        render(content(recording = RecordingState.LaunchFailed))

        composeRule.onNodeWithText(string(R.string.call_detail_recording_launch_failed)).performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).assertExists()
    }

    private fun string(id: Int): String =
        ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test
    fun `a resolving recording replaces the button with a spinner`() {
        render(
            content(
                call = testCall(recordingUrl = "https://example.invalid/rec.mp3"),
                recording = RecordingState.Resolving,
            ),
        )

        composeRule.onNodeWithContentDescription(CALL_DETAIL_PLAY_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the transcript is not fetched until asked for`() {
        // ⚠️ Transcripts are large enough that the timeline stopped embedding them, so Idle offers
        // a control rather than showing content.
        var shows = 0
        render(content(), onShowTranscript = { shows++ })

        composeRule.onNodeWithContentDescription(CALL_DETAIL_SHOW_TRANSCRIPT_DESCRIPTION)
            .performScrollTo().performClick()

        assertEquals(1, shows)
    }

    @Test
    fun `a loaded transcript is rendered`() {
        render(content(transcript = TranscriptState.Loaded("Agent: Hello.")))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_TRANSCRIPT_DESCRIPTION)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Agent: Hello.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `an absent transcript is the empty case rather than an error`() {
        // ⚠️ The server sends "" for a call with no transcript.
        render(content(transcript = TranscriptState.Absent))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_NO_TRANSCRIPT_DESCRIPTION)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a failed transcript fetch shows why`() {
        render(content(transcript = TranscriptState.Failed(failure("Transcript unavailable."))))

        composeRule.onNodeWithText("Transcript unavailable.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a loading transcript shows neither the control nor stale content`() {
        render(content(transcript = TranscriptState.Loading))

        composeRule.onNodeWithContentDescription(CALL_DETAIL_SHOW_TRANSCRIPT_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(CALL_DETAIL_TRANSCRIPT_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `an outbound call says so`() {
        render(content(testCall().copy(direction = "outbound", type = "outbound")))

        composeRule.onNodeWithText("Outbound · Aug 15, 02:30 PM · 1m 5s").assertIsDisplayed()
    }
}
