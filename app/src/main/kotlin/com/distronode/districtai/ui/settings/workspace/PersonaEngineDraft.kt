package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PersonaEngineCapabilities
import com.distronode.districtai.core.model.PersonaEngineOption
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaVoiceGroup
import com.distronode.districtai.core.model.defaultVoice
import com.distronode.districtai.core.model.languagesForEngine
import com.distronode.districtai.core.model.responseLengthsForEngine
import com.distronode.districtai.core.model.voiceExists
import com.distronode.districtai.core.model.voiceGroups

/**
 * The seven engine fields a persona form edits, as one value.
 *
 * ⚠️ NO DEFAULTS: [PersonaEngineDraft.hydrate] is the only constructor call and it passes all
 * seven, from what is stored or from the server's published defaults.
 */
data class PersonaEngineValues(
    val modelId: String,
    val language: String,
    val voice: String,
    val responseLength: String,
    val temperature: Double,
    val voiceStyle: String,
    val preemptiveTts: Boolean,
)

/**
 * The engine half of the persona form: what the pickers offer, what changed, and what goes on the
 * wire.
 *
 * ⛔ EVERY LIST COMES FROM [options] AND NOTHING IS HARDCODED HERE. `PATCH workspace/persona`
 * COERCES rather than rejects — an unrecognised `modelId` becomes `deepgram-pipeline`, an
 * unrecognised `voice` is stored and then silently replaced by the agent — so a built-in catalogue
 * does not fail when it drifts; it produces a persona nobody chose, with a 200.
 *
 * ⛔ A DATA CLASS WITH `copy`, NOT A MUTABLE OBJECT. Plain field edits are `draft.copy(values =
 * draft.values.copy(...))` from the ViewModel; only the two edits that CASCADE ([selectEngine] and
 * [selectLanguage]) are methods here, because those are the two that are correctness rules rather
 * than assignments.
 *
 * ⚠️ [baseline] IS NOT FROZEN. [selectEngine] moves the response-length baseline with it, because
 * the stored level is per ENGINE: switching engine loads that engine's stored level, and treating
 * the previous engine's level as the baseline would report a change nobody made.
 */
