package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioLatency
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chain strip, the meter and the residency summary.
 *
 * ⛔ NO NUMBER HERE IS INVENTED. Every expected figure below is one the fixture carries, and a
 * stage the fixture does not measure is asserted MISSING, with the meter saying "at least".
 */
class StudioReadoutTest {

    private val studio = VoiceStudioFixture.studio
    private val saved = StudioEngine.of(studio.current.chain)
    private val mix = (saved as StudioEngine.Chained).mix

    private fun edited(transform: (EngineMix) -> EngineMix) =
        StudioEngine.Chained(transform(mix))

    // ── The server's own words ───────────────────────────────────────────────

    @Test
    fun `the saved engine reads exactly as the server described it`() {
        val blocks = StudioReadout.blocks(saved, studio)

        assertEquals(studio.current.chain.blocks.map { it.model }, blocks.map { it.model })
        assertEquals(LatencyText.Server("Lab: 150\u00a0ms"), blocks.first().latency)
        val meter = StudioReadout.meter(saved, studio)
        assertEquals(MeterHeadline.Server(studio.latency.text), meter.headline)
        assertEquals(studio.latency.stages.size, meter.stages.size)
        assertEquals(studio.current.chain.residency.text, StudioReadout.residency(saved, studio).text)
    }

    @Test
    fun `a recipe's engine reads as the server described that recipe`() {
        val natural = studio.recipes.first { it.id == "natural" }
        val engine = StudioEngine.of(natural.chain)

        assertEquals(natural.chain.blocks.map { it.model }, StudioReadout.blocks(engine, studio).map { it.model })
        assertEquals(MeterHeadline.Server(natural.timeToFirstWord.text), StudioReadout.meter(engine, studio).headline)
        assertEquals(natural.timeToFirstWord.note, StudioReadout.meter(engine, studio).note)
        assertEquals(natural.chain.residency.legsOut, StudioReadout.residency(engine, studio).legsOut)
    }

    @Test
    fun `a block the server left unmeasured says so`() {
        val inRegion = studio.recipes.first { it.id == "in-region" && it.tier == "stable" }
        val ear = StudioReadout.blocks(StudioEngine.of(inRegion.chain), studio).first()

        assertEquals(LatencyText.None, ear.latency)
        assertEquals(LatencyText.None, LatencyText.of(null))
    }

    // ── An unsaved edit, assembled ───────────────────────────────────────────

    @Test
    fun `an edited chain is assembled from the catalogue, in the server's words`() {
        val engine = edited { it.copy(stt = it.stt.copy(model = "nova-3-general"), preemptiveTts = true) }
        // Nova-3 plus Aura-2 is not a recipe, and speak-sooner keeps it off the saved chain.
        val blocks = StudioReadout.blocks(engine, studio)

        assertEquals(listOf("stt", "turn", "llm", "tts"), blocks.map { it.leg })
        assertEquals("Deepgram Nova-3", blocks[0].model)
        assertEquals("Speech recognition", blocks[0].role)
        assertEquals("Turn detector", blocks[1].model)
        assertNull("the turn detector runs in the voice agent", blocks[1].inRegion)
        assertEquals(LatencyText.Server("Median 400\u00a0ms over 200 calls"), blocks[1].latency)
        assertEquals("Gemini 2.5 Flash", blocks[2].model)
        assertEquals(LatencyText.Server("Median 450\u00a0ms over 300 calls"), blocks[2].latency)
        assertEquals("Deepgram Aura-2", blocks[3].model)
        assertEquals(LatencyText.Server("Median 120\u00a0ms over 100 calls"), blocks[3].latency)

        val meter = StudioReadout.meter(engine, studio)
        assertEquals(MeterHeadline.Local(970.0, atLeast = false), meter.headline)
        assertNull(meter.note)
        assertEquals(StudioReadout.residency(engine, studio).text, studio.labels.allInRegion)
    }

