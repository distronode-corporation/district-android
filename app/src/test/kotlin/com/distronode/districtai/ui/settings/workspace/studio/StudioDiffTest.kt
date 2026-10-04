package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.VoiceStudioFields
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a save sends, whether it landed, and how far an edit is from its recipe.
 *
 * ⛔ ONLY CHANGED KEYS ARE SENT, so a teammate's save of a key this screen never touched survives.
 */
class StudioDiffTest {

    private val studio = VoiceStudioFixture.studio
    private val saved = studio.current.fields
    private val savedMix = (StudioEngine.of(studio.current.chain) as StudioEngine.Chained).mix

    private fun fields(
        modelId: String = saved.modelId,
        voice: String = saved.voice,
    ) = saved.copy(modelId = modelId, voice = voice)

    @Test
    fun `nothing changed sends nothing`() {
        assertTrue(StudioDiff.changedKeys(saved, saved).isEmpty())
    }

    @Test
    fun `a new voice on the same preset sends the voice alone`() {
        assertEquals(setOf(StudioKey.VOICE), StudioDiff.changedKeys(saved, fields(voice = "aura-2-luna-en")))
    }

    @Test
    fun `a move to a custom chain sends the engine id and the mix together`() {
        val custom = saved.copy(modelId = StudioSave.CUSTOM_PIPELINE, engineMix = savedMix, bilingual = false)

        assertEquals(setOf(StudioKey.MODEL_ID, StudioKey.ENGINE_MIX), StudioDiff.changedKeys(saved, custom))
    }

    @Test
    fun `an edit of the mix alone still carries the engine id`() {
        val before = saved.copy(modelId = StudioSave.CUSTOM_PIPELINE, engineMix = savedMix)
        val after = before.copy(engineMix = savedMix.copy(llm = savedMix.llm.copy(temperature = 1.2)))

        assertEquals(setOf(StudioKey.MODEL_ID, StudioKey.ENGINE_MIX), StudioDiff.changedKeys(before, after))
    }

    @Test
    fun `a move from a custom chain to a preset sends the id and no mix`() {
        val before = saved.copy(modelId = StudioSave.CUSTOM_PIPELINE, engineMix = savedMix)

        assertEquals(setOf(StudioKey.MODEL_ID), StudioDiff.changedKeys(before, saved))
    }

    @Test
    fun `an absent bilingual flag and false are the same, so neither is sent`() {
        assertTrue(StudioDiff.changedKeys(saved.copy(bilingual = null), saved.copy(bilingual = false)).isEmpty())
        assertEquals(
            setOf(StudioKey.BILINGUAL),
            StudioDiff.changedKeys(saved.copy(bilingual = null), saved.copy(bilingual = true)),
        )
    }

    @Test
    fun `the request carries exactly the keys asked for`() {
        val realtime = VoiceStudioFields(
            modelId = StudioSave.GEMINI_LIVE_25,
            voice = "Kore",
            engineMix = savedMix,
            preemptiveTts = true,
            temperature = 0.4,
            bilingual = true,
            voiceStyle = "en-US-News-K",
        )

        assertEquals(
            PersonaPatchRequest(
                workspaceId = "ws-1",
                modelId = StudioSave.GEMINI_LIVE_25,
                voice = "Kore",
                engineMix = savedMix,
                preemptiveTts = true,
                temperature = 0.4,
                bilingual = true,
                voiceStyle = "en-US-News-K",
            ),
            StudioDiff.request("ws-1", realtime, StudioKey.entries.toSet()),
        )
        assertEquals(
            PersonaPatchRequest(workspaceId = "ws-1", voice = "Kore"),
            StudioDiff.request("ws-1", realtime, setOf(StudioKey.VOICE)),
        )
    }

    @Test
    fun `a re-read that holds what was sent landed, within a slider's float noise`() {
        val sent = saved.copy(temperature = 0.7)
        val reread = saved.copy(temperature = 0.7000001, bilingual = false)

        assertTrue(StudioDiff.landed(sent, setOf(StudioKey.TEMPERATURE, StudioKey.BILINGUAL), reread))
    }

    @Test
    fun `a re-read that coerced or dropped a key did not land`() {
        // ⛔ THE PATCH COERCES AN UNKNOWN ENGINE TO `deepgram-pipeline` WITH A 200.
        val sent = saved.copy(modelId = "cartesia-pipeline")

        assertFalse(StudioDiff.landed(sent, setOf(StudioKey.MODEL_ID), saved))
        assertFalse(StudioDiff.landed(saved.copy(temperature = 0.2), setOf(StudioKey.TEMPERATURE), saved))
        assertFalse(
            StudioDiff.landed(
                saved.copy(temperature = 0.2),
                setOf(StudioKey.TEMPERATURE),
                saved.copy(temperature = 0.9)
            )
        )
    }

    @Test
    fun `changes are counted leaf by leaf, across kinds too`() {
        val chain = StudioEngine.Chained(savedMix)
        assertEquals(0, StudioDiff.countChanges(chain, chain))
        val twice = StudioEngine.Chained(
            savedMix.copy(tts = savedMix.tts.copy(voice = "aura-2-luna-en"), preemptiveTts = true),
        )
        assertEquals(2, StudioDiff.countChanges(chain, twice))
        // An absent-when-unset key that appears is one change.
        val tuned = StudioEngine.Chained(savedMix.copy(tts = savedMix.tts.copy(stability = 0.4)))
        assertEquals(1, StudioDiff.countChanges(chain, tuned))

        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Puck")
        assertEquals(1, StudioDiff.countChanges(live, live.copy(voice = "Kore")))
        assertTrue(StudioDiff.countChanges(chain, live) > 2)
    }
}
