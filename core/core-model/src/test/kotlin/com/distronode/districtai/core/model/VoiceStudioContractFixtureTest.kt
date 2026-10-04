package com.distronode.districtai.core.model

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The native Voice Studio read, against the committed fixture.
 *
 * ⛔ DECODED STRICTLY (`ignoreUnknownKeys = false`, through [ContractFixtures]) AND MIRRORED. A key
 * the server adds fails here, and a property whose default or serial name drifted fails the
 * mirror, before a phone silently drops either.
 *
 * ⚠️ NOTHING HERE ASSERTS A LATENCY NUMBER. The fixture's figures come from the server's TEST
 * latency table; pinning one would make a Kotlin literal of a figure nobody measured.
 */
class VoiceStudioContractFixtureTest {

    private val fixture = "district-voice-studio.json"

    private fun read(): VoiceStudioResponse =
        ContractFixtures.json.decodeFromString(VoiceStudioResponse.serializer(), ContractFixtures.read(fixture))

    @Test
    fun `the studio decodes strictly and writes back what it decoded`() {
        val studio = WireMirror.assertMirrors(VoiceStudioResponse.serializer(), fixture)

        assertTrue(studio.success)
        assertEquals("us", studio.region)
        assertEquals("en", studio.locale)
        // Guards against a vacuous mirror: every list the screen draws must carry rows.
        assertTrue(studio.recipes.isNotEmpty())
        assertTrue(studio.catalog.presets.isNotEmpty())
        assertTrue(studio.catalog.stt.isNotEmpty() && studio.catalog.llm.isNotEmpty())
        assertTrue(studio.catalog.tts.isNotEmpty() && studio.catalog.realtime.isNotEmpty())
        assertTrue(studio.voices.isNotEmpty() && studio.advanced.isNotEmpty())
    }

    @Test
    fun `the rows read on their own`() {
        WireMirror.assertRowMirrors(VoiceStudioCurrent.serializer(), fixture, "current")
        WireMirror.assertRowMirrors(EngineMix.serializer(), fixture, "current.chain.engineMix")
        WireMirror.assertRowMirrors(VoiceStudioRecipe.serializer(), fixture, "recipes.0")
        WireMirror.assertRowMirrors(VoiceStudioCurrentMeter.serializer(), fixture, "latency")
        WireMirror.assertRowMirrors(VoiceStudioMeter.serializer(), fixture, "recipes.0.timeToFirstWord")
        WireMirror.assertRowMirrors(VoiceStudioSttModel.serializer(), fixture, "catalog.stt.0")
        WireMirror.assertRowMirrors(VoiceStudioLlmModel.serializer(), fixture, "catalog.llm.0")
        WireMirror.assertRowMirrors(VoiceStudioTtsModel.serializer(), fixture, "catalog.tts.0")
        WireMirror.assertRowMirrors(VoiceStudioRealtimeModel.serializer(), fixture, "catalog.realtime.0")
        WireMirror.assertRowMirrors(VoiceStudioVoiceList.serializer(), fixture, "voices.0")
        WireMirror.assertRowMirrors(VoiceStudioTuningKey.serializer(), fixture, "advanced.0")
        WireMirror.assertRowMirrors(VoiceStudioLabels.serializer(), fixture, "labels")
    }

    @Test
    fun `a chain has four blocks in call order and a realtime engine has one`() {
        val studio = read()
        val chains = studio.recipes.map { it.chain } + studio.current.chain
        chains.forEach { chain ->
            when (chain.kind) {
                "chained" -> {
                    assertEquals(listOf("stt", "turn", "llm", "tts"), chain.blocks.map { it.leg })
                    assertNotNull(chain.engineMix)
                    assertNull(chain.realtimeModelId)
                }
                else -> {
                    assertEquals("realtime", chain.kind)
                    assertEquals(listOf("realtime"), chain.blocks.map { it.leg })
                    assertNull(chain.engineMix)
                    assertNotNull(chain.realtimeModelId)
                }
            }
        }
    }

    @Test
    fun `every recipe is one of the published ids, on one of the two tiers`() {
        val studio = read()
        studio.recipes.forEach { recipe ->
            assertTrue(recipe.id, recipe.id in studio.recipeIds)
            assertTrue(recipe.tier, recipe.tier in setOf("stable", "latest"))
        }
        assertTrue(studio.current.recipeId in studio.recipeIds)
    }

