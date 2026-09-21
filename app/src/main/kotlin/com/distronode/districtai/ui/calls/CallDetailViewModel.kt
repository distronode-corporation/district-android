package com.distronode.districtai.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One call, its transcript on demand, and a recording URL resolved only at playback. */
class CallDetailViewModel(
    private val repository: CallsRepository,
    private val workspaceId: String,
    private val callId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<CallDetailUiState>(CallDetailUiState.Loading)
    val state: StateFlow<CallDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = CallDetailUiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.detail(workspaceId, callId)) {
                is ApiResult.Success -> CallDetailUiState.Content(result.value)
                is ApiResult.Failure -> CallDetailUiState.Failed(result.toFailureText())
            }
        }
    }

    /**
     * Fetch the transcript.
     *
     * ⚠️ ON DEMAND, AND NOT TAKEN FROM THE CALL ROW even though the row carries one. Transcripts are
     * large — the contact timeline stopped embedding them because they dominated its payload — and a
     * transcript can still be written after a call ends, so the row's copy is only as fresh as the
     * page that loaded it.
     */
    fun loadTranscript() {
        val content = _state.value as? CallDetailUiState.Content ?: return
        // Already loading or loaded: re-fetching on every recomposition would hammer the endpoint.
        if (content.transcript != TranscriptState.Idle) return

        _state.value = content.copy(transcript = TranscriptState.Loading)
        viewModelScope.launch {
            val next = when (val result = repository.transcript(workspaceId, callId)) {
                is ApiResult.Success ->
                    // ⚠️ "" is what the server sends for a call with no transcript, so emptiness is
                    // the "nothing to show" state rather than an error.
                    if (result.value.isBlank()) {
                        TranscriptState.Absent
                    } else {
                        TranscriptState.Loaded(result.value)
                    }
                is ApiResult.Failure -> TranscriptState.Failed(result.toFailureText())
            }
            // Re-read the state: the detail may have been reloaded while this was in flight.
            (_state.value as? CallDetailUiState.Content)?.let {
                _state.value = it.copy(transcript = next)
            }
        }
    }

    /**
     * Resolve a playable URL and hand it to [onResolved].
     *
     * ⛔ RESOLVED AT THE MOMENT OF PLAYBACK, NEVER CACHED. The server redirects to a short-lived
     * presigned object URL; a stored one expires and then fails inside whatever player received it,
     * where the failure looks like a corrupt recording rather than a stale link.
     *
     * ⚠️ [ApiResult.NotFound] here is ordinary — a missed call has no recording — so it is reported
     * as "no recording" rather than as an error.
     *
     * @param onResolved ⛔ MUST NOT CAPTURE AN ACTIVITY `Context`. This callback is held across the
     *   suspending request while this ViewModel is scoped to the nav entry, which outlives a
     *   configuration change — so a rotation mid-resolve would invoke it with a DESTROYED Activity
     *   and `startActivity` would fail. The call site passes the application context; see
     *   `DistrictNavHost`.
     */
    fun resolveRecording(onResolved: (String) -> Unit) {
        val content = _state.value as? CallDetailUiState.Content ?: return
        // ⛔ THE IN-FLIGHT GUARD, WHICH loadTranscript/rename/delete ALL HAD AND THIS DID NOT. This
        // set `Resolving` and then never checked it, so a double tap resolved the recording twice
        // and fired TWO ACTION_VIEW intents — the user gets two players, and the server issues two
        // presigned URLs for one deliberate action.
        if (content.recording == RecordingState.Resolving) return

        _state.value = content.copy(recording = RecordingState.Resolving)

        viewModelScope.launch {
            val next = when (val result = repository.recordingUrl(workspaceId, callId)) {
                is ApiResult.Success -> {
                    onResolved(result.value)
                    RecordingState.Idle
                }
                is ApiResult.NotFound -> RecordingState.Absent
                is ApiResult.Failure -> RecordingState.Failed(result.toFailureText())
            }
            (_state.value as? CallDetailUiState.Content)?.let {
                _state.value = it.copy(recording = next)
            }
        }
    }

    companion object {
        fun factory(
            repository: CallsRepository,
            workspaceId: String,
            callId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CallDetailViewModel(repository, workspaceId, callId) as T
        }
    }
}

sealed interface CallDetailUiState {
    data object Loading : CallDetailUiState

    data class Content(
        val call: CallSummary,
        val transcript: TranscriptState = TranscriptState.Idle,
        val recording: RecordingState = RecordingState.Idle,
    ) : CallDetailUiState {
        /**
         * ⚠️ Whether a recording is worth OFFERING, from the row we already have — so the screen does
         * not show a play button that resolves to a 404. Not authoritative: the archived copy lives
         * under a key this shape does not expose, so the server can still produce a URL when this is
         * false. Erring toward offering it is the friendlier mistake.
         */
        val mayHaveRecording: Boolean get() = call.recordingUrl != null
    }

    data class Failed(val failure: FailureText) : CallDetailUiState
}

sealed interface TranscriptState {
    /** Not requested yet — the user has not expanded it. */
    data object Idle : TranscriptState
    data object Loading : TranscriptState
    data class Loaded(val text: String) : TranscriptState

    /** The call exists and has no transcript. ⚠️ The server sends "" for this, not null. */
    data object Absent : TranscriptState
    data class Failed(val failure: FailureText) : TranscriptState
}

sealed interface RecordingState {
    data object Idle : RecordingState
    data object Resolving : RecordingState

    /** No recording for this call. Ordinary for a missed call, not an error. */
    data object Absent : RecordingState
    data class Failed(val failure: FailureText) : RecordingState
}
