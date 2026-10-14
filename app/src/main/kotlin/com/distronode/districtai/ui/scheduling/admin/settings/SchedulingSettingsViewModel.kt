package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.branding
import com.distronode.districtai.core.data.llmSettings
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.model.SchedulingBranding
import com.distronode.districtai.core.model.SchedulingLlmSettings
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The settings screen: four tabs, each read the first time it is shown.
 *
 * ⚠️ A NULL READ HAS NOT BEEN MADE YET, which the screen draws as loading. The profile and the
 * notifications tabs share [profile]: they are two views of one `me.get`.
 *
 * @property canManage whether the booking page and assistant editors are drawn. ⛔ It never gates
 *   the profile, the picture or the switches, which are the member's own (`viewer`-level).
 */
data class SchedulingSettingsUiState(
    val tab: SchedulingSettingsTab = SchedulingSettingsTab.BOOKING_PAGE,
    val branding: SectionState<SchedulingBranding>? = null,
    val assistant: SectionState<SchedulingLlmSettings>? = null,
    val profile: SectionState<SchedulingMe>? = null,
    val canManage: Boolean = false,
)

/**
 * Reads the settings, ported from district-ios's `SchedulingSettingsModel`.
 *
 * ⛔ ONLY THE TAB ON SCREEN READS: four reads on entry would spend three requests on panels nobody
 * opened, and a tab already read is not read again when it is returned to.
 */
class SchedulingSettingsViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingSettingsUiState())
    val state: StateFlow<SchedulingSettingsUiState> = _state.asStateFlow()

    val writes = SchedulingSettingsWrites(viewModelScope, admin, workspaceId, ::adopt)

    init {
        load()
    }

    /** Read everything again: the status, then the tab on screen. The tab choice stays. */
    fun load() {
        _state.value = SchedulingSettingsUiState(tab = _state.value.tab)
        viewModelScope.launch {
            val access = scheduling.access(workspaceId)
            _state.update { it.copy(canManage = access.canManage) }
            loadCurrentTab()
        }
    }

    fun selectTab(tab: SchedulingSettingsTab) {
        _state.update { it.copy(tab = tab) }
        loadCurrentTab()
    }

    /** Read the tab on screen again. */
    fun reload() {
        _state.update {
            when (it.tab) {
                SchedulingSettingsTab.BOOKING_PAGE -> it.copy(branding = null)
                SchedulingSettingsTab.ASSISTANT -> it.copy(assistant = null)
                SchedulingSettingsTab.PROFILE, SchedulingSettingsTab.NOTIFICATIONS -> it.copy(profile = null)
            }
        }
        loadCurrentTab()
    }

    /** ⚠️ What a write stored is shown as it came back, without another read. */
    private fun adopt(saved: SettingsSaved) {
        _state.update {
            when (saved) {
                is SettingsSaved.Profile -> it.copy(profile = SectionState.Ready(saved.me))
                is SettingsSaved.Branding -> it.copy(branding = SectionState.Ready(saved.branding))
                is SettingsSaved.Assistant -> it.copy(assistant = SectionState.Ready(saved.llm))
            }
        }
    }

    private fun loadCurrentTab() {
        val current = _state.value
        when (current.tab) {
            SchedulingSettingsTab.BOOKING_PAGE -> if (current.branding == null) {
                read({ admin.branding(workspaceId) }) { state, read -> state.copy(branding = read) }
            }
            SchedulingSettingsTab.ASSISTANT -> if (current.assistant == null) {
                read({ admin.llmSettings(workspaceId) }) { state, read -> state.copy(assistant = read) }
            }
            SchedulingSettingsTab.PROFILE, SchedulingSettingsTab.NOTIFICATIONS -> if (current.profile == null) {
                read({ admin.me(workspaceId) }) { state, read -> state.copy(profile = read) }
            }
        }
    }

    private fun <T> read(
        fetch: suspend () -> SchedulingAdminResult<T>,
        put: (SchedulingSettingsUiState, SectionState<T>) -> SchedulingSettingsUiState,
    ) {
        _state.update { put(it, SectionState.Loading) }
        viewModelScope.launch {
            val result = fetch().toSectionState()
            _state.update { put(it, result) }
        }
    }

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingSettingsViewModel(admin, scheduling, workspaceId) }
        }
    }
}
