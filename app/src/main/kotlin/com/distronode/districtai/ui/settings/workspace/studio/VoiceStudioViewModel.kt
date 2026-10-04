package com.distronode.districtai.ui.settings.workspace.studio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.VoiceStudioRepository
import com.distronode.districtai.core.data.VoiceStudioSave
import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The native Voice Studio: one read, local edits, a save through the persona PATCH, then the read
 * again.
 *
 * ⛔ SINGLE FLIGHT, AND NOTHING MOVES WHILE A SAVE IS IN THE AIR. A second tap before the first
 * request returns is dropped, and every edit is refused while saving: the request was built from
 * the state on screen, and an edit landing mid-request would make the outcome describe something
 * the screen no longer shows.
 *
 * ⛔ ONLY CHANGED KEYS ARE SENT ([StudioDiff.changedKeys]), so a teammate's save of a key this
 * screen never touched is not undone, and after every 200 the Studio is read back and compared
 * with what was sent ([StudioDiff.landed]).
 */
class VoiceStudioViewModel(
    private val repository: VoiceStudioRepository,
    private val workspaceId: String,
) : ViewModel(), VoiceStudioActions {

    private val _state = MutableStateFlow<VoiceStudioUiState>(VoiceStudioUiState.Loading)
    val state: StateFlow<VoiceStudioUiState> = _state.asStateFlow()

    init {
        load()
    }

    override fun load() {
        _state.value = VoiceStudioUiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.load(workspaceId)) {
                is ApiResult.Success -> VoiceStudioUiState.Ready.of(result.value)
                is ApiResult.Failure -> VoiceStudioUiState.LoadFailed(result.toFailureText())
            }
        }
    }

    override fun selectTier(tier: String) = edit { it.withTier(tier) }

    override fun applyRecipe(id: String) = edit { it.withRecipe(id) }

    override fun reset() = edit { it.withReset() }

    /** ⚠️ Not an edit: choosing which leg to look at is allowed mid-save. */
    override fun selectLeg(leg: String) {
        val ready = _state.value as? VoiceStudioUiState.Ready ?: return
        _state.value = ready.withLeg(leg)
    }

    override fun editMix(transform: (EngineMix) -> EngineMix) = edit { it.withMix(transform) }

    override fun updateHeld(transform: (StudioState) -> StudioState) = edit { it.withHeld(transform(it.held)) }

    override fun save() {
        val ready = _state.value as? VoiceStudioUiState.Ready ?: return
        if (!ready.canSave) return
        val sent = ready.heldFields
        val keys = ready.pending
        val saving = ready.copy(save = StudioSaveState.Saving)
        _state.value = saving
        viewModelScope.launch {
            _state.value = when (val outcome = repository.save(StudioDiff.request(workspaceId, sent, keys))) {
                is VoiceStudioSave.Saved -> VoiceStudioUiState.Ready.of(
                    outcome.studio,
                    if (StudioDiff.landed(sent, keys, outcome.studio.current.fields)) {
                        StudioSaveState.Saved
                    } else {
                        StudioSaveState.Mismatch
                    },
                )
                is VoiceStudioSave.SavedButStale ->
                    saving.copy(save = StudioSaveState.SavedButStale(outcome.failure.toFailureText()))
                is VoiceStudioSave.NotSaved ->
                    saving.copy(save = StudioSaveState.Failed(outcome.failure.toFailureText()))
            }
        }
    }

    private fun edit(transform: (VoiceStudioUiState.Ready) -> VoiceStudioUiState.Ready) {
        val ready = _state.value as? VoiceStudioUiState.Ready ?: return
        if (ready.save == StudioSaveState.Saving) return
        _state.value = transform(ready)
    }

    companion object {
        fun factory(repository: VoiceStudioRepository, workspaceId: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { VoiceStudioViewModel(repository, workspaceId) } }
    }
}
