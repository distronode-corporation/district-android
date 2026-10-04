package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.ui.resolve
import com.distronode.districtai.ui.settings.workspace.studio.PickerOption
import com.distronode.districtai.ui.settings.workspace.studio.StudioPicker

/**
 * The agent persona form.
 *
 * ⛔ THE FORM ONLY EXISTS ONCE THE CONFIGURATION HAS LOADED. A failed read renders
 * [ConfigLoadFailure] — a retry and nothing else — and the screen test asserts that not one
 * editable handle is present in that state. The reason is on this whole surface rather than on
 * this route: two of its siblings replace their stored value wholesale, so "render an empty form
 * anyway" is a habit that deletes a workspace's transfer directory the first time it is copied to
 * the wrong screen.
 *
 * ⛔ THREE FREE-TEXT FIELDS, AND TWO PICKERS FROM THE SERVER'S CATALOGUE. The language and the
 * answer length are drawn from server-side vocabularies that COERCE rather than reject, so they are
 * picked from `workspace/persona/options` and never typed. The engine, voice and tuning are the
 * Voice Studio's (its own screen), and this form neither shows nor sends them. See [PersonaField].
 *
 * ⚠️ THE PENDING-EDIT WARNING IS ON BACK, not on save. The save button is disabled when nothing
 * is dirty, so the only way to lose work here is to leave.
 */
@Composable
fun PersonaFormScreen(
    state: PersonaFormUiState,
    onEdit: (PersonaField, String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectResponseLength: (String) -> Unit,
    onPreview: () -> Unit,
) {
    val guardedBack = rememberUnsavedChangesGuard(state.hasUnsavedChanges, onBack)

    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = PERSONA_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.persona_title),
                onBack = guardedBack,
            )
        },
    ) { inset ->
        Column(
            modifier = inset.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            when (val load = state.load) {
                ConfigState.Loading -> ContentContainer { ConfigSkeleton() }
                is ConfigState.LoadFailed -> ContentContainer {
                    ConfigLoadFailure(failure = load.failure, onRetry = onRetry)
                }
                is ConfigState.Ready -> {
                    ContentContainer { PersonaFields(state, onEdit, onSave) }
                    ContentContainer {
                        IdentityArea(
                            state = state,
                            onSelectLanguage = onSelectLanguage,
                            onSelectResponseLength = onSelectResponseLength,
                            onRetry = onRetry,
                            onPreview = onPreview,
                        )
                    }
                }
            }
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * The three editable boxes plus the save control.
 *
 * ⚠️ EVERY INPUT IS DISABLED WHILE A SAVE IS IN FLIGHT. A keystroke landing mid-request would
 * change the draft the request was built from, so the banner would report a save of something the
 * form no longer shows.
 */
@Composable
private fun PersonaFields(
    state: PersonaFormUiState,
    onEdit: (PersonaField, String) -> Unit,
    onSave: () -> Unit,
) {
    val busy = state.save.busy
    Column(
        modifier = Modifier.padding(DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        PersonaTextField(
            value = state.value(PersonaField.NAME),
            label = stringResource(R.string.persona_name),
            description = PERSONA_NAME_DESCRIPTION,
            enabled = !busy,
            singleLine = true,
            onValueChange = { onEdit(PersonaField.NAME, it) },
        )
        PersonaTextField(
            value = state.value(PersonaField.GREETING),
            label = stringResource(R.string.persona_greeting),
            description = PERSONA_GREETING_DESCRIPTION,
            enabled = !busy,
            singleLine = false,
            onValueChange = { onEdit(PersonaField.GREETING, it) },
        )
        PersonaTextField(
            value = state.value(PersonaField.PERSONALITY),
            label = stringResource(R.string.persona_personality),
            description = PERSONA_PERSONALITY_DESCRIPTION,
            enabled = !busy,
            singleLine = false,
            onValueChange = { onEdit(PersonaField.PERSONALITY, it) },
        )

        // ⚠️ Clearing a box is a real edit: the server stores the empty string. Said out loud
        // because "leave it blank to keep the old one" is what a reader would otherwise assume.
        Text(
            text = stringResource(R.string.persona_clear_hint),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )

        SaveNotice(state = state.save, description = PERSONA_NOTICE_DESCRIPTION)
        state.refit?.let { RefitNotice(it) }

        DistrictButton(
            text = stringResource(
                if (busy) R.string.workspace_settings_saving else R.string.workspace_settings_save,
            ),
            onClick = onSave,
            // ⛔ Disabled unless something is genuinely dirty AND the config loaded. A request
            // carrying only a workspaceId would still spend a rate-limit slot and still stamp
            // `updatedAt`, reporting an edit nobody made.
            enabled = state.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = PERSONA_SAVE_DESCRIPTION },
        )
    }
}

/**
 * One labelled box.
 *
 * ⚠️ NAMED `PersonaTextField` RATHER THAN `PersonaField` because that name is already the enum of
 * editable fields, and a private composable sharing it would read as a constructor call at three
 * call sites that are ALSO passing `PersonaField.X` to their callbacks.
 */
@Composable
private fun PersonaTextField(
    value: String,
    label: String,
    description: String,
    enabled: Boolean,
    singleLine: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Eyebrow(label) },
        colors = districtFieldColors(),
        singleLine = singleLine,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
    )
}

