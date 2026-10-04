package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PersonaEngineCapabilities
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.defaultVoice
import com.distronode.districtai.core.model.languagesForEngine
import com.distronode.districtai.core.model.responseLengthsForEngine

/** The persona fields this form edits besides the three free-text boxes. */
data class PersonaIdentityValues(
    val language: String,
    /** ⚠️ Not a picker here: it moves only with the language, for the language-keyed engine. */
    val voice: String,
    val responseLength: String,
)

/**
 * The persona's language and answer length, drawn from the server's catalogue.
 *
 * ⛔ THE ENGINE, VOICE, TEMPERATURE, SPEAK-SOONER AND VOICE STYLE ARE THE VOICE STUDIO'S, NOT
 * THIS FORM'S. They moved to their own screen (as they did on the web, where the Studio is its own
 * tab), and this form neither shows nor sends them, with ONE exception: changing the language of
 * the language-keyed engine moves the voice with it, because a Deepgram voice id encodes its own
 * language (`aura-2-asteria-en` cannot speak Italian) and the route would store the mismatch.
 *
 * ⛔ EVERY LIST COMES FROM [options] AND NOTHING IS HARDCODED HERE. The save route COERCES rather
 * than rejects, so a built-in list does not fail when it drifts; it produces a persona nobody
 * chose, with a 200.
 *
 * ⚠️ [modelId] IS THE STORED ENGINE AND IS READ-ONLY HERE. It decides which language list and
 * which answer lengths apply, and it rides along on a save only when the answer length changed
 * (see [write]).
 */
data class PersonaIdentityDraft(
    val options: PersonaOptionsResponse,
    val persona: AiPersona?,
    val values: PersonaIdentityValues,
    val baseline: PersonaIdentityValues,
) {

    val modelId: String get() = persona?.modelId.orEmpty()

    val languages: List<PersonaLabelledValue> get() = options.languagesForEngine(modelId.wire())

    val responseLengths: List<PersonaLabelledValue> get() = options.responseLengthsForEngine(modelId.wire())

    /**
     * Choose a language.
     *
     * ⛔ IT MOVES THE VOICE ONLY FOR THE LANGUAGE-KEYED ENGINE; every other engine keeps one voice
     * per engine, and that voice is the Voice Studio's to change.
     */
    fun selectLanguage(language: String): PersonaIdentityDraft {
        val next = copy(values = values.copy(language = language))
        if (!PersonaEngineCapabilities.forEngine(modelId.wire()).languageSelectsVoice) return next
        val voice = options.defaultVoice(modelId.wire(), language.wire()) ?: values.voice
        return next.copy(values = next.values.copy(voice = voice))
    }

    fun selectResponseLength(level: String): PersonaIdentityDraft = copy(values = values.copy(responseLength = level))

    val isDirty: Boolean get() = values != baseline

    /**
     * What to send.
     *
     * ⛔ `responseLength` WITHOUT `modelId` IS SILENTLY DISCARDED SERVER-SIDE (it is stored under
     * `responseLength[modelId]`), so a level change carries the STORED engine id with it. The same
     * id as stored is a no-op for the engine itself.
     *
     * ⛔ AN EMPTY STRING IS DROPPED RATHER THAN SENT: on this route `""` is a deliberate clear.
     */
    val write: PersonaIdentityWrite
        get() {
            val level = values.responseLength.takeIf { it != baseline.responseLength }?.wire()
            val engine = modelId.wire()
            val sendsLevel = level != null && engine != null
            return PersonaIdentityWrite(
                modelId = engine.takeIf { sendsLevel },
                language = values.language.takeIf { it != baseline.language }?.wire(),
                voice = values.voice.takeIf { it != baseline.voice }?.wire(),
                responseLength = level.takeIf { sendsLevel },
            )
        }

    /**
     * The unsaved form, as the preview route's sanitiser reads it.
     *
     * ⛔ IT SENDS WHAT IS ON SCREEN, CHANGED OR NOT, plus the STORED engine settings: the preview
     * persists nothing and merges against nothing, so an omitted key would leave the agent on its
     * own fallback rather than on what the workspace runs.
     */
    fun previewForm(name: String, greeting: String, personality: String): PersonaPreviewForm =
        PersonaPreviewForm(
            name = name.wire(),
            greeting = greeting.wire(),
            personality = personality.wire(),
            voice = values.voice.wire(),
            language = values.language.wire(),
            modelId = modelId.wire(),
            responseLength = values.responseLength.wire(),
            temperature = persona?.temperature ?: options.defaults.temperature,
            voiceStyle = persona?.voiceStyle?.wire(),
            preemptiveTts = persona?.preemptiveTts == true,
        )

    companion object {
        /**
         * The starting values, from what is stored and what the server publishes.
         *
         * ⚠️ THE DEFAULTS ARE THE SERVER'S. A workspace that never chose a voice starts on the
         * engine's published default, because a blank one saved as `""` would be a cleared voice.
         */
        fun hydrate(persona: AiPersona?, options: PersonaOptionsResponse): PersonaIdentityDraft {
            val modelId = persona?.modelId.orEmpty()
            val language = persona?.language.orEmpty()
            val values = PersonaIdentityValues(
                language = language,
                voice = persona?.voice ?: options.defaultVoice(modelId.wire(), language.wire()).orEmpty(),
                responseLength = persona?.responseLength.orEmpty()[modelId] ?: options.defaults.responseLength,
            )
            return PersonaIdentityDraft(options = options, persona = persona, values = values, baseline = values)
        }
    }
}

/** Exactly what a persona PATCH carries for this form's non-text half. A null is left alone. */
data class PersonaIdentityWrite(
    val modelId: String? = null,
    val language: String? = null,
    val voice: String? = null,
    val responseLength: String? = null,
)

/** ⛔ Empty means "not on the wire" for these fields, unlike the three free-text ones. */
private fun String.wire(): String? = takeIf { it.isNotEmpty() }