data class PersonaEngineDraft(
    val options: PersonaOptionsResponse,
    val values: PersonaEngineValues,
    val baseline: PersonaEngineValues,
    /** ⚠️ The stored per-engine map, kept so [selectEngine] can rehydrate a level rather than guess. */
    val storedResponseLengths: Map<String, String>,
) {

    /** ⛔ EVERY ENGINE, INCLUDING OUT-OF-REGION ONES. The picker disables them; it does not hide them. */
    val engines: List<PersonaEngineOption> get() = options.engines

    val languages: List<PersonaLabelledValue> get() = options.languagesForEngine(values.modelId.wire())

    val voiceGroups: List<PersonaVoiceGroup>
        get() = options.voiceGroups(values.modelId.wire(), values.language.wire())

    val responseLengths: List<PersonaLabelledValue>
        get() = options.responseLengthsForEngine(values.modelId.wire())

    val voiceStyles: List<PersonaLabelledValue> get() = options.voiceStyles

    val capabilities: PersonaEngineCapabilities
        get() = PersonaEngineCapabilities.forEngine(values.modelId.wire())

    /**
     * ⚠️ A STORED VOICE THE CATALOGUE NO LONGER PUBLISHES IS STILL WHAT THE WORKSPACE SPEAKS IN
     * TODAY. It is reported so the screen can say so, and never silently corrected.
     */
    val voiceIsOffCatalogue: Boolean
        get() = values.voice.isNotEmpty() &&
            !options.voiceExists(values.voice, values.modelId.wire(), values.language.wire())

    /**
     * Choose an engine.
     *
     * ⛔ THREE CASCADES, ALL OF THEM CORRECTNESS RULES. A language the new engine does not publish
     * is CLEARED (Deepgram has no `hi-IN`, and the save route would store it anyway); the voice
     * moves to the new engine's default (a voice id belongs to one engine's registry); and the
     * response level is rehydrated from what is STORED for that engine, with the baseline moved
     * with it so an untouched level is not reported as a change.
     */
    fun selectEngine(engineId: String): PersonaEngineDraft {
        val language = if (values.language.isNotEmpty() && !offersLanguage(values.language, engineId)) {
            ""
        } else {
            values.language
        }
        val voice = options.defaultVoice(engineId, language.wire()) ?: values.voice
        val level = storedResponseLengths[engineId] ?: options.defaults.responseLength
        return copy(
            values = values.copy(
                modelId = engineId,
                language = language,
                voice = voice,
                responseLength = level,
            ),
            baseline = baseline.copy(responseLength = level),
        )
    }

    /**
     * Choose a language.
     *
     * ⛔ IT MOVES THE VOICE ONLY FOR THE LANGUAGE-KEYED ENGINE. A Deepgram voice id encodes its own
     * language (`aura-2-asteria-en` cannot speak Italian) and the save route would store the
     * mismatch happily; every other engine keeps one voice per engine and its language picker must
     * not touch it.
     */
    fun selectLanguage(language: String): PersonaEngineDraft {
        val next = copy(values = values.copy(language = language))
        if (!next.capabilities.languageSelectsVoice) return next
        val voice = options.defaultVoice(values.modelId.wire(), language.wire()) ?: values.voice
        return next.copy(values = next.values.copy(voice = voice))
    }

    /** Which of the seven differ from the baseline. */
    val changes: PersonaEngineChanges
        get() = PersonaEngineChanges(
            modelId = values.modelId != baseline.modelId,
            language = values.language != baseline.language,
            voice = values.voice != baseline.voice,
            responseLength = values.responseLength != baseline.responseLength,
            // ⚠️ AN EPSILON, BECAUSE THIS ONE IS A SLIDER. A float round trip through the wire and
            // back is not bit-identical, and an exact comparison would report a change on every
            // load for a value nobody touched.
            temperature = kotlin.math.abs(values.temperature - baseline.temperature) > TEMPERATURE_EPSILON,
            voiceStyle = values.voiceStyle != baseline.voiceStyle,
            preemptiveTts = values.preemptiveTts != baseline.preemptiveTts,
        )

    /**
     * What to send.
     *
     * ⛔ `responseLength` WITHOUT `modelId` IS SILENTLY DISCARDED SERVER-SIDE. The route stores the
     * level under `aiPersona.responseLength[modelId]` and refuses to guess at the stored engine, so
     * a level change carries the engine id with it even when the engine itself did not change —
     * which is the one place this write deliberately sends a field that is not dirty.
     *
     * ⛔ AN EMPTY STRING IS DROPPED RATHER THAN SENT. On this route `""` is a deliberate CLEAR, and
     * clearing a voice makes the agent fall back to one nobody chose; a form that has simply never
     * had a value must not clear anything.
     */
    val write: PersonaEngineWrite
        get() {
            val changed = changes
            val engine = values.modelId.wire()
            val level = if (changed.responseLength) values.responseLength.wire() else null
            val sendsLevel = level != null && engine != null
            return PersonaEngineWrite(
                modelId = if (changed.modelId || sendsLevel) engine else null,
                language = if (changed.language) values.language.wire() else null,
                voice = if (changed.voice) values.voice.wire() else null,
                responseLength = if (sendsLevel) level else null,
                temperature = if (changed.temperature) values.temperature else null,
                voiceStyle = if (changed.voiceStyle) values.voiceStyle.wire() else null,
                preemptiveTts = if (changed.preemptiveTts) values.preemptiveTts else null,
            )
        }

    /**
     * The unsaved form, as the preview route's sanitiser reads it.
     *
     * ⛔ IT SENDS WHAT IS ON SCREEN, CHANGED OR NOT, which is the exact opposite of [write]. The
     * preview persists nothing and merges against nothing, so an omitted key leaves the agent on
     * its own fallback for that session rather than preserving a stored value — sending only the
     * dirty fields would audition a persona that is neither the stored one nor the drafted one.
     */
    fun previewForm(name: String, greeting: String, personality: String): PersonaPreviewForm =
        PersonaPreviewForm(
            name = name.wire(),
            greeting = greeting.wire(),
            personality = personality.wire(),
            voice = values.voice.wire(),
            language = values.language.wire(),
            modelId = values.modelId.wire(),
            responseLength = values.responseLength.wire(),
            temperature = values.temperature,
            voiceStyle = values.voiceStyle.wire(),
            preemptiveTts = values.preemptiveTts,
        )

    private fun offersLanguage(language: String, engineId: String): Boolean =
        options.languagesForEngine(engineId).any { it.value == language }

    companion object {
        /**
         * Build the starting values from what is stored and what the server publishes.
         *
         * ⚠️ THE DEFAULTS ARE THE SERVER'S. A workspace that has never chosen a voice starts on the
         * engine's published default rather than on a blank picker, because a blank one saved as
         * `""` is a cleared voice.
         */
        fun hydrate(persona: AiPersona?, options: PersonaOptionsResponse): PersonaEngineDraft {
            val modelId = persona?.modelId.orEmpty()
            val language = persona?.language.orEmpty()
            val stored = persona?.responseLength.orEmpty()
            val values = PersonaEngineValues(
                modelId = modelId,
                language = language,
                voice = persona?.voice
                    ?: options.defaultVoice(modelId.wire(), language.wire())
                    ?: "",
                responseLength = stored[modelId] ?: options.defaults.responseLength,
                temperature = persona?.temperature ?: options.defaults.temperature,
                voiceStyle = persona?.voiceStyle.orEmpty(),
                preemptiveTts = persona?.preemptiveTts == true,
            )
            return PersonaEngineDraft(
                options = options,
                values = values,
                baseline = values,
                storedResponseLengths = stored,
            )
        }
    }
}

/** Which engine fields differ from what was loaded. */
data class PersonaEngineChanges(
    val modelId: Boolean = false,
    val language: Boolean = false,
    val voice: Boolean = false,
    val responseLength: Boolean = false,
    val temperature: Boolean = false,
    val voiceStyle: Boolean = false,
    val preemptiveTts: Boolean = false,
) {
    val isEmpty: Boolean
        get() = !(modelId || language || voice || responseLength || temperature || voiceStyle || preemptiveTts)
}

/** Exactly what a persona PATCH should carry for the engine half. A null is left alone. */
data class PersonaEngineWrite(
    val modelId: String? = null,
    val language: String? = null,
    val voice: String? = null,
    val responseLength: String? = null,
    val temperature: Double? = null,
    val voiceStyle: String? = null,
    val preemptiveTts: Boolean? = null,
)

/**
 * ⛔ EMPTY MEANS "NOT ON THE WIRE" FOR THE ENGINE FIELDS, which is the opposite of the three
 * free-text ones on the same route. `greeting = ""` is a deliberate clear; `voice = ""` would make
 * the agent fall back to a voice nobody chose, and a picker that has never been touched must not
 * send one.
 */
private fun String.wire(): String? = takeIf { it.isNotEmpty() }

/** ⚠️ A slider's worth of float noise. See [PersonaEngineDraft.changes]. */
private const val TEMPERATURE_EPSILON: Double = 0.0005
