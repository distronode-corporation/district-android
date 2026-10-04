package com.distronode.districtai.ui.settings.workspace.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow

/**
 * The editor for one leg: a chain's Ear, Turn-taking, Brain or Voice (chosen here or by tapping a
 * block), or the one realtime model.
 *
 * ⛔ EVERY PICKER OFFERS ONLY WHAT THE SERVER LISTED ([StudioPickers]) and every edit goes through
 * [StudioLegEdits], which moves the dependent fields with it. There is no free text except key
 * terms, which the server documents as free text within its limits. What each leg offers is
 * decided in [StudioLegControls]; this file only draws it.
 */
@Composable
internal fun StudioLegEditor(state: VoiceStudioUiState.Ready, enabled: Boolean, actions: VoiceStudioActions) {
    if (state.held.engine is StudioEngine.Chained) LegChoice(state, actions)
    StudioLegControls.pickers(state, actions).forEach { picker ->
        StudioPicker(
            label = picker.label,
            options = picker.options,
            selected = picker.selected,
            enabled = enabled,
            description = studioHandle(HANDLE_PICKER, picker.handle),
            onSelect = picker.onSelect,
        )
    }
    StudioLegControls.voice(state, actions)?.let { VoicePicker(it, enabled) }
    StudioTuningSection(state.leg, state, enabled, actions)
}

/** Which of a chain's four legs the editor is on. ⚠️ Allowed mid-save: it only chooses what to show. */
@Composable
private fun LegChoice(state: VoiceStudioUiState.Ready, actions: VoiceStudioActions) {
    val labels = state.studio.labels
    Eyebrow(labels.editLeg)
    Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        listOf(
            StudioLegEdits.EAR to labels.legs.stt,
            StudioLegEdits.TURN to labels.legs.turn,
            StudioLegEdits.BRAIN to labels.legs.llm,
            StudioLegEdits.MOUTH to labels.legs.tts,
        ).forEach { (leg, title) ->
            DistrictButton(
                text = title,
                onClick = @DontMemoize { actions.selectLeg(leg) },
                size = ButtonSize.Sm,
                variant = if (leg == state.leg) ButtonVariant.Primary else ButtonVariant.Secondary,
                modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_LEG, leg) },
            )
        }
    }
}

/** The held mouth's (or realtime model's) voices, under their group headings. */
@Composable
private fun VoicePicker(model: VoiceModel, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DistrictListRow(
            title = model.label,
            subtitle = model.selectedLabel,
            onClick = @DontMemoize { expanded = true }.takeIf { enabled },
            modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_PICKER, "voice") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = @DontMemoize { expanded = false }) {
            model.voices.forEachIndexed { index, (_, option) ->
                model.heading(index)?.let {
                    Eyebrow(it, modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter))
                }
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = @DontMemoize {
                        expanded = false
                        model.onSelect(option.value)
                    },
                    modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_OPTION, option.value) },
                )
            }
        }
    }
}

/**
 * One labelled list that opens a menu.
 *
 * ⚠️ A model option carries its channel in TEXT ("Gemini 3.8 Flash · Latest"), and a Preview
 * model its note, as the web's picker does; colour never carries the meaning alone.
 */
@Composable
internal fun StudioPicker(
    label: String,
    options: List<PickerOption>,
    selected: String?,
    enabled: Boolean,
    description: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DistrictListRow(
            title = label,
            subtitle = PickerOption.subtitle(options, selected),
            onClick = @DontMemoize { expanded = true }.takeIf { enabled },
            modifier = Modifier.semantics { contentDescription = description },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = @DontMemoize { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.text) },
                    onClick = @DontMemoize {
                        expanded = false
                        onSelect(option.value)
                    },
                    modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_OPTION, option.value) },
                )
            }
        }
    }
}
