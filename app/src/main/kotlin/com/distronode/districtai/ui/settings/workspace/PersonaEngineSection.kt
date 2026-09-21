package com.distronode.districtai.ui.settings.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.PersonaLabelledValue
import java.util.Locale

/**
 * The engine, language, voice and tuning pickers — every one of them drawn from the server's own
 * catalogue.
 *
 * ⛔ THERE IS NO FREE TEXT AND NO BUILT-IN LIST ANYWHERE IN THIS FILE. `PATCH workspace/persona`
 * COERCES rather than rejects: an unrecognised `modelId` becomes `deepgram-pipeline` and an
 * unrecognised `voice` is stored verbatim and then replaced by the agent's own fallback at
 * synthesis time. Both answer 200, so a typed value does not fail — it produces a persona nobody
 * chose, in a voice nobody picked.
 *
 * ⛔ AN OUT-OF-REGION ENGINE IS SHOWN AND DISABLED, NOT HIDDEN. Its label states where its audio
 * would be processed, which is a public residency claim; hiding it would leave an operator unable
 * to see why their region offers fewer choices than a colleague's, and offering it would make a
 * data-residency decision on a settings screen with a 200.
 */
@Composable
internal fun PersonaEngineSection(
    draft: PersonaEngineDraft,
    enabled: Boolean,
    onSelectEngine: (String) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onUpdateValues: (PersonaEngineValues) -> Unit,
) {
    val capabilities = draft.capabilities
    Column(
        modifier = Modifier
            .padding(vertical = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = PERSONA_ENGINE_SECTION_DESCRIPTION },
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        Eyebrow(
            text = stringResource(R.string.persona_engine_title),
            modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        )

        EnginePicker(draft, enabled, onSelectEngine)

        PersonaPicker(
            label = stringResource(R.string.persona_language_label),
            selected = draft.values.language,
            options = draft.languages,
            enabled = enabled,
            description = PERSONA_LANGUAGE_DESCRIPTION,
            onSelect = onSelectLanguage,
        )

        VoicePicker(draft, enabled) { onUpdateValues(draft.values.copy(voice = it)) }

        PersonaPicker(
            label = stringResource(R.string.persona_response_length_label),
            selected = draft.values.responseLength,
            options = draft.responseLengths,
            enabled = enabled,
            description = PERSONA_RESPONSE_LENGTH_DESCRIPTION,
            onSelect = { onUpdateValues(draft.values.copy(responseLength = it)) },
        )

        // ⛔ GEMINI LIVE ONLY. Every other engine would accept this field, store it and ignore it —
        // a control with no effect and no error, which is worse than an absent one.
        if (capabilities.showsVoiceStyle) {
            PersonaPicker(
                label = stringResource(R.string.persona_voice_style_label),
                selected = draft.values.voiceStyle,
                options = draft.voiceStyles,
                enabled = enabled,
                description = PERSONA_VOICE_STYLE_DESCRIPTION,
                onSelect = { onUpdateValues(draft.values.copy(voiceStyle = it)) },
            )
        }

        TemperatureRow(draft, enabled) { onUpdateValues(draft.values.copy(temperature = it)) }

        // ⚠️ EVERY ENGINE EXCEPT GEMINI LIVE. A realtime engine does not speculate ahead of its own
        // audio, so the setting is meaningless there — mirroring the web form's own condition.
        if (capabilities.showsPreemptiveTts) {
            DistrictListRow(
                title = stringResource(R.string.persona_preemptive_tts_label),
                subtitle = stringResource(R.string.persona_preemptive_tts_body),
                trailing = {
                    Switch(
                        checked = draft.values.preemptiveTts,
                        onCheckedChange = { onUpdateValues(draft.values.copy(preemptiveTts = it)) },
                        enabled = enabled,
                        modifier = Modifier.semantics {
                            contentDescription = PERSONA_PREEMPTIVE_TTS_DESCRIPTION
                        },
                    )
                },
            )
        }
    }
}

/**
 * ⛔ THE ONE PICKER THAT RENDERS ITS DISABLED ROWS. `inRegion: false` is selectable-looking and not
 * selectable; the label carries the residency claim and is shown verbatim rather than shortened.
 */
@Composable
private fun EnginePicker(
    draft: PersonaEngineDraft,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = draft.engines.firstOrNull { it.id == draft.values.modelId }
    Box {
        DistrictListRow(
            title = stringResource(R.string.persona_engine_label),
            subtitle = selected?.label ?: stringResource(R.string.persona_engine_default),
            onClick = if (enabled) {
                { expanded = true }
            } else {
                null
            },
            modifier = Modifier.semantics { contentDescription = PERSONA_ENGINE_PICKER_DESCRIPTION },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            draft.engines.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    enabled = option.inRegion,
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = personaEngineOptionDescription(option.id)
                    },
                )
            }
        }
    }
}

