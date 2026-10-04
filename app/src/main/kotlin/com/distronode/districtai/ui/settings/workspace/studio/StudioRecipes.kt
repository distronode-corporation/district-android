package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.VoiceStudioRecipe
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.VoiceStudioVoiceList

/**
 * Recipes, voices, and how far the held engine is from where it started.
 *
 * ⚠️ PORTED FROM THE WEB STUDIO (`choiceEngine`, `appliedEngine`, `countChanges`), with the lists
 * read from the Studio response rather than from a catalogue in this app.
 */
object StudioRecipes {

    const val CUSTOM: String = "custom"
    const val STABLE: String = "stable"
    const val LATEST: String = "latest"

    private const val TTS_KIND = "tts"
    private const val REALTIME_KIND = "realtime"

    /** The state the Studio opens on: the persona as saved, read from `current`. */
    fun initialState(studio: VoiceStudioResponse): StudioState = StudioState(
        engine = StudioEngine.of(studio.current.chain),
        realtimeTemperature = studio.current.temperature,
        bilingual = studio.current.bilingual,
        voiceStyle = studio.current.voiceStyle,
    )

    /** One tier's tiles, in the server's tile order. */
    fun tiles(studio: VoiceStudioResponse, tier: String): List<VoiceStudioRecipe> =
        studio.recipeIds.mapNotNull { id -> studio.recipes.firstOrNull { it.id == id && it.tier == tier } }

    /**
     * The engine a recipe tile applies.
     *
     * ⛔ "YOUR CHAIN" IS THE SAVED CHAIN ITSELF, voice included, when the saved engine is a chain.
     * Any other recipe keeps the voice the Studio holds now where the new mouth (or realtime
     * model) has it: picking "Fastest" should not silently change who the caller hears when it
     * need not. Otherwise the recipe's own voice, which the server chose for that mouth.
     */
    fun applied(
        recipe: VoiceStudioRecipe,
        saved: StudioEngine,
        current: StudioEngine,
        studio: VoiceStudioResponse,
    ): StudioEngine {
        if (recipe.id == CUSTOM && saved is StudioEngine.Chained) return saved
        val engine = StudioEngine.of(recipe.chain)
        val keeps = voiceValues(voicesFor(engine, studio)).contains(current.voice)
        return if (keeps) withVoice(engine, current.voice) else engine
    }

    /** The engine with a different voice. */
    fun withVoice(engine: StudioEngine, voice: String): StudioEngine = when (engine) {
        is StudioEngine.Chained -> StudioEngine.Chained(engine.mix.copy(tts = engine.mix.tts.copy(voice = voice)))
        is StudioEngine.Realtime -> engine.copy(voice = voice)
    }

    /** The voices an engine's mouth (or realtime model) speaks, or null when the server lists none. */
    fun voicesFor(engine: StudioEngine, studio: VoiceStudioResponse): VoiceStudioVoiceList? = when (engine) {
        is StudioEngine.Chained -> ttsVoices(engine.mix.tts.provider, engine.mix.tts.model, studio)
        is StudioEngine.Realtime -> studio.voices.firstOrNull { it.kind == REALTIME_KIND && it.model == engine.modelId }
    }

    /** One text-to-speech model's voices. */
    fun ttsVoices(provider: String, model: String, studio: VoiceStudioResponse): VoiceStudioVoiceList? =
        studio.voices.firstOrNull { it.kind == TTS_KIND && it.provider == provider && it.model == model }

    /** Every voice id of a list. */
    fun voiceValues(list: VoiceStudioVoiceList?): List<String> =
        list?.groups.orEmpty().flatMap { group -> group.options.map { it.value } }

    /** Every voice the held mouth (or realtime model) speaks, flattened with its group heading. */
    fun voices(engine: StudioEngine, studio: VoiceStudioResponse): List<Pair<String, PickerOption>> =
        voicesFor(engine, studio)?.groups.orEmpty().flatMap { group ->
            group.options.map { group.label to PickerOption(it.value, it.label) }
        }
}