/**
 * The language and answer length, drawn from the server's catalogue, and the audition.
 *
 * ⛔ A FAILED CATALOGUE READ OFFERS A RETRY, NEVER A BUILT-IN LIST. `PATCH workspace/persona`
 * COERCES rather than rejects, so a hardcoded catalogue does not fail when it drifts; it produces
 * a persona nobody chose, with a 200.
 *
 * ⛔ THE ENGINE, VOICE AND TUNING ARE NOT HERE. They are the Voice Studio's, a screen of its own
 * in workspace settings; the note says so, so the persona does not read as engine-less.
 */
@Composable
private fun IdentityArea(
    state: PersonaFormUiState,
    onSelectLanguage: (String) -> Unit,
    onSelectResponseLength: (String) -> Unit,
    onRetry: () -> Unit,
    onPreview: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        // ⚠️ An `if` chain rather than an exhaustive `when`, whose synthetic last arm no test reaches.
        val options = state.options
        if (options is PersonaOptionsState.Ready) {
            val enabled = !state.save.busy
            StudioPicker(
                label = stringResource(R.string.persona_language_label),
                options = options.draft.languages.map { PickerOption(it.value, it.label) },
                selected = options.draft.values.language,
                enabled = enabled,
                description = PERSONA_LANGUAGE_DESCRIPTION,
                onSelect = onSelectLanguage,
            )
            StudioPicker(
                label = stringResource(R.string.persona_response_length_label),
                options = options.draft.responseLengths.map { PickerOption(it.value, it.label) },
                selected = options.draft.values.responseLength,
                enabled = enabled,
                description = PERSONA_RESPONSE_LENGTH_DESCRIPTION,
                onSelect = onSelectResponseLength,
            )
            // ⛔ THE AUDITION IS A REAL, BILLED CALL, and the button says so through the dialog it
            // opens rather than starting one on tap.
            DistrictButton(
                text = stringResource(R.string.persona_preview_open),
                onClick = onPreview,
                enabled = state.canPreview,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DistrictTheme.spacing.gutter)
                    .semantics { contentDescription = PERSONA_PREVIEW_OPEN_DESCRIPTION },
            )
        } else if (options is PersonaOptionsState.LoadFailed) {
            ConfigLoadFailure(failure = options.failure, onRetry = onRetry)
        } else {
            ConfigSkeleton()
        }
        Text(
            text = stringResource(R.string.persona_voice_studio_note),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .semantics { contentDescription = PERSONA_VOICE_STUDIO_NOTE_DESCRIPTION },
        )
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val PERSONA_ROOT_DESCRIPTION: String = "district-persona-root"
const val PERSONA_NAME_DESCRIPTION: String = "district-persona-name"
const val PERSONA_GREETING_DESCRIPTION: String = "district-persona-greeting"
const val PERSONA_PERSONALITY_DESCRIPTION: String = "district-persona-personality"
const val PERSONA_SAVE_DESCRIPTION: String = "district-persona-save"
const val PERSONA_REFIT_DESCRIPTION: String = "district-persona-refit"
const val PERSONA_NOTICE_DESCRIPTION: String = "district-persona-notice"
const val PERSONA_LANGUAGE_DESCRIPTION: String = "district-persona-language"
const val PERSONA_RESPONSE_LENGTH_DESCRIPTION: String = "district-persona-response-length"
const val PERSONA_VOICE_STUDIO_NOTE_DESCRIPTION: String = "district-persona-voice-studio-note"
const val PERSONA_PREVIEW_OPEN_DESCRIPTION: String = "district-persona-preview-open"

/**
 * ⛔ EVERY EDITABLE HANDLE ON THIS SCREEN, IN ONE LIST, BECAUSE THE TEST THAT MATTERS ASSERTS
 * THEIR ABSENCE. `PersonaFormScreenTest` walks this in the load-failed state: if a future field is
 * added and not listed here, the "a failed load offers no editable field" assertion silently stops
 * covering it — which is precisely how a form ends up rendering from nothing.
 */
val PERSONA_EDITABLE_DESCRIPTIONS: List<String> = listOf(
    PERSONA_NAME_DESCRIPTION,
    PERSONA_GREETING_DESCRIPTION,
    PERSONA_PERSONALITY_DESCRIPTION,
    PERSONA_SAVE_DESCRIPTION,
    PERSONA_LANGUAGE_DESCRIPTION,
    PERSONA_RESPONSE_LENGTH_DESCRIPTION,
)

/** How fitting the voice chain to a new language went. See [PersonaRefit]. */
@Composable
private fun RefitNotice(refit: PersonaRefit) {
    val text = when (refit) {
        PersonaRefit.Running -> stringResource(R.string.persona_refit_running)
        PersonaRefit.Refitted -> stringResource(R.string.persona_refit_done)
        PersonaRefit.NoFit -> stringResource(R.string.persona_refit_no_fit)
        PersonaRefit.NotSeen -> stringResource(R.string.persona_refit_not_seen)
        is PersonaRefit.Failed -> stringResource(R.string.persona_refit_failed, refit.failure.message.resolve())
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (refit is PersonaRefit.Failed || refit == PersonaRefit.NoFit) {
            DistrictTheme.colors.destructive
        } else {
            DistrictTheme.colors.foreground
        },
        modifier = Modifier.semantics { contentDescription = PERSONA_REFIT_DESCRIPTION },
    )
}
