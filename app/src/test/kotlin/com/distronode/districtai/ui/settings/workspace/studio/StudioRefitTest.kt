package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A chain moved to models that speak a new persona language.
 *
 * ⛔ WHICH MODELS FIT IS THE READ'S WORD (`offered && forLanguage`). Each case below narrows the
 * fixture's catalogue to the models a new language would leave, and asserts where each speech leg
 * lands; nothing here knows which vendor speaks what.
 */
class StudioRefitTest {

    private val studio = VoiceStudioFixture.studio
    private val mix: EngineMix = studio.current.chain.engineMix!!

    /** The fixture read for a language only [ears] and [mouths] (by model id) speak. */
    private fun speaking(ears: Set<String>, mouths: Set<String>): VoiceStudioResponse = studio.copy(
        catalog = studio.catalog.copy(
            stt = studio.catalog.stt.map { it.copy(forLanguage = it.model in ears) },
            tts = studio.catalog.tts.map { it.copy(forLanguage = it.model in mouths) },
        ),
    )

    @Test
    fun `a chain that already fits comes back unchanged`() {
        assertEquals(mix, StudioRefit.refit(mix, studio))
    }

    @Test
    fun `each leg moves to the same vendor first, the ear to one that takes turns the same way`() {
        val tuned = mix.copy(turn = mix.turn.copy(eotThreshold = 0.95))
        val french = speaking(
            ears = setOf("nova-3-general", "flux-general-multi", "chirp_3"),
            mouths = setOf("sonic-3", "chirp-3-hd"),
        )

        val out = StudioRefit.refit(tuned, french)!!

        assertEquals("deepgram" to "flux-general-multi", out.stt.provider to out.stt.model)
        assertEquals("cartesia" to "sonic-3", out.tts.provider to out.tts.model)
        // The voice the new model lacks becomes its default.
        assertEquals("db6b0ed5-d5d3-463d-ae85-518a07d3c2b4", out.tts.voice)
        assertEquals("the brain does not depend on the language", mix.llm, out.llm)
        assertEquals("a tuning number is kept in the new ear's range", 0.9, out.turn.eotThreshold)
    }

    @Test
    fun `an ear the same vendor has only with other turn-taking still stays with the vendor`() {
        val nova = mix.copy(stt = mix.stt.copy(model = "nova-3-general", keyterms = listOf("Distronode")))
        val out = StudioRefit.refit(nova, speaking(setOf("flux-general-multi", "chirp_3"), setOf("aura-2")))!!

        assertEquals("flux-general-multi", out.stt.model)
        assertEquals(listOf("Distronode"), out.stt.keyterms)
        assertEquals("a mouth that fits stays", mix.tts, out.tts)
    }

    @Test
    fun `another vendor keeps the held location where it is offered, and drops key terms it does not take`() {
        val held = mix.copy(
            stt = mix.stt.copy(
                provider = "assemblyai",
                model = "universal-3-5-pro",
                location = "eu",
                keyterms = listOf("A"),
            ),
            tts = mix.tts.copy(provider = "aws-polly", model = "neural", voice = "Aoede", location = "eu"),
        )
        val google = speaking(setOf("chirp_3"), setOf("chirp-3-hd"))

        val out = StudioRefit.refit(held, google)!!
        assertEquals("google-stt" to "chirp_3", out.stt.provider to out.stt.model)
        assertEquals("eu", out.stt.location)
        assertNull(out.stt.keyterms)
        assertNull(out.stt.language)
        assertEquals("google-tts" to "chirp-3-hd", out.tts.provider to out.tts.model)
        assertEquals("a voice the new model has is kept", "Aoede", out.tts.voice)
        assertEquals("eu", out.tts.location)

        val elsewhere = held.copy(
            stt = held.stt.copy(location = "nowhere"),
            tts = held.tts.copy(location = null),
        )
        val moved = StudioRefit.refit(elsewhere, google)!!
        assertEquals("us", moved.stt.location)
        assertEquals("us", moved.tts.location)
    }

    @Test
    fun `an ear the catalogue does not list moves like one that does not take turns`() {
        val unknown = mix.copy(stt = mix.stt.copy(provider = "deepgram", model = "retired"))
        val read = speaking(setOf("flux-general-en", "nova-3-general"), setOf("aura-2"))
        val out = StudioRefit.refit(unknown, read)!!

        assertEquals("nova-3-general", out.stt.model)
    }

    @Test
    fun `a model that is not offered never fits, and nothing fitting means no refit`() {
        val notOffered = speaking(setOf("flux-general-en"), setOf("aura-2")).let { read ->
            read.copy(catalog = read.catalog.copy(stt = read.catalog.stt.map { it.copy(offered = false) }))
        }
        assertNull("no ear", StudioRefit.refit(mix, notOffered))
        assertNull("no voice", StudioRefit.refit(mix, speaking(setOf("flux-general-en"), emptySet())))
    }
}
