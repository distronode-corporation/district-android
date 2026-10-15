package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.designsystem.districtSwitchColors

/**
 * A labelled text field with its hint and the reason it was refused, the shape of district-ios's
 * `SchedulingWriteField`. ⚠️ The hint gives way to the refusal rather than stacking under it.
 */
@Composable
fun SchedulingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    tag: String,
    enabled: Boolean = true,
    hint: String? = null,
    error: String? = null,
    kind: SchedulingFieldKind = SchedulingFieldKind.TEXT,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Eyebrow(label) },
            colors = districtFieldColors(),
            singleLine = kind.singleLine,
            minLines = if (kind.singleLine) 1 else MULTILINE_MIN_LINES,
            enabled = enabled,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = kind.keyboard, autoCorrectEnabled = !kind.secret),
            visualTransformation = if (kind.secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
        if (error == null) hint?.let { SchedulingHint(it) }
        SchedulingRejection(error)
    }
}

/** A label with a switch. */
@Composable
fun SchedulingToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = districtSwitchColors(),
            modifier = Modifier.testTag(tag),
        )
    }
}

/**
 * One choice among a few, as a row of buttons with the chosen one filled: a booking view, a day,
 * a slot, a host. ⚠️ The chosen one is marked `selected` for TalkBack, not only by its colour.
 *
 * @param options value to label, in display order.
 * @param tagPrefix each button's test tag is the prefix, a dash and the option's value.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SchedulingChoiceRow(
    options: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    enabled: Boolean = true,
    label: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        label?.let { Eyebrow(it) }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            options.forEach { (value, text) ->
                val chosen = value == selected
                DistrictButton(
                    text = text,
                    onClick = { onSelect(value) },
                    variant = if (chosen) ButtonVariant.Primary else ButtonVariant.Secondary,
                    size = ButtonSize.Sm,
                    enabled = enabled,
                    modifier = Modifier
                        .semantics { this.selected = chosen }
                        .testTag("$tagPrefix-$value"),
                )
            }
        }
    }
}

private const val MULTILINE_MIN_LINES = 3