    @Test
    fun `a Flux ear decides turn-taking itself, so its block is the ear's`() {
        val engine = edited { it.copy(preemptiveTts = true) }

        val turn = StudioReadout.blocks(engine, studio)[1]

        assertEquals("Flux end of turn", turn.model)
        assertEquals(true, turn.inRegion)
    }

    @Test
    fun `a brain away from the region's own location, or thinking, is not measured`() {
        // ⛔ THE BRAIN'S MEDIAN WAS MEASURED AT THE REGION'S OWN LOCATION WITH THINKING OFF.
        listOf(
            edited { it.copy(llm = it.llm.copy(location = "global")) },
            edited { it.copy(llm = it.llm.copy(thinking = "dynamic")) },
        ).forEach { engine ->
            val meter = StudioReadout.meter(engine, studio)
            assertEquals(MeterHeadline.Local(520.0, atLeast = true), meter.headline)
            assertEquals(LatencyText.None, meter.stages[1].value)
            assertEquals("Some steps are not measured yet, so the real time is longer.", meter.note)
            assertEquals(LatencyText.None, StudioReadout.blocks(engine, studio)[2].latency)
        }
    }

    @Test
    fun `the region's own location named explicitly is still the measured one`() {
        val engine = edited { it.copy(llm = it.llm.copy(location = "us-east4"), preemptiveTts = true) }

        assertEquals(MeterHeadline.Local(970.0, atLeast = false), StudioReadout.meter(engine, studio).headline)
    }

    @Test
    fun `a brain with no auto location is never at the region's own`() {
        val engine =
            edited { it.copy(llm = it.llm.copy(model = "gemini-3.5-flash", location = "northamerica-northeast1")) }
        val measured = VoiceStudioLatency("measured", 900.0, 10, "Median 900 ms over 10 calls")
        val withNumber = studio.copy(
            catalog = studio.catalog.copy(
                llm = studio.catalog.llm.map {
                    if (it.model == "gemini-3.5-flash") it.copy(latency = measured) else it
                },
            ),
        )

        assertEquals(LatencyText.None, StudioReadout.meter(engine, withNumber).stages[1].value)
        val residency = StudioReadout.residency(engine, withNumber)
        assertFalse(residency.inRegion)
        assertEquals(listOf("Brain: Leaves your region: Canada"), residency.legsOut)
    }

    @Test
    fun `a lab number is shown on a brain block wherever it runs`() {
        val lab = VoiceStudioLatency("lab", 600.0, null, "Lab: 600\u00a0ms")
        val withLab = studio.copy(
            catalog = studio.catalog.copy(llm = studio.catalog.llm.map { it.copy(latency = lab) }),
        )
        val engine = edited { it.copy(llm = it.llm.copy(location = "global")) }

        assertEquals(LatencyText.Server("Lab: 600\u00a0ms"), StudioReadout.blocks(engine, withLab)[2].latency)
        assertEquals(LatencyText.None, StudioReadout.meter(engine, withLab).stages[1].value)
    }

    @Test
    fun `a Deepgram voice has its own median, and a voice without one is missing`() {
        val luna = edited { it.copy(tts = it.tts.copy(voice = "aura-2-luna-en")) }
        val meter = StudioReadout.meter(luna, studio)
        assertEquals(MeterHeadline.Local(850.0, atLeast = true), meter.headline)
        assertEquals(LatencyText.None, StudioReadout.blocks(luna, studio)[3].latency)

        val measured = studio.copy(
            voices = studio.voices.map { list ->
                list.copy(
                    groups = list.groups.map { group ->
                        group.copy(
                            options = group.options.map {
                                if (it.value == "aura-2-luna-en") it.copy(p50 = 140.0) else it
                            }
                        )
                    },
                )
            },
        )
        assertEquals(MeterHeadline.Local(990.0, atLeast = false), StudioReadout.meter(luna, measured).headline)
        assertEquals(LatencyText.Millis(140.0), StudioReadout.meter(luna, measured).stages[2].value)
        assertEquals(LatencyText.Millis(140.0), StudioReadout.blocks(luna, measured)[3].latency)
    }

