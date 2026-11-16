package com.distronode.districtai.ui.hq.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.HqVoiceRepository
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The voice settings screen's state. */
data class HqVoiceSettingsUiState(
    /** What the menus show; null until loaded. */
    val draft: HqVoiceSettings? = null,
    val loaded: HqVoiceSettings? = null,
    val loadFailure: FailureText? = null,
    val saveFailure: FailureText? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
) {
    val isDirty: Boolean get() = draft != null && draft != loaded
}

/** One of the five settings. */
enum class HqVoiceSetting { VOICE, LENGTH, PACE, LANGUAGE, MIC_MODE }

/**
 * The member's own HQ voice settings in one workspace (`GET` and `PUT hq/voice-settings`), ported
 * from iOS (`HQVoiceSettingsModel.swift`).
 *
 * ⚠️ THE MEMBER'S OWN, NOT THE WORKSPACE'S, so a viewer may change them too.
 * ⚠️ THE WIRE VALUES ARE THE SERVICE'S. A value this build does not list is kept and shown as
 * itself, so a save never rewrites a newer choice.
 */
class HqVoiceSettingsViewModel(
    private val repository: HqVoiceRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(HqVoiceSettingsUiState())
    val state: StateFlow<HqVoiceSettingsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loadFailure = null) }
        viewModelScope.launch {
            when (val result = repository.settings(workspaceId)) {
                is ApiResult.Success -> _state.update { it.copy(draft = result.value, loaded = result.value) }
                is ApiResult.Failure -> _state.update { it.copy(loadFailure = result.toFailureText()) }
            }
        }
    }

    fun edit(setting: HqVoiceSetting, value: String) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            val next = when (setting) {
                HqVoiceSetting.VOICE -> draft.copy(voice = value)
                HqVoiceSetting.LENGTH -> draft.copy(length = value)
                HqVoiceSetting.PACE -> draft.copy(pace = value)
                HqVoiceSetting.LANGUAGE -> draft.copy(language = value)
                HqVoiceSetting.MIC_MODE -> draft.copy(micMode = value)
            }
            current.copy(draft = next, isSaved = false, saveFailure = null)
        }
    }

    /** ⛔ SINGLE-FLIGHT: a second tap while a save is in flight does nothing. */
    fun save() {
        val current = _state.value
        val settings = current.draft ?: return
        if (current.isSaving) return
        _state.update { it.copy(isSaving = true, saveFailure = null) }
        viewModelScope.launch {
            when (val result = repository.saveSettings(workspaceId, settings)) {
                is ApiResult.Success -> _state.update {
                    it.copy(
                        isSaving = false,
                        loaded = result.value,
                        // ⚠️ AN EDIT MADE WHILE SAVING IS KEPT; otherwise the screen shows what was stored.
                        draft = if (it.draft == settings) result.value else it.draft,
                        isSaved = true,
                    )
                }
                is ApiResult.Failure -> _state.update {
                    it.copy(isSaving = false, saveFailure = result.toFailureText())
                }
            }
        }
    }

    companion object {
        fun factory(repository: HqVoiceRepository, workspaceId: String): ViewModelProvider.Factory =
            viewModelFactory { initializer { HqVoiceSettingsViewModel(repository, workspaceId) } }
    }
}