    @Test
    fun `a saved fixed engine carries no stored mix but still resolves to a chain to draw`() {
        // ⛔ THE BASELINE AND THE DRAWING ARE DIFFERENT KEYS. A preset persona stores no mix, so
        // `current.engineMix` is null while `current.chain.engineMix` is the chain the preset runs.
        val current = read().current

        assertEquals("deepgram-pipeline", current.fields.modelId)
        assertNull(current.engineMix)
        assertNull(current.fields.engineMix)
        assertNotNull(current.chain.engineMix)
    }

    @Test
    fun `a mix without the absent-when-unset keys decodes, and keeps them absent on the way out`() {
        val bare = """{"v":1,"stt":{"provider":"deepgram","model":"nova-3-general","language":null,""" +
            """"location":null},"llm":{"model":"gemini-2.5-flash","location":"auto","thinking":"off",""" +
            """"temperature":null},"tts":{"provider":"deepgram","model":"aura-2","voice":"v","speed":null,""" +
            """"location":null},"turn":{"minDelay":null,"maxDelay":null,"eotThreshold":null},"preemptiveTts":false}"""

        val mix = ContractFixtures.json.decodeFromString(EngineMix.serializer(), bare)

        assertNull(mix.stt.keyterms)
        assertNull(mix.turn.mode)
        assertNull(mix.turn.interruption)
        assertNull(mix.tts.stability)
        assertNull(mix.userAwayTimeout)
        WireMirror.assertRoundTrips(EngineMix.serializer(), mix, "a bare mix")
    }

    @Test
    fun `a tuned mix keeps every tuning key`() {
        val tuned = """{"v":1,"stt":{"provider":"deepgram","model":"flux-general-en","language":null,""" +
            """"location":null,"keyterms":["Distronode"]},"llm":{"model":"gemini-2.5-flash","location":"auto",""" +
            """"thinking":"off","temperature":1.2},"tts":{"provider":"elevenlabs","model":"eleven_v4","voice":"v",""" +
            """"speed":null,"location":null,"stability":0.4,"expressivity":0.1},"turn":{"minDelay":0.3,""" +
            """"maxDelay":1.8,"eotThreshold":0.7,"mode":"fixed","eagerEotThreshold":0.5,"eotTimeoutMs":5000,""" +
            """"interruption":{"minDuration":0.5,"minWords":2,"resume":true,"falseTimeout":2}},""" +
            """"preemptiveTts":true,"userAwayTimeout":20}"""

        val mix = ContractFixtures.json.decodeFromString(EngineMix.serializer(), tuned)

        assertEquals(listOf("Distronode"), mix.stt.keyterms)
        assertEquals("fixed", mix.turn.mode)
        assertEquals(true, mix.turn.interruption?.resume)
        assertEquals(0.4, mix.tts.stability)
        assertEquals(20.0, mix.userAwayTimeout)
        WireMirror.assertRoundTrips(EngineMix.serializer(), mix, "a tuned mix")
    }

    @Test
    fun `a mix missing a key the server always sends is refused`() {
        // ⛔ STRICT: `preemptiveTts` is always present in what the server emits.
        val missing = """{"v":1,"stt":{"provider":"p","model":"m","language":null,"location":null},""" +
            """"llm":{"model":"m","location":"auto","thinking":"off","temperature":null},""" +
            """"tts":{"provider":"p","model":"m","voice":"v","speed":null,"location":null},""" +
            """"turn":{"minDelay":null,"maxDelay":null,"eotThreshold":null}}"""

        val decoded = runCatching { ContractFixtures.json.decodeFromString(EngineMix.serializer(), missing) }

        assertTrue(decoded.exceptionOrNull() is SerializationException)
    }

