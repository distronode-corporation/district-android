package com.distronode.districtai.ui.settings.workspace

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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

    /** A catalogue for the stored engine: its languages and answer lengths. */
    private val catalogue = PersonaIdentityDraft.hydrate(
        persona = loaded.config.aiPersona,
        options = PersonaOptionsResponse(
            success = true,
            region = "us",
            engines = listOf(
                PersonaEngineOption(
                    id = "deepgram-pipeline",
                    label = "Deepgram Pipeline — US (processed in your region)",
                    inRegion = true,
                    responseLengths = listOf(
                        PersonaLabelledValue("concise", "Concise"),
                        PersonaLabelledValue("balanced", "Balanced"),
                    ),
                ),
                PersonaEngineOption(
                    id = "elevenlabs-pipeline",
                    label = "ElevenLabs Pipeline — EU (processed outside your region)",
                    inRegion = false,
                    responseLengths = listOf(PersonaLabelledValue("concise", "Concise")),
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
        onSelectLanguage: (String) -> Unit = {},
        onSelectResponseLength: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                PersonaFormScreen(
                    state = state,
                    onEdit = onEdit,
                    onSave = onSave,
                    onRetry = onRetry,
                    onBack = onBack,
                    onSelectLanguage = onSelectLanguage,
                    onSelectResponseLength = onSelectResponseLength,
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
    fun `a failed catalogue read offers a retry and NO picker, never a built-in list`() {
        // ⛔ `PATCH workspace/persona` COERCES rather than refusing, so a hardcoded catalogue would
        // not fail when it drifted; it would produce a persona nobody chose. The text boxes stay.
        var retries = 0
        render(
            PersonaFormUiState(
                load = loaded,
                options = PersonaOptionsState.LoadFailed(
                    FailureText(UiText.Literal("The list could not be loaded.")),
                ),
            ),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithText("The list could not be loaded.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_LANGUAGE_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(PERSONA_RESPONSE_LENGTH_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(PERSONA_NAME_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(WORKSPACE_SETTINGS_RETRY_DESCRIPTION).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a loaded catalogue offers the language, the answer length and the audition, and points at the Studio`() {
        // ⛔ THE ENGINE, VOICE AND TUNING ARE THE VOICE STUDIO'S. The note says where they went,
        // so the persona does not read as one with no voice.
        render(PersonaFormUiState(load = loaded, options = PersonaOptionsState.Ready(catalogue)))

        composeRule.onNodeWithContentDescription(PERSONA_LANGUAGE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("English (US)").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_RESPONSE_LENGTH_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Concise").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_PREVIEW_OPEN_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(PERSONA_VOICE_STUDIO_NOTE_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the pickers report the value chosen from the catalogue`() {
        val picked = mutableListOf<String>()
        render(
            PersonaFormUiState(load = loaded, options = PersonaOptionsState.Ready(catalogue)),
            onSelectLanguage = { picked += "language:$it" },
            onSelectResponseLength = { picked += "length:$it" },
        )

        composeRule.onNodeWithContentDescription(PERSONA_LANGUAGE_DESCRIPTION).performClick()
        composeRule.onNodeWithText("Italian").performClick()
        composeRule.onNodeWithContentDescription(PERSONA_RESPONSE_LENGTH_DESCRIPTION).performClick()
        composeRule.onNodeWithText("Balanced").performClick()

        assertEquals(listOf("language:it-IT", "length:balanced"), picked)
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
    fun `saving locks the pickers as well as the text boxes`() {
        render(
            PersonaFormUiState(
                load = loaded,
                options = PersonaOptionsState.Ready(catalogue),
                edits = mapOf(PersonaField.NAME to "Bea"),
                save = SaveState.Saving,
            ),
        )

        composeRule.onNodeWithContentDescription(PERSONA_LANGUAGE_DESCRIPTION)
            .assertHasNoClickAction()
        composeRule.onNodeWithContentDescription(PERSONA_RESPONSE_LENGTH_DESCRIPTION)
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
                    onSelectLanguage = {},
                    onSelectResponseLength = {},
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

    @Test
    fun `fitting the voice chain to a new language says how it went`() {
        val state = mutableStateOf(PersonaFormUiState(load = loaded, refit = PersonaRefit.Running))
        composeRule.setContent {
            DistrictTheme {
                PersonaFormScreen(state.value, { _, _ -> }, {}, {}, {}, {}, {}, {})
            }
        }
        val notice = composeRule.onNodeWithContentDescription(PERSONA_REFIT_DESCRIPTION)
        notice.assertTextEquals("Checking that the voice chain speaks the new language.")

        state.value = state.value.copy(refit = PersonaRefit.Refitted)
        notice.assertTextEquals(
            "The voice chain was moved to models that speak the new language. Voice Studio shows it.",
        )
        state.value = state.value.copy(refit = PersonaRefit.NoFit)
        notice.assertTextEquals(
            "No model this workspace may use speaks the new language for every part of the voice chain. " +
                "Choose them in Voice Studio.",
        )
        state.value = state.value.copy(refit = PersonaRefit.NotSeen)
        notice.assertTextEquals(
            "The voice chain was saved, but Voice Studio does not show it fitting the new language. Check it there.",
        )
        state.value = state.value.copy(refit = PersonaRefit.Failed(FailureText(UiText.Literal("Refused."))))
        notice.assertTextEquals(
            "The voice chain could not be moved to the new language. Fit it in Voice Studio. Refused.",
        )
        state.value = state.value.copy(refit = null)
        composeRule.onNodeWithContentDescription(PERSONA_REFIT_DESCRIPTION).assertDoesNotExist()
    }
}
