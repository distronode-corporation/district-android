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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One call and its transcript on demand. */
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

    companion object {
        fun factory(
            repository: CallsRepository,
            workspaceId: String,
            callId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CallDetailViewModel(repository, workspaceId, callId) }
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
