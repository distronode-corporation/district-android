package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's pickers and tuning controls, built outside composition, and what each callback
 * does to the held state.
 */
class StudioControlsTest {

    private val studio = VoiceStudioFixture.studio
    private val ready = VoiceStudioUiState.Ready.of(studio)

    private fun on(leg: String, from: VoiceStudioUiState.Ready = ready) = StudioTestActions(from.withLeg(leg))

    private fun key(path: String) = studio.advanced.single { it.key == path }

    private fun control(path: String, actions: StudioTestActions) =
        StudioTuningControls.of(key(path), actions.state, actions)

    // ── Pickers ──────────────────────────────────────────────────────────────

    @Test
    fun `each leg offers its own pickers, and turn-taking none`() {
        fun handles(leg: String) = on(leg).let { StudioLegControls.pickers(it.state, it).map { p -> p.handle } }

        assertEquals(listOf("ear-vendor", "ear-model"), handles(StudioLegEdits.EAR))
        assertEquals(emptyList<String>(), handles(StudioLegEdits.TURN))
        assertEquals(listOf("brain-model", "llm-location"), handles(StudioLegEdits.BRAIN))
        assertEquals(listOf("voice-vendor", "voice-model"), handles(StudioLegEdits.MOUTH))
    }

    @Test
    fun `every picker's choice makes its own edit`() {
        val ear = on(StudioLegEdits.EAR)
        StudioLegControls.pickers(ear.state, ear)[0].onSelect("google-stt")
        assertEquals("chirp_3", ear.mix.stt.model)
        val location = StudioLegControls.pickers(ear.state, ear).last()
        assertEquals("stt-location", location.handle)
        assertEquals("us", location.selected)
        location.onSelect("eu")
        assertEquals("eu", ear.mix.stt.location)
        StudioLegControls.pickers(ear.state, ear)[1].onSelect("chirp_3")
        assertEquals("eu", ear.mix.stt.location)

        val brain = on(StudioLegEdits.BRAIN)
        StudioLegControls.pickers(brain.state, brain)[0].onSelect("gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", brain.mix.llm.model)

        val mouth = on(StudioLegEdits.MOUTH)
        StudioLegControls.pickers(mouth.state, mouth)[1].onSelect("flux-tts")
        assertEquals("flux-tts", mouth.mix.tts.model)
        StudioLegControls.pickers(mouth.state, mouth)[0].onSelect("google-tts")
        assertEquals("chirp-3-hd", mouth.mix.tts.model)
        assertEquals("tts-location", StudioLegControls.pickers(mouth.state, mouth).last().handle)
    }

    @Test
    fun `an unset location shows the first one the model offers`() {
        val chirp = ready.withLeg(StudioLegEdits.MOUTH).withMix {
            val tts = it.tts.copy(provider = "google-tts", model = "chirp-3-hd", voice = "Achernar", location = null)
            it.copy(tts = tts)
        }
        val actions = StudioTestActions(chirp)

        assertEquals("global", StudioLegControls.pickers(chirp, actions).last().selected)
    }

    @Test
    fun `a realtime engine offers its model and its voices`() {
        val live = StudioTestActions(ready.withRecipe("realtime"))
        val pickers = StudioLegControls.pickers(live.state, live)
        assertEquals(listOf("realtime-model"), pickers.map { it.handle })
        pickers.single().onSelect("gemini-3.8-live")
        assertEquals(StudioEngine.Realtime("gemini-3.8-live", "Puck"), live.state.held.engine)

        val voice = StudioLegControls.voice(live.state, live)!!
        assertEquals("Puck (Professional Male)", voice.selectedLabel)
        assertNull("Gemini Live's flat list has no heading", voice.heading(0))
        voice.onSelect("Kore")
        assertEquals("Kore", live.state.held.engine.voice)
    }

    @Test
    fun `the voice picker is on the Voice leg of a chain only, with a heading per group`() {
        val ear = on(StudioLegEdits.EAR)
        assertNull(StudioLegControls.voice(ear.state, ear))

        val mouth = on(StudioLegEdits.MOUTH)
        val voice = StudioLegControls.voice(mouth.state, mouth)!!
        assertEquals("English (Feminine)", voice.heading(0))
        assertNull("the second row of a group has no heading", voice.heading(1))
        val masculine = voice.voices.indexOfFirst { it.first == "English (Masculine)" }
        assertEquals("English (Masculine)", voice.heading(masculine))
        voice.onSelect("aura-2-orion-en")
        assertEquals("aura-2-orion-en", mouth.mix.tts.voice)
    }

    @Test
    fun `a held voice the list does not have shows the placeholder`() {
        val odd = ready.withLeg(StudioLegEdits.MOUTH).withMix { it.copy(tts = it.tts.copy(voice = "retired")) }

        val voice = StudioLegControls.voice(odd, StudioTestActions(odd))!!

        assertEquals(studio.labels.voicePlaceholder, voice.selectedLabel)
    }

    @Test
    fun `a closed picker shows the held option, or the held value when it is not listed`() {
        val options = listOf(PickerOption("a", "Alpha", "Stable", "Note"), PickerOption("b", "Beta"))

        assertEquals("Alpha · Stable · Note", PickerOption.subtitle(options, "a"))
        assertEquals("Beta", PickerOption.subtitle(options, "b"))
        assertEquals("gone", PickerOption.subtitle(options, "gone"))
        assertEquals("", PickerOption.subtitle(options, null))
    }

    // ── Tuning controls ──────────────────────────────────────────────────────

