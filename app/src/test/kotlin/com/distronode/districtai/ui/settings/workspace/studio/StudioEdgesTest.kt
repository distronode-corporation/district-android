package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.VoiceStudioLatency
import com.distronode.districtai.core.model.VoiceStudioResidency
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edges of the Studio's rules that the fixture's own shape does not reach: a model that is not
 * offered, a Preview model in a chain picker, a voice list the server did not send, a stored
 * realtime engine with no id.
 */
class StudioEdgesTest {

    private val studio = VoiceStudioFixture.studio
    private val mix = (StudioEngine.of(studio.current.chain) as StudioEngine.Chained).mix

    @Test
    fun `a brain that is not offered is listed only while it is held, and a Preview one carries its note`() {
        val narrowed = studio.copy(
            catalog = studio.catalog.copy(
                llm = studio.catalog.llm.map {
                    when (it.model) {
                        "gemini-3.5-flash" -> it.copy(offered = false)
                        "gemini-3.8-flash" -> it.copy(channel = "preview")
                        else -> it
                    }
                },
            ),
        )

        val brains = StudioPickers.brainModels(mix, narrowed)
        assertFalse("gemini-3.5-flash" in brains.map { it.value })
        assertEquals(narrowed.labels.previewNote, brains.single { it.value == "gemini-3.8-flash" }.note)
        val held = mix.copy(llm = mix.llm.copy(model = "gemini-3.5-flash"))
        assertTrue("gemini-3.5-flash" in StudioPickers.brainModels(held, narrowed).map { it.value })
    }

    @Test
    fun `an ear or mouth the catalogue does not list has no locations`() {
        val ear = mix.copy(stt = mix.stt.copy(model = "x"))
        val mouth = mix.copy(tts = mix.tts.copy(model = "x"))
        assertTrue(StudioPickers.locations(StudioLegEdits.EAR, ear, studio).isEmpty())
        assertTrue(StudioPickers.locations(StudioLegEdits.MOUTH, mouth, studio).isEmpty())
    }

    @Test
    fun `a realtime model that is not offered is not listed`() {
        val withheld = studio.copy(
            catalog = studio.catalog.copy(
                realtime = studio.catalog.realtime.map {
                    if (it.model == "gemini-3.8-live") it.copy(offered = false) else it
                },
            ),
        )
        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Puck")

        assertEquals(listOf(StudioSave.GEMINI_LIVE_25), StudioPickers.realtimeModels(live, withheld).map { it.value })
    }

    @Test
    fun `bilingual narrows the mouths too, and the vendor edits follow it`() {
        // No Deepgram mouth is proven on both languages; the held one stays listed.
        val pair = StudioPickers.voiceModels(mix, bilingual = true, studio = studio).map { it.value }
        assertEquals(listOf("aura-2"), pair)

        val ear = StudioLegEdits.earVendor(mix, "deepgram", bilingual = true, studio = studio)
        assertEquals("nova-3-general", ear.stt.model)
        val mouth = StudioLegEdits.voiceVendor(mix, "elevenlabs", bilingual = true, studio = studio)
        assertEquals("eleven_multilingual_v2", mouth.tts.model)
    }

    @Test
    fun `a vendor whose only fitting ear is not offered still moves, to that ear`() {
        assertEquals("inworld/inworld-stt-1", StudioLegEdits.earVendor(mix, "inworld", false, studio).stt.model)
    }

    @Test
    fun `a location in region but processed elsewhere than auto is not the region's own`() {
        val elsewhere =
            VoiceStudioResidency("Canada", inRegion = true, text = "In Canada", vendorsOutOfRegion = emptyList())
        val bent = studio.copy(
            catalog = studio.catalog.copy(
                llm = studio.catalog.llm.map { brain ->
                    brain.copy(
                        locations = brain.locations.map {
                            if (it.value == "northamerica-northeast1") it.copy(residency = elsewhere) else it
                        },
                    )
                },
            ),
        )
        val engine = StudioEngine.Chained(mix.copy(llm = mix.llm.copy(location = "northamerica-northeast1")))

        assertEquals(LatencyText.None, StudioReadout.meter(engine, bent).stages[1].value)
    }

    @Test
    fun `a mouth whose voice list the server did not send, or that lacks the voice, has no per-voice number`() {
        val engine = StudioEngine.Chained(mix.copy(tts = mix.tts.copy(voice = "aura-2-luna-en"), preemptiveTts = true))
        val noVoices = studio.copy(voices = studio.voices.filterNot { it.model == "aura-2" })
        assertEquals(LatencyText.None, StudioReadout.meter(engine, noVoices).stages[2].value)

        val unknownVoice = StudioEngine.Chained(mix.copy(tts = mix.tts.copy(voice = "aura-2-nobody-en")))
        assertEquals(LatencyText.None, StudioReadout.blocks(unknownVoice, studio)[3].latency)
        assertNull(StudioRecipes.ttsVoices("nobody", "nothing", studio))
        assertTrue(StudioRecipes.voices(StudioEngine.Realtime("unknown", "Puck"), studio).isEmpty())
    }

    @Test
    fun `an end-of-turn number from the lab is not a measured stage`() {
        val lab = studio.copy(latency = studio.latency.copy(eou = VoiceStudioLatency("lab", 300.0, null, "Lab")))
        val engine = StudioEngine.Chained(mix.copy(preemptiveTts = true))

        assertEquals(LatencyText.None, StudioReadout.meter(engine, lab).stages[0].value)
    }

    @Test
    fun `an unknown realtime model's meter has nothing measured`() {
        val meter = StudioReadout.meter(StudioEngine.Realtime("unknown", "Puck"), studio)

        assertEquals(MeterHeadline.None, meter.headline)
    }

    @Test
    fun `the longest wait is raised only when it is below the shortest`() {
        val onlyShortest = mix.copy(turn = mix.turn.copy(minDelay = 0.5))
        assertEquals(onlyShortest, StudioTuning.conform(onlyShortest, studio))
        val ordered = mix.copy(turn = mix.turn.copy(minDelay = 0.5, maxDelay = 2.0))
        assertEquals(ordered, StudioTuning.conform(ordered, studio))
    }

    @Test
    fun `a realtime chain with no model id opens on an empty id rather than failing`() {
        val chain = studio.current.chain.copy(
            kind = "realtime",
            engineMix = null,
            realtimeModelId = null,
            voice = "Puck",
        )

        assertEquals(StudioEngine.Realtime("", "Puck"), StudioEngine.of(chain))
    }

    @Test
    fun `a bilingual engine with a language that has no counterpart is not bilingual`() {
        assertFalse(StudioSave.bilingualAvailable(StudioSave.CUSTOM_PIPELINE, "es-ES"))
    }
}