    @Test
    fun `the persona patch carries a mix and the bilingual flag, and omits every null`() {
        val mix = read().current.chain.engineMix!!
        val request = PersonaPatchRequest(
            workspaceId = "ws_1",
            modelId = "custom-pipeline",
            voice = "aura-2-asteria-en",
            preemptiveTts = false,
            engineMix = mix,
            bilingual = false,
        )

        WireMirror.assertWire(
            PersonaPatchRequest.serializer(),
            request,
            """{"workspaceId":"ws_1","voice":"aura-2-asteria-en","modelId":"custom-pipeline",""" +
                """"preemptiveTts":false,"engineMix":{"v":1,"stt":{"provider":"deepgram",""" +
                """"model":"flux-general-en"},"llm":{"model":"gemini-2.5-flash","location":"auto",""" +
                """"thinking":"off"},"tts":{"provider":"deepgram","model":"aura-2",""" +
                """"voice":"aura-2-asteria-en"},"turn":{},"preemptiveTts":false},"bilingual":false}""",
        )
        assertFalse(request.engineMix!!.preemptiveTts)
    }

    @Test
    fun `every type is buildable by hand and every key is readable`() {
        // ⚠️ Decoding goes through the serializer's own constructor, so without this the primary
        // constructors and the keys the screen does not draw would be counted as never run.
        val studio = read()
        val labels = studio.labels
        assertEquals(labels, labels.copy())
        assertEquals(labels.legs, labels.legs.copy())
        assertEquals(labels.stages, labels.stages.copy())
        assertEquals(labels.channels, labels.channels.copy())
        listOf(labels.edit, labels.listen, labels.stopListening, labels.channels.latest, labels.channels.preview)
            .plus(labels.channels.legacy)
            .forEach { assertTrue(it.isNotBlank()) }

        val stage = studio.latency.stages.first()
        assertEquals(stage, stage.copy())
        assertEquals("eou", stage.stage)
        assertEquals(stage.ms, stage.copy().ms)
        assertEquals(stage.samples, stage.copy().samples)
        val recipe = studio.recipes.first()
        assertEquals(recipe, recipe.copy())
        assertEquals(recipe.timeToFirstWord, recipe.timeToFirstWord.copy())
        assertEquals(recipe.timeToFirstWord.ms, recipe.timeToFirstWord.copy().ms)
        assertFalse(recipe.timeToFirstWord.atLeast)
        assertEquals(recipe.chain.blocks.first(), recipe.chain.blocks.first().copy())
        assertEquals(recipe.chain.residency, recipe.chain.residency.copy())

        val catalog = studio.catalog
        assertEquals(catalog.presets.first(), catalog.presets.first().copy())
        assertEquals(catalog.llm.first().thinking.first(), catalog.llm.first().thinking.first().copy())
        assertEquals(catalog.stt.first(), catalog.stt.first().copy())
        assertTrue(catalog.stt.first().available)
        assertEquals(catalog.tts.first(), catalog.tts.first().copy())
        assertTrue(catalog.tts.first().available)
        assertEquals(catalog.turn, catalog.turn.copy())
        assertEquals(catalog.turn.detector, catalog.turn.detector.copy())
        assertEquals(catalog.turn.ear, catalog.turn.ear.copy())
        val honoured = studio.advanced.first().honouredBy!!.first()
        assertEquals(honoured, honoured.copy())

        val mix = studio.current.chain.engineMix!!
        val byHand = EngineMix(
            v = mix.v,
            stt = mix.stt,
            llm = mix.llm,
            tts = mix.tts,
            turn = EngineMixTurn(minDelay = null, maxDelay = null, eotThreshold = null),
            preemptiveTts = mix.preemptiveTts,
        )
        assertEquals(mix, byHand)

        // The keys this client reads nowhere yet, read once so a rename server-side fails here.
        assertTrue(studio.previewAllowed)
        assertEquals(200, studio.latency.eou!!.samples)
        assertEquals(emptyList<String>(), recipe.residency.vendorsOutOfRegion)
        assertEquals(970.0, studio.latency.ms!!, 0.0)
        assertFalse(studio.latency.atLeast)
        assertEquals("2026-10-03", studio.latency.measuredThrough)
        assertEquals(30, studio.latency.measuredDays)
        assertEquals("deepgram-pipeline", studio.current.modelId)
        assertFalse(studio.current.preemptiveTts)
        assertTrue(catalog.llm.first().available)
        assertEquals(0.0, catalog.llm.first().temperatureMin, 0.0)
        assertEquals(2.0, catalog.llm.first().temperatureMax, 0.0)
        assertTrue(catalog.realtime.first().available)
        assertEquals("/voice-clips/us/en/Puck.mp3", studio.voices.last().groups.first().options.first().clip)
        assertTrue(studio.advanced.first().nullable)
        assertNull(mix.stt.language)
    }
}
