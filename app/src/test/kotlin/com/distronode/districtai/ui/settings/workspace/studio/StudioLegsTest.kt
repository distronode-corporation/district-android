package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each leg's pickers offer, and what each leg edit does to the rest of the chain.
 *
 * ⛔ EVERY CASCADE HERE EXISTS BECAUSE THE PATCH REFUSES THE ALTERNATIVE with 400
 * `invalid_engine_mix` and writes nothing.
 */
class StudioLegsTest {

    private val studio = VoiceStudioFixture.studio
    private val mix = (StudioEngine.of(studio.current.chain) as StudioEngine.Chained).mix

    // ── Pickers ──────────────────────────────────────────────────────────────

    @Test
    fun `a vendor is listed only when one of its models is offered and speaks the language`() {
        val vendors = StudioPickers.earVendors(mix, bilingual = false, studio = studio).map { it.value }

        // Inworld's ear is not offered (vendor readiness), so the vendor is not listed.
        assertEquals(listOf("deepgram", "assemblyai", "aws-transcribe", "google-stt"), vendors)
        assertEquals("Deepgram", StudioPickers.earVendors(mix, false, studio).first().label)
    }

    @Test
    fun `bilingual narrows the ear models to those proven on both languages`() {
        val all = StudioPickers.earModels(mix, bilingual = false, studio = studio).map { it.value }
        val pair = StudioPickers.earModels(mix, bilingual = true, studio = studio).map { it.value }

        assertEquals(listOf("nova-3-general", "flux-general-en", "flux-general-multi"), all)
        // The held model stays listed even though it is not proven bilingual.
        assertEquals(listOf("nova-3-general", "flux-general-en", "flux-general-multi"), pair)
        val held = mix.copy(stt = mix.stt.copy(model = "nova-3-general"))
        assertFalse(
            "flux-general-en" in StudioPickers.earModels(held, bilingual = true, studio = studio).map { it.value }
        )
    }

    @Test
    fun `a model that is not offered stays listed while it is the one held`() {
        val held = mix.copy(tts = mix.tts.copy(provider = "elevenlabs", model = "eleven_v4"))

        assertTrue("eleven_v4" in StudioPickers.voiceModels(held, false, studio).map { it.value })
        assertTrue("elevenlabs" in StudioPickers.voiceVendors(held, false, studio).map { it.value })
        assertFalse("elevenlabs" in StudioPickers.voiceVendors(mix, false, studio).map { it.value })
        assertTrue(
            "a bilingual mouth list still keeps the held model",
            "eleven_v4" in StudioPickers.voiceModels(held, true, studio).map { it.value },
        )
    }

    @Test
    fun `a model option says its channel, and a Preview model its note`() {
        val brains = StudioPickers.brainModels(mix, studio)

        assertEquals("Stable", brains.first().channelLabel)
        assertNull(brains.first().note)
        val ears = StudioPickers.earModels(mix, false, studio)
        assertTrue(ears.all { it.channelLabel != null })
    }

    @Test
    fun `locations are the held model's own, and a vendor endpoint has none`() {
        assertTrue(StudioPickers.locations(StudioLegEdits.EAR, mix, studio).isEmpty())
        assertEquals(
            listOf("auto", "us-east4", "northamerica-northeast1", "europe-west4", "asia-southeast1", "global"),
            StudioPickers.locations(StudioLegEdits.BRAIN, mix, studio).map { it.value },
        )
        val chirp = mix.copy(tts = mix.tts.copy(provider = "google-tts", model = "chirp-3-hd"))
        assertEquals(4, StudioPickers.locations(StudioLegEdits.MOUTH, chirp, studio).size)
        val unknown = mix.copy(llm = mix.llm.copy(model = "unknown"))
        assertTrue(StudioPickers.locations(StudioLegEdits.BRAIN, unknown, studio).isEmpty())
    }

    @Test
    fun `a realtime model refused in the region is not offered unless it is held`() {
        val refused = studio.copy(
            catalog = studio.catalog.copy(
                realtime = studio.catalog.realtime.map {
                    if (it.model == "gemini-3.8-live") it.copy(refusedInRegion = true) else it
                },
            ),
        )
        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Puck")

        assertEquals(listOf(StudioSave.GEMINI_LIVE_25), StudioPickers.realtimeModels(live, refused).map { it.value })
        assertEquals(
            2,
            StudioPickers.realtimeModels(StudioEngine.Realtime("gemini-3.8-live", "Puck"), refused).size,
        )
    }

    // ── Edits ────────────────────────────────────────────────────────────────

    @Test
    fun `a new ear vendor starts on its first offered model at that model's default location`() {
        val next = StudioLegEdits.earVendor(
            mix.copy(stt = mix.stt.copy(keyterms = listOf("x"))),
            "google-stt",
            false,
            studio
        )

        assertEquals("chirp_3", next.stt.model)
        assertEquals("us", next.stt.location)
        assertNull("key terms belong to the previous ear", next.stt.keyterms)
    }

