package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persona form's derived rules, each pinned on its own.
 *
 * ⚠️ THE ViewModel TEST DRIVES THESE THROUGH A LOAD AND A SAVE; this one names the states that
 * cycle passes through too quickly to observe, such as a config read that landed while the
 * catalogue read failed.
 */
class PersonaFormUiStateTest {

    private val persona = AiPersona(
        name = "Ada",
        greeting = "Thanks for calling.",
        personality = "Warm and concise.",
        modelId = "deepgram-pipeline",
        voice = "aura-2-asteria-en",
        language = "en-US",
    )

    private val loaded = ConfigState.Ready(WorkspaceConfig(aiPersona = persona))

    private val catalogue =
        PersonaOptionsState.Ready(PersonaIdentityDraft.hydrate(persona, TEST_PERSONA_OPTIONS))

    @Test
    fun `a workspace with no persona reads every box as empty rather than failing`() {
        val state = PersonaFormUiState(load = ConfigState.Ready(WorkspaceConfig()))

        PersonaField.entries.forEach { assertEquals("", state.stored(it)) }
        assertFalse(state.hasUnsavedChanges)
    }

    @Test
    fun `a picker change alone is unsaved work, and so is a text change alone`() {
        val lengthOnly = catalogue.draft.selectResponseLength("balanced")
        val byEngine = PersonaFormUiState(
            load = loaded,
            options = PersonaOptionsState.Ready(lengthOnly),
        )
        assertTrue(byEngine.hasUnsavedChanges)
        assertTrue(byEngine.canSave)

        val byText = PersonaFormUiState(
            load = loaded,
            options = catalogue,
            edits = mapOf(PersonaField.PERSONALITY to "Brisk."),
        )
        assertEquals(setOf(PersonaField.PERSONALITY), byText.dirtyFields)
        assertTrue(byText.hasUnsavedChanges)

        assertFalse(PersonaFormUiState(load = loaded, options = catalogue).hasUnsavedChanges)
    }

    @Test
    fun `an audition needs both the stored persona and the catalogue`() {
        // ⛔ AN UNKNOWN `modelId` IS COERCED TO `deepgram-pipeline`, so a preview built without the
        // catalogue would run on an engine nobody chose and sound convincing about it.
        val noCatalogue = PersonaFormUiState(
            load = loaded,
            options = PersonaOptionsState.LoadFailed(FailureText(UiText.Literal("down"))),
        )
        assertFalse(noCatalogue.canPreview)
        assertNull("no form can be built without a catalogue", noCatalogue.previewForm())

        val noConfig = PersonaFormUiState(load = ConfigState.Loading, options = catalogue)
        assertFalse(noConfig.canPreview)

        assertTrue(PersonaFormUiState(load = loaded, options = catalogue).canPreview)
    }

    @Test
    fun `the audition hears the form on screen, including the fields that are not dirty`() {
        val state = PersonaFormUiState(
            load = loaded,
            options = catalogue,
            edits = mapOf(PersonaField.GREETING to "Good afternoon."),
        )

        val form = state.previewForm()!!

        assertEquals("Ada", form.name)
        assertEquals("Good afternoon.", form.greeting)
        assertEquals("Warm and concise.", form.personality)
        assertEquals("aura-2-asteria-en", form.voice)
    }
}
