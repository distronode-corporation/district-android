package com.distronode.districtai.ui.rooms

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.MeetingDetail
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THREE THINGS ON THIS SCREEN ARE ONLY WRONG IN THE RENDERING. Offering "rejoin" on a finished
 * meeting (which silently starts a SECOND meeting under the name whose minutes the user was
 * reading, and the Companion writes those up too); presenting the 220-character `summaryPreview`
 * as though it were the minutes; and hiding the join form when the history read fails, which turns
 * an outage of the archive into an inability to hold a meeting.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class RoomsLobbyScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val liveMeeting = MeetingSummary(
        id = "m-live",
        roomName = "meet_ws-abc_standup",
        status = "in-progress",
        createdAt = "2026-08-18T09:00:00.000Z",
    )

    private val doneMeeting = MeetingSummary(
        id = "m-done",
        roomName = "meet_ws-abc_weekly-review",
        title = "Weekly review",
        status = "completed",
        durationSec = 2520,
        summaryPreview = "The team reviewed Thursday's inbound volume",
        participantCount = 2,
        createdAt = "2026-08-14T15:00:00.000Z",
    )

    private var typed = mutableListOf<String>()
    private var joinTaps = 0
    private var rejoined = mutableListOf<String>()
    private var opened = mutableListOf<String>()
    private var closeTaps = 0
    private var retryTaps = 0

    private fun render(state: RoomsLobbyUiState, modifier: Modifier = Modifier) {
        composeRule.setContent {
            DistrictTheme {
                RoomsLobbyScreen(
                    modifier = modifier,
                    state = state,
                    onRoomNameChange = { typed += it },
                    onJoin = { joinTaps++ },
                    onRejoin = { rejoined += it },
                    onOpenMeeting = { opened += it },
                    onCloseMeeting = { closeTaps++ },
                    onRetry = { retryTaps++ },
                    onBack = {},
                )
            }
        }
    }

    private fun ready(vararg meetings: MeetingSummary) =
        RoomsLobbyUiState(meetings = MeetingsListState.Ready(meetings.toList()))

    // ── The history ──────────────────────────────────────────────────────────

    @Test
    fun `renders a row per meeting`() {
        render(ready(liveMeeting, doneMeeting))

        composeRule.onNodeWithContentDescription(ROOMS_ROOT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(meetingRowDescription("m-live")).assertExists()
        composeRule.onNodeWithContentDescription(meetingRowDescription("m-done")).assertExists()
    }

    @Test
    fun `rejoin is offered ONLY for a meeting still running`() {
        // ⛔ THE SAFETY ASSERTION ON THIS SCREEN. Joining the room of a finished meeting is not an
        // error server-side — the name is still valid — so nothing would stop it. It would just
        // start a second meeting under the name whose minutes the user was reading.
        render(ready(liveMeeting, doneMeeting))

        composeRule.onNodeWithContentDescription(meetingRejoinDescription("m-live")).assertExists()
        composeRule
            .onNodeWithContentDescription(meetingRejoinDescription("m-done"))
            .assertDoesNotExist()
    }

    @Test
    fun `rejoin passes the stored room name, never a rebuilt one`() {
        render(ready(liveMeeting))

        composeRule
            .onNodeWithContentDescription(meetingRejoinDescription("m-live"))
            .performScrollTo()
            .performClick()

        assertEquals(listOf("meet_ws-abc_standup"), rejoined)
    }

    @Test
    fun `a preview is labelled as one, never as the minutes`() {
        // ⛔ THE SERVER TRUNCATES TO 220 CHARACTERS. Presenting two sentences as the minutes would
        // quietly deliver a fraction of what was written, and the row would look complete.
        render(ready(doneMeeting))

        composeRule
            .onNodeWithText("Preview: The team reviewed Thursday's inbound volume")
            .assertExists()
    }

    @Test
    fun `an in-progress meeting says its minutes are still to come`() {
        // ⚠️ AN EMPTY LINE WOULD READ AS A FAULT. A meeting that has not ended genuinely has no
        // summary, and that deserves its own sentence.
        render(ready(liveMeeting))

        composeRule.onNodeWithText("Minutes are written when the meeting ends.").assertExists()
    }

    @Test
    fun `an untitled meeting falls back to the human half of the room name`() {
        // ⚠️ `meet_<uuid>_standup` is not something to show a person, and a blank title is a row
        // nobody can identify.
        render(ready(liveMeeting))

        composeRule.onNodeWithText("standup").assertExists()
    }

    @Test
    fun `an empty history is an explanatory empty state`() {
        render(ready())

        composeRule.onNodeWithContentDescription(ROOMS_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a failed history offers a retry and KEEPS the join form`() {
        // ⛔ THEY ARE UNRELATED SERVER SURFACES. Hiding the field behind the archive would turn an
        // outage of the minutes history into an inability to hold a meeting.
        render(
            RoomsLobbyUiState(
                meetings = MeetingsListState.Failed(FailureText(UiText.Literal("eu is down"))),
                roomName = "standup",
                normalizedName = "standup",
            ),
        )

        composeRule.onNodeWithContentDescription(ROOMS_HISTORY_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(ROOMS_START_DESCRIPTION).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(ROOMS_JOIN_DESCRIPTION)
            .performScrollTo()
            .assertIsEnabled()
    }

    @Test
    fun `a non-retryable failure offers no retry button`() {
        // ⚠️ A role refusal repeats identically, and a button that cannot help is worse than none.
        render(
            RoomsLobbyUiState(
                meetings = MeetingsListState.Failed(
                    FailureText(UiText.Literal("forbidden"), retryable = false),
                ),
            ),
        )

        composeRule.onNodeWithText("Try again").assertDoesNotExist()
        assertEquals(0, retryTaps)
    }

    // ── Starting a room ──────────────────────────────────────────────────────

    @Test
    fun `join is disabled until a usable name is typed`() {
        // ⛔ `meet_<ws>_` fails the server's regex and comes back as 400 "Invalid meeting room
        // format", which reads as a server fault for what is really an empty field.
        render(ready(liveMeeting))

        composeRule
            .onNodeWithContentDescription(ROOMS_JOIN_DESCRIPTION)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun `the field reports what was typed, character by character`() {
        // ⚠️ THE SCREEN NEVER REWRITES THE FIELD. Normalising in place would move the cursor and
        // eat spaces; the preview line is what tells the user what the room will be called.
        render(ready(liveMeeting))

        composeRule
            .onNodeWithContentDescription(ROOMS_NAME_FIELD_DESCRIPTION)
            .performScrollTo()
            .performTextInput("Weekly")

        assertEquals(listOf("Weekly"), typed)
    }

    @Test
    fun `the preview says what the typed name will become`() {
        render(
            RoomsLobbyUiState(
                meetings = MeetingsListState.Ready(emptyList()),
                roomName = "Weekly Review",
                normalizedName = "weekly-review",
            ),
        )

        composeRule
            .onNodeWithText("Everyone joining “weekly-review” lands in the same room.")
            .assertExists()
    }

    @Test
    fun `the Companion is announced before anybody joins`() {
        // ⛔ IT TRANSCRIBES EVERY MEETING AUTOMATICALLY. Being told afterwards is being told too
        // late, so the notice sits on the form rather than only inside the room.
        render(ready())

        composeRule
            .onNodeWithContentDescription(ROOMS_COMPANION_NOTICE_DESCRIPTION)
            .assertExists()
    }

    @Test
    fun `join fires once`() {
        render(
            RoomsLobbyUiState(
                meetings = MeetingsListState.Ready(emptyList()),
                roomName = "standup",
                normalizedName = "standup",
            ),
        )

        composeRule
            .onNodeWithContentDescription(ROOMS_JOIN_DESCRIPTION)
            .performScrollTo()
            .performClick()

        assertEquals(1, joinTaps)
    }

    // ── The meeting record ───────────────────────────────────────────────────

    @Test
    fun `opening a meeting asks for it by id`() {
        render(ready(doneMeeting))

        composeRule
            .onNodeWithContentDescription(meetingOpenDescription("m-done"))
            .performScrollTo()
            .performClick()

        assertEquals(listOf("m-done"), opened)
    }

    @Test
    fun `the record shows the full minutes and the transcript`() {
        render(
            ready(doneMeeting).copy(
                openMeeting = MeetingDetailState.Ready(
                    MeetingDetail(
                        id = "m-done",
                        summary = "The full minutes, all of them.",
                        transcript = "Ada: Inbound is up about a third on Thursdays.",
                    ),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(ROOMS_RECORD_DESCRIPTION).assertExists()
        composeRule.onNodeWithText("The full minutes, all of them.").assertExists()
        composeRule
            .onNodeWithContentDescription(ROOMS_RECORD_TRANSCRIPT_DESCRIPTION)
            .assertExists()
    }

    @Test
    fun `a meeting with no transcript shows no transcript section`() {
        // ⚠️ An empty heading over nothing reads as data that failed to load.
        render(
            ready(doneMeeting).copy(
                openMeeting = MeetingDetailState.Ready(
                    MeetingDetail(id = "m-done", summary = "Minutes.", transcript = null),
                ),
            ),
        )

        composeRule
            .onNodeWithContentDescription(ROOMS_RECORD_TRANSCRIPT_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a failed record read is reported inside the overlay`() {
        render(
            ready(doneMeeting).copy(
                openMeeting = MeetingDetailState.Failed(FailureText(UiText.Literal("Not found."))),
            ),
        )

        composeRule.onNodeWithContentDescription(ROOMS_RECORD_FAILURE_DESCRIPTION).assertExists()
        // ⚠️ The list stays behind it — a detail failure must not blank rows the user is reading.
        composeRule.onNodeWithContentDescription(meetingRowDescription("m-done")).assertExists()
    }

    @Test
    fun `closing the record fires the dismiss callback`() {
        render(
            ready(doneMeeting).copy(
                openMeeting = MeetingDetailState.Ready(MeetingDetail(id = "m-done")),
            ),
        )

        composeRule.onNodeWithContentDescription(ROOMS_RECORD_CLOSE_DESCRIPTION).performClick()

        assertEquals(1, closeTaps)
    }

    @Test
    fun `no record is shown when none is open`() {
        render(ready(doneMeeting))

        composeRule.onNodeWithContentDescription(ROOMS_RECORD_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `the caller's modifier reaches the lobby`() {
        render(ready(), Modifier.testTag("lobby-host"))

        composeRule.onNodeWithTag("lobby-host").assertIsDisplayed()
    }

    @Test
    fun `a blank title falls back to the room name, like a missing one`() {
        render(ready(liveMeeting.copy(title = "   ")))

        composeRule.onNodeWithText("standup").assertExists()
    }

    @Test
    fun `a finished meeting with no preview says no minutes were saved, not that they are coming`() {
        render(ready(doneMeeting.copy(summaryPreview = null)))

        composeRule.onNodeWithText("No minutes were saved for this meeting.").assertExists()
        composeRule.onNodeWithText("Minutes are written when the meeting ends.").assertDoesNotExist()
    }

    @Test
    fun `an opening record shows a placeholder, not an empty dialog`() {
        render(ready(doneMeeting).copy(openMeeting = MeetingDetailState.Loading))

        composeRule.onNodeWithContentDescription(ROOMS_RECORD_DESCRIPTION).assertExists()
        composeRule.onNodeWithContentDescription(ROOMS_RECORD_FAILURE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText("Minutes").assertDoesNotExist()
    }

    @Test
    fun `a record with no minutes says so, whether the summary is missing or blank`() {
        var summary: String? by mutableStateOf(null)
        composeRule.setContent {
            DistrictTheme {
                MeetingRecordDialog(
                    detail = MeetingDetailState.Ready(
                        MeetingDetail(id = "m-done", summary = summary, transcript = "  "),
                    ),
                    onDismiss = {},
                )
            }
        }

        listOf(null, "  ").forEach { next ->
            summary = next
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Minutes are written when the meeting ends.").assertExists()
            // ⚠️ A blank transcript is no transcript: an empty heading over nothing reads as a
            // failed load.
            composeRule
                .onNodeWithContentDescription(ROOMS_RECORD_TRANSCRIPT_DESCRIPTION)
                .assertDoesNotExist()
        }
    }

    @Test
    fun `the design preview draws the join form and both kinds of meeting`() {
        composeRule.setContent { RoomsLobbyScreenPreview() }

        composeRule.onNodeWithContentDescription(meetingRowDescription("m1")).assertExists()
        composeRule.onNodeWithContentDescription(meetingRowDescription("m2")).assertExists()
    }
}