    @Test
    fun `another vendor's mouth number is the model's, and a lab one does not count`() {
        val cartesia =
            edited {
                it.copy(
                    tts = it.tts.copy(provider = "cartesia", model = "sonic-3", voice = "x"),
                    preemptiveTts = true
                )
            }

        assertEquals(LatencyText.Server("Lab: 180\u00a0ms"), StudioReadout.blocks(cartesia, studio)[3].latency)
        assertEquals(LatencyText.None, StudioReadout.meter(cartesia, studio).stages[2].value)
    }

    @Test
    fun `a location's residency replaces the model's`() {
        val eu = edited { it.copy(stt = it.stt.copy(provider = "google-stt", model = "chirp_3", location = "eu")) }

        val residency = StudioReadout.residency(eu, studio)

        assertFalse(residency.inRegion)
        assertEquals(studio.labels.leavesRegion, residency.text)
        assertEquals(listOf("Ear: Leaves your region: EU"), residency.legsOut)
        assertEquals("Leaves your region: EU", StudioReadout.blocks(eu, studio)[0].where)
    }

    @Test
    fun `nothing measured anywhere says not measured, with no note`() {
        val bare = studio.copy(latency = studio.latency.copy(eou = null))
        val engine =
            edited { it.copy(llm = it.llm.copy(location = "global"), tts = it.tts.copy(voice = "aura-2-luna-en")) }

        val meter = StudioReadout.meter(engine, bare)

        assertEquals(MeterHeadline.None, meter.headline)
        assertNull(meter.note)
    }

    @Test
    fun `a realtime model that is not a recipe's is assembled from the catalogue`() {
        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Kore")

        val block = StudioReadout.blocks(live, studio).single()
        assertEquals("All-in-one", block.title)
        assertEquals("Listens, takes turns, thinks and speaks", block.role)
        assertEquals(LatencyText.Server("Median 300\u00a0ms over 100 calls"), block.latency)
        val meter = StudioReadout.meter(live, studio)
        assertEquals(MeterHeadline.Local(300.0, atLeast = true), meter.headline)
        assertEquals(LatencyText.None, meter.stages[0].value)
        assertTrue(StudioReadout.residency(live, studio).inRegion)
        assertEquals(studio.labels.allInRegion, StudioReadout.residency(live, studio).text)
    }

    @Test
    fun `a realtime model that leaves the region lists itself, and without server blocks uses its own name`() {
        val next = StudioEngine.Realtime("gemini-3.8-live", "Kore")
        val noBlocks = studio.copy(
            recipes = emptyList(),
            current = studio.current.copy(chain = studio.current.chain.copy(blocks = emptyList())),
        )

        val block = StudioReadout.blocks(next, noBlocks).single()
        assertEquals("Gemini 3.8 Live", block.title)
        assertEquals("", block.role)
        assertEquals("Preview: processed globally by Google", block.note)
        assertEquals(LatencyText.None, StudioReadout.meter(next, noBlocks).stages[1].value)
        assertEquals(
            listOf("Gemini 3.8 Live: Leaves your region: global (Google)"),
            StudioReadout.residency(next, noBlocks).legsOut,
        )
    }

    @Test
    fun `an engine naming a model the catalogue does not list draws nothing and claims nothing`() {
        listOf(
            edited { it.copy(stt = it.stt.copy(model = "unknown"), preemptiveTts = true) },
            edited { it.copy(llm = it.llm.copy(model = "unknown")) },
            edited { it.copy(tts = it.tts.copy(model = "unknown")) },
            StudioEngine.Realtime("unknown", "Puck"),
        ).forEach { engine ->
            assertTrue(StudioReadout.blocks(engine, studio).isEmpty())
            val residency = StudioReadout.residency(engine, studio)
            assertFalse(residency.inRegion)
            assertEquals(studio.labels.leavesRegion, residency.text)
        }
        val meter = StudioReadout.meter(edited { it.copy(llm = it.llm.copy(model = "unknown")) }, studio)
        assertEquals(MeterHeadline.Local(400.0, atLeast = true), meter.headline)
    }
}
