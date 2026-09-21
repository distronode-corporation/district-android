package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a persona picker follows.
 *
 * ⛔ EVERY ONE OF THESE IS A CORRECTNESS RULE, NOT A LAYOUT ONE, WHICH IS WHY THEY ARE TESTED HERE
 * RATHER THAN THROUGH A SCREEN. `PATCH workspace/persona` COERCES instead of refusing: an
 * unrecognised engine becomes `deepgram-pipeline`, an unrecognised voice is stored and then
 * replaced by the agent at synthesis time, and both answer 200. So a picker that offers the wrong
 * list does not fail — it produces a persona nobody chose, with nothing reporting it.
 */
class PersonaOptionsLookupTest {

    private val options = PersonaOptionsResponse(
        success = true,
        region = "us",
        engines = listOf(
            PersonaEngineOption(
                id = PERSONA_GEMINI_LIVE_ENGINE,
                label = "Gemini 2.5 Live — US (processed in your region)",
                inRegion = true,
                responseLengths = listOf(PersonaLabelledValue("concise", "Concise")),
            ),
            PersonaEngineOption(
                id = PERSONA_LANGUAGE_KEYED_ENGINE,
                label = "Deepgram Pipeline — US (processed in your region)",
                inRegion = true,
                responseLengths = listOf(
                    PersonaLabelledValue("concise", "Concise"),
                    PersonaLabelledValue("balanced", "Balanced"),
                ),
            ),
            PersonaEngineOption(
                id = "elevenlabs-pipeline",
                label = "ElevenLabs Pipeline — EU (processed outside your region)",
                inRegion = false,
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
            PersonaVoiceCatalog(
                engine = PERSONA_LANGUAGE_KEYED_ENGINE,
                language = "it-IT",
                groups = listOf(
                    PersonaVoiceGroup(
                        label = "Voices",
                        options = listOf(PersonaLabelledValue("aura-2-alba-it", "Alba")),
                    ),
                ),
            ),
        ),
        voiceStyles = listOf(PersonaLabelledValue("warm", "Warm")),
        defaults = PersonaDefaults(
            voiceByEngine = mapOf(
                PERSONA_GEMINI_LIVE_ENGINE to "Puck",
                PERSONA_LANGUAGE_KEYED_ENGINE to "aura-2-asteria-en",
            ),
            voiceByDeepgramLanguage = mapOf(
                "en-US" to "aura-2-asteria-en",
                "it-IT" to "aura-2-alba-it",
            ),
            responseLength = "concise",
            temperature = 0.7,
        ),
    )

    @Test
    fun `an out-of-region engine is published but not selectable`() {
        // ⛔ THE PICKER SHOWS EVERY ENGINE AND DISABLES THE REST, so the label can say what each
        // one would mean. `selectableEngines` is what a SELECTION is validated against — offering
        // an out-of-region engine would make a residency decision on a settings screen, with a 200.
        assertEquals(3, options.engines.size)
        assertEquals(
            listOf(PERSONA_GEMINI_LIVE_ENGINE, PERSONA_LANGUAGE_KEYED_ENGINE),
            options.selectableEngines.map { it.id },
        )
    }

    @Test
    fun `the short language list is NOT a subset of the long one`() {
        // ⛔ THE ASYMMETRY IS THE CONTRACT. Deepgram has it-IT and no hi-IN; the general list is
        // the other way round. One list for every engine would offer Hindi on Deepgram, whose
        // voice catalogue for it is empty, and the save would store a language it cannot speak.
        val deepgram = options.languagesForEngine(PERSONA_LANGUAGE_KEYED_ENGINE).map { it.value }
        val general = options.languagesForEngine(PERSONA_GEMINI_LIVE_ENGINE).map { it.value }

        assertTrue("it-IT" in deepgram)
        assertFalse("it-IT" in general)
        assertTrue("hi-IN" in general)
        assertFalse("hi-IN" in deepgram)
    }

    @Test
    fun `an engine this build has never heard of gets the general list`() {
        // ⚠️ WHAT THE SERVER'S OWN TERNARY DOES. Only Deepgram is language-keyed; anything else,
        // including a future engine, reads the general list rather than nothing.
        assertEquals(
            options.languages.general,
            options.languagesForEngine("some-engine-shipped-next-quarter"),
        )
    }

    @Test
    fun `a pair with no catalogue entry is an empty list, not a failure`() {
        // ⚠️ A REAL ANSWER. A stored persona can name a language its engine does not publish — the
        // save route never refused one — and the honest rendering is an empty picker with the
        // existing value still shown, never a silent substitution.
        assertTrue(options.voiceGroups(PERSONA_LANGUAGE_KEYED_ENGINE, "hi-IN").isEmpty())
        assertTrue(options.voiceGroups(null, "en-US").isEmpty())
        assertTrue(options.voiceGroups(PERSONA_LANGUAGE_KEYED_ENGINE, null).isEmpty())
    }

    @Test
    fun `a voice the catalogue has dropped reports as absent without being corrected`() {
        assertTrue(
            options.voiceExists("aura-2-asteria-en", PERSONA_LANGUAGE_KEYED_ENGINE, "en-US"),
        )
        // ⛔ THE QUESTION A FORM ASKS BEFORE DECIDING A STORED VALUE IS STALE. The answer must not
        // drive a correction: an id the catalogue dropped is still what the workspace speaks in.
        assertFalse(options.voiceExists("aura-2-retired-en", PERSONA_LANGUAGE_KEYED_ENGINE, "en-US"))
        assertFalse(options.voiceExists(null, PERSONA_LANGUAGE_KEYED_ENGINE, "en-US"))
    }

    @Test
    fun `the per-language map wins for Deepgram and is ignored for every other engine`() {
        // ⛔ THE ONE ORDERING RULE. A Deepgram voice id encodes its own language
        // (`aura-2-asteria-en` cannot speak Italian) and the save route would store the mismatch
        // happily, so switching language has to move the voice with it.
        assertEquals(
            "aura-2-alba-it",
            options.defaultVoice(PERSONA_LANGUAGE_KEYED_ENGINE, "it-IT"),
        )
        // ⚠️ Gemini keeps one starting voice per engine; its language picker does not touch it.
        assertEquals("Puck", options.defaultVoice(PERSONA_GEMINI_LIVE_ENGINE, "it-IT"))
    }

    @Test
    fun `no published default is null, which is not an error and not an empty string`() {
        // ⚠️ A CALLER LEAVES THE FIELD AS IT WAS. Clearing it would be a save that stores `""` and
        // makes the agent fall back to a voice nobody chose.
        assertNull(options.defaultVoice("elevenlabs-pipeline", "en-US"))
        assertNull(options.defaultVoice(null, "en-US"))
    }

    @Test
    fun `answer lengths are per engine, and empty for one the catalogue does not carry`() {
        assertEquals(2, options.responseLengthsForEngine(PERSONA_LANGUAGE_KEYED_ENGINE).size)
        assertEquals(1, options.responseLengthsForEngine(PERSONA_GEMINI_LIVE_ENGINE).size)
        // ⚠️ HIDES THE PICKER rather than showing options that would be saved under a modelId the
        // route is about to coerce.
        assertTrue(options.responseLengthsForEngine("unknown-engine").isEmpty())
    }

    @Test
    fun `the two capability flags are mutually exclusive, and a null engine is a pipeline`() {
        val gemini = PersonaEngineCapabilities.forEngine(PERSONA_GEMINI_LIVE_ENGINE)
        assertTrue(gemini.showsVoiceStyle)
        assertFalse(gemini.showsPreemptiveTts)
        assertFalse(gemini.languageSelectsVoice)

        val deepgram = PersonaEngineCapabilities.forEngine(PERSONA_LANGUAGE_KEYED_ENGINE)
        assertFalse(deepgram.showsVoiceStyle)
        assertTrue(deepgram.showsPreemptiveTts)
        assertTrue(deepgram.languageSelectsVoice)

        // ⚠️ TRUE FOR A null ENGINE, deliberately: an unloaded or unrecognised engine is treated
        // as a chained pipeline, which is what the web's `!==` comparison does.
        assertTrue(PersonaEngineCapabilities.forEngine(null).showsPreemptiveTts)
        assertFalse(PersonaEngineCapabilities.forEngine(null).showsVoiceStyle)
    }
}
