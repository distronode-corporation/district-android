package com.distronode.districtai.ui.rooms

import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.model.MeetingDetail
import com.distronode.districtai.core.model.MeetingSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.MeetingsApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The lobby's state machine.
 *
 * ⛔ THE ONE THING THAT CANNOT BE ALLOWED TO GO WRONG HERE IS THE ROOM NAME. `/api/district/calls/token`
 * accepts `meet_` and `video_` through the same branch, and a `video_` room starts a billable Tavus
 * avatar session — so a lobby that assembled a name itself would be one character from spending
 * money on a surface this app does not implement, with nothing server-side to refuse it.
 *
 * ⛔ AND AN EMPTY MEETINGS LIST IS NOT A FAILURE. It is every workspace on day one, and the screen
 * a new customer is sent to first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomsLobbyViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

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
        summaryPreview = "The team reviewed Thursday's inbound volume…",
        participantCount = 2,
        createdAt = "2026-08-14T15:00:00.000Z",
    )

    private fun api(meetings: List<MeetingSummary> = listOf(liveMeeting, doneMeeting)) =
        FakeDistrictApi().apply { meetingsResult = ApiResult.Success(meetings) }

    private fun viewModel(api: FakeDistrictApi) =
        RoomsLobbyViewModel(MeetingsRepository(api), workspaceId = "ws-abc")

    // ── The history ──────────────────────────────────────────────────────────

    @Test
    fun `the history loads on entry, scoped to this workspace`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        assertEquals("ws-abc", api.meetingsRequests.single())
        assertEquals(
            listOf("m-live", "m-done"),
            (vm.state.value.meetings as MeetingsListState.Ready).meetings.map { it.id },
        )
    }

    @Test
    fun `an empty history is a success, not a failure`() = runTest {
        val vm = viewModel(api(emptyList()))
        advanceUntilIdle()

        val list = vm.state.value.meetings
        assertTrue("an empty list must be Ready, never Failed", list is MeetingsListState.Ready)
        assertTrue((list as MeetingsListState.Ready).meetings.isEmpty())
    }

    @Test
    fun `a failed history read does not disable the join form`() = runTest {
        // ⛔ THEY ARE UNRELATED SERVER SURFACES. Gating the field on the archive would turn an
        // outage of the minutes history into an inability to hold a meeting.
        val api = FakeDistrictApi().apply {
            meetingsResult = ApiResult.RegionsDegraded("eu is down", listOf("eu"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.meetings is MeetingsListState.Failed)

        vm.onRoomNameChange("standup")
        assertTrue("the form must stay usable", vm.state.value.canJoin)
        assertEquals("meet_ws-abc_standup", vm.roomNameToJoin())
    }

    @Test
    fun `a refresh keeps the rows on screen`() = runTest {
        // ⚠️ A re-read on a session change would otherwise blank a list somebody is reading in
        // order to redraw almost the same thing.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.load(refreshing = true)
        assertTrue(
            "the previous rows must remain while refreshing",
            vm.state.value.meetings is MeetingsListState.Ready,
        )
    }

    // ── Minting the room name ────────────────────────────────────────────────

    @Test
    fun `the room name is always a meet_ name and never a video_ one`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.onRoomNameChange("Weekly Review")
        assertEquals("meet_ws-abc_weekly-review", vm.roomNameToJoin())

        // ⛔ EVEN WHEN THE USER TYPES THE OTHER PREFIX. The suffix is normalised into the name, it
        // never replaces the prefix.
        vm.onRoomNameChange("video_avatar")
        assertEquals("meet_ws-abc_videoavatar", vm.roomNameToJoin())
        assertFalse(vm.roomNameToJoin()!!.startsWith("video_"))
    }

    @Test
    fun `the field keeps what was typed and shows what it will become`() = runTest {
        // ⚠️ REWRITING THE FIELD AS SOMEBODY TYPES MOVES THEIR CURSOR AND EATS THEIR SPACES. The
        // preview is what tells them "Weekly Review" and "weekly review" are the same room, before
        // they find out by being alone in one.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.onRoomNameChange("Weekly Review")

        assertEquals("Weekly Review", vm.state.value.roomName)
        assertEquals("weekly-review", vm.state.value.normalizedName)
    }

    @Test
    fun `an unusable name cannot be joined`() = runTest {
        // ⛔ REFUSED HERE RATHER THAN AT THE SERVER. `meet_<ws>_` fails the route's regex and comes
        // back as 400 "Invalid meeting room format", which reads as a server fault for what is
        // really an empty field.
        val vm = viewModel(api())
        advanceUntilIdle()

        assertFalse("nothing typed", vm.state.value.canJoin)
        assertNull(vm.roomNameToJoin())

        vm.onRoomNameChange("   ")
        assertFalse("whitespace only", vm.state.value.canJoin)
        assertNull(vm.roomNameToJoin())

        vm.onRoomNameChange("!!!")
        assertFalse("punctuation only", vm.state.value.canJoin)
        assertNull(vm.roomNameToJoin())
    }

    @Test
    fun `a rejoin uses the stored room name and refuses a foreign one`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        assertEquals("meet_ws-abc_standup", vm.rejoinName("meet_ws-abc_standup"))
        // ⚠️ A `video_` row can reach the client through the Meeting table (the Companion is
        // dispatched into those too); the repository filters the list, and this is the second
        // guard on the path that actually navigates.
        assertNull(vm.rejoinName("video_ws-abc_avatar"))
    }

    // ── The meeting record ───────────────────────────────────────────────────

    @Test
    fun `opening a meeting reads the detail route scoped to this workspace`() = runTest {
        val api = api().apply {
            meetingDetailResult = ApiResult.Success(
                MeetingDetail(id = "m-done", summary = "full minutes", transcript = "Ada: hello"),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.openMeeting("m-done")
        // ⚠️ THE OVERLAY OPENS IMMEDIATELY. Waiting for the response would make a tap on a slow
        // connection look like it did nothing, and the second tap would issue a second read.
        assertTrue(vm.state.value.openMeeting is MeetingDetailState.Loading)

        advanceUntilIdle()
        val open = vm.state.value.openMeeting as MeetingDetailState.Ready
        assertEquals("full minutes", open.meeting.summary)
        assertEquals("ws-abc/m-done", api.meetingDetailRequests.single())
    }

    @Test
    fun `a detail read that lands after the overlay was closed is dropped`() = runTest {
        // ⚠️ WRITING IT ANYWAY WOULD REOPEN A TRANSCRIPT THE USER HAD JUST DISMISSED — and the
        // transcript is the most sensitive thing this app displays.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.openMeeting("m-done")
        vm.closeMeeting()
        advanceUntilIdle()

        assertNull(vm.state.value.openMeeting)
    }

    @Test
    fun `closing when no meeting was ever opened changes nothing and reads nothing`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()
        val before = vm.state.value

        vm.closeMeeting()
        advanceUntilIdle()

        assertEquals(before, vm.state.value)
        assertTrue(api.meetingDetailRequests.isEmpty())
    }

    @Test
    fun `a failed detail read is reported inside the overlay, not as a list failure`() = runTest {
        // ⚠️ The list they can see is a correct answer already in hand; replacing it with an error
        // would lose the rows they were about to act on.
        val api = api().apply { meetingDetailResult = ApiResult.NotFound("Meeting not found.") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.openMeeting("m-gone")
        advanceUntilIdle()

        assertTrue(vm.state.value.openMeeting is MeetingDetailState.Failed)
        assertTrue("the list must be untouched", vm.state.value.meetings is MeetingsListState.Ready)
    }

    @Test
    fun `a slower read for a closed meeting cannot fill the next meeting's overlay`() = runTest {
        // ⛔ OPEN A, CLOSE, OPEN B, AND A'S READ IS THE SLOWER ONE. Before the fix the overlay was
        // non-null when A landed, so A's transcript and minutes replaced B's under B's row.
        val api = api()
        val gates = mapOf(
            "m-a" to CompletableDeferred<Unit>(),
            "m-b" to CompletableDeferred<Unit>(),
        )
        val gated = object : MeetingsApi by api {
            override suspend fun meetingDetail(workspaceId: String, meetingId: String): ApiResult<MeetingDetail> {
                gates.getValue(meetingId).await()
                return ApiResult.Success(MeetingDetail(id = meetingId, summary = "minutes of $meetingId"))
            }
        }
        val vm = RoomsLobbyViewModel(MeetingsRepository(gated), workspaceId = "ws-abc")
        advanceUntilIdle()

        vm.openMeeting("m-a")
        advanceUntilIdle()
        vm.closeMeeting()
        vm.openMeeting("m-b")
        advanceUntilIdle()
        gates.getValue("m-b").complete(Unit)
        advanceUntilIdle()
        gates.getValue("m-a").complete(Unit)
        advanceUntilIdle()

        val open = vm.state.value.openMeeting as MeetingDetailState.Ready
        assertEquals("m-b", open.meeting.id)
        assertEquals("minutes of m-b", open.meeting.summary)
    }

    @Test
    fun `opening a second meeting over a pending first one shows only the second`() = runTest {
        // The same race without a close in between: a re-tap on another row while A is loading.
        val api = api()
        val gateA = CompletableDeferred<Unit>()
        val gated = object : MeetingsApi by api {
            override suspend fun meetingDetail(workspaceId: String, meetingId: String): ApiResult<MeetingDetail> {
                if (meetingId == "m-a") gateA.await()
                return ApiResult.Success(MeetingDetail(id = meetingId))
            }
        }
        val vm = RoomsLobbyViewModel(MeetingsRepository(gated), workspaceId = "ws-abc")
        advanceUntilIdle()

        vm.openMeeting("m-a")
        advanceUntilIdle()
        vm.openMeeting("m-b")
        advanceUntilIdle()
        gateA.complete(Unit)
        advanceUntilIdle()

        assertEquals("m-b", (vm.state.value.openMeeting as MeetingDetailState.Ready).meeting.id)
    }
}
