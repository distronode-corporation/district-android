package com.distronode.districtai.core.model

/**
 * The rules a persona picker follows, expressed once and on the tier that has tests.
 *
 * ⛔ HERE RATHER THAN IN A COMPOSABLE, BECAUSE EVERY ONE OF THEM IS A CORRECTNESS RULE AND NOT A
 * LAYOUT ONE. Which languages an engine offers, which voice a language switch lands on, whether a
 * style picker appears at all — each is a claim the SERVER will not contradict, because the save
 * route coerces instead of refusing. A rule implemented inside a `@Composable` is a rule no JVM
 * unit test can reach without Robolectric.
 */

/**
 * ⛔ THE ONE ENGINE WHOSE CATALOGUES ARE KEYED BY LANGUAGE, named once. The server states the same
 * asymmetry in its own route; restating it in a screen would put "which languages" and "which
 * voices" in two places that can disagree.
 */
const val PERSONA_LANGUAGE_KEYED_ENGINE: String = "deepgram-pipeline"

/**
 * ⛔ THE GEMINI LIVE ENGINE ID, AND IT IS SPELLED OUT RATHER THAN DERIVED. The options payload
 * marks no engine as realtime, and inferring it from the substring "gemini" would classify a
 * future chained Gemini pipeline wrongly in the one direction that shows a picker with no effect.
 */
const val PERSONA_GEMINI_LIVE_ENGINE: String = "gemini-live-2.5-flash-native-audio"

/**
 * The engines an operator may actually choose.
 *
 * ⚠️ NOT WHAT THE PICKER SHOWS. The picker shows every engine and DISABLES the rest, so the label
 * can say why; this is what a selection is validated against.
 */
val PersonaOptionsResponse.selectableEngines: List<PersonaEngineOption>
    get() = engines.filter { it.inRegion }

fun PersonaOptionsResponse.engine(id: String?): PersonaEngineOption? =
    id?.let { wanted -> engines.firstOrNull { it.id == wanted } }

/**
 * The languages one engine offers.
 *
 * ⛔ THE SHORT LIST IS NOT A SUBSET OF THE LONG ONE. Deepgram has `nl-NL` and `it-IT` and no
 * `hi-IN`. An engine this client has never heard of gets the general list, which is what the
 * server's own ternary does.
 */
fun PersonaOptionsResponse.languagesForEngine(engineId: String?): List<PersonaLabelledValue> =
    if (engineId == PERSONA_LANGUAGE_KEYED_ENGINE) languages.deepgram else languages.general

/**
 * The voice groups for one engine in one language.
 *
 * ⚠️ AN EMPTY LIST IS A REAL ANSWER. A stored persona can name a language its engine does not
 * publish — the save route never refused one — and the honest rendering is an empty picker with
 * the existing value still shown, never a silent substitution.
 */
fun PersonaOptionsResponse.voiceGroups(engineId: String?, language: String?): List<PersonaVoiceGroup> {
    if (engineId == null || language == null) return emptyList()
    return voices.firstOrNull { it.engine == engineId && it.language == language }?.groups.orEmpty()
}

/**
 * Whether `voice` appears anywhere in that engine-and-language catalogue.
 *
 * ⛔ THE QUESTION A FORM MUST ASK BEFORE IT DECIDES A STORED VALUE IS STALE, and the answer must
 * not drive a silent correction: an id the catalogue has dropped is still what the workspace is
 * speaking in today.
 */
fun PersonaOptionsResponse.voiceExists(voice: String?, engineId: String?, language: String?): Boolean {
    if (voice == null) return false
    return voiceGroups(engineId, language).any { group -> group.options.any { it.value == voice } }
}

/**
 * The voice to land on after an engine or language change.
 *
 * ⛔ DEEPGRAM'S PER-LANGUAGE MAP WINS, AND THAT ORDER IS THE WHOLE FUNCTION. A Deepgram voice id
 * encodes its own language (`aura-2-asteria-en` cannot speak Italian) and the save route would
 * store the mismatch happily, so switching language has to move the voice with it. Every other
 * engine keeps one starting voice per engine and its language picker does not touch it.
 *
 * ⚠️ null MEANS "THE SERVER PUBLISHED NO DEFAULT FOR THIS COMBINATION", which is not the same as
 * an error and not the same as an empty string. A caller leaves the field as it was rather than
 * clearing it — clearing it would be a save that stores `""` and makes the agent fall back to a
 * voice nobody chose.
 */
fun PersonaOptionsResponse.defaultVoice(engineId: String?, language: String?): String? {
    if (engineId == null) return null
    if (engineId == PERSONA_LANGUAGE_KEYED_ENGINE && language != null) {
        return defaults.voiceByDeepgramLanguage[language]
    }
    return defaults.voiceByEngine[engineId]
}

/**
 * The answer lengths one engine offers.
 *
 * ⚠️ EMPTY FOR AN ENGINE THE CATALOGUE DOES NOT CARRY, which hides the picker rather than showing
 * three options that would be saved under a `modelId` the route is about to coerce.
 */
fun PersonaOptionsResponse.responseLengthsForEngine(engineId: String?): List<PersonaLabelledValue> =
    engine(engineId)?.responseLengths.orEmpty()

/**
 * What the persona form's non-text controls do for one engine.
 *
 * ⛔ A TYPE RATHER THAN THREE BOOLEANS SCATTERED THROUGH A SCREEN, because two of the three are the
 * difference between a control that works and a control that silently does nothing. `voiceStyle`
 * is read only by Gemini Live (the chained pipelines have no TTS stage to posture) and
 * `preemptiveTts` is meaningless to it (a realtime engine does not speculate ahead of its own
 * audio), so each is a setting the other engine will accept, store and ignore — with a 200.
 */
data class PersonaEngineCapabilities(
    /** ⚠️ Gemini Live only. */
    val showsVoiceStyle: Boolean,
    /** ⚠️ Every engine EXCEPT Gemini Live, mirroring the web form's own condition. */
    val showsPreemptiveTts: Boolean,
    /** Whether switching language should move the voice with it. */
    val languageSelectsVoice: Boolean,
) {
    companion object {
        /**
         * ⚠️ `showsPreemptiveTts` IS TRUE FOR A null ENGINE, deliberately: an unloaded or
         * unrecognised engine is treated as a chained pipeline, which is what the web's `!==`
         * comparison does and what every engine but one actually is.
         */
        fun forEngine(engineId: String?): PersonaEngineCapabilities {
            val gemini = engineId == PERSONA_GEMINI_LIVE_ENGINE
            return PersonaEngineCapabilities(
                showsVoiceStyle = gemini,
                showsPreemptiveTts = !gemini,
                languageSelectsVoice = engineId == PERSONA_LANGUAGE_KEYED_ENGINE,
            )
        }
    }
}
