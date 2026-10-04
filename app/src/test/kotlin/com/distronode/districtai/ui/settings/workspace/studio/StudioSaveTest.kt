package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMixInterruption
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a Studio state saves as, held against the SERVER'S OWN answers in the fixture.
 *
 * ⛔ THE PARITY TESTS ARE THE POINT. `current.fields` and every recipe's `save` are what the web
 * Studio's `fieldsOf` computes on the server; this port must compute the same bodies from the same
 * states, or a phone and a browser would save the same choice differently.
 */
class StudioSaveTest {

    private val studio = VoiceStudioFixture.studio
    private val presets = studio.catalog.presets
    private val savedMix = (StudioEngine.of(studio.current.chain) as StudioEngine.Chained).mix

    @Test
    fun `the saved state saves as exactly the server's current fields`() {
        val state = StudioRecipes.initialState(studio)

        assertEquals(studio.current.fields, StudioSave.fieldsOf(state, presets, studio.language))
    }

    @Test
    fun `every recipe's engine saves as exactly the body the server computed for it`() {
        studio.recipes.forEach { recipe ->
            val state = StudioRecipes.initialState(studio).copy(
                engine = StudioEngine.of(recipe.chain),
                bilingual = recipe.bilingual,
            )

            assertEquals(
                "${recipe.tier} ${recipe.id}",
                recipe.save,
                StudioSave.fieldsOf(state, presets, studio.language),
            )
        }
    }

    @Test
    fun `a chain equal to a preset is that preset, whatever its voice and speak-sooner`() {
        val voiced = savedMix.copy(tts = savedMix.tts.copy(voice = "aura-2-luna-en"), preemptiveTts = true)

        assertEquals("deepgram-pipeline", StudioSave.canonicalModelId(voiced, presets))
    }

    @Test
    fun `any studio tuning makes a chain custom, even one otherwise equal to a preset`() {
        val tunings = listOf(
            savedMix.copy(stt = savedMix.stt.copy(keyterms = listOf("Distronode"))),
            savedMix.copy(tts = savedMix.tts.copy(stability = 0.4)),
            savedMix.copy(tts = savedMix.tts.copy(expressivity = 0.1)),
            savedMix.copy(turn = savedMix.turn.copy(mode = "fixed")),
            savedMix.copy(turn = savedMix.turn.copy(eagerEotThreshold = 0.5)),
            savedMix.copy(turn = savedMix.turn.copy(eotTimeoutMs = 5000.0)),
            savedMix.copy(turn = savedMix.turn.copy(interruption = EngineMixInterruption(0.5, null, null, null))),
        )

        tunings.forEach { assertEquals(StudioSave.CUSTOM_PIPELINE, StudioSave.canonicalModelId(it, presets)) }
    }

    @Test
    fun `a changed core field leaves every preset behind`() {
        // ⛔ EACH OF THE FOUR LEGS TAKES PART IN THE EQUALITY.
        listOf(
            savedMix.copy(stt = savedMix.stt.copy(location = "us")),
            savedMix.copy(llm = savedMix.llm.copy(temperature = 1.2)),
            savedMix.copy(tts = savedMix.tts.copy(speed = 1.1)),
            savedMix.copy(turn = savedMix.turn.copy(minDelay = 0.4)),
        ).forEach { assertEquals(StudioSave.CUSTOM_PIPELINE, StudioSave.canonicalModelId(it, presets)) }
    }

    @Test
    fun `a bilingual chain is the custom engine, carrying its mix and the flag`() {
        val state = StudioRecipes.initialState(studio).copy(bilingual = true)

        val fields = StudioSave.fieldsOf(state, presets, "en-US")

        assertEquals(StudioSave.CUSTOM_PIPELINE, fields.modelId)
        assertEquals(savedMix, fields.engineMix)
        assertEquals(true, fields.bilingual)
    }

    @Test
    fun `bilingual on a language with no counterpart is not a pair, and the preset stands`() {
        val state = StudioRecipes.initialState(studio).copy(bilingual = true)

        val fields = StudioSave.fieldsOf(state, presets, "es-ES")

        assertEquals("deepgram-pipeline", fields.modelId)
        assertNull(fields.bilingual)
    }

    @Test
    fun `the voice style travels only on Gemini 2_5 Live`() {
        val live = StudioState(StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Kore"), 0.4, false, "en-US-News-K")
        val fields = StudioSave.fieldsOf(live, presets, "en-US")
        assertEquals("en-US-News-K", fields.voiceStyle)
        assertEquals(0.4, fields.temperature!!, 0.0)
        assertNull("2.5 Live does not carry the bilingual flag", fields.bilingual)

        val next = StudioSave.fieldsOf(
            live.copy(engine = StudioEngine.Realtime("gemini-3.8-live", "Kore")),
            presets,
            "fr-CA",
        )
        assertNull(next.voiceStyle)
        assertEquals(false, next.bilingual)
    }

    @Test
    fun `the bilingual pair is English and French, by the language's prefix`() {
        assertTrue(StudioSave.bilingualPair("en-GB"))
        assertTrue(StudioSave.bilingualPair("FR"))
        assertFalse(StudioSave.bilingualPair("es-ES"))
        assertTrue(StudioSave.bilingualAvailable(StudioSave.CUSTOM_PIPELINE, "fr-CA"))
        assertFalse(StudioSave.bilingualAvailable("deepgram-pipeline", "fr-CA"))
    }
}
