package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.ToolsPatchRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The capabilities screen's state machine.
 *
 * ⛔ THIS IS THE DESTRUCTIVE ONE, AND EVERY GUARD HERE EXISTS FOR THAT REASON.
 * `PATCH workspace/tools` sets `toolConfig.allowedTools` to EXACTLY the array it receives — no
 * merge, no diff — so the list is only ever built from a configuration that actually loaded
 * ([CapabilitiesUiState.pendingTools] is null otherwise), and it is built from that baseline
 * rather than from this client's catalog. A workspace can legitimately store an id this app has
 * never heard of (`transfer_to_creator`, retired but still present in real rows), and a
 * list rebuilt from the catalog would drop it with a 200 and no error anywhere.
 *
 * ⛔ AND AN ABSENT ALLOWLIST MEANS EVERY TOOL IS ON. A brand-new workspace has `toolConfig: null`,
 * which the web reads as all eight enabled. Reading it as none and saving would switch the agent
 * off entirely for someone who opened the screen to look at it. [baselineTools] is where that
 * lives, and it is why an untouched form saves the identical array it loaded.
 *
 * ⚠️ TWO SECTIONS, TWO ROUTES, TWO SAVE STATES — see [CapabilitiesUiState]. The enrichment flag is
 * a persona field and rides the merge-safe persona route, exactly as the web's
 * `EnrichmentSettingsForm` does from inside the same tab.
 */
class CapabilitiesViewModel(
    private val repository: WorkspaceConfigRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(CapabilitiesUiState())
    val state: StateFlow<CapabilitiesUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Read the configuration both sections hydrate from. ⛔ The only route to an editable state. */
    fun load() {
        _state.value = _state.value.copy(load = ConfigState.Loading)
        viewModelScope.launch {
            _state.value = when (val result = repository.load(workspaceId)) {
                is ApiResult.Success -> _state.value.copy(
                    load = ConfigState.Ready(result.value),
                    toolToggles = emptyMap(),
                    enrichmentDraft = null,
                    toolsSave = SaveState.Idle,
                    enrichmentSave = SaveState.Idle,
                )
                is ApiResult.Failure -> _state.value.copy(
                    load = ConfigState.LoadFailed(result.toFailureText()),
                )
            }
        }
    }

    /**
     * Flip one capability.
     *
     * ⚠️ IGNORED UNLESS THE CONFIG LOADED, for the same reason the persona form ignores a
     * keystroke: a toggle map accumulated against no baseline is the input to a wholesale replace.
     */
    fun toggleTool(id: String, enabled: Boolean) {
        if (_state.value.load !is ConfigState.Ready) return
        _state.value = _state.value.copy(
            toolToggles = _state.value.toolToggles + (id to enabled),
            toolsSave = SaveState.Idle,
        )
    }

    /** Flip the external-lead-enrichment opt-in. ⚠️ Saved separately, through the persona route. */
    fun toggleEnrichment(enabled: Boolean) {
        if (_state.value.load !is ConfigState.Ready) return
        _state.value = _state.value.copy(
            enrichmentDraft = enabled,
            enrichmentSave = SaveState.Idle,
        )
    }

    /**
     * Replace the allowlist.
     *
     * ⛔ SENDS `pendingTools`, WHICH IS THE LOADED LIST WITH THE TOGGLES APPLIED. Not the catalog,
     * not the enabled rows rebuilt from scratch. The `?: return` is the last line of defence
     * behind [CapabilitiesUiState.canSaveTools]: with no loaded config there is no list, and with
     * no list there is nothing that may be sent to a route that replaces the stored one.
     */
    fun saveTools() {
        val current = _state.value
        if (!current.canSaveTools) return
        val tools = current.pendingTools ?: return

        _state.value = current.copy(toolsSave = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.saveTools(
                ToolsPatchRequest(workspaceId = workspaceId, allowedTools = tools),
            )
            _state.value = applyToolsOutcome(_state.value, outcome)
        }
    }

    /**
     * Save the enrichment opt-in.
     *
     * ⚠️ ONE FIELD, THROUGH THE MERGE-SAFE PERSONA ROUTE. Every other persona field is omitted and
     * therefore preserved — the same contract the persona form relies on, and the reason this
     * toggle can live on a different screen from the one that edits the persona's text.
     */
    fun saveEnrichment() {
        val current = _state.value
        if (!current.canSaveEnrichment) return
        val enabled = current.enrichmentDraft ?: return

        _state.value = current.copy(enrichmentSave = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.savePersona(
                PersonaPatchRequest(workspaceId = workspaceId, dgiEnabled = enabled),
            )
            _state.value = applyEnrichmentOutcome(_state.value, outcome)
        }
    }

    /**
     * ⛔ ON SUCCESS THE TOGGLES ARE CLEARED AND THE FRESH CONFIG BECOMES THE BASELINE. Keeping them
     * would leave the screen showing "changed" against a server that now agrees, and the NEXT save
     * would re-send a diff against a baseline that had moved.
     *
     * ⛔ ON [SaveOutcome.SavedButStale] THE TOGGLES ARE ALSO CLEARED — the write LANDED, so they
     * are no longer pending — but the baseline is the stale one, which is exactly what the banner
     * says. Re-saving from here is what the wording steers away from; re-reading is the fix.
     */
    private fun applyToolsOutcome(
        current: CapabilitiesUiState,
        outcome: SaveOutcome,
    ): CapabilitiesUiState = when (outcome) {
        is SaveOutcome.Saved -> current.copy(
            load = ConfigState.Ready(outcome.config),
            toolToggles = emptyMap(),
            toolsSave = SaveState.Saved,
        )
        is SaveOutcome.SavedButStale -> current.copy(
            toolToggles = emptyMap(),
            toolsSave = SaveState.SavedButStale(outcome.failure.toFailureText()),
        )
        // ⛔ THE TOGGLES SURVIVE. The operator's intent is still on screen and still theirs.
        is SaveOutcome.NotSaved -> current.copy(
            toolsSave = SaveState.Failed(outcome.failure.toFailureText()),
        )
    }

    private fun applyEnrichmentOutcome(
        current: CapabilitiesUiState,
        outcome: SaveOutcome,
    ): CapabilitiesUiState = when (outcome) {
        is SaveOutcome.Saved -> current.copy(
            load = ConfigState.Ready(outcome.config),
            enrichmentDraft = null,
            enrichmentSave = SaveState.Saved,
        )
        is SaveOutcome.SavedButStale -> current.copy(
            enrichmentDraft = null,
            enrichmentSave = SaveState.SavedButStale(outcome.failure.toFailureText()),
        )
        is SaveOutcome.NotSaved -> current.copy(
            enrichmentSave = SaveState.Failed(outcome.failure.toFailureText()),
        )
    }

    companion object {
        fun factory(
            repository: WorkspaceConfigRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CapabilitiesViewModel(repository, workspaceId) as T
        }
    }
}
