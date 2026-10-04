package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.EngineMixStt
import com.distronode.districtai.core.model.VoiceStudioResponse

/**
 * A chain of the member's own, moved to models that speak a new persona language: the web's
 * `conformEngineMix`, from what the Studio's read says (as district-linux's `studio::refit`).
 *
 * ⛔ THE PERSONA FORM SAVES A NEW LANGUAGE WITH THE STORED CHAIN LEFT AS IT WAS, and a chain whose
 * ear or voice does not speak that language is one the server no longer accepts (its read then
 * carries `current.engineMix` null). The read taken after that save is computed for the new
 * language, so it says which models fit (`offered && forLanguage`). Each speech leg that no longer
 * fits moves to the nearest model that does: the same vendor's first (for the ear, one that takes
 * turns the same way first), then any other in catalogue order. The voice is kept where the new
 * model has it, the location where it is offered. The brain does not depend on the language.
 */
object StudioRefit {

    /** [mix] fitted to the language [studio] was read for, or null when nothing offered speaks it. */
    fun refit(mix: EngineMix, studio: VoiceStudioResponse): EngineMix? {
        val ear = refitEar(mix, studio) ?: return null
        val mouth = nearest(
            studio.catalog.tts,
            fits = { it.offered && it.forLanguage },
            best = { it.provider == mix.tts.provider && it.model == mix.tts.model },
            good = { it.provider == mix.tts.provider },
        ) ?: return null
        val voices = StudioRecipes.voiceValues(StudioRecipes.ttsVoices(mouth.provider, mouth.model, studio))
        val tts = mix.tts.copy(
            provider = mouth.provider,
            model = mouth.model,
            voice = mix.tts.voice.takeIf { it in voices } ?: mouth.defaultVoice,
            location = mix.tts.location.takeIf { held -> mouth.locations.any { it.value == held } }
                ?: mouth.defaultLocation,
        )
        return StudioTuning.conform(mix.copy(stt = ear, tts = tts), studio)
    }

    private fun refitEar(mix: EngineMix, studio: VoiceStudioResponse): EngineMixStt? {
        val held = studio.catalog.stt.firstOrNull { it.provider == mix.stt.provider && it.model == mix.stt.model }
        if (held != null && held.offered && held.forLanguage) return mix.stt
        val takesTurns = held?.takesTurns == true
        val ear = nearest(
            studio.catalog.stt,
            fits = { it.offered && it.forLanguage },
            best = { it.provider == mix.stt.provider && it.takesTurns == takesTurns },
            good = { it.provider == mix.stt.provider },
        ) ?: return null
        return EngineMixStt(
            provider = ear.provider,
            model = ear.model,
            language = null,
            location = mix.stt.location.takeIf { held -> ear.locations.any { it.value == held } }
                ?: ear.defaultLocation,
            keyterms = mix.stt.keyterms.takeIf { ear.keyterms },
        )
    }

    /** The first model that [fits], looking among the [best], then the [good], then all of them. */
    private fun <T> nearest(models: List<T>, fits: (T) -> Boolean, best: (T) -> Boolean, good: (T) -> Boolean): T? =
        models.firstOrNull { fits(it) && best(it) }
            ?: models.firstOrNull { fits(it) && good(it) }
            ?: models.firstOrNull(fits)
}
