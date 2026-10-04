package com.distronode.districtai.ui.settings.workspace.studio

/** One picker as the leg editor draws it: what it offers, what is held, and what a choice does. */
class PickerModel(
    val label: String,
    val options: List<PickerOption>,
    val selected: String?,
    /** The picker's test handle id (`ear-vendor`, `llm-location`, ...). */
    val handle: String,
    val onSelect: (String) -> Unit,
)

/** The voice picker: every voice of the held mouth (or realtime model), under its group headings. */
class VoiceModel(
    val label: String,
    private val placeholder: String,
    /** (group heading, option), in the server's order; a heading of `""` is no heading. */
    val voices: List<Pair<String, PickerOption>>,
    private val selected: String,
    val onSelect: (String) -> Unit,
) {
    /** The held voice's name, or the placeholder when the list does not have it. */
    val selectedLabel: String
        get() = voices.firstOrNull { it.second.value == selected }?.second?.label ?: placeholder

    /** The heading to draw above row [index]: the first row of each named group, else null. */
    fun heading(index: Int): String? {
        val group = voices[index].first
        return group.takeIf { it.isNotEmpty() && voices.getOrNull(index - 1)?.first != it }
    }
}

/**
 * What the open leg's editor offers, built outside composition.
 *
 * ⚠️ PLAIN KOTLIN RATHER THAN LAMBDAS WRITTEN IN THE COMPOSABLES, for two reasons. The rules
 * (which picker a leg has, which edit a choice makes) are tested here without a UI; and a lambda
 * written inside a composable is memoized by the Compose compiler behind change checks that a
 * screen test can only partly reach, so the composables stay straight-line.
 */
object StudioLegControls {

    private const val VENDOR = "vendor"
    private const val MODEL = "model"

    /** The pickers for the leg the editor is on: vendor, model and location, as each leg has them. */
    fun pickers(state: VoiceStudioUiState.Ready, actions: VoiceStudioActions): List<PickerModel> {
        val engine = state.held.engine
        if (engine is StudioEngine.Realtime) return listOf(realtimeModel(state, engine, actions))
        val mix = (engine as StudioEngine.Chained).mix
        val studio = state.studio
        val labels = studio.labels
        val bilingual = state.held.bilingual
        val leg = state.leg
        val pickers = when (leg) {
            StudioLegEdits.EAR -> listOf(
                PickerModel(
                    labels.providerLabel,
                    StudioPickers.earVendors(mix, bilingual, studio),
                    mix.stt.provider,
                    "ear-$VENDOR",
                ) { p -> actions.editMix { StudioLegEdits.earVendor(it, p, bilingual, studio) } },
                PickerModel(
                    labels.modelLabel,
                    StudioPickers.earModels(mix, bilingual, studio),
                    mix.stt.model,
                    "ear-$MODEL",
                ) { m -> actions.editMix { StudioLegEdits.earModel(it, m, studio) } },
            )
            StudioLegEdits.BRAIN -> listOf(
                PickerModel(
                    labels.modelLabel,
                    StudioPickers.brainModels(mix, studio),
                    mix.llm.model,
                    "brain-$MODEL",
                ) { m -> actions.editMix { StudioLegEdits.brainModel(it, m, studio) } },
            )
            StudioLegEdits.MOUTH -> listOf(
                PickerModel(
                    labels.providerLabel,
                    StudioPickers.voiceVendors(mix, bilingual, studio),
                    mix.tts.provider,
                    "voice-$VENDOR",
                ) { p -> actions.editMix { StudioLegEdits.voiceVendor(it, p, bilingual, studio) } },
                PickerModel(
                    labels.modelLabel,
                    StudioPickers.voiceModels(mix, bilingual, studio),
                    mix.tts.model,
                    "voice-$MODEL",
                ) { m -> actions.editMix { StudioLegEdits.voiceModel(it, m, studio) } },
            )
            // Turn-taking has no model of its own to pick: Flux's, or the agent's detector.
            else -> return emptyList()
        }
        return pickers + listOfNotNull(location(state, leg, actions))
    }

    /** The voice picker, on the Voice leg of a chain and on a realtime engine. */
    fun voice(state: VoiceStudioUiState.Ready, actions: VoiceStudioActions): VoiceModel? {
        val engine = state.held.engine
        if (engine is StudioEngine.Chained && state.leg != StudioLegEdits.MOUTH) return null
        val labels = state.studio.labels
        return VoiceModel(
            label = labels.voiceLabel,
            placeholder = labels.voicePlaceholder,
            voices = StudioRecipes.voices(engine, state.studio),
            selected = engine.voice,
        ) { voice -> actions.updateHeld { it.copy(engine = StudioRecipes.withVoice(it.engine, voice)) } }
    }

    /**
     * A leg's location, when its model has a list (a vendor endpoint has none).
     *
     * ⚠️ An unset location shows the list's first entry, which is what the model runs at.
     */
    private fun location(state: VoiceStudioUiState.Ready, leg: String, actions: VoiceStudioActions): PickerModel? {
        val mix = (state.held.engine as StudioEngine.Chained).mix
        val options = StudioPickers.locations(leg, mix, state.studio)
        val held = when (leg) {
            StudioLegEdits.EAR -> mix.stt.location
            StudioLegEdits.BRAIN -> mix.llm.location
            else -> mix.tts.location
        }
        return options.firstOrNull()?.let { first ->
            PickerModel(state.studio.labels.locationLabel, options, held ?: first.value, "$leg-location") { l ->
                actions.editMix { StudioLegEdits.location(it, leg, l) }
            }
        }
    }

    private fun realtimeModel(
        state: VoiceStudioUiState.Ready,
        engine: StudioEngine.Realtime,
        actions: VoiceStudioActions,
    ): PickerModel = PickerModel(
        state.studio.labels.modelLabel,
        StudioPickers.realtimeModels(engine, state.studio),
        engine.modelId,
        "realtime-$MODEL",
    ) { m -> actions.updateHeld { it.copy(engine = StudioLegEdits.realtimeModel(engine, m, state.studio)) } }
}
