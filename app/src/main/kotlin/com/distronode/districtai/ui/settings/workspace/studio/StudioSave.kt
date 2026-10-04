package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioChain
import com.distronode.districtai.core.model.VoiceStudioFields
import com.distronode.districtai.core.model.VoiceStudioPreset

/**
 * The engine the Studio holds: a chain of legs, or one realtime model.
 *
 * ⚠️ A DATA CLASS PAIR, SO "IS THIS THE ENGINE A RECIPE (OR THE SAVED PERSONA) RUNS" IS EQUALITY.
 * The readout uses that to show the server's own numbers whenever it can (see [StudioReadout]).
 */
sealed interface StudioEngine {

    /** The voice the caller hears, whichever kind this is. */
    val voice: String

    data class Chained(val mix: EngineMix) : StudioEngine {
        override val voice: String get() = mix.tts.voice
    }

    data class Realtime(val modelId: String, override val voice: String) : StudioEngine

    companion object {
        /**
         * A resolved chain as the Studio holds it.
         *
         * ⚠️ ONE TEST, NOT A `kind` SWITCH. The server sets `engineMix` exactly when `kind` is
         * `chained` (the contract test pins it), so "has a mix" is the same question with no
         * third arm to leave uncovered.
         */
        fun of(chain: VoiceStudioChain): StudioEngine =
            chain.engineMix?.let(::Chained) ?: Realtime(chain.realtimeModelId.orEmpty(), chain.voice)
    }
}

/**
 * Everything the Studio's save is computed from (the web's `StudioState`).
 *
 * [realtimeTemperature] is `aiPersona.temperature`, the REALTIME model's, 0-1; a chain's brain
 * temperature lives in its mix (`engineMix.llm.temperature`, 0-2). They are different keys.
 */
data class StudioState(
    val engine: StudioEngine,
    val realtimeTemperature: Double,
    /** English and French on one call. */
    val bilingual: Boolean,
    /** Gemini 2.5 Live's voice style; null until chosen. */
    val voiceStyle: String?,
)

/** The persona PATCH keys the Studio owns. */
enum class StudioKey { MODEL_ID, VOICE, ENGINE_MIX, PREEMPTIVE_TTS, TEMPERATURE, BILINGUAL, VOICE_STYLE }

/**
 * What a Studio state saves as, which keys a save sends, and whether the re-read agrees.
 *
 * ⛔ A PORT OF THE WEB STUDIO'S `fieldsOf` / `changedFields` / `canonicalModelId`, AND THE PRESET
 * RULE IS THE ONE THAT MATTERS: a chain equal to one of the server's presets is saved as that
 * fixed engine's id (with its voice and speak-sooner at top level), never as `custom-pipeline`
 * with a mix. Saving it as custom would move a workspace off a fixed engine nobody asked to leave.
 */
object StudioSave {

    const val CUSTOM_PIPELINE: String = "custom-pipeline"
    const val GEMINI_LIVE_25: String = "gemini-live-2.5-flash-native-audio"
    private const val GEMINI_38_LIVE: String = "gemini-3.8-live"

    /** The two engines that carry `aiPersona.bilingual` (the agent's `BILINGUAL_MODEL_IDS`). */
    private val BILINGUAL_ENGINES = setOf(CUSTOM_PIPELINE, GEMINI_38_LIVE)

    /** English and French are the only bilingual pair. */
    private val BILINGUAL_LANGUAGES = setOf("en", "fr")

    /** Whether a persona language has a bilingual counterpart (English or French). */
    fun bilingualPair(language: String): Boolean =
        language.substringBefore('-').lowercase() in BILINGUAL_LANGUAGES

    /** Whether `bilingual` applies to an engine id and a persona language. */
    fun bilingualAvailable(modelId: String, language: String): Boolean =
        modelId in BILINGUAL_ENGINES && bilingualPair(language)

    /**
     * The engine id a chain saves as: the first preset it equals, else `custom-pipeline`.
     *
     * ⛔ ANY STUDIO TUNING KEY MAKES IT CUSTOM, because the fixed engines cannot carry one. Then
     * equality covers the ear (provider, model, language, location), the brain (model, location,
     * thinking, temperature), the mouth (provider, model, speed, location) and turn-taking's three
     * numbers. The VOICE and speak-sooner are ignored: a fixed engine takes any voice of its model
     * and its own speak-sooner flag. So is `userAwayTimeout`.
     */
    fun canonicalModelId(mix: EngineMix, presets: List<VoiceStudioPreset>): String {
        if (hasChainTuning(mix)) return CUSTOM_PIPELINE
        return presets.firstOrNull { sameCore(it.engineMix, mix) }?.modelId ?: CUSTOM_PIPELINE
    }

    private fun hasChainTuning(mix: EngineMix): Boolean =
        listOf(
            mix.stt.keyterms,
            mix.tts.stability,
            mix.tts.expressivity,
            mix.turn.mode,
            mix.turn.eagerEotThreshold,
            mix.turn.eotTimeoutMs,
            mix.turn.interruption,
        ).any { it != null }

    private fun sameCore(a: EngineMix, b: EngineMix): Boolean =
        a.stt == b.stt &&
            a.llm == b.llm &&
            a.tts.copy(voice = "") == b.tts.copy(voice = "") &&
            a.turn == b.turn

    /** What [state] saves as for a persona speaking [language]. */
    fun fieldsOf(state: StudioState, presets: List<VoiceStudioPreset>, language: String): VoiceStudioFields {
        val fields = when (val engine = state.engine) {
            is StudioEngine.Chained -> {
                // ⛔ A bilingual chain is the custom engine by definition: only the two preview
                // engines carry `aiPersona.bilingual`.
                val modelId = if (state.bilingual && bilingualPair(language)) {
                    CUSTOM_PIPELINE
                } else {
                    canonicalModelId(engine.mix, presets)
                }
                VoiceStudioFields(
                    modelId = modelId,
                    voice = engine.mix.tts.voice,
                    engineMix = engine.mix.takeIf { modelId == CUSTOM_PIPELINE },
                    preemptiveTts = engine.mix.preemptiveTts,
                    temperature = null,
                    bilingual = null,
                    voiceStyle = null,
                )
            }
            is StudioEngine.Realtime -> VoiceStudioFields(
                modelId = engine.modelId,
                voice = engine.voice,
                engineMix = null,
                preemptiveTts = null,
                temperature = state.realtimeTemperature,
                bilingual = null,
                voiceStyle = state.voiceStyle.takeIf { engine.modelId == GEMINI_LIVE_25 },
            )
        }
        return fields.copy(bilingual = state.bilingual.takeIf { bilingualAvailable(fields.modelId, language) })
    }
}
