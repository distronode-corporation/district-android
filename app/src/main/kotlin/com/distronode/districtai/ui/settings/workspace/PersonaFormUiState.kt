package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.ui.FailureText

enum class PersonaField { NAME, GREETING, PERSONALITY }

/**
 * Whether the persona form may offer its language and answer-length pickers at all.
 *
 * ⛔ A FAILED READ HIDES THOSE PICKERS AND MUST NEVER FALL BACK TO A BUILT-IN CATALOGUE. `PATCH workspace/persona` COERCES rather than rejects, so a hardcoded list does not
 * fail when it drifts — every value it offered would still be accepted, stored, and then quietly
 * substituted by the agent, with a 200 and nothing anywhere reporting it. That is the exact failure
 * `workspace/persona/options` exists to retire.
 *
 * ⛔ SEPARATE FROM [ConfigState] BECAUSE EITHER READ CAN FAIL ALONE. The three free-text fields are
 * editable from the config read alone and must stay editable when the catalogue read fails; a
 * single load state would hide a working greeting box behind an unrelated failure.
 */
sealed interface PersonaOptionsState {

    data object Loading : PersonaOptionsState

    data class Ready(val draft: PersonaIdentityDraft) : PersonaOptionsState

    data class LoadFailed(val failure: FailureText) : PersonaOptionsState
}

/**
 * Fitting a chain of the member's own to a new language, after the save that changed it (see
 * [PersonaFormViewModel.save] and `studio/StudioRefit`).
 */
sealed interface PersonaRefit {

    /** Reading the Studio for the new language, saving the moved chain, or reading it back. */
    data object Running : PersonaRefit

    /** Moved, and the read after the save shows the server accepting the chain. */
    data object Refitted : PersonaRefit

    /** No model this workspace may use speaks the new language for the ear or the voice. */
    data object NoFit : PersonaRefit

    /** Could not be moved: a read or the second save failed. */
    data class Failed(val failure: FailureText) : PersonaRefit

    /** Saved, and the read after it does not show it fitting, or could not be taken. */
    data object NotSeen : PersonaRefit
}

data class PersonaFormUiState(
    val load: ConfigState = ConfigState.Loading,
    val edits: Map<PersonaField, String> = emptyMap(),
    val save: SaveState = SaveState.Idle,
    val options: PersonaOptionsState = PersonaOptionsState.Loading,
    val refit: PersonaRefit? = null,
) {

    val persona: AiPersona? get() = (load as? ConfigState.Ready)?.config?.aiPersona

    val draft: PersonaIdentityDraft? get() = (options as? PersonaOptionsState.Ready)?.draft

    fun stored(field: PersonaField): String = when (field) {
        PersonaField.NAME -> persona?.name
        PersonaField.GREETING -> persona?.greeting
        PersonaField.PERSONALITY -> persona?.personality
    }.orEmpty()

    fun value(field: PersonaField): String = edits[field] ?: stored(field)

    val dirtyFields: Set<PersonaField>
        get() = edits.filter { (field, draft) -> draft != stored(field) }.keys

    /** ⚠️ False when the catalogue never loaded, which is why those pickers are absent then. */
    val hasUnsavedChanges: Boolean get() = dirtyFields.isNotEmpty() || draft?.isDirty == true

    val canSave: Boolean
        get() = load is ConfigState.Ready && save != SaveState.Saving && refit != PersonaRefit.Running &&
            hasUnsavedChanges

    /**
     * ⛔ THE PREVIEW NEEDS THE CATALOGUE, NOT JUST A LOADED CONFIG. A `modelId` the registry does
     * not know is coerced to `deepgram-pipeline`, so an audition built without the options read
     * would run on an engine nobody chose and sound convincing about it.
     */
    val canPreview: Boolean get() = load is ConfigState.Ready && options is PersonaOptionsState.Ready

    /**
     * What an audition should hear.
     *
     * ⛔ IT IS THE FORM ON SCREEN, INCLUDING THE FIELDS THAT ARE NOT DIRTY. The preview persists
     * nothing and merges against nothing, so a form carrying only the changes would audition a
     * persona that is neither what is stored nor what is drafted.
     */
    fun previewForm(): PersonaPreviewForm? = draft?.previewForm(
        name = value(PersonaField.NAME),
        greeting = value(PersonaField.GREETING),
        personality = value(PersonaField.PERSONALITY),
    )
}