    @Test
    fun `a vendor with nothing for the language leaves the chain alone`() {
        assertSame(mix, StudioLegEdits.earVendor(mix, "nobody", false, studio))
        assertSame(mix, StudioLegEdits.voiceVendor(mix, "nobody", false, studio))
    }

    @Test
    fun `a vendor whose only fitting model is not offered still moves, to that model`() {
        val next = StudioLegEdits.voiceVendor(mix, "elevenlabs", false, studio)

        assertEquals("eleven_flash_v2_5", next.tts.model)
        assertEquals("EXAVITQu4vr4xnSDxMaL", next.tts.voice)
    }

    @Test
    fun `an ear model keeps key terms only where they are taken, and its location only where offered`() {
        val tuned = mix.copy(stt = mix.stt.copy(keyterms = listOf("Distronode")))
        assertEquals(listOf("Distronode"), StudioLegEdits.earModel(tuned, "nova-3-general", studio).stt.keyterms)

        val chirp = StudioLegEdits.earVendor(mix, "google-stt", false, studio)
        val eu = StudioLegEdits.location(chirp, StudioLegEdits.EAR, "eu")
        assertEquals("eu", StudioLegEdits.earModel(eu, "chirp_3", studio).stt.location)

        val transcribe = StudioLegEdits.earVendor(tuned, "aws-transcribe", false, studio)
        assertNull(transcribe.stt.keyterms)
        val withTerms = transcribe.copy(stt = transcribe.stt.copy(keyterms = listOf("x")))
        assertNull(StudioLegEdits.earModel(withTerms, "transcribe-streaming", studio).stt.keyterms)
        assertSame(mix, StudioLegEdits.earModel(mix, "unknown", studio))
    }

    @Test
    fun `a new ear drops a turn-taking value its model does not honour`() {
        // ⛔ THE END-OF-TURN THRESHOLD IS FLUX'S. Kept on Nova-3 it would be dropped by the server
        // with a 200, and the re-read would report the save as failed.
        val flux = mix.copy(turn = mix.turn.copy(eotThreshold = 0.8))

        assertNull(StudioLegEdits.earModel(flux, "nova-3-general", studio).turn.eotThreshold)
        assertEquals(0.8, StudioLegEdits.earModel(flux, "flux-general-multi", studio).turn.eotThreshold!!, 0.0)
    }

    @Test
    fun `a new brain keeps its location where offered and takes its own default thinking`() {
        val next = StudioLegEdits.brainModel(mix, "gemini-3.5-flash", studio)
        assertEquals("northamerica-northeast1", next.llm.location)
        assertEquals("low", next.llm.thinking)

        val global = StudioLegEdits.location(mix, StudioLegEdits.BRAIN, "global")
        assertEquals("global", StudioLegEdits.brainModel(global, "gemini-3.8-flash", studio).llm.location)
        assertSame(mix, StudioLegEdits.brainModel(mix, "unknown", studio))
    }

    @Test
    fun `a new mouth model keeps the voice it has, else starts on its own`() {
        val flux = StudioLegEdits.voiceModel(mix, "flux-tts", studio)
        assertEquals("flux-alexis-en", flux.tts.voice)
        val back = StudioLegEdits.voiceModel(flux, "aura-2", studio)
        assertEquals("aura-2-asteria-en", back.tts.voice)

        val chirp = StudioLegEdits.voiceVendor(mix, "google-tts", false, studio)
        val eu = StudioLegEdits.location(chirp, StudioLegEdits.MOUTH, "eu")
        assertEquals("eu", StudioLegEdits.voiceModel(eu, "chirp-3-hd", studio).tts.location)
        assertSame(mix, StudioLegEdits.voiceModel(mix, "unknown", studio))
    }

    @Test
    fun `a new mouth drops a speed its model does not honour and clamps one it does`() {
        val fast = mix.copy(tts = mix.tts.copy(speed = 1.5))

        assertNull(StudioLegEdits.voiceModel(fast, "flux-tts", studio).tts.speed)
        val cartesia = StudioLegEdits.voiceVendor(mix, "cartesia", false, studio)
        val quick = cartesia.copy(tts = cartesia.tts.copy(speed = 2.0))
        assertEquals(2.0, StudioLegEdits.voiceModel(quick, "sonic-3.6", studio).tts.speed!!, 0.0)
    }

    @Test
    fun `a realtime switch keeps a voice the new model speaks, else its first`() {
        val live = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "Kore")
        assertEquals("Kore", StudioLegEdits.realtimeModel(live, "gemini-3.8-live", studio).voice)

        val odd = StudioEngine.Realtime(StudioSave.GEMINI_LIVE_25, "nobody")
        assertEquals("Puck", StudioLegEdits.realtimeModel(odd, "gemini-3.8-live", studio).voice)
        assertEquals("nobody", StudioLegEdits.realtimeModel(odd, "unknown", studio).voice)
    }
}
