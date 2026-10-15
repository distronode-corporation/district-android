package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Token colours for a Material `Switch`. Pass it as `colors =` on every switch.
 *
 * ⛔ THE OFF STATE MUST BE VISIBLE. Material takes an unchecked switch's thumb and outline from the
 * scheme's `outline` (here `--border`) and its track from `surfaceContainerHighest` (`--elevated`),
 * which on this palette measured 1.04:1 thumb on track in dark and 1.41:1 in light (visual QA AB8):
 * an off switch read as an empty pill. WCAG 1.4.11 asks 3:1 for a control's parts, so the off thumb
 * and outline take `--muted-foreground` and the track the card it sits on.
 */
@Composable
fun districtSwitchColors(): SwitchColors = SwitchDefaults.colors(
    uncheckedThumbColor = DistrictTheme.colors.mutedForeground,
    uncheckedBorderColor = DistrictTheme.colors.mutedForeground,
    uncheckedTrackColor = DistrictTheme.colors.card,
)

/**
 * One choice of a single-choice list: a radio button and its label, the whole row the target.
 *
 * ⚠️ THE ROW IS `selectable`, NOT THE 24dp DOT. Material's single-choice pattern makes the label part
 * of the target, so the row is at least 48dp tall and a tap on the words selects (visual QA A-S4, the
 * knowledge mode radios measured 24x24dp). The radio itself takes no click of its own, so one choice
 * is one semantics node with the radio-button role.
 *
 * ⚠️ A COLUMN OF THESE, NOT A ROW OF BUTTONS, for a choice of a few words each. Three request kinds
 * as buttons in one row broke mid-word at 100% ("A sugges / tion") and pushed the form off a 200%
 * dialog (visual QA AD1).
 */
@Composable
fun DistrictRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RADIO_ROW_MIN_HEIGHT)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = DistrictTheme.colors.district,
                unselectedColor = DistrictTheme.colors.mutedForeground,
            ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
    }
}

private val RADIO_ROW_MIN_HEIGHT = 48.dp
