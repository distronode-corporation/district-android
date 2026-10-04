package com.distronode.districtai.ui.settings.workspace.studio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.network.testing.VoiceStudioFixture
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_DISCARD_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_LOADING_DESCRIPTION
import com.distronode.districtai.ui.settings.workspace.WORKSPACE_SETTINGS_RETRY_DESCRIPTION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the Studio draws, and that every control routes to the right operation.
 *
 * ⚠️ THE ACTIONS ARE A SMALL IN-MEMORY STUDIO ([LiveActions]) that applies the same pure
 * transitions the ViewModel does, so a tap is checked by what the state became, not only by
 * which callback fired.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT: the screen is a `verticalScroll` Column, and `assertIsDisplayed` checks
// visible bounds, so a phone-sized display fails against nodes that are present below the fold.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h9000dp")
class VoiceStudioScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val studio = VoiceStudioFixture.studio
    private val labels = studio.labels

    /** An in-memory Studio: the ViewModel's routing without its coroutines. */
    private class LiveActions(initial: VoiceStudioUiState) : VoiceStudioActions {
        var state by mutableStateOf(initial)
        val calls = mutableListOf<String>()

        private fun ready(transform: (VoiceStudioUiState.Ready) -> VoiceStudioUiState.Ready) {
            state = transform(state as VoiceStudioUiState.Ready)
        }

        override fun load() {
            calls += "load"
        }

        override fun selectTier(tier: String) = ready { it.withTier(tier) }.also { calls += "tier:$tier" }

        override fun applyRecipe(id: String) = ready { it.withRecipe(id) }.also { calls += "recipe:$id" }

        override fun reset() = ready { it.withReset() }.also { calls += "reset" }

        override fun selectLeg(leg: String) = ready { it.withLeg(leg) }.also { calls += "leg:$leg" }

        override fun editMix(transform: (EngineMix) -> EngineMix) = ready { it.withMix(transform) }

        override fun updateHeld(transform: (StudioState) -> StudioState) = ready { it.withHeld(transform(it.held)) }

        override fun save() {
            calls += "save"
        }

        val ready: VoiceStudioUiState.Ready get() = state as VoiceStudioUiState.Ready
        val mix: EngineMix get() = (ready.held.engine as StudioEngine.Chained).mix
    }

    private fun render(initial: VoiceStudioUiState, onBack: () -> Unit = {}): LiveActions {
        val actions = LiveActions(initial)
        composeRule.setContent {
            DistrictTheme { VoiceStudioScreen(state = actions.state, actions = actions, onBack = onBack) }
        }
        return actions
    }

    private fun tap(description: String) = composeRule.onNodeWithContentDescription(description).performClick()

    private fun pick(picker: String, option: String) {
        tap(studioHandle(HANDLE_PICKER, picker))
        tap(studioHandle(HANDLE_OPTION, option))
    }

    private fun ready() = VoiceStudioUiState.Ready.of(studio)

    // ── Load ─────────────────────────────────────────────────────────────────

    @Test
    fun `loading draws a skeleton under the app's own title`() {
        render(VoiceStudioUiState.Loading)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Voice Studio").assertIsDisplayed()
    }

    @Test
    fun `a failed read offers a retry and nothing to save`() {
        val actions = render(VoiceStudioUiState.LoadFailed(FailureText(UiText.Literal("Could not reach the server."))))

        composeRule.onNodeWithText("Could not reach the server.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_SAVE_DESCRIPTION).assertDoesNotExist()
        tap(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
        assertEquals(listOf("load"), actions.calls)
    }

    // ── The loaded Studio ────────────────────────────────────────────────────

    @Test
    fun `the studio draws the server's own words`() {
        render(ready())

        composeRule.onNodeWithText(labels.description).assertIsDisplayed()
        composeRule.onNodeWithText(labels.allSaved).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_SAVE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_RECIPE, "fastest", selected = true))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Stays in the United States").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_BLOCK, "stt", selected = true))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Deepgram Flux (English)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_METER_DESCRIPTION).assertTextEquals(studio.latency.text)
        composeRule.onNodeWithText(studio.latency.sourceText).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_RESIDENCY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(studio.current.chain.residency.text).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_BASED_ON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a recipe tile applies its engine, and the save becomes possible`() {
        val actions = render(ready())

        tap(studioHandle(HANDLE_RECIPE, "realtime"))

        assertTrue(actions.ready.held.engine is StudioEngine.Realtime)
        composeRule.onNodeWithText(labels.unsaved).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_SAVE_DESCRIPTION).assertIsEnabled().performClick()
        assertEquals(listOf("recipe:realtime", "save"), actions.calls)
        // A realtime engine is one block, and its editor is open on it.
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_BLOCK, StudioLegEdits.REALTIME, selected = true))
            .assertIsDisplayed()
    }

    @Test
    fun `the tier switch re-applies the chosen recipe`() {
        val actions = render(ready())

        tap(studioHandle(HANDLE_TIER, StudioRecipes.LATEST))

        assertEquals(StudioRecipes.LATEST, actions.ready.tier)
        assertEquals("flux-general-multi", actions.mix.stt.model)
    }

    @Test
    fun `an edit says how far it is from its recipe, and reset takes it back`() {
        val actions = render(ready().withMix { it.copy(preemptiveTts = true) })

        composeRule.onNodeWithText("Based on Fastest, 1 change").assertIsDisplayed()
        tap(VOICE_STUDIO_RESET_DESCRIPTION)

        assertEquals(0, actions.ready.changes)
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_BASED_ON_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `tapping a block opens that leg's editor`() {
        val actions = render(ready())

        tap(studioHandle(HANDLE_BLOCK, StudioLegEdits.BRAIN))
        assertEquals(StudioLegEdits.BRAIN, actions.ready.leg)
        tap(studioHandle(HANDLE_LEG, StudioLegEdits.MOUTH))
        assertEquals(StudioLegEdits.MOUTH, actions.ready.leg)
    }

    // ── Save outcomes ────────────────────────────────────────────────────────

    @Test
    fun `each save outcome says what happened`() {
        val dirty = ready().withRecipe("realtime")
        val actions = render(dirty.copy(save = StudioSaveState.Saving))
        composeRule.onNodeWithText("Saving", substring = true).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_SAVE_DESCRIPTION).assertIsNotEnabled()

        actions.state = dirty.copy(save = StudioSaveState.Saved)
        composeRule.onNodeWithText(labels.saved).assertIsDisplayed()

        actions.state = dirty.copy(save = StudioSaveState.Mismatch)
        composeRule.onNodeWithText(labels.saveFailed).assertIsDisplayed()

        actions.state = dirty.copy(save = StudioSaveState.Failed(FailureText(UiText.Literal("Refused."))))
        composeRule.onNodeWithText("${labels.saveFailed} Refused.").assertIsDisplayed()

        actions.state = dirty.copy(save = StudioSaveState.SavedButStale(FailureText(UiText.Literal("Offline."))))
        composeRule.onNodeWithContentDescription(VOICE_STUDIO_NOTICE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `back with unsaved edits asks first`() {
        var backs = 0
        render(ready().withRecipe("realtime"), onBack = { backs += 1 })

        tap(TOP_BAR_BACK_DESCRIPTION)
        tap(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)

        assertEquals(1, backs)
    }

    // ── A meter the server has not described ─────────────────────────────────

    @Test
    fun `an unsaved edit's meter is summed from measured medians, and a missing stage says at least`() {
        val actions = render(ready().withMix { it.copy(llm = it.llm.copy(location = "global")) })
        val meter = composeRule.onNodeWithContentDescription(VOICE_STUDIO_METER_DESCRIPTION)
        meter.assertTextEquals("At least 520 ms")
        composeRule.onNodeWithText("${labels.stages.llmTtft}: ${labels.notMeasured}").assertIsDisplayed()

        actions.editMix { it.copy(llm = it.llm.copy(location = "auto"), preemptiveTts = true) }
        meter.assertTextEquals("About 970 ms")

        val measured = studio.copy(
            voices = studio.voices.map { list ->
                list.copy(
                    groups = list.groups.map { group ->
                        group.copy(
                            options = group.options.map {
                                if (it.value == "aura-2-luna-en") {
                                    it.copy(
                                        p50 = 140.0
                                    )
                                } else {
                                    it
                                }
                            }
                        )
                    },
                )
            },
        )
        actions.state = VoiceStudioUiState.Ready.of(measured).withMix {
            it.copy(tts = it.tts.copy(voice = "aura-2-luna-en"))
        }
        composeRule.onNodeWithText("${labels.stages.ttsTtfb}: 140 ms").assertIsDisplayed()

        actions.state = VoiceStudioUiState.Ready.of(studio.copy(latency = studio.latency.copy(eou = null))).withMix {
            it.copy(llm = it.llm.copy(location = "global"), tts = it.tts.copy(voice = "aura-2-luna-en"))
        }
        meter.assertTextEquals(labels.notMeasured)
    }

    // ── The leg editors ──────────────────────────────────────────────────────

    @Test
    fun `the ear editor moves vendor, model and location, and edits key terms`() {
        val actions = render(ready())

        pick("ear-vendor", "google-stt")
        assertEquals("chirp_3", actions.mix.stt.model)
        pick("stt-location", "eu")
        assertEquals("eu", actions.mix.stt.location)
        pick("ear-vendor", "deepgram")
        pick("ear-model", "nova-3-general")
        assertEquals("nova-3-general", actions.mix.stt.model)

        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.EAR))
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_KEYTERMS, StudioTuningValues.KEYTERMS))
            .performTextReplacement("Distronode\nDistronode\nLedger")
        assertEquals(listOf("Distronode", "Ledger"), actions.mix.stt.keyterms)
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_KEYTERMS, StudioTuningValues.KEYTERMS))
            .performTextReplacement("")
        assertEquals(null, actions.mix.stt.keyterms)
    }

    @Test
    fun `the turn-taking editor has speak sooner up front and the rest behind Advanced`() {
        val actions = render(ready().withLeg(StudioLegEdits.TURN))
        val sooner = studioHandle(HANDLE_CHECKBOX, StudioTuningValues.PREEMPTIVE_TTS)

        composeRule.onNodeWithContentDescription(sooner).assertIsOff().performClick()
        assertEquals(true, actions.mix.preemptiveTts)
        composeRule.onNodeWithContentDescription(sooner).assertIsOn()

        val minDelay = "engineMix.turn.minDelay"
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_SLIDER, minDelay)).assertDoesNotExist()
        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.TURN))
        composeRule.onNodeWithText(labels.interruptions.uppercase()).assertIsDisplayed()

        // Unticking "use the default" starts the slider where the server says it starts.
        tap(studioHandle(HANDLE_DEFAULT, minDelay))
        assertEquals(0.3, actions.mix.turn.minDelay!!, 0.0)
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_SLIDER, minDelay))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.8f) }
        assertEquals(0.8, actions.mix.turn.minDelay!!, 0.0)
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_VALUE, minDelay)).assertIsDisplayed()
        tap(studioHandle(HANDLE_DEFAULT, minDelay))
        assertEquals(null, actions.mix.turn.minDelay)

        // A whole-number slider.
        val words = "engineMix.turn.interruption.minWords"
        tap(studioHandle(HANDLE_DEFAULT, words))
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_SLIDER, words))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(3.2f) }
        assertEquals(3.0, actions.mix.turn.interruption!!.minWords!!, 0.0)

        pick("engineMix.turn.mode", "fixed")
        assertEquals("fixed", actions.mix.turn.mode)

        // And Advanced closes again.
        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.TURN))
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_SLIDER, words)).assertDoesNotExist()
    }

    @Test
    fun `the brain editor picks a model and a location, and tunes thinking and temperature`() {
        val actions = render(ready().withLeg(StudioLegEdits.BRAIN))

        pick("llm-location", "global")
        assertEquals("global", actions.mix.llm.location)
        pick("brain-model", "gemini-3.8-flash")
        assertEquals("gemini-3.8-flash", actions.mix.llm.model)

        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.BRAIN))
        pick(StudioTuningValues.THINKING, "high")
        assertEquals("high", actions.mix.llm.thinking)
        tap(studioHandle(HANDLE_DEFAULT, "engineMix.llm.temperature"))
        assertEquals(1.0, actions.mix.llm.temperature!!, 0.0)
    }

    @Test
    fun `the voice editor picks a vendor, a model, a voice and a location`() {
        val actions = render(ready().withLeg(StudioLegEdits.MOUTH))

        pick("voice", "aura-2-orion-en")
        assertEquals("aura-2-orion-en", actions.mix.tts.voice)
        composeRule.onNodeWithText("ENGLISH (MASCULINE)").assertDoesNotExist()

        pick("voice-model", "flux-tts")
        assertEquals("flux-alexis-en", actions.mix.tts.voice)
        pick("voice-vendor", "google-tts")
        assertEquals("chirp-3-hd", actions.mix.tts.model)
        pick("tts-location", "eu")
        assertEquals("eu", actions.mix.tts.location)

        pick("voice-vendor", "deepgram")
        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.MOUTH))
        tap(studioHandle(HANDLE_DEFAULT, "engineMix.tts.speed"))
        assertEquals(1.0, actions.mix.tts.speed!!, 0.0)
    }

    @Test
    fun `the realtime editor picks the model and the voice, and tunes temperature and voice style`() {
        val actions = render(ready().withRecipe("realtime"))

        pick("voice", "Kore")
        assertEquals("Kore", actions.ready.held.engine.voice)
        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.REALTIME))
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_SLIDER, StudioTuningValues.REALTIME_TEMPERATURE))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }
        assertEquals(0.25, actions.ready.held.realtimeTemperature, 0.0)
        pick(StudioTuningValues.VOICE_STYLE, "en-US-News-K")
        assertEquals("en-US-News-K", actions.ready.held.voiceStyle)

        pick("realtime-model", "gemini-3.8-live")
        assertEquals(StudioEngine.Realtime("gemini-3.8-live", "Kore"), actions.ready.held.engine)
        composeRule.onNodeWithText("Gemini 3.8 Live · Preview · ${labels.previewNote}").assertIsDisplayed()
    }

    @Test
    fun `nothing is editable while a save is in the air`() {
        render(ready().withRecipe("in-region").copy(save = StudioSaveState.Saving))

        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_TIER, StudioRecipes.LATEST)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_PICKER, "ear-vendor")).performClick()
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_OPTION, "google-stt")).assertDoesNotExist()
    }

    @Test
    fun `a key the server gave no default label or description still draws, with the app's own words`() {
        val bare = studio.copy(
            advanced = studio.advanced.map {
                when (it.key) {
                    "engineMix.turn.minDelay" -> it.copy(useDefaultLabel = null)
                    StudioTuningValues.KEYTERMS -> it.copy(description = null)
                    else -> it
                }
            },
        )
        val actions = render(VoiceStudioUiState.Ready.of(bare).withLeg(StudioLegEdits.TURN))

        tap(studioHandle(HANDLE_ADVANCED, StudioLegEdits.TURN))
        composeRule.onNodeWithText("Use the default").assertIsDisplayed()

        // ⚠️ Advanced stays open across a leg change: it is one section that follows the editor.
        actions.selectLeg(StudioLegEdits.EAR)
        composeRule.onNodeWithContentDescription(studioHandle(HANDLE_KEYTERMS, StudioTuningValues.KEYTERMS))
            .assertIsDisplayed()
        composeRule.onNodeWithText(studio.advanced.first().description!!).assertDoesNotExist()
    }
}
