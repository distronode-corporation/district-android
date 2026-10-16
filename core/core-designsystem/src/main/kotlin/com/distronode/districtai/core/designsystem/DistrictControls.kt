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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color

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

/**
 * A filter chip: an outlined chip that fills with the accent and shows a check when chosen.
 *
 * ⛔ A FILTER MUST LOOK LIKE ONE, CHOSEN OR NOT. The desk's status filters were Ghost buttons, so with
 * nothing chosen « Ouvert 1 / En attente 1 / Réglé 1 » read as plain text, with no outline and no
 * selected state (3.0.1 review). The outline is `--muted-foreground` so it holds 3:1 against the page in
 * both modes; the chosen chip adds the accent fill, the accent ink and a check, so the state does not
 * rest on colour alone. Material's chip reserves the 48dp touch target and reports `selected`.
 */
@Composable
fun DistrictFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DistrictTheme.colors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text = label, style = MaterialTheme.typography.labelLarge) },
        modifier = modifier,
        leadingIcon = if (selected) {
            { Icon(DistrictIcons.Check, contentDescription = null, modifier = Modifier.size(CHIP_ICON_SIZE)) }
        } else {
            null
        },
        shape = RoundedCornerShape(CHIP_RADIUS),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = colors.foreground,
            selectedContainerColor = colors.district.copy(alpha = ACCENT_CONTAINER_ALPHA),
            selectedLabelColor = colors.district,
            selectedLeadingIconColor = colors.district,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = colors.mutedForeground,
            selectedBorderColor = colors.district,
        ),
    )
}

private val CHIP_RADIUS = 8.dp
private val CHIP_ICON_SIZE = 18.dp
