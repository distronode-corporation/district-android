package com.distronode.districtai.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.model.MeetRoomName
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The lobby: what has already happened in this workspace, and how to start the next one.
 *
 * ⛔ NO ENGINE HERE. The lobby holds no LiveKit session — an engine owns a socket and audio-device
 * state, and constructing one before anybody has decided to join would take audio focus from
 * whatever the user is listening to. The engine belongs to [ActiveRoomViewModel], which is created
 * when the destination is, and torn down with it.
 *
 * ⚠️ THE ROOM NAME IS MINTED THROUGH [MeetRoomName] AND NOWHERE ELSE. See that object for why a
 * string template here would be one character from a billable avatar session.
 */
class RoomsLobbyViewModel(
    private val repository: MeetingsRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(RoomsLobbyUiState())
    val state: StateFlow<RoomsLobbyUiState> = _state.asStateFlow()

    /**
     * The meeting-detail read in flight, if any.
     *
     * ⛔ CANCELLED BY [openMeeting] AND [closeMeeting]. Open A, close it, open B: if A's read is
     * the slower one it would otherwise land in B's overlay, and the user would read one
     * meeting's transcript and minutes under another meeting's row.
     */
    private var detailJob: Job? = null

    init {
        load()
    }

    /**
     * Read the meetings history.
     *
     * @param refreshing ⚠️ TRUE KEEPS THE ROWS ON SCREEN, the same call every list in this app
     *   makes: a re-read on a session change would otherwise blank a list somebody is reading in
     *   order to redraw almost the same thing.
     */
    fun load(refreshing: Boolean = false) {
        if (!refreshing) _state.value = _state.value.copy(meetings = MeetingsListState.Loading)
        viewModelScope.launch {
            _state.value = _state.value.copy(
                meetings = when (val result = repository.meetings(workspaceId)) {
                    // ⛔ AN EMPTY LIST IS A SUCCESS. A workspace that has held no meetings is every
                    // workspace on day one, and rendering that as a failure would tell a new
                    // customer something is broken on the screen they were sent to first.
                    is ApiResult.Success -> MeetingsListState.Ready(result.value)
                    is ApiResult.Failure -> MeetingsListState.Failed(result.toFailureText())
                },
            )
        }
    }

    /**
     * ⚠️ NORMALISES ON EVERY KEYSTROKE BUT NEVER REWRITES THE FIELD. Both values are kept so the
     * screen can show the user what their room will be called while they are still typing it — see
     * [RoomsLobbyUiState.roomName].
     */
    fun onRoomNameChange(value: String) {
        _state.value = _state.value.copy(
            roomName = value,
            normalizedName = MeetRoomName.normalizeSuffix(value),
        )
    }

    /**
     * The full room name to navigate with, or null when nothing usable was typed.
     *
     * ⛔ RETURNS THE WHOLE `meet_<ws>_<suffix>` NAME RATHER THAN THE SUFFIX, so the destination
     * carries a self-describing value: a room screen restored after process death has the exact
     * name the token was minted for, with no second chance to assemble it differently.
     */
    fun roomNameToJoin(): String? = MeetRoomName.of(workspaceId, _state.value.roomName)

    /**
     * Open one meeting's full record.
     *
     * ⛔ RE-READ ON EVERY OPEN, NEVER CACHED. A meeting still running has no minutes yet; the whole
     * point of coming back to it is that the Companion has since written them. A cached copy would
     * answer "where are my minutes" with the snapshot from before they existed.
     *
     * ⚠️ THE LOADING STATE IS SET BEFORE THE REQUEST so the overlay opens immediately. Waiting for
     * the response would make a tap on a slow connection look like it did nothing, and the second
     * tap would issue a second read.
     */
    fun openMeeting(meetingId: String) {
        detailJob?.cancel()
        _state.value = _state.value.copy(openMeeting = MeetingDetailState.Loading)
        detailJob = viewModelScope.launch {
            val next = when (val result = repository.meetingDetail(workspaceId, meetingId)) {
                is ApiResult.Success -> MeetingDetailState.Ready(result.value)
                is ApiResult.Failure -> MeetingDetailState.Failed(result.toFailureText())
            }
            // No "is the overlay still open" check: closing cancels this read, so reaching here
            // means the overlay is open and showing this meeting.
            _state.value = _state.value.copy(openMeeting = next)
        }
    }

    /**
     * ⚠️ CANCELS THE READ as well as hiding the overlay. A detail landing after a close would
     * otherwise reopen a transcript the user had just dismissed.
     */
    fun closeMeeting() {
        detailJob?.cancel()
        _state.value = _state.value.copy(openMeeting = null)
    }

    /**
     * Rejoin a meeting that is still running.
     *
     * ⛔ ONLY MEANINGFUL FOR AN `in-progress` ROW, AND THE SCREEN IS WHAT ENFORCES THAT. Joining the
     * room of a COMPLETED meeting is not an error server-side — the name is still valid and a fresh
     * empty room would be created — but it would silently start a second meeting under a name whose
     * minutes the user was reading. The row's own status is the gate.
     */
    fun rejoinName(roomName: String): String? =
        roomName.takeIf { MeetRoomName.isMeetRoom(it) }

    companion object {
        fun factory(
            repository: MeetingsRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RoomsLobbyViewModel(repository, workspaceId) }
        }
    }
}
