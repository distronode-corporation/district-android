package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Tiles, the engine a tile applies, and the voices a mouth speaks. */
class StudioRecipesTest {

    private val studio = VoiceStudioFixture.studio
    private val saved = StudioEngine.of(studio.current.chain)

    private fun recipe(id: String, tier: String = StudioRecipes.STABLE, from: VoiceStudioResponse = studio) =
        from.recipes.single { it.id == id && it.tier == tier }

    @Test
    fun `a tier's tiles follow the server's tile order`() {
        assertEquals(studio.recipeIds, StudioRecipes.tiles(studio, StudioRecipes.STABLE).map { it.id })
        assertEquals(
            listOf("in-region", "fastest", "natural", "bilingual", "realtime", "custom"),
            StudioRecipes.tiles(studio, StudioRecipes.LATEST).map { it.id },
        )
    }

    @Test
    fun `a tier missing a recipe simply has no tile for it`() {
        val fewer = studio.copy(recipes = studio.recipes.filterNot { it.id == "bilingual" })

        assertEquals(5, StudioRecipes.tiles(fewer, StudioRecipes.STABLE).size)
    }

    @Test
    fun `your chain is the saved chain itself, voice included`() {
        val held = StudioRecipes.withVoice(saved, "aura-2-luna-en")

        assertSame(saved, StudioRecipes.applied(recipe("custom"), saved, held, studio))
    }

    @Test
    fun `your chain from a saved realtime engine is the region's starting chain`() {
        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Puck")

        val applied = StudioRecipes.applied(recipe("custom"), live, live, studio)

        assertEquals(StudioEngine.of(recipe("custom").chain), applied)
    }

    @Test
    fun `a recipe keeps the voice the studio holds when its mouth has it`() {
        val held = StudioRecipes.withVoice(saved, "aura-2-luna-en")

        val applied = StudioRecipes.applied(recipe("in-region"), saved, held, studio)

        assertEquals("aura-2-luna-en", applied.voice)
        assertEquals("nova-3-general", (applied as StudioEngine.Chained).mix.stt.model)
    }

    @Test
    fun `a recipe whose mouth lacks the held voice uses the recipe's own`() {
        val applied = StudioRecipes.applied(recipe("natural"), saved, saved, studio)

        assertEquals(recipe("natural").chain.voice, applied.voice)
    }

    @Test
    fun `a realtime recipe keeps a Gemini voice the studio already holds`() {
        val held = StudioEngine.Realtime("gemini-3.8-live", "Kore")

        val applied = StudioRecipes.applied(recipe("realtime"), saved, held, studio)

        assertEquals(StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Kore"), applied)
    }

    @Test
    fun `voices come from the held mouth or the held realtime model`() {
        assertEquals("aura-2-asteria-en", StudioRecipes.voiceValues(StudioRecipes.voicesFor(saved, studio)).first())
        val live = StudioRecipes.voicesFor(StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Puck"), studio)
        assertEquals("Puck", StudioRecipes.voiceValues(live).first())
        assertNull(StudioRecipes.voicesFor(StudioEngine.Realtime("unknown", "Puck"), studio))
        assertEquals(emptyList<String>(), StudioRecipes.voiceValues(null))
    }

    @Test
    fun `the flattened voice list keeps each group's heading`() {
        val voices = StudioRecipes.voices(saved, studio)

        assertEquals("English (Feminine)" to "aura-2-asteria-en", voices.first().first to voices.first().second.value)
        assertEquals(setOf("English (Feminine)", "English (Masculine)", "Spanish"), voices.map { it.first }.toSet())
    }

    @Test
    fun `the studio opens on what the server says is saved`() {
        val state = StudioRecipes.initialState(studio)

        assertEquals(saved, state.engine)
        assertEquals(studio.current.temperature, state.realtimeTemperature, 0.0)
        assertEquals(studio.current.bilingual, state.bilingual)
        assertNull(state.voiceStyle)
    }
}