/**
 * The voice list, which arrives GROUPED on the wire even when the source list is flat.
 *
 * ⚠️ AN EMPTY CATALOGUE IS A REAL ANSWER and means "no voices for that engine and language", not a
 * failed read. ⛔ A STORED VOICE THE CATALOGUE NO LONGER PUBLISHES IS SAID SO AND LEFT ALONE: it is
 * what the workspace is speaking in today, and silently substituting it would change the agent's
 * voice because a list moved.
 */
@Composable
private fun VoicePicker(draft: PersonaEngineDraft, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = draft.voiceGroups
        .flatMap { it.options }
        .firstOrNull { it.value == draft.values.voice }
        ?.label
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline)) {
        Box {
            DistrictListRow(
                title = stringResource(R.string.persona_voice_label),
                subtitle = label
                    ?: draft.values.voice.takeIf { it.isNotEmpty() }
                    ?: stringResource(R.string.persona_engine_default),
                onClick = if (enabled) {
                    { expanded = true }
                } else {
                    null
                },
                modifier = Modifier.semantics {
                    contentDescription = PERSONA_VOICE_PICKER_DESCRIPTION
                },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                draft.voiceGroups.forEach { group ->
                    Eyebrow(
                        text = group.label,
                        modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
                    )
                    group.options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            onClick = {
                                expanded = false
                                onSelect(option.value)
                            },
                        )
                    }
                }
            }
        }
        if (draft.voiceIsOffCatalogue) {
            Text(
                text = stringResource(R.string.persona_voice_off_catalogue, draft.values.voice),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
                modifier = Modifier
                    .padding(horizontal = DistrictTheme.spacing.gutter)
                    .semantics { contentDescription = PERSONA_VOICE_OFF_CATALOGUE_DESCRIPTION },
            )
        }
    }
}

/** One `value`/`label` list, rendered as a row that opens a menu. */
@Composable
private fun PersonaPicker(
    label: String,
    selected: String,
    options: List<PersonaLabelledValue>,
    enabled: Boolean,
    description: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DistrictListRow(
            title = label,
            subtitle = options.firstOrNull { it.value == selected }?.label
                ?: selected.takeIf { it.isNotEmpty() }
                ?: stringResource(R.string.persona_engine_default),
            onClick = if (enabled) {
                { expanded = true }
            } else {
                null
            },
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelect(option.value)
                    },
                )
            }
        }
    }
}

/** ⚠️ Clamped to 0..1 server-side; the slider's range is the same so a save cannot be refused. */
@Composable
private fun TemperatureRow(
    draft: PersonaEngineDraft,
    enabled: Boolean,
    onChange: (Double) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        Eyebrow(stringResource(R.string.persona_temperature_label))
        Text(
            text = String.format(Locale.US, "%.2f", draft.values.temperature),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.semantics {
                contentDescription = PERSONA_TEMPERATURE_VALUE_DESCRIPTION
            },
        )
        Slider(
            value = draft.values.temperature.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = PERSONA_TEMPERATURE_DESCRIPTION },
        )
        Text(
            text = stringResource(R.string.persona_temperature_hint),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
        )
    }
}

const val PERSONA_ENGINE_SECTION_DESCRIPTION: String = "district-persona-engine-section"
const val PERSONA_ENGINE_PICKER_DESCRIPTION: String = "district-persona-engine-picker"
const val PERSONA_LANGUAGE_DESCRIPTION: String = "district-persona-language"
const val PERSONA_VOICE_PICKER_DESCRIPTION: String = "district-persona-voice-picker"
const val PERSONA_VOICE_OFF_CATALOGUE_DESCRIPTION: String = "district-persona-voice-off-catalogue"
const val PERSONA_RESPONSE_LENGTH_DESCRIPTION: String = "district-persona-response-length"
const val PERSONA_VOICE_STYLE_DESCRIPTION: String = "district-persona-voice-style"
const val PERSONA_TEMPERATURE_DESCRIPTION: String = "district-persona-temperature"
const val PERSONA_TEMPERATURE_VALUE_DESCRIPTION: String = "district-persona-temperature-value"
const val PERSONA_PREEMPTIVE_TTS_DESCRIPTION: String = "district-persona-preemptive-tts"

fun personaEngineOptionDescription(engineId: String): String = "district-persona-engine-$engineId"
