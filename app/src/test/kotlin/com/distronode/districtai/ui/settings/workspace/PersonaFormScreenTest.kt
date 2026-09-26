package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PersonaDefaults
import com.distronode.districtai.core.model.PersonaEngineOption
import com.distronode.districtai.core.model.PersonaLabelledValue
import com.distronode.districtai.core.model.PersonaLanguageCatalog
import com.distronode.districtai.core.model.PersonaOptionsResponse
import com.distronode.districtai.core.model.PersonaVoiceCatalog
import com.distronode.districtai.core.model.PersonaVoiceGroup
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the persona form draws, and the one thing it must never draw: an editable field when the
 * configuration did not load.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, for the reason the marketplace and analytics screens need one: this is a
// `verticalScroll` Column, so every child is COMPOSED whether or not it is on screen while
// `assertIsDisplayed` checks visible BOUNDS. On a phone-sized Robolectric display the read-only
// engine block sits below the fold and the assertion fails against a node that is perfectly
// present — which reads as a rendering bug rather than a short viewport.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class PersonaFormScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val loaded = ConfigState.Ready(
        WorkspaceConfig(
            aiPersona = AiPersona(
                name = "Ada",
                greeting = "Thanks for calling.",
                personality = "Warm and concise.",
                modelId = "deepgram-pipeline",
                voice = "aura-2-asteria-en",
                language = "en-US",
            ),
        ),
    )

    /**
     * A catalogue with one in-region engine and one that is not.
     *
     * ⛔ THE OUT-OF-REGION ROW IS PART OF THE FIXTURE ON PURPOSE. `inRegion: false` is
     * selectable-LOOKING and not selectable, and the label is what says where its audio would be
     * processed — a screen that hid it would be hiding a residency statement.
     */
    private val catalogue = PersonaEngineDraft.hydrate(
        persona = loaded.config.aiPersona,
        options = PersonaOptionsResponse(
            success = true,
            region = "us",
            engines = listOf(
                PersonaEngineOption(
                    id = "deepgram-pipeline",
                    label = "Deepgram Pipeline — US (processed in your region)",
                    inRegion = true,
                    responseLengths = listOf(PersonaLabelledValue("concise", "Concise")),
                ),
                PersonaEngineOption(
                    id = "elevenlabs-pipeline",
                    label = "ElevenLabs Pipeline — EU (processed outside your region)",
                    inRegion = false,
                    responseLengths = listOf(PersonaLabelledValue("concise", "Concise")),
                ),
            ),
            languages = PersonaLanguageCatalog(
                deepgram = listOf(PersonaLabelledValue("en-US", "English (US)")),
                general = listOf(PersonaLabelledValue("en-US", "English (US)")),
            ),
            voices = listOf(
                PersonaVoiceCatalog(
                    engine = "deepgram-pipeline",
                    language = "en-US",
                    groups = listOf(
                        PersonaVoiceGroup(
                            label = "Voices",
                            options = listOf(PersonaLabelledValue("aura-2-asteria-en", "Asteria")),
                        ),
                    ),
                ),
            ),
            voiceStyles = listOf(PersonaLabelledValue("warm", "Warm")),
            defaults = PersonaDefaults(
                voiceByEngine = mapOf("deepgram-pipeline" to "aura-2-asteria-en"),
                voiceByDeepgramLanguage = mapOf("en-US" to "aura-2-asteria-en"),
                responseLength = "concise",
                temperature = 0.7,
            ),
        ),
    )

    private fun render(
        state: PersonaFormUiState,
        onEdit: (PersonaField, String) -> Unit = { _, _ -> },
        onSave: () -> Unit = {},
        onRetry: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                PersonaFormScreen(
                    state = state,
                    onEdit = onEdit,
                    onSave = onSave,
                    onRetry = onRetry,
                    onBack = onBack,
                    onSelectEngine = {},
                    onSelectLanguage = {},
                    onUpdateEngineValues = {},
                    onPreview = {},
                )
            }
        }
    }

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load offers a retry and NOT ONE editable field`() {
        // ⛔ THE ASSERTION THIS ENTIRE PACKAGE EXISTS TO MAKE. Three of this surface's save routes
        // replace their stored value wholesale, so an empty form that can be saved is a deletion.
        // Walked over PERSONA_EDITABLE_DESCRIPTIONS rather than named field by field, so a control
        // added later cannot quietly fall out of this check.
        render(
            PersonaFormUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Could not reach the server."))),
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Could not reach the server.").assertIsDisplayed()

        PERSONA_EDITABLE_DESCRIPTIONS.forEach { handle ->
            composeRule.onNodeWithContentDescription(handle).assertDoesNotExist()
        }
    }

    @Test
    fun `the retry fires the reload`() {
        var retries = 0
        render(
            PersonaFormUiState(
                load = ConfigState.LoadFailed(FailureText(UiText.Literal("Offline."))),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION).performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `an unretryable failure offers no retry button`() {
        // ⚠️ A 403 or a signed-out failure repeats identically; a button that cannot help is worse
        // than none. Still no editable field either.
        render(
            PersonaFormUiState(
                load = ConfigState.LoadFailed(
                    FailureText(UiText.Literal("You do not have access."), retryable = false),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOAD_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(PERSONA_NAME_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `loading draws skeletons and no fields`() {
        render(PersonaFormUiState())

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_LOADING_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_NAME_DESCRIPTION).assertDoesNotExist()
    }

    // ── The loaded form ──────────────────────────────────────────────────────

    @Test
    fun `a loaded form is hydrated from the server's values`() {
        render(PersonaFormUiState(load = loaded))

        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText("Thanks for calling.").assertIsDisplayed()
        composeRule.onNodeWithText("Warm and concise.").assertIsDisplayed()
    }

    @Test
    fun `a failed catalogue read shows the stored engine and offers NO picker`() {
        // ⛔ THE FALLBACK IS READ-ONLY TEXT, NEVER A BUILT-IN LIST. `PATCH workspace/persona`
        // COERCES rather than refusing — an unrecognised engine becomes `deepgram-pipeline` and an
        // unrecognised voice is stored and then replaced by the agent, both with a 200 — so a
        // hardcoded catalogue would not fail when it drifted; it would produce a persona nobody
        // chose. ⚠️ The stored values are still SHOWN, because they are what the workspace speaks
        // in today and a blank panel would read as "unset" rather than "unavailable".
        render(
            PersonaFormUiState(
                load = loaded,
                options = PersonaOptionsState.LoadFailed(
                    FailureText(UiText.Literal("The list could not be loaded.")),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Engine deepgram-pipeline · Voice aura-2-asteria-en · Language en-US",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_PICKER_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a loaded catalogue offers the pickers and the audition, and no free-text engine box`() {
        // ⛔ THE WHOLE POINT OF THE OPTIONS ROUTE. Every value on offer is the server's own, so a
        // save cannot store one the registry will silently rewrite.
        render(PersonaFormUiState(load = loaded, options = PersonaOptionsState.Ready(catalogue)))

        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_SECTION_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_PICKER_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_VOICE_PICKER_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_OPEN_DESCRIPTION)
            .assertIsDisplayed()
        // ⛔ THE READ-ONLY FALLBACK MUST NOT BE DRAWN AS WELL. Two descriptions of the same three
        // values, one of them stale, is worse than either alone.
        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `an out-of-region engine is offered as a disabled row rather than hidden`() {
        // ⛔ ITS LABEL CARRIES THE RESIDENCY CLAIM. Hiding it leaves an operator unable to see why
        // their region offers fewer choices; offering it would make a data-residency decision on a
        // settings screen, silently, with a 200.
        render(PersonaFormUiState(load = loaded, options = PersonaOptionsState.Ready(catalogue)))

        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_PICKER_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(
            personaEngineOptionDescription("deepgram-pipeline"),
        ).assertIsEnabled()
        composeRule.onNodeWithContentDescription(
            personaEngineOptionDescription("elevenlabs-pipeline"),
        ).assertIsNotEnabled()
    }

    @Test
    fun `typing reports the field and the new value`() {
        var edited: Pair<PersonaField, String>? = null
        render(PersonaFormUiState(load = loaded), onEdit = { field, value -> edited = field to value })

        composeRule.onNodeWithContentDescription(PERSONA_GREETING_DESCRIPTION)
            .performTextReplacement("Good afternoon.")

        assertEquals(PersonaField.GREETING to "Good afternoon.", edited)
    }

    @Test
    fun `save is disabled until something is dirty`() {
        // ⚠️ Not merely cosmetic: a request carrying only a workspaceId still spends a 30/min
        // rate-limit slot and still stamps `updatedAt`, reporting an edit nobody made.
        render(PersonaFormUiState(load = loaded))

        composeRule.onNodeWithContentDescription(PERSONA_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `save is enabled once a field differs from what was loaded`() {
        render(
            PersonaFormUiState(
                load = loaded,
                edits = mapOf(PersonaField.NAME to "Bea"),
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_SAVE_DESCRIPTION).assertIsEnabled()
    }

    @Test
    fun `saving disables every input`() {
        // ⚠️ A keystroke landing mid-request would change the draft the request was built from, so
        // the banner would report a save of something the form no longer shows.
        render(
            PersonaFormUiState(
                load = loaded,
                edits = mapOf(PersonaField.NAME to "Bea"),
                save = SaveState.Saving,
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_NAME_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(PERSONA_GREETING_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(PERSONA_SAVE_DESCRIPTION).assertIsNotEnabled()
    }

    // ── The save banners ─────────────────────────────────────────────────────

    @Test
    fun `a landed write that could not be re-read says so, and does not read as a failure`() {
        // ⛔ THE WORDING MATTERS: the write LANDED. Telling the operator it failed invites a second
        // save from state the client can no longer vouch for.
        render(
            PersonaFormUiState(
                load = loaded,
                save = SaveState.SavedButStale(FailureText(UiText.Literal("Offline."))),
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_NOTICE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Offline.").assertDoesNotExist()
    }

    @Test
    fun `a failed save shows the server's own message and keeps the edits`() {
        render(
            PersonaFormUiState(
                load = loaded,
                edits = mapOf(PersonaField.NAME to "Bea"),
                save = SaveState.Failed(FailureText(UiText.Literal("Too many updates."))),
            ),
        )

        composeRule.onNodeWithText("Too many updates.").assertIsDisplayed()
        composeRule.onNodeWithText("Bea").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_SAVE_DESCRIPTION).assertIsEnabled()
    }

    // ── Leaving with pending edits ───────────────────────────────────────────

    @Test
    fun `back with unsaved edits asks before discarding them`() {
        var backs = 0
        render(
            PersonaFormUiState(load = loaded, edits = mapOf(PersonaField.NAME to "Bea")),
            onBack = { backs += 1 },
        )

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertIsDisplayed()
        assertEquals("the screen must not have left yet", 0, backs)

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `keeping editing dismisses the dialog and stays`() {
        var backs = 0
        render(
            PersonaFormUiState(load = loaded, edits = mapOf(PersonaField.NAME to "Bea")),
            onBack = { backs += 1 },
        )

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_KEEP_EDITING_DESCRIPTION)
            .performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertDoesNotExist()
        assertEquals(0, backs)
        composeRule.onNodeWithText("Bea").assertIsDisplayed()
    }

    @Test
    fun `back with nothing pending leaves immediately`() {
        var backs = 0
        render(PersonaFormUiState(load = loaded), onBack = { backs += 1 })

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()

        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION)
            .assertDoesNotExist()
        assertEquals(1, backs)
    }

    // ── The rest of the states ───────────────────────────────────────────────

    @Test
    fun `a failed catalogue read on a workspace with no persona says every engine value is not set`() {
        // ⚠️ "NOT SET" RATHER THAN BLANK. A blank panel would read as a rendering fault, not as a
        // workspace that has never chosen.
        render(
            PersonaFormUiState(
                load = ConfigState.Ready(WorkspaceConfig()),
                options = PersonaOptionsState.LoadFailed(FailureText(UiText.Literal("Offline."))),
            ),
        )

        composeRule.onNodeWithText("Engine not set · Voice not set · Language not set")
            .assertIsDisplayed()
    }

    @Test
    fun `saving locks the engine pickers as well as the text boxes`() {
        render(
            PersonaFormUiState(
                load = loaded,
                options = PersonaOptionsState.Ready(catalogue),
                edits = mapOf(PersonaField.NAME to "Bea"),
                save = SaveState.Saving,
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_ENGINE_PICKER_DESCRIPTION)
            .assertHasNoClickAction()
        composeRule.onNodeWithText("Saving", substring = true).assertIsDisplayed()
    }

    @Test
    fun `every box and the discard still report correctly after the form redraws`() {
        // ⚠️ EVERY KEYSTROKE REDRAWS THIS FORM FROM A NEW STATE WITH THE SAME CALLBACKS. A box that
        // kept a stale callback, or a dialog that did, would edit or leave the wrong way.
        val calls = mutableListOf<String>()
        val current = mutableStateOf(PersonaFormUiState(load = loaded))
        // ⚠️ Folded back into the state the way the ViewModel does, so a box shows what was typed.
        val onEdit: (PersonaField, String) -> Unit = { field, value ->
            calls += "$field:$value"
            current.value = current.value.copy(edits = current.value.edits + (field to value))
        }
        val onBack: () -> Unit = { calls += "back" }
        composeRule.setContent {
            DistrictTheme {
                PersonaFormScreen(
                    state = current.value,
                    onEdit = onEdit,
                    onSave = {},
                    onRetry = {},
                    onBack = onBack,
                    onSelectEngine = {},
                    onSelectLanguage = {},
                    onUpdateEngineValues = {},
                    onPreview = {},
                )
            }
        }

        current.value = PersonaFormUiState(load = loaded, edits = mapOf(PersonaField.NAME to "Bea"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(PERSONA_NAME_DESCRIPTION).performTextReplacement("Cara")
        composeRule.onNodeWithContentDescription(PERSONA_GREETING_DESCRIPTION).performTextReplacement("Hi.")
        composeRule.onNodeWithContentDescription(PERSONA_PERSONALITY_DESCRIPTION)
            .performTextReplacement("Brisk.")

        composeRule.onNodeWithContentDescription(TOP_BAR_BACK_DESCRIPTION).performClick()
        current.value = current.value.copy(edits = mapOf(PersonaField.NAME to "Dee"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_DISCARD_DESCRIPTION).performClick()

        assertEquals(listOf("NAME:Cara", "GREETING:Hi.", "PERSONALITY:Brisk.", "back"), calls)
    }
}