    @Test
    fun `a chain number slides on its step, and the default box clears it`() {
        val turn = on(StudioLegEdits.TURN)
        fun minDelay() = control("engineMix.turn.minDelay", turn) as TuningControl.Number

        assertNull(minDelay().value)
        minDelay().onUseDefault!!(false)
        assertEquals(0.3, turn.mix.turn.minDelay!!, 0.0)
        minDelay().onSlide(0.80000001f)
        assertEquals(0.8, turn.mix.turn.minDelay!!, 0.0)
        minDelay().onUseDefault!!(true)
        assertNull(turn.mix.turn.minDelay)
    }

    @Test
    fun `the realtime temperature always has a value and no default box`() {
        val live = StudioTestActions(ready.withRecipe("realtime"))
        val temperature = StudioTuningControls.of(key("temperature"), live.state, live) as TuningControl.Number

        assertEquals(0.7, temperature.value!!, 0.0)
        assertNull(temperature.onUseDefault)
        temperature.onSlide(0.25f)
        assertEquals(0.25, live.state.held.realtimeTemperature, 0.0)
    }

    @Test
    fun `a slider with no range, or a choice nobody mapped, is not drawn`() {
        val turn = on(StudioLegEdits.TURN)
        assertNull(StudioTuningControls.of(key("engineMix.tts.stability"), turn.state, turn))
        val unmapped = key("engineMix.turn.minDelay").copy(key = "engineMix.turn.unmapped")
        assertNull(StudioTuningControls.of(unmapped, turn.state, turn))
        val oddSelect = key("engineMix.turn.mode").copy(key = "engineMix.turn.unmapped")
        assertNull(StudioTuningControls.of(oddSelect, turn.state, turn))
    }

    @Test
    fun `choices read and write their paths, and thinking's options are the brain's`() {
        val turn = on(StudioLegEdits.TURN)
        val mode = StudioTuningControls.of(key("engineMix.turn.mode"), turn.state, turn) as TuningControl.Choice
        assertEquals("auto", mode.selected)
        assertEquals(listOf("auto", "dynamic", "fixed"), mode.options.map { it.value })
        mode.onSelect("dynamic")
        assertEquals("dynamic", turn.mix.turn.mode)

        val brain = on(StudioLegEdits.BRAIN)
        val thinking = control(StudioTuningValues.THINKING, brain) as TuningControl.Choice
        assertEquals(listOf("off", "dynamic"), thinking.options.map { it.value })

        val unknown = ready.withMix { it.copy(llm = it.llm.copy(model = "unknown")) }
        val none = StudioTuningControls.of(key(StudioTuningValues.THINKING), unknown, StudioTestActions(unknown))
        assertTrue((none as TuningControl.Choice).options.isEmpty())

        val bare = key("engineMix.turn.mode").copy(options = null)
        assertTrue((StudioTuningControls.of(bare, turn.state, turn) as TuningControl.Choice).options.isEmpty())
    }

    @Test
    fun `the voice style is the realtime persona's own`() {
        val live = StudioTestActions(ready.withRecipe("realtime"))
        val style = control(StudioTuningValues.VOICE_STYLE, live) as TuningControl.Choice

        assertNull(style.selected)
        style.onSelect("en-US-News-K")
        assertEquals("en-US-News-K", live.state.held.voiceStyle)
    }

    @Test
    fun `speak sooner is a flag on the mix`() {
        val turn = on(StudioLegEdits.TURN)
        val flag = control(StudioTuningValues.PREEMPTIVE_TTS, turn) as TuningControl.Flag

        assertFalse(flag.checked)
        flag.onChange(true)
        assertTrue(turn.mix.preemptiveTts)
    }

    @Test
    fun `key terms keep what is being typed while it still says the same thing`() {
        val ear = on(StudioLegEdits.EAR)
        val lines = StudioTuningControls.of(key(StudioTuningValues.KEYTERMS), ear.state, ear) as TuningControl.Lines
        assertEquals("", lines.shown(""))

        lines.onText("Distronode\n")
        assertEquals(listOf("Distronode"), ear.mix.stt.keyterms)
        val typed = StudioTuningControls.of(key(StudioTuningValues.KEYTERMS), ear.state, ear) as TuningControl.Lines
        assertEquals("Distronode\n", typed.shown("Distronode\n"))
        assertEquals("a reset shows the held list", "Distronode", typed.shown("Something else"))

        typed.onText("  ")
        assertNull(ear.mix.stt.keyterms)
    }

    @Test
    fun `the interruptions heading goes above the first interruption control`() {
        val turn = on(StudioLegEdits.TURN)
        val controls = StudioTuning.keysFor(StudioLegEdits.TURN, turn.state.held.engine, studio)
            .mapNotNull { StudioTuningControls.of(it, turn.state, turn) }

        val first = StudioTuningControls.firstInterruption(controls)!!
        assertEquals("engineMix.turn.interruption.minDuration", first.key.key)
        assertNull(StudioTuningControls.firstInterruption(controls.take(1)))
    }

    @Test
    fun `a slider's stops and its whole-number display follow its step`() {
        assertEquals(19, TuningRange(0.0, 1.0, 0.05, 0.3, null).steps)
        assertFalse(TuningRange(0.0, 1.0, 0.05, 0.3, null).whole)
        assertEquals(9, TuningRange(0.0, 10.0, 1.0, 0.0, null).steps)
        assertTrue(TuningRange(0.0, 10.0, 1.0, 0.0, null).whole)
        assertEquals(0, TuningRange(0.0, 1.0, null, 0.3, null).steps)
        assertFalse(TuningRange(0.0, 1.0, null, 0.3, null).whole)
        assertEquals(0, TuningRange(0.0, 1.0, 2.0, 0.3, null).steps)
    }

    @Test
    fun `the change count names the recipe the edit started from, on this tier`() {
        assertEquals("Fastest", ready.baseName)
        assertSame(ready, ready.withRecipe("nothing"))
        assertEquals("", ready.copy(baseRecipe = "gone").baseName)
    }
}
