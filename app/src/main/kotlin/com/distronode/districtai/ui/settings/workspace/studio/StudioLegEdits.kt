package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.EngineMixStt
import com.distronode.districtai.core.model.EngineMixTts
import com.distronode.districtai.core.model.VoiceStudioResponse

/**
 * The edits a leg editor makes, each keeping the chain one the PATCH accepts.
 *
 * ⛔ SECTION 3.3 OF THE SERVER'S SPEC, AND EVERY RULE IS THERE BECAUSE THE PATCH REFUSES THE
 * ALTERNATIVE. A mix the catalogue does not accept (a voice the new mouth does not have, a
 * location the new model is not offered in, key terms on an ear that takes none) answers 400
 * `invalid_engine_mix` and writes nothing, so a vendor or model switch moves the dependent fields
 * with it rather than leaving them for the server to refuse. Every edit then goes through
 * [StudioTuning.conform], which drops a tuning value the new model does not honour (the server
 * would drop it silently, and the re-read would then report the save as failed).
 */
object StudioLegEdits {

    const val EAR: String = "stt"
    const val TURN: String = "turn"
    const val BRAIN: String = "llm"
    const val MOUTH: String = "tts"
    const val REALTIME: String = "realtime"

    /** A new ear vendor: its first model for the language, at that model's default location. */
    fun earVendor(mix: EngineMix, provider: String, bilingual: Boolean, studio: VoiceStudioResponse): EngineMix {
        val fit = studio.catalog.stt.filter {
            it.provider == provider && if (bilingual) it.forBilingual else it.forLanguage
        }
        val model = fit.firstOrNull { it.offered } ?: fit.firstOrNull() ?: return mix
        val stt = EngineMixStt(provider, model.model, language = null, location = model.defaultLocation)
        return StudioTuning.conform(mix.copy(stt = stt), studio)
    }

    /** A new ear model of the same vendor: its location kept where offered, key terms only where taken. */
    fun earModel(mix: EngineMix, model: String, studio: VoiceStudioResponse): EngineMix {
        val next = studio.catalog.stt.firstOrNull { it.provider == mix.stt.provider && it.model == model } ?: return mix
        val location = mix.stt.location.takeIf { held -> next.locations.any { it.value == held } }
            ?: next.defaultLocation
        val stt = mix.stt.copy(model = model, location = location, keyterms = mix.stt.keyterms.takeIf { next.keyterms })
        return StudioTuning.conform(mix.copy(stt = stt), studio)
    }

    /** A new brain: its location kept where offered, its own default thinking, the temperature kept. */
    fun brainModel(mix: EngineMix, model: String, studio: VoiceStudioResponse): EngineMix {
        val next = studio.catalog.llm.firstOrNull { it.model == model } ?: return mix
        val location = mix.llm.location.takeIf { held -> next.locations.any { it.value == held } }
            ?: next.defaultLocation
        val llm = mix.llm.copy(model = model, location = location, thinking = next.defaultThinking)
        return StudioTuning.conform(mix.copy(llm = llm), studio)
    }

    /** A new mouth vendor: its first model for the language, on that model's starting voice. */
    fun voiceVendor(mix: EngineMix, provider: String, bilingual: Boolean, studio: VoiceStudioResponse): EngineMix {
        val fit = studio.catalog.tts.filter {
            it.provider == provider && if (bilingual) it.forBilingual else it.forLanguage
        }
        val model = fit.firstOrNull { it.offered } ?: fit.firstOrNull() ?: return mix
        val tts =
            EngineMixTts(provider, model.model, model.defaultVoice, speed = null, location = model.defaultLocation)
        return StudioTuning.conform(mix.copy(tts = tts), studio)
    }

    /** A new mouth model of the same vendor: the voice kept if it has it, else its starting voice. */
    fun voiceModel(mix: EngineMix, model: String, studio: VoiceStudioResponse): EngineMix {
        val next = studio.catalog.tts.firstOrNull { it.provider == mix.tts.provider && it.model == model } ?: return mix
        val voices = StudioRecipes.voiceValues(StudioRecipes.ttsVoices(mix.tts.provider, model, studio))
        val voice = mix.tts.voice.takeIf { it in voices } ?: next.defaultVoice
        val location = mix.tts.location.takeIf { held -> next.locations.any { it.value == held } }
            ?: next.defaultLocation
        return StudioTuning.conform(
            mix.copy(tts = mix.tts.copy(model = model, voice = voice, location = location)),
            studio
        )
    }

    /** A location from the held model's own list (the picker offers nothing else). */
    fun location(mix: EngineMix, leg: String, location: String): EngineMix = when (leg) {
        EAR -> mix.copy(stt = mix.stt.copy(location = location))
        BRAIN -> mix.copy(llm = mix.llm.copy(location = location))
        else -> mix.copy(tts = mix.tts.copy(location = location))
    }

    /**
     * Another realtime model: the voice kept if the new model speaks it, else its first voice.
     * (Gemini 3.8 Live speaks 2.5 Live's five voices, so a switch between them keeps the voice.)
     */
    fun realtimeModel(
        engine: StudioEngine.Realtime,
        model: String,
        studio: VoiceStudioResponse,
    ): StudioEngine.Realtime {
        val voices = StudioRecipes.voiceValues(StudioRecipes.voicesFor(StudioEngine.Realtime(model, ""), studio))
        val voice = engine.voice.takeIf { it in voices } ?: voices.firstOrNull() ?: engine.voice
        return StudioEngine.Realtime(model, voice)
    }
}
