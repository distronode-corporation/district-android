package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Studio's transitions, each a pure function of the state it starts from. */
class VoiceStudioUiStateTest {

    private val studio = VoiceStudioFixture.studio
    private val ready = VoiceStudioUiState.Ready.of(studio)

    @Test
    fun `a realtime recipe opens the realtime leg, and a chain recipe goes back to the ear`() {
        val live = ready.withLeg(StudioLegEdits.MOUTH).withRecipe("realtime")
        assertEquals(StudioLegEdits.REALTIME, live.leg)
        assertTrue(live.held.engine is StudioEngine.Realtime)
        assertTrue(live.dirty)

        val chain = live.withRecipe("fastest")
        assertEquals(StudioLegEdits.EAR, chain.leg)
        assertFalse(chain.dirty)
    }

    @Test
    fun `a chain edit keeps the leg the editor is on`() {
        val brain = ready.withLeg(StudioLegEdits.BRAIN).withMix { it.copy(preemptiveTts = true) }

        assertEquals(StudioLegEdits.BRAIN, brain.leg)
        assertEquals(setOf(StudioKey.PREEMPTIVE_TTS), brain.pending)
    }

    @Test
    fun `a chain edit on a realtime engine changes nothing`() {
        val live = ready.withRecipe("realtime")

        assertSame(live, live.withMix { it.copy(preemptiveTts = true) })
    }

    @Test
    fun `a recipe this tier does not have changes nothing`() {
        assertSame(ready, ready.withRecipe("no-such-recipe"))
    }

    @Test
    fun `a tier switch re-applies the chosen recipe, and your chain is no tier's`() {
        val latest = ready.withTier(StudioRecipes.LATEST)
        val fastestLatest = studio.recipes.single { it.id == "fastest" && it.tier == StudioRecipes.LATEST }
        assertEquals(StudioEngine.of(fastestLatest.chain), latest.held.engine)

        val custom = ready.withRecipe(StudioRecipes.CUSTOM).withTier(StudioRecipes.LATEST)
        assertEquals(StudioRecipes.LATEST, custom.tier)
        assertEquals(ready.held.engine, custom.held.engine)
    }

    @Test
    fun `reset goes back to the engine the recipe applied, and the count says how far an edit went`() {
        val edited = ready.withMix { it.copy(preemptiveTts = true, tts = it.tts.copy(voice = "aura-2-luna-en")) }
        assertEquals(2, edited.changes)
        assertTrue(edited.canSave)

        val reset = edited.withReset()
        assertEquals(ready.baseEngine, reset.held.engine)
        assertEquals(0, reset.changes)
        assertFalse(reset.canSave)
    }

    @Test
    fun `a save in flight cannot be saved again`() {
        val saving = ready.withRecipe("realtime").copy(save = StudioSaveState.Saving)

        assertTrue(saving.dirty)
        assertFalse(saving.canSave)
    }

    @Test
    fun `a saved realtime engine opens on its one block`() {
        val live = studio.recipes.single { it.id == "realtime" && it.tier == StudioRecipes.STABLE }
        val savedLive = studio.copy(current = studio.current.copy(chain = live.chain, fields = live.save))

        val opened = VoiceStudioUiState.Ready.of(savedLive, StudioSaveState.Saved)

        assertEquals(StudioLegEdits.REALTIME, opened.leg)
        assertEquals(StudioSaveState.Saved, opened.save)
        assertEquals(6, opened.tiles.size)
    }
}
