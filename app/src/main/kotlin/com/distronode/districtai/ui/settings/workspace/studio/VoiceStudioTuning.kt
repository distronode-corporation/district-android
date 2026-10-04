package com.distronode.districtai.ui.settings.workspace.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.VoiceStudioTuningKey
import java.util.Locale

private const val MAIN_SECTION = "main"
private const val KEYTERM_LINES = 3

/**
 * A leg's tuning: the `main` controls on the editor itself ("Start speaking sooner"), the rest
 * behind Advanced, in the server's order, each only for the model that honours it.
 *
 * ⚠️ THE CONTROLS ARRIVE BUILT ([StudioTuningControls]), callbacks included, so nothing below
 * decides anything: it draws.
 */
@Composable
internal fun StudioTuningSection(
    leg: String,
    state: VoiceStudioUiState.Ready,
    enabled: Boolean,
    actions: VoiceStudioActions,
) {
    val (main, advanced) = StudioTuning.keysFor(leg, state.held.engine, state.studio)
        .mapNotNull { StudioTuningControls.of(it, state, actions) }
        .partition { it.key.section == MAIN_SECTION }
    main.forEach { TuningControlView(it, enabled) }
    if (advanced.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    DistrictListRow(
        title = state.studio.labels.advanced,
        subtitle = stringResource(
            if (open) R.string.voice_studio_advanced_hide else R.string.voice_studio_advanced_show,
        ),
        onClick = @DontMemoize { open = !open },
        modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_ADVANCED, leg) },
    )
    if (!open) return
    val interruptions = StudioTuningControls.firstInterruption(advanced)
    advanced.forEach { control ->
        if (control === interruptions) Eyebrow(state.studio.labels.interruptions)
        TuningControlView(control, enabled)
    }
}

/** ⚠️ An `if` chain rather than an exhaustive `when`, whose synthetic last arm no test can reach. */
@Composable
private fun TuningControlView(control: TuningControl, enabled: Boolean) {
    Column(
        modifier = Modifier.semantics(
            properties = @DontMemoize { contentDescription = studioHandle(HANDLE_TUNING, control.key.key) },
        ),
        verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.hairline),
    ) {
        if (control is TuningControl.Number) {
            NumberControl(control, enabled)
        } else if (control is TuningControl.Choice) {
            StudioPicker(
                label = control.key.label,
                options = control.options,
                selected = control.selected,
                enabled = enabled,
                description = studioHandle(HANDLE_PICKER, control.key.key),
                onSelect = control.onSelect,
            )
            control.key.description?.let { Caption(it) }
        } else if (control is TuningControl.Flag) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = control.checked,
                    onCheckedChange = control.onChange,
                    enabled = enabled,
                    modifier = Modifier.semantics(
                        properties = @DontMemoize {
                            contentDescription = studioHandle(HANDLE_CHECKBOX, control.key.key)
                        },
                    ),
                )
                TuningLabel(control.key)
            }
        } else {
            KeytermsControl(control as TuningControl.Lines, enabled)
        }
    }
}

/**
 * A number: the value and its slider while it has one, and, for a number that may be unset, the
 * "use the default" box that clears it.
 */
@Composable
private fun NumberControl(control: TuningControl.Number, enabled: Boolean) {
    val range = control.range
    val key = control.key.key
    TuningLabel(control.key)
    control.value?.let { value ->
        Text(
            text = tuningText(value, range.whole),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_VALUE, key) },
        )
        Slider(
            value = value.toFloat(),
            onValueChange = control.onSlide,
            valueRange = range.min.toFloat()..range.max.toFloat(),
            steps = range.steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = studioHandle(HANDLE_SLIDER, key) },
        )
    }
    control.onUseDefault?.let { onUseDefault ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = control.value == null,
                onCheckedChange = onUseDefault,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = studioHandle(HANDLE_DEFAULT, key) },
            )
            Text(
                text = range.useDefaultLabel ?: stringResource(R.string.voice_studio_use_default),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.foreground,
            )
        }
    }
}

/** Key terms, one per line. See [TuningControl.Lines.shown]. */
@Composable
private fun KeytermsControl(control: TuningControl.Lines, enabled: Boolean) {
    var typed by remember { mutableStateOf(control.terms.joinToString("\n")) }
    OutlinedTextField(
        value = control.shown(typed),
        onValueChange = @DontMemoize { text ->
            typed = text
            control.onText(text)
        },
        label = { Eyebrow(control.key.label) },
        colors = districtFieldColors(),
        enabled = enabled,
        minLines = KEYTERM_LINES,
        modifier = Modifier.fillMaxWidth().semantics(
            properties = @DontMemoize { contentDescription = studioHandle(HANDLE_KEYTERMS, control.key.key) },
        ),
    )
    control.key.description?.let { Caption(it) }
}

@Composable
private fun TuningLabel(key: VoiceStudioTuningKey) {
    Column {
        Text(key.label, style = MaterialTheme.typography.bodyMedium, color = DistrictTheme.colors.foreground)
        key.description?.let { Caption(it) }
    }
}

/**
 * A tuning value as the slider shows it: whole numbers, or two decimals.
 *
 * ⚠️ OUTSIDE ANY COMPOSABLE, in the device's own digits, like the meter's milliseconds. The
 * server's own sentences ("Use the default (0.30)") carry the portal locale's.
 */
private fun tuningText(value: Double, whole: Boolean): String =
    String.format(Locale.getDefault(), if (whole) "%.0f" else "%.2f", value)
