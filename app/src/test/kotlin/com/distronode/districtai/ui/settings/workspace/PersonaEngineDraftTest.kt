package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PERSONA_GEMINI_LIVE_ENGINE
import com.distronode.districtai.core.model.PERSONA_LANGUAGE_KEYED_ENGINE
import com.distronode.districtai.core.model.PersonaDefaults
import com.distronode.districtai.core.model.PersonaEngineOption
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaLanguageCatalog
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaVoiceCatalog
import com.distronode.districtai.core.model.PersonaVoiceGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine half of the persona form: the cascades, the dirty tracking, and exactly what reaches
 * the wire.
 *
 * ⛔ EVERY ASSERTION HERE IS ABOUT A SAVE THAT WOULD SUCCEED AND BE WRONG. `PATCH
 * workspace/persona` COERCES rather than refusing — an unrecognised engine becomes
 * `deepgram-pipeline`, an unrecognised voice is stored and then replaced by the agent at synthesis
 * time, and a `responseLength` with no `modelId` is silently discarded — all with a 200. None of
 * those is observable after the fact, which is why they are pinned before it.
 */
class PersonaEngineDraftTest {

    private val options = PersonaOptionsResponse(
        success = true,
        region = "us",
        engines = listOf(
            PersonaEngineOption(
                id = PERSONA_LANGUAGE_KEYED_ENGINE,
                label = "Deepgram Pipeline — US",
                inRegion = true,
                responseLengths = listOf(
                    PersonaLabelledValue("concise", "Concise"),
                    PersonaLabelledValue("balanced", "Balanced"),
                ),
            ),
            PersonaEngineOption(
                id = PERSONA_GEMINI_LIVE_ENGINE,
                label = "Gemini 2.5 Live — US",
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

    private val stored = AiPersona(
        modelId = PERSONA_LANGUAGE_KEYED_ENGINE,
        language = "en-US",
        voice = "aura-2-asteria-en",
        responseLength = mapOf(PERSONA_LANGUAGE_KEYED_ENGINE to "balanced"),
        temperature = 0.4,
        preemptiveTts = true,
    )

    private fun draft(persona: AiPersona? = stored) = PersonaEngineDraft.hydrate(persona, options)

    @Test
    fun `an untouched draft sends nothing`() {
        // ⛔ THE ROUTE MERGES PER FIELD, so an omitted key is PRESERVED. A request carrying the
        // whole form would overwrite the avatar settings and the tuning parameters with whatever
        // this screen happened to hold.
        val write = draft().write

        assertTrue(draft().changes.isEmpty)
        assertNull(write.modelId)
        assertNull(write.voice)
        assertNull(write.responseLength)
        assertNull(write.temperature)
    }

    @Test
    fun `a workspace that has never chosen starts on the server's own defaults`() {
        // ⚠️ NOT ON A BLANK PICKER. A blank one saved as `""` is a cleared voice, and the agent
        // then falls back to one nobody chose.
        val fresh = draft(persona = null)

        assertEquals("", fresh.values.modelId)
        assertEquals("concise", fresh.values.responseLength)
        assertEquals(0.7, fresh.values.temperature, 0.0001)
        assertFalse(fresh.values.preemptiveTts)
    }

    @Test
    fun `the stored per-engine level is hydrated, not the global default`() {
        assertEquals("balanced", draft().values.responseLength)
    }

    @Test
    fun `switching to Gemini clears a language it does not publish`() {
        // ⛔ DEEPGRAM HAS it-IT AND GEMINI DOES NOT. The save route would store the mismatch
        // happily, and the agent would then speak a language the engine cannot.
        val italian = draft().selectLanguage("it-IT")
        assertEquals("it-IT", italian.values.language)

        val gemini = italian.selectEngine(PERSONA_GEMINI_LIVE_ENGINE)
        assertEquals("", gemini.values.language)
    }

    @Test
    fun `switching engine moves the voice to the new engine's default`() {
        // ⛔ A VOICE ID BELONGS TO ONE ENGINE'S REGISTRY. Carrying `aura-2-asteria-en` onto Gemini
        // would be stored verbatim and then silently replaced at synthesis time.
        val gemini = draft().selectEngine(PERSONA_GEMINI_LIVE_ENGINE)

        assertEquals("Puck", gemini.values.voice)
    }

    @Test
    fun `switching engine rehydrates the level AND moves its baseline with it`() {
        // ⛔ THE BASELINE MOVES, WHICH IS THE HALF THAT IS EASY TO MISS. The stored level is per
        // ENGINE, so treating the previous engine's level as the baseline would report a change
        // nobody made — and then WRITE it.
        val gemini = draft().selectEngine(PERSONA_GEMINI_LIVE_ENGINE)

        assertEquals("concise", gemini.values.responseLength)
        assertFalse("an untouched level is not a change", gemini.changes.responseLength)
    }

    @Test
    fun `switching language moves the voice for Deepgram only`() {
        // ⛔ A DEEPGRAM VOICE ID ENCODES ITS OWN LANGUAGE. `aura-2-asteria-en` cannot speak Italian.
        val italian = draft().selectLanguage("it-IT")
        assertEquals("aura-2-alba-it", italian.values.voice)

        // ⚠️ EVERY OTHER ENGINE KEEPS ONE VOICE PER ENGINE and its language picker must not touch
        // it — the opposite behaviour, from the same control.
        val gemini = draft().selectEngine(PERSONA_GEMINI_LIVE_ENGINE).selectLanguage("hi-IN")
        assertEquals("Puck", gemini.values.voice)
    }

    @Test
    fun `a changed level carries the engine id even when the engine did not change`() {
        // ⛔ `responseLength` WITHOUT `modelId` IS SILENTLY DISCARDED. The route writes the level
        // under `aiPersona.responseLength[modelId]` and refuses to guess at the stored engine, so
        // it writes nothing and answers 200.
        val write = draft().let { it.copy(values = it.values.copy(responseLength = "concise")) }.write

        assertEquals("concise", write.responseLength)
        assertEquals(PERSONA_LANGUAGE_KEYED_ENGINE, write.modelId)
    }

    @Test
    fun `only the changed fields reach the wire`() {
        val write = draft().let { it.copy(values = it.values.copy(voiceStyle = "warm")) }.write

        assertEquals("warm", write.voiceStyle)
        assertNull("an untouched voice is left alone", write.voice)
        assertNull("an untouched language is left alone", write.language)
        assertNull("an untouched level is left alone", write.responseLength)
    }

    @Test
    fun `a blank engine field is dropped rather than sent as a clear`() {
        // ⛔ `""` IS A DELIBERATE CLEAR ON THIS ROUTE, and clearing a voice makes the agent fall
        // back to one nobody chose. A form that has never had a value must not clear anything.
        val fresh = draft(persona = null)
        val touched = fresh.copy(values = fresh.values.copy(voiceStyle = "warm", voice = ""))

        assertNull("a never-set voice must not be sent as an empty clear", touched.write.voice)
        assertEquals("warm", touched.write.voiceStyle)
    }

    @Test
    fun `a float round trip is not reported as a temperature change`() {
        // ⚠️ THIS ONE IS A SLIDER. An exact comparison would report a change on every load for a
        // value nobody touched, and then write it.
        val jitter = draft().let { it.copy(values = it.values.copy(temperature = 0.4000001)) }

        assertFalse(jitter.changes.temperature)
        assertNull(jitter.write.temperature)
    }

    @Test
    fun `a stored voice the catalogue has dropped is reported and left alone`() {
        val retired = draft(persona = stored.copy(voice = "aura-2-retired-en"))

        assertTrue(retired.voiceIsOffCatalogue)
        assertEquals("aura-2-retired-en", retired.values.voice)
        assertNull("it must not be corrected into a save", retired.write.voice)
    }

    @Test
    fun `the preview form carries the whole form, not only what changed`() {
        // ⛔ THE OPPOSITE RULE FROM `write`. The preview persists nothing and merges against
        // nothing, so a form carrying only the changes would audition a persona that is neither
        // what is stored nor what is drafted.
        val form = draft().previewForm(name = "Ada", greeting = "", personality = "Warm")

        assertEquals("Ada", form.name)
        assertEquals(PERSONA_LANGUAGE_KEYED_ENGINE, form.modelId)
        assertEquals("aura-2-asteria-en", form.voice)
        assertEquals("en-US", form.language)
        assertEquals("balanced", form.responseLength)
        assertEquals(0.4, form.temperature!!, 0.0001)
        assertEquals(true, form.preemptiveTts)
        // ⚠️ An empty free-text box is left off the wire rather than sent as a clear: the preview
        // has no stored row to clear, and the agent's own fallback is the honest answer.
        assertNull(form.greeting)
    }

    @Test
    fun `a voice the catalogue publishes, or no voice at all, is not reported as off catalogue`() {
        assertFalse(draft().voiceIsOffCatalogue)
        val blank = draft().let { it.copy(values = it.values.copy(voice = "")) }
        assertFalse("an empty picker names nothing to be stale", blank.voiceIsOffCatalogue)
    }

    @Test
    fun `switching engine keeps a language the new engine also publishes`() {
        val gemini = draft().selectEngine(PERSONA_GEMINI_LIVE_ENGINE)

        assertEquals("en-US", gemini.values.language)
        assertFalse(gemini.changes.language)
    }

    @Test
    fun `switching engine with no language chosen leaves it unchosen`() {
        val fresh = draft(persona = null).selectEngine(PERSONA_GEMINI_LIVE_ENGINE)

        assertEquals("", fresh.values.language)
        assertEquals("Puck", fresh.values.voice)
    }

    @Test
    fun `an engine with no published default voice keeps the current one rather than clearing it`() {
        // ⚠️ NULL IS "NO DEFAULT FOR THIS COMBINATION", not an instruction to clear. Clearing would
        // save `""` and make the agent fall back to a voice nobody chose.
        val unknown = draft().selectEngine("some-future-engine")

        assertEquals("aura-2-asteria-en", unknown.values.voice)
        assertEquals("concise", unknown.values.responseLength)
    }

    @Test
    fun `returning to an engine rehydrates ITS stored level, not the default`() {
        val back = draft()
            .selectEngine(PERSONA_GEMINI_LIVE_ENGINE)
            .selectEngine(PERSONA_LANGUAGE_KEYED_ENGINE)

        assertEquals("balanced", back.values.responseLength)
        assertFalse(back.changes.responseLength)
    }

    @Test
    fun `a Deepgram language with no published voice keeps the current voice`() {
        val hindi = draft().selectLanguage("hi-IN")

        assertEquals("hi-IN", hindi.values.language)
        assertEquals("aura-2-asteria-en", hindi.values.voice)
    }

    @Test
    fun `each engine field reaches the wire on its own when it alone changed`() {
        val base = draft()
        fun write(values: PersonaEngineValues) = base.copy(values = values).write

        assertEquals("it-IT", write(base.values.copy(language = "it-IT")).language)
        assertEquals("aura-2-other-en", write(base.values.copy(voice = "aura-2-other-en")).voice)
        assertEquals(0.9, write(base.values.copy(temperature = 0.9)).temperature!!, 0.0001)
        assertEquals(false, write(base.values.copy(preemptiveTts = false)).preemptiveTts)
        val engine = write(base.values.copy(modelId = PERSONA_GEMINI_LIVE_ENGINE))
        assertEquals(PERSONA_GEMINI_LIVE_ENGINE, engine.modelId)
        assertNull("the level did not change, so it is not sent", engine.responseLength)
    }

    @Test
    fun `a level with no engine to file it under is not sent at all`() {
        // ⛔ THE ROUTE DISCARDS `responseLength` WITHOUT `modelId`, silently. With no engine
        // chosen there is nothing to carry, so the level stays off the wire rather than vanishing.
        val fresh = draft(persona = null)
        val write = fresh.copy(values = fresh.values.copy(responseLength = "balanced")).write

        assertTrue(fresh.copy(values = fresh.values.copy(responseLength = "balanced")).changes.responseLength)
        assertNull(write.responseLength)
        assertNull(write.modelId)
    }

    @Test
    fun `any single changed field makes the change set non-empty`() {
        listOf(
            PersonaEngineChanges(modelId = true),
            PersonaEngineChanges(language = true),
            PersonaEngineChanges(voice = true),
            PersonaEngineChanges(responseLength = true),
            PersonaEngineChanges(temperature = true),
            PersonaEngineChanges(voiceStyle = true),
            PersonaEngineChanges(preemptiveTts = true),
        ).forEach { assertFalse(it.toString(), it.isEmpty) }
        assertTrue(PersonaEngineChanges().isEmpty)
    }

    @Test
    fun `a stored persona with no voice starts on its engine's published default`() {
        val noVoice = draft(persona = AiPersona(modelId = PERSONA_LANGUAGE_KEYED_ENGINE, language = "it-IT"))

        assertEquals("aura-2-alba-it", noVoice.values.voice)
    }
}
