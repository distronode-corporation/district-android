package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.data.SaveOutcome
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The agent's three free-text fields, and the seven engine fields that are drawn from the server's
 * own catalogue.
 *
 * ⛔ TWO READS, AND THE ENGINE HALF IS UNAVAILABLE WITHOUT THE SECOND ONE. `workspace/config`
 * carries what is STORED; `workspace/persona/options` carries what may be OFFERED. The save route
 * COERCES rather than rejects — an unrecognised `modelId` becomes `deepgram-pipeline` and an
 * unrecognised `voice` is stored and then silently replaced by the agent, both with a 200 — so a
 * picker built on anything but the catalogue produces a persona nobody chose and reports nothing.
 * A failed catalogue read therefore leaves the engine section read-only; it never falls back.
 *
 * ⚠️ THE TEXT HALF SURVIVES A FAILED CATALOGUE READ. Those three fields are free text on the
 * server too, so there is nothing for a catalogue to authorise and no reason to withhold them.
 */
class PersonaFormViewModel(
    private val repository: WorkspaceConfigRepository,
    private val personaOptions: PersonaOptionsRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(PersonaFormUiState())
    val state: StateFlow<PersonaFormUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(
            load = ConfigState.Loading,
            options = PersonaOptionsState.Loading,
        )
        viewModelScope.launch {
            val config = repository.load(workspaceId)
            val options = personaOptions.options(workspaceId)
            _state.value = _state.value.copy(
                load = when (config) {
                    is ApiResult.Success -> ConfigState.Ready(config.value)
                    is ApiResult.Failure -> ConfigState.LoadFailed(config.toFailureText())
                },
                edits = emptyMap(),
                save = SaveState.Idle,
                options = hydrated(config, options),
            )
        }
    }

    /**
     * ⛔ THE DRAFT NEEDS BOTH ANSWERS. Hydrating from a catalogue without the stored persona would
     * start every field on a default, and saving that would overwrite the workspace's voice with
     * one nobody chose. A config failure therefore reports itself in the engine section too, using
     * the config's own failure sentence.
     */
    private fun hydrated(
        config: ApiResult<WorkspaceConfig>,
        options: ApiResult<PersonaOptionsResponse>,
    ): PersonaOptionsState = when (options) {
        is ApiResult.Failure -> PersonaOptionsState.LoadFailed(options.toFailureText())
        // ⚠️ Nested rather than a flat `when` with an `else`: both reads are finished here, so
        // there is no "still loading" answer, and the old `else -> Loading` arm could never run.
        is ApiResult.Success -> when (config) {
            is ApiResult.Failure -> PersonaOptionsState.LoadFailed(config.toFailureText())
            is ApiResult.Success -> PersonaOptionsState.Ready(
                PersonaEngineDraft.hydrate(config.value.aiPersona, options.value),
            )
        }
    }

    fun edit(field: PersonaField, value: String) {
        if (_state.value.load !is ConfigState.Ready) return
        _state.value = _state.value.copy(
            edits = _state.value.edits + (field to value),
            save = SaveState.Idle,
        )
    }

    /**
     * ⛔ EVERY ENGINE EDIT GOES THROUGH ONE PRIVATE ENTRY POINT so the two cascading ones cannot be
     * bypassed, and it is a no-op without a loaded catalogue. Changing engine clears a language it
     * does not publish and moves the voice; changing language moves the voice for the
     * language-keyed engine only. See [PersonaEngineDraft].
     */
    private fun editEngine(transform: (PersonaEngineDraft) -> PersonaEngineDraft) {
        val current = _state.value.options
        if (current !is PersonaOptionsState.Ready) return
        _state.value = _state.value.copy(
            options = PersonaOptionsState.Ready(transform(current.draft)),
            save = SaveState.Idle,
        )
    }

    /** ⛔ Cascades. Never assign `modelId` through [updateValues]. */
    fun selectEngine(engineId: String) = editEngine { it.selectEngine(engineId) }

    /** ⛔ Cascades for the language-keyed engine. Never assign `language` through [updateValues]. */
    fun selectLanguage(language: String) = editEngine { it.selectLanguage(language) }

    /**
     * The five fields with no cascade — voice, response length, temperature, voice style and
     * preemptive TTS.
     *
     * ⚠️ ONE ENTRY POINT RATHER THAN FIVE NAMED ONES, which is a detekt `TooManyFunctions` ceiling
     * respected rather than raised: five setters that each do `copy` of one field earn no
     * correctness by being named, while [selectEngine] and [selectLanguage] do.
     *
     * ⛔ IT MUST NOT BE USED TO SET `modelId` OR `language`. Doing so would skip the cascades and
     * leave a Deepgram voice selected for a language that engine cannot speak — which the save
     * route stores happily.
     */
    fun updateValues(values: PersonaEngineValues) = editEngine { it.copy(values = values) }

    /**
     * ⛔ ONLY WHAT CHANGED GOES ON THE WIRE, with one deliberate exception. The route merges per
     * field (`x !== undefined ? x : existing`), so an omitted key is PRESERVED and a request
     * carrying the whole form would overwrite the avatar settings and the tuning parameters with
     * whatever this screen happened to hold. The exception is `modelId`, which rides along whenever
     * a response level changed, because the route stores the level under
     * `aiPersona.responseLength[modelId]` and silently discards a level with no engine.
     */
    fun save() {
        val current = _state.value
        if (!current.canSave) return
        val dirty = current.dirtyFields
        val engine = current.draft?.write ?: PersonaEngineWrite()

        _state.value = current.copy(save = SaveState.Saving)
        viewModelScope.launch {
            val request = PersonaPatchRequest(
                workspaceId = workspaceId,
                name = current.value(PersonaField.NAME).takeIf { PersonaField.NAME in dirty },
                greeting = current.value(PersonaField.GREETING)
                    .takeIf { PersonaField.GREETING in dirty },
                personality = current.value(PersonaField.PERSONALITY)
                    .takeIf { PersonaField.PERSONALITY in dirty },
                voice = engine.voice,
                language = engine.language,
                modelId = engine.modelId,
                responseLength = engine.responseLength,
                temperature = engine.temperature,
                voiceStyle = engine.voiceStyle,
                preemptiveTts = engine.preemptiveTts,
            )
            applyOutcome(repository.savePersona(request))
        }
    }

    /**
     * ⛔ THE DRAFT IS REHYDRATED FROM THE RE-READ CONFIG, AGAINST THE CATALOGUE ALREADY IN HAND.
     * Keeping the old draft would leave a saved value showing as dirty for ever; refetching the
     * catalogue would spend a request on a list that cannot have changed in the meantime.
     *
     * ⚠️ `SavedButStale` LEAVES THE DRAFT ALONE. The write landed but the re-read did not, so the
     * client cannot vouch for what is stored — and clearing the drafts would claim it can.
     */
    private fun applyOutcome(outcome: SaveOutcome) {
        _state.value = when (outcome) {
            is SaveOutcome.Saved -> _state.value.copy(
                load = ConfigState.Ready(outcome.config),
                edits = emptyMap(),
                save = SaveState.Saved,
                options = _state.value.draft?.let {
                    PersonaOptionsState.Ready(
                        PersonaEngineDraft.hydrate(outcome.config.aiPersona, it.options),
                    )
                } ?: _state.value.options,
            )
            is SaveOutcome.SavedButStale -> _state.value.copy(
                edits = emptyMap(),
                save = SaveState.SavedButStale(outcome.failure.toFailureText()),
            )
            is SaveOutcome.NotSaved -> _state.value.copy(
                save = SaveState.Failed(outcome.failure.toFailureText()),
            )
        }
    }

    companion object {
        fun factory(
            repository: WorkspaceConfigRepository,
            personaOptions: PersonaOptionsRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                PersonaFormViewModel(repository, personaOptions, workspaceId) as T
        }
    }
}
