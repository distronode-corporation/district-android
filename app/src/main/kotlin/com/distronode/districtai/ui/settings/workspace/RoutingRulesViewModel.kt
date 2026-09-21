package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.RoutingRule
import com.distronode.districtai.core.model.RoutingRuleField
import com.distronode.districtai.core.model.routingRulesRequest
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The routing rules' state machine.
 *
 * ⛔ SAME CENTRAL RULE AS EVERY OTHER SCREEN IN THIS PACKAGE: no load, no edit, no save. The array
 * built here replaces the stored one, and a rule decides which VOICE and which MODEL answer a
 * segment of callers — so a rules list assembled from nothing is not a blank form, it is the agent
 * losing every persona override with a 200.
 *
 * ⛔ EDITS OVERWRITE ONE KEY OF THE STORED OBJECT. `RoutingRule.with` copies the raw JSON object
 * and replaces one entry, so a rule carrying keys this client never modelled — which the committed
 * fixture proves is the ordinary case, not a hypothetical — survives an edit to a neighbouring
 * field untouched.
 *
 * @param newRuleId the id a new rule gets. ⚠️ INJECTED so a test can assert a deterministic array;
 *   the default mirrors the web builder's `window.crypto.randomUUID()`.
 */
class RoutingRulesViewModel(
    private val repository: WorkspaceConfigRepository,
    private val workspaceId: String,
    private val newRuleId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    private val _state = MutableStateFlow(RoutingRulesUiState())
    val state: StateFlow<RoutingRulesUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** ⛔ The only route to an editable state, and it CLEARS the draft. */
    fun load() {
        _state.value = _state.value.copy(load = ConfigState.Loading)
        viewModelScope.launch {
            _state.value = when (val result = repository.load(workspaceId)) {
                is ApiResult.Success -> RoutingRulesUiState(load = ConfigState.Ready(result.value))
                is ApiResult.Failure -> _state.value.copy(
                    load = ConfigState.LoadFailed(result.toFailureText()),
                )
            }
        }
    }

    /** ⚠️ Appended with the WEB BUILDER'S OWN DEFAULTS — see `RoutingRule.newRule`. */
    fun addRule() {
        val current = _state.value
        if (!current.editable || current.save.busy) return
        _state.value = current.copy(
            draft = current.rules + RoutingRule.newRule(newRuleId()),
            save = SaveState.Idle,
        )
    }

    /** ⛔ One key of one stored object. Everything else on the rule is carried, not rebuilt. */
    fun edit(index: Int, field: RoutingRuleField, value: String) {
        val current = _state.value
        if (!current.editable || current.save.busy) return
        val rules = current.rules
        val rule = rules.getOrNull(index) ?: return
        _state.value = current.copy(
            draft = rules.toMutableList().also { it[index] = rule.with(field, value) },
            save = SaveState.Idle,
        )
    }

    /** ⚠️ Local only. The deletion reaches the server on the confirmed save. */
    fun remove(index: Int) {
        val current = _state.value
        if (!current.editable || current.save.busy) return
        val rules = current.rules
        if (index !in rules.indices) return
        _state.value = current.copy(
            draft = rules.filterIndexed { i, _ -> i != index },
            save = SaveState.Idle,
        )
    }

    /**
     * Replace the stored rules.
     *
     * ⚠️ A 400 FROM HERE IS USUALLY A REAL REFUSAL, NOT A BUG. A workspace that restricts voices or
     * models rejects a rule naming one outside its allow-list, BY NAME, and the message is worth
     * showing verbatim — this client cannot see either list and deliberately does not guess.
     */
    fun save() {
        val current = _state.value
        if (!current.canSave) return

        _state.value = current.copy(save = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.saveRoutingRules(
                routingRulesRequest(workspaceId, current.rules),
            )
            _state.value = applyOutcome(_state.value, outcome)
        }
    }

    /** ⚠️ Retires the banner without a re-read. */
    fun dismissSaveNotice() {
        val current = _state.value
        if (current.save.busy) return
        _state.value = current.copy(save = SaveState.Idle)
    }

    /** ⛔ Identical outcome handling to the directory editor — see its [applyOutcome]. */
    private fun applyOutcome(
        current: RoutingRulesUiState,
        outcome: SaveOutcome,
    ): RoutingRulesUiState = when (outcome) {
        is SaveOutcome.Saved -> current.copy(
            load = ConfigState.Ready(outcome.config),
            draft = null,
            save = SaveState.Saved,
        )
        is SaveOutcome.SavedButStale -> current.copy(
            draft = null,
            save = SaveState.SavedButStale(outcome.failure.toFailureText()),
        )
        is SaveOutcome.NotSaved -> current.copy(
            save = SaveState.Failed(outcome.failure.toFailureText()),
        )
    }

    companion object {
        fun factory(
            repository: WorkspaceConfigRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                RoutingRulesViewModel(repository, workspaceId) as T
        }
    }
}
