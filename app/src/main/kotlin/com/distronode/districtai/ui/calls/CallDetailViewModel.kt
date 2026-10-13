package com.distronode.districtai.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One call, its transcript on demand, and its live transcript while it is in progress.
 *
 * @param liveTranscript makes the live transcript's controller in the scope it is given; null
 *   where there is no socket to open (a test about the rest of the screen).
 */
class CallDetailViewModel(
    private val repository: CallsRepository,
    private val workspaceId: String,
    private val callId: String,
    private val liveTranscript: ((CoroutineScope) -> LiveTranscriptController)? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<CallDetailUiState>(CallDetailUiState.Loading)
    val state: StateFlow<CallDetailUiState> = _state.asStateFlow()

    /**
     * The live transcript, for a call that was in progress when it loaded; null otherwise.
     *
     * ⚠️ MADE ONCE AND KEPT ACROSS A RELOAD of the same call, so a retry does not drop the lines
     * already on screen or open a second socket.
     */
    private var live: LiveTranscriptController? = null
    private val _liveState = MutableStateFlow<LiveTranscriptController.State?>(null)
    val liveState: StateFlow<LiveTranscriptController.State?> = _liveState.asStateFlow()

    /** The destination is started (see [onScreenStarted]). */
    private var started = false

    init {
        load()
    }

    fun load() {
        _state.value = CallDetailUiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.detail(workspaceId, callId)) {
                is ApiResult.Success -> {
                    followLive(result.value)
                    CallDetailUiState.Content(result.value)
                }
                is ApiResult.Failure -> CallDetailUiState.Failed(result.toFailureText())
            }
        }
    }

    /**
     * ⚠️ EVERY LOAD IS A CALL-STATUS SIGNAL FOR THE LIVE TRANSCRIPT, the only one this screen gets
     * (contract §4.12 Q10): in progress, it may subscribe again after `not_live`; no longer live,
     * the call has ended. Ringing says nothing yet: nothing has been said.
     */
    private fun followLive(call: CallSummary) {
        val display = call.toDisplay()
        val factory = liveTranscript
        if (live == null && display.transcribesLive && factory != null) {
            val made = factory(viewModelScope)
            live = made
            viewModelScope.launch { made.state.collect { _liveState.value = it } }
            if (started) made.activate()
        }
        when {
            display.transcribesLive -> live?.callShownInProgress()
            !display.live -> live?.callEnded()
        }
    }

    /**
     * The destination started or stopped (a `LifecycleStartEffect` in `DistrictNavHost`).
     *
     * ⛔ THE LIVE SOCKET FOLLOWS THIS, so none is held for a screen nobody can see.
     */
    fun onScreenStarted(started: Boolean) {
        this.started = started
        if (started) live?.activate() else live?.deactivate()
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

    companion object {
        fun factory(
            repository: CallsRepository,
            workspaceId: String,
            callId: String,
            liveTranscript: ((CoroutineScope) -> LiveTranscriptController)?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CallDetailViewModel(repository, workspaceId, callId, liveTranscript) }
        }
    }
}

sealed interface CallDetailUiState {
    data object Loading : CallDetailUiState

    data class Content(
        val call: CallSummary,
        val transcript: TranscriptState = TranscriptState.Idle,
    ) : CallDetailUiState

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
