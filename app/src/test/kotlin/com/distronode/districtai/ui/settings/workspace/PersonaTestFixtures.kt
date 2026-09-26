package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.PERSONA_GEMINI_LIVE_ENGINE
import com.distronode.districtai.core.model.PERSONA_LANGUAGE_KEYED_ENGINE
import com.distronode.districtai.core.model.PersonaDefaults
import com.distronode.districtai.core.model.PersonaEngineOption
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaLanguageCatalog
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaVoiceCatalog
import com.distronode.districtai.core.model.PersonaVoiceGroup

/**
 * A two-engine catalogue shared by the persona ViewModel tests.
 *
 * ⚠️ THE SAME SHAPE `PersonaEngineDraftTest` PINS: Deepgram keyed by language (with `it-IT` and no
 * `hi-IN`), Gemini Live keyed by engine, and per-engine response lengths. A test that drives the
 * ViewModel's engine edits needs a catalogue the cascades can act on; the repository's default
 * empty catalogue would make every one of them a no-op.
 */
internal val TEST_PERSONA_OPTIONS = PersonaOptionsResponse(
    success = true,
    region = "us",
    engines = listOf(
        PersonaEngineOption(
            id = PERSONA_LANGUAGE_KEYED_ENGINE,
            label = "Deepgram Pipeline, US",
            inRegion = true,
            responseLengths = listOf(
                PersonaLabelledValue("concise", "Concise"),
                PersonaLabelledValue("balanced", "Balanced"),
            ),
        ),
        PersonaEngineOption(
            id = PERSONA_GEMINI_LIVE_ENGINE,
            label = "Gemini 2.5 Live, US",
            inRegion = true,
            responseLengths = listOf(PersonaLabelledValue("concise", "Concise")),
        ),
    ),
    languages = PersonaLanguageCatalog(
        deepgram = listOf(
            PersonaLabelledValue("en-US", "English (US)"),
            PersonaLabelledValue("it-IT", "Italian"),
        ),
        general = listOf(
            PersonaLabelledValue("en-US", "English (US)"),
            PersonaLabelledValue("hi-IN", "Hindi"),
        ),
    ),
    voices = listOf(
        PersonaVoiceCatalog(
            engine = PERSONA_LANGUAGE_KEYED_ENGINE,
            language = "en-US",
            groups = listOf(
                PersonaVoiceGroup(
                    label = "Voices",
                    options = listOf(PersonaLabelledValue("aura-2-asteria-en", "Asteria")),
                ),
            ),
        ),
    ),
    voiceStyles = listOf(PersonaLabelledValue("warm", "Warm")),
    defaults = PersonaDefaults(
        voiceByEngine = mapOf(
            PERSONA_LANGUAGE_KEYED_ENGINE to "aura-2-asteria-en",
            PERSONA_GEMINI_LIVE_ENGINE to "Puck",
        ),
        voiceByDeepgramLanguage = mapOf(
            "en-US" to "aura-2-asteria-en",
            "it-IT" to "aura-2-alba-it",
        ),
        responseLength = "concise",
        temperature = 0.7,
    ),
)
