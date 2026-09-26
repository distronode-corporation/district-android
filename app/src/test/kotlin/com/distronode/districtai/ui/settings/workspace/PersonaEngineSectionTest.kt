package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PERSONA_GEMINI_LIVE_ENGINE
import com.distronode.districtai.core.model.PERSONA_LANGUAGE_KEYED_ENGINE
import com.distronode.districtai.core.model.PersonaDefaults
import com.distronode.districtai.core.model.PersonaEngineOption
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaLanguageCatalog
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaVoiceCatalog
import com.distronode.districtai.core.model.PersonaVoiceGroup
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The engine section's pickers, driven: what each row offers, and exactly what a choice hands back.
 *
 * WHAT THE FORM-LEVEL TEST DOES NOT REACH. [PersonaFormScreenTest] proves the section appears and
 * that a failed load draws no editable field. Nothing opened a picker, so nothing proved that the
 * engine menu keeps an out-of-region engine visible and unselectable, that choosing a voice
 * changes the voice and nothing else, or that a stored voice the catalogue dropped is said out
 * loud instead of silently replaced. Each of those is a save that would succeed and be wrong.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class PersonaEngineSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = PersonaOptionsResponse(
        success = true,
        region = "us",
        engines = listOf(
            PersonaEngineOption(
                id = PERSONA_LANGUAGE_KEYED_ENGINE,
                label = "Deepgram Pipeline (in region)",
                inRegion = true,
                responseLengths = listOf(
                    PersonaLabelledValue("concise", "Concise"),
                    PersonaLabelledValue("detailed", "Detailed"),
                ),
            ),
            PersonaEngineOption(
                id = PERSONA_GEMINI_LIVE_ENGINE,
                label = "Gemini Live (in region)",
                inRegion = true,
                responseLengths = listOf(PersonaLabelledValue("concise", "Concise")),
            ),
            PersonaEngineOption(
                id = "elevenlabs-pipeline",
                label = "ElevenLabs Pipeline (processed outside your region)",
                inRegion = false,
                responseLengths = emptyList(),
            ),
        ),
        languages = PersonaLanguageCatalog(
            deepgram = listOf(
                PersonaLabelledValue("en-US", "English (US)"),
                PersonaLabelledValue("it-IT", "Italian"),
            ),
            general = listOf(PersonaLabelledValue("en-US", "English (US)")),
        ),
        voices = listOf(
            PersonaVoiceCatalog(
                engine = PERSONA_LANGUAGE_KEYED_ENGINE,
                language = "en-US",
                groups = listOf(
                    PersonaVoiceGroup(label = "Featured", options = listOf(PersonaLabelledValue("asteria", "Asteria"))),
                    PersonaVoiceGroup(label = "More voices", options = listOf(PersonaLabelledValue("luna", "Luna"))),
                ),
            ),
            PersonaVoiceCatalog(
                engine = PERSONA_GEMINI_LIVE_ENGINE,
                language = "en-US",
                groups = listOf(
                    PersonaVoiceGroup(label = "Voices", options = listOf(PersonaLabelledValue("Puck", "Puck"))),
                ),
            ),
        ),
        voiceStyles = listOf(
            PersonaLabelledValue("warm", "Warm"),
            PersonaLabelledValue("crisp", "Crisp"),
        ),
        defaults = PersonaDefaults(
            voiceByEngine = mapOf(PERSONA_GEMINI_LIVE_ENGINE to "Puck"),
            voiceByDeepgramLanguage = mapOf("en-US" to "asteria"),
            responseLength = "concise",
            temperature = 0.7,
        ),
    )

    private fun draft(modelId: String, voice: String, voiceStyle: String? = null) = PersonaEngineDraft.hydrate(
        persona = AiPersona(modelId = modelId, language = "en-US", voice = voice, voiceStyle = voiceStyle),
        options = options,
    )

    private val engines = mutableListOf<String>()
    private val languages = mutableListOf<String>()
    private val updates = mutableListOf<PersonaEngineValues>()

    private fun render(draft: PersonaEngineDraft, enabled: Boolean = true) {
        composeRule.setContent {
            DistrictTheme {
                PersonaEngineSection(
                    draft = draft,
                    enabled = enabled,
                    onSelectEngine = { engines += it },
                    onSelectLanguage = { languages += it },
                    onUpdateValues = { updates += it },
                )
            }
        }
    }

    private fun open(description: String) {
        composeRule.onNodeWithContentDescription(description).performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `the engine menu offers in-region engines and shows the out-of-region one disabled`() {
        render(draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria"))

        // The row names the selected engine by its label, not its id.
        composeRule.onNodeWithText("Deepgram Pipeline (in region)").assertIsDisplayed()
        open(PERSONA_ENGINE_PICKER_DESCRIPTION)

        composeRule.onNodeWithContentDescription(personaEngineOptionDescription(PERSONA_GEMINI_LIVE_ENGINE))
            .assertIsEnabled()
        // Shown, with its residency label, and not selectable.
        composeRule.onNodeWithContentDescription(personaEngineOptionDescription("elevenlabs-pipeline"))
            .assertIsNotEnabled()
        composeRule.onNodeWithText("ElevenLabs Pipeline (processed outside your region)").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(personaEngineOptionDescription(PERSONA_GEMINI_LIVE_ENGINE))
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(PERSONA_GEMINI_LIVE_ENGINE), engines)
        // The menu closed behind the choice.
        composeRule.onNodeWithContentDescription(personaEngineOptionDescription("elevenlabs-pipeline"))
            .assertDoesNotExist()
    }

    @Test
    fun `a voice is chosen from its group and changes the voice and nothing else`() {
        val start = draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria")
        render(start)

        composeRule.onNodeWithText("Asteria").assertIsDisplayed()
        open(PERSONA_VOICE_PICKER_DESCRIPTION)
        // Both groups are labelled in the menu, as eyebrows.
        composeRule.onNodeWithText("FEATURED").assertIsDisplayed()
        composeRule.onNodeWithText("MORE VOICES").assertIsDisplayed()
        composeRule.onNodeWithText("Luna").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(start.values.copy(voice = "luna")), updates)
    }

    @Test
    fun `the language and the response length pickers hand back exactly what was chosen`() {
        val start = draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria")
        render(start)

        open(PERSONA_LANGUAGE_DESCRIPTION)
        composeRule.onNodeWithText("Italian").performClick()
        composeRule.waitForIdle()
        open(PERSONA_RESPONSE_LENGTH_DESCRIPTION)
        composeRule.onNodeWithText("Detailed").performClick()
        composeRule.waitForIdle()

        // A language goes through its own callback, because it can cascade into the voice.
        assertEquals(listOf("it-IT"), languages)
        assertEquals(listOf(start.values.copy(responseLength = "detailed")), updates)
    }

    @Test
    fun `gemini offers a voice style and no preemptive speech, and the style is written back`() {
        val start = draft(PERSONA_GEMINI_LIVE_ENGINE, voice = "Puck", voiceStyle = "warm")
        render(start)

        composeRule.onNodeWithContentDescription(PERSONA_PREEMPTIVE_TTS_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText("Warm").assertIsDisplayed()
        open(PERSONA_VOICE_STYLE_DESCRIPTION)
        composeRule.onNodeWithText("Crisp").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(start.values.copy(voiceStyle = "crisp")), updates)
    }

    @Test
    fun `a pipeline engine offers preemptive speech and no voice style`() {
        render(draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria"))

        composeRule.onNodeWithContentDescription(PERSONA_VOICE_STYLE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(PERSONA_PREEMPTIVE_TTS_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a stored voice the catalogue no longer publishes is named and left alone`() {
        render(draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "retired-voice"))

        // The row still shows what the workspace is speaking in today, by its stored value.
        composeRule.onNodeWithText("retired-voice").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_VOICE_OFF_CATALOGUE_DESCRIPTION).assertIsDisplayed()
        assertEquals("nothing is substituted on render", emptyList<PersonaEngineValues>(), updates)
    }

    @Test
    fun `a read-only section opens no picker at all`() {
        render(draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria"), enabled = false)

        listOf(
            PERSONA_ENGINE_PICKER_DESCRIPTION,
            PERSONA_LANGUAGE_DESCRIPTION,
            PERSONA_VOICE_PICKER_DESCRIPTION,
            PERSONA_RESPONSE_LENGTH_DESCRIPTION,
        ).forEach { composeRule.onNodeWithContentDescription(it).assertHasNoClickAction() }
        composeRule.onNodeWithContentDescription(PERSONA_TEMPERATURE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `an editable section makes every picker tappable`() {
        render(draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria"))

        listOf(
            PERSONA_ENGINE_PICKER_DESCRIPTION,
            PERSONA_LANGUAGE_DESCRIPTION,
            PERSONA_VOICE_PICKER_DESCRIPTION,
            PERSONA_RESPONSE_LENGTH_DESCRIPTION,
        ).forEach { composeRule.onNodeWithContentDescription(it).assertHasClickAction() }
    }

    @Test
    fun `values the catalogue cannot name are shown as stored, and absent ones as not set`() {
        // ⚠️ AN ENGINE WITH NO CATALOGUE ENTRIES STILL SHOWS WHAT IS STORED. The level the route
        // holds is named verbatim, and a picker with nothing chosen says so rather than blank.
        render(
            PersonaEngineDraft.hydrate(
                persona = AiPersona(modelId = "elevenlabs-pipeline", voice = ""),
                options = options,
            ),
        )

        composeRule.onNodeWithText("ElevenLabs Pipeline (processed outside your region)")
            .assertIsDisplayed()
        composeRule.onNodeWithText("concise").assertIsDisplayed()
        // The language and the voice are both unset.
        composeRule.onAllNodesWithText("not set").assertCountEquals(2)
    }

    @Test
    fun `an engine the catalogue does not list reads as not set`() {
        render(PersonaEngineDraft.hydrate(persona = null, options = options))

        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_PICKER_DESCRIPTION).assertIsDisplayed()
        composeRule.onAllNodesWithText("not set").assertCountEquals(3)
    }

    @Test
    fun `every control writes back through the same callback after the section redraws`() {
        // ⚠️ A REDRAW WITH THE SAME DRAFT AND THE SAME CALLBACKS, which is what a save starting
        // and finishing does to this section. Each control must still write exactly its own field.
        val start = draft(PERSONA_GEMINI_LIVE_ENGINE, voice = "Puck", voiceStyle = "warm")
        val enabled = mutableStateOf(true)
        val onSelectEngine: (String) -> Unit = { engines += it }
        val onSelectLanguage: (String) -> Unit = { languages += it }
        val onUpdateValues: (PersonaEngineValues) -> Unit = { updates += it }
        composeRule.setContent {
            DistrictTheme {
                PersonaEngineSection(
                    draft = start,
                    enabled = enabled.value,
                    onSelectEngine = onSelectEngine,
                    onSelectLanguage = onSelectLanguage,
                    onUpdateValues = onUpdateValues,
                )
            }
        }
        enabled.value = false
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(PERSONA_VOICE_PICKER_DESCRIPTION).assertHasNoClickAction()
        enabled.value = true
        composeRule.waitForIdle()

        open(PERSONA_VOICE_PICKER_DESCRIPTION)
        composeRule.onAllNodesWithText("Puck")[1].performClick()
        composeRule.waitForIdle()
        open(PERSONA_RESPONSE_LENGTH_DESCRIPTION)
        composeRule.onAllNodesWithText("Concise")[1].performClick()
        composeRule.waitForIdle()
        open(PERSONA_VOICE_STYLE_DESCRIPTION)
        composeRule.onNodeWithText("Crisp").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(PERSONA_TEMPERATURE_DESCRIPTION)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.25f) }

        assertEquals(
            listOf(
                start.values.copy(voice = "Puck"),
                start.values.copy(responseLength = "concise"),
                start.values.copy(voiceStyle = "crisp"),
                start.values.copy(temperature = 0.25),
            ),
            updates,
        )
    }

    @Test
    fun `the preemptive switch writes back after a redraw too`() {
        val start = draft(PERSONA_LANGUAGE_KEYED_ENGINE, voice = "asteria")
        val enabled = mutableStateOf(false)
        val onUpdateValues: (PersonaEngineValues) -> Unit = { updates += it }
        composeRule.setContent {
            DistrictTheme {
                PersonaEngineSection(
                    draft = start,
                    enabled = enabled.value,
                    onSelectEngine = {},
                    onSelectLanguage = {},
                    onUpdateValues = onUpdateValues,
                )
            }
        }
        composeRule.onNodeWithContentDescription(PERSONA_PREEMPTIVE_TTS_DESCRIPTION).assertIsNotEnabled()
        enabled.value = true
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(PERSONA_PREEMPTIVE_TTS_DESCRIPTION).performClick()

        assertEquals(listOf(start.values.copy(preemptiveTts = !start.values.preemptiveTts)), updates)
    }
}
