package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMixInterruption
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which tuning controls a leg shows, over what range, and every path they read and write.
 *
 * ⛔ A CONTROL APPEARS ONLY FOR THE MODEL THAT HONOURS IT, and a value on one that does not is
 * dropped, because the server drops it silently and the re-read would then report a failed save.
 */
class StudioTuningTest {

    private val studio = VoiceStudioFixture.studio
    private val mix = (StudioEngine.of(studio.current.chain) as StudioEngine.Chained).mix
    private val chain = StudioEngine.Chained(mix)

    private fun key(path: String) = studio.advanced.single { it.key == path }

    @Test
    fun `every key the server publishes is one this client can draw`() {
        // ⛔ A NEW SERVER KEY WOULD SILENTLY NOT BE DRAWN. This makes it a named failure instead.
        assertEquals(studio.advanced.map { it.key }.toSet(), StudioTuningValues.KNOWN)
    }

    @Test
    fun `a Flux ear shows the Flux turn-taking keys, and another ear does not`() {
        val flux = StudioTuning.keysFor(StudioLegEdits.TURN, chain, studio).map { it.key }
        assertTrue("engineMix.turn.eotThreshold" in flux)
        assertTrue("engineMix.turn.eagerEotThreshold" in flux)
        assertTrue("engineMix.preemptiveTts" in flux)

        val nova = StudioEngine.Chained(mix.copy(stt = mix.stt.copy(model = "nova-3-general")))
        val keys = StudioTuning.keysFor(StudioLegEdits.TURN, nova, studio).map { it.key }
        assertFalse("engineMix.turn.eotThreshold" in keys)
        assertTrue("engineMix.turn.minDelay" in keys)
    }

    @Test
    fun `a key the client cannot map is not drawn`() {
        val extra = key("engineMix.turn.minDelay").copy(key = "engineMix.turn.somethingNew")
        val widened = studio.copy(advanced = studio.advanced + extra)

        assertFalse(
            "engineMix.turn.somethingNew" in StudioTuning.keysFor(StudioLegEdits.TURN, chain, widened).map { it.key },
        )
    }

    @Test
    fun `the realtime keys follow the realtime model`() {
        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Puck")
        assertEquals(
            listOf("temperature", "voiceStyle"),
            StudioTuning.keysFor(StudioLegEdits.REALTIME, live, studio).map { it.key },
        )
        val next = StudioEngine.Realtime("gemini-3.8-live", "Puck")
        assertEquals(listOf("temperature"), StudioTuning.keysFor(StudioLegEdits.REALTIME, next, studio).map { it.key })
    }

    @Test
    fun `a range is the model's own where it has one, else the key's`() {
        val eot = StudioTuning.range(key("engineMix.turn.eotThreshold"), chain)!!
        assertEquals(TuningRange(0.5, 0.9, 0.05, 0.7, "Use the default (0.70)"), eot)

        val minDelay = StudioTuning.range(key("engineMix.turn.minDelay"), chain)!!
        assertEquals(TuningRange(0.0, 1.0, 0.05, 0.3, "Use the default (0.30)"), minDelay)

        val brain = StudioTuning.range(key("engineMix.llm.temperature"), chain)!!
        assertEquals(TuningRange(0.0, 2.0, 0.05, 1.0, "Use the default (the model's own)"), brain)

        val speed = StudioTuning.range(key("engineMix.tts.speed"), chain)!!
        assertEquals("Use the default (1.00)", speed.useDefaultLabel)
        assertEquals(0.7, speed.min, 0.0)
    }

    @Test
    fun `a slider with no range from the model or the key is not drawn`() {
        assertNull(StudioTuning.range(key("engineMix.tts.stability"), chain))
        val noMax = key("engineMix.turn.minDelay").copy(max = null)
        assertNull(StudioTuning.range(noMax, chain))
    }

    @Test
    fun `a range with no start, own default or key default starts at its minimum`() {
        val bare = key("engineMix.turn.minDelay").copy(start = null, default = null)
        assertEquals(0.0, StudioTuning.range(bare, chain)!!.start, 0.0)
        val ownDefault = key("engineMix.turn.eotThreshold").copy(start = null)
        assertEquals(0.7, StudioTuning.range(ownDefault, chain)!!.start, 0.0)
        val keyDefault = key("engineMix.turn.minDelay").copy(start = null)
        assertEquals(0.3, StudioTuning.range(keyDefault, chain)!!.start, 0.0)
    }

    @Test
    fun `a slider's float lands on its step and inside its range`() {
        val range = TuningRange(0.0, 1.0, 0.05, 0.3, null)
        assertEquals(0.8, StudioTuning.snap(0.800000011920929, range), 0.0)
        assertEquals(1.0, StudioTuning.snap(1.2, range), 0.0)
        val free = TuningRange(0.0, 1.0, null, 0.3, null)
        assertEquals(0.1234, StudioTuning.snap(0.12341, free), 0.0)
    }

