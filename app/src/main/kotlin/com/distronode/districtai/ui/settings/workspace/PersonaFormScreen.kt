package com.distronode.districtai.ui.settings.workspace

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.distronode.districtai.core.model.AiPersona

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
 * ⛔ THREE EDITABLE FIELDS, AND THE REST OF THE PERSONA IS SHOWN READ-ONLY ON PURPOSE. `voice`,
 * `language`, `modelId`, `voiceStyle` and the avatar fields are drawn from server-side
 * vocabularies that COERCE rather than reject — an unrecognised `modelId` is silently rewritten
 * to `deepgram-pipeline`, an unrecognised `voice` is stored verbatim and the agent then speaks in
 * a voice nobody chose, and `responseLength` is a per-engine map the route only writes alongside
 * a valid `modelId`. All of those answer 200. Showing them is useful; letting a phone type into
 * them is not. See [PersonaField].
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
    modifier: Modifier = Modifier,
    onSelectEngine: (String) -> Unit = {},
    onSelectLanguage: (String) -> Unit = {},
    onUpdateEngineValues: (PersonaEngineValues) -> Unit = {},
    onPreview: () -> Unit = {},
) {
    // ⚠️ `remember`, not `rememberSaveable`: the dialog is a transient response to a back press
    // and nothing typed lives in it. The DRAFT lives in the ViewModel, which survives rotation.
    var confirmingExit by remember { mutableStateOf(false) }

    // ⛔ INTERCEPTS THE SYSTEM BACK GESTURE, NOT JUST THE TOP-BAR ARROW. On Android the gesture is
    // how people actually leave a screen, so a guard wired only to the arrow guards nothing.
    BackHandler(enabled = state.hasUnsavedChanges) { confirmingExit = true }

    if (confirmingExit) {
        UnsavedChangesDialog(
            onDiscard = {
                confirmingExit = false
                onBack()
            },
            onDismiss = { confirmingExit = false },
        )
    }

    DistrictScaffold(
        modifier = modifier.semantics { contentDescription = PERSONA_ROOT_DESCRIPTION },
        topBar = {
            DistrictTopBar(
                title = stringResource(R.string.persona_title),
                onBack = { if (state.hasUnsavedChanges) confirmingExit = true else onBack() },
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
                        EngineArea(
                            state = state,
                            onSelectEngine = onSelectEngine,
                            onSelectLanguage = onSelectLanguage,
                            onUpdateValues = onUpdateEngineValues,
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
 * The engine and voice configuration, shown and not editable.
 *
 * ⛔ THIS IS NOT A PLACEHOLDER FOR CONTROLS THAT ARE COMING. Every value here belongs to a
 * server-side vocabulary that coerces a wrong answer instead of refusing it, so a mobile control
 * would be a way to silently retarget every subsequent call. It is displayed because an operator
 * reading a persona needs to know which brain is speaking it — and because a screen that hid it
 * would look like the whole persona.
 */
/**
 * The engine half: server-driven pickers when the catalogue loaded, and the stored values
 * read-only when it did not.
 *
 * ⛔ THE FALLBACK IS READ-ONLY TEXT, NEVER A BUILT-IN LIST. `PATCH workspace/persona` COERCES
 * rather than rejects — an unrecognised `modelId` becomes `deepgram-pipeline` and an unrecognised
 * `voice` is stored and then silently replaced by the agent, both with a 200 — so a hardcoded
 * catalogue does not fail when it drifts; it produces a persona nobody chose, with nothing
 * anywhere reporting the substitution. That is the exact failure the options route exists to
 * retire, and offering values from memory would reintroduce it.
 *
 * ⚠️ THE STORED VALUES ARE STILL SHOWN WHEN THE CATALOGUE FAILS. They are what the workspace is
 * speaking in today, and a blank panel would read as "unset" rather than "unavailable".
 */
@Composable
private fun EngineArea(
    state: PersonaFormUiState,
    onSelectEngine: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateValues: (PersonaEngineValues) -> Unit,
    onRetry: () -> Unit,
    onPreview: () -> Unit,
) {
    when (val options = state.options) {
        PersonaOptionsState.Loading -> ConfigSkeleton()
        is PersonaOptionsState.LoadFailed -> Column(
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            ReadOnlyEngine(state.persona)
            ConfigLoadFailure(failure = options.failure, onRetry = onRetry)
        }
        is PersonaOptionsState.Ready -> Column(
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            PersonaEngineSection(
                draft = options.draft,
                enabled = !state.save.busy,
                onSelectEngine = onSelectEngine,
                onSelectLanguage = onSelectLanguage,
                onUpdateValues = onUpdateValues,
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
        }
    }
}

/** ⚠️ The stored triple, shown verbatim when there is no catalogue to edit against. */
@Composable
private fun ReadOnlyEngine(persona: AiPersona?) {
    Column(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = PERSONA_ENGINE_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(stringResource(R.string.persona_engine_title))
        Text(
            text = stringResource(
                R.string.persona_engine_body,
                persona?.modelId ?: stringResource(R.string.persona_engine_default),
                persona?.voice ?: stringResource(R.string.persona_engine_default),
                persona?.language ?: stringResource(R.string.persona_engine_default),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
        Text(
            text = stringResource(R.string.persona_engine_read_only),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
    }
}

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val PERSONA_ROOT_DESCRIPTION: String = "district-persona-root"
const val PERSONA_NAME_DESCRIPTION: String = "district-persona-name"
const val PERSONA_GREETING_DESCRIPTION: String = "district-persona-greeting"
const val PERSONA_PERSONALITY_DESCRIPTION: String = "district-persona-personality"
const val PERSONA_SAVE_DESCRIPTION: String = "district-persona-save"
const val PERSONA_NOTICE_DESCRIPTION: String = "district-persona-notice"
const val PERSONA_ENGINE_DESCRIPTION: String = "district-persona-engine"
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
)