    @Test
    fun `every number path reads and writes its own field`() {
        StudioTuningValues.KNOWN.mapNotNull { path -> StudioTuningValues.numberSlot(path)?.let { path to it } }
            .forEach { (path, slot) ->
                val written = slot.set(mix, 0.55)
                assertEquals(path, 0.55, slot.get(written)!!, 0.0)
                assertNull(path, slot.get(slot.set(written, null)))
            }
    }

    @Test
    fun `an interruption with every field unset is no object at all`() {
        val slot = StudioTuningValues.numberSlot("engineMix.turn.interruption.minWords")!!
        val set = slot.set(mix, 2.0)
        assertEquals(EngineMixInterruption(null, 2.0, null, null), set.turn.interruption)
        assertNull(slot.set(set, null).turn.interruption)
    }

    @Test
    fun `every choice path reads and writes its own field`() {
        val mode = StudioTuningValues.choiceSlot("engineMix.turn.mode")!!
        assertEquals("auto", mode.get(mix))
        assertEquals("fixed", mode.get(mode.set(mix, "fixed")))
        assertNull("automatic is the absent key", mode.set(mode.set(mix, "fixed"), "auto").turn.mode)

        val resume = StudioTuningValues.choiceSlot("engineMix.turn.interruption.resume")!!
        assertEquals("default", resume.get(mix))
        assertEquals(true, resume.set(mix, "on").turn.interruption?.resume)
        assertEquals("on", resume.get(resume.set(mix, "on")))
        assertEquals("off", resume.get(resume.set(mix, "off")))
        assertNull(resume.set(resume.set(mix, "off"), "default").turn.interruption)

        val thinking = StudioTuningValues.choiceSlot(StudioTuningValues.THINKING)!!
        assertEquals("dynamic", thinking.get(thinking.set(mix, "dynamic")))
        assertNull(StudioTuningValues.choiceSlot("engineMix.turn.minDelay"))
    }

    @Test
    fun `conform drops what the held models do not honour and clamps what they do`() {
        val messy = mix.copy(
            stt = mix.stt.copy(model = "nova-3-general"),
            turn = mix.turn.copy(eotThreshold = 0.8, minDelay = 0.9, maxDelay = 0.6),
            tts = mix.tts.copy(speed = 3.0, stability = 0.5),
        )

        val clean = StudioTuning.conform(messy, studio)

        assertNull("a Flux threshold on Nova-3", clean.turn.eotThreshold)
        assertNull("stability on a Deepgram mouth", clean.tts.stability)
        assertEquals("clamped into Aura-2's range", 1.5, clean.tts.speed!!, 0.0)
        assertEquals("the longest wait is never below the shortest", 0.9, clean.turn.maxDelay!!, 0.0)
        assertEquals(mix, StudioTuning.conform(mix, studio))
    }

    @Test
    fun `a value on a key with no range at all is kept as sent`() {
        val noRange = studio.copy(
            advanced = studio.advanced.map {
                if (it.key == "engineMix.turn.minDelay") it.copy(min = null, max = null) else it
            },
        )
        val set = mix.copy(turn = mix.turn.copy(minDelay = 9.0))

        assertEquals(9.0, StudioTuning.conform(set, noRange).turn.minDelay!!, 0.0)
    }

    @Test
    fun `key terms are trimmed, cut, de-duplicated and capped as typed`() {
        val terms = key(StudioTuningValues.KEYTERMS)
        val long = "x".repeat(150)

        assertEquals(
            listOf("Distronode", "x".repeat(100)),
            StudioTuning.parseKeyterms("  Distronode \n\nDistronode\n$long\n", terms),
        )
        assertEquals(50, StudioTuning.parseKeyterms((1..60).joinToString("\n") { "t$it" }, terms).size)
        val unlimited = terms.copy(maxCount = null, maxLength = null)
        assertEquals(60, StudioTuning.parseKeyterms((1..60).joinToString("\n") { "t$it" }, unlimited).size)
        assertEquals(listOf(long), StudioTuning.parseKeyterms(long, unlimited))
    }

    @Test
    fun `honour follows the leg's own model`() {
        val brain = key("engineMix.llm.temperature")
        assertTrue(StudioTuning.honoured(brain, chain))
        assertFalse(StudioTuning.honoured(brain, StudioEngine.Chained(mix.copy(llm = mix.llm.copy(model = "x")))))
        assertTrue(StudioTuning.honoured(key("engineMix.turn.minDelay"), chain))
        assertNotNull(StudioTuning.range(key("temperature"), StudioEngine.Realtime("gemini-3.8-live", "Puck")))
    }
}
