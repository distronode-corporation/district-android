package com.distronode.districtai.core.designsystem

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable

/**
 * Token colours for a Material `OutlinedTextField`. Pass it as `colors =` on every text field.
 *
 * ⚠️ MATERIAL'S DEFAULTS ARE THE WRONG PANEL. A field left on `OutlinedTextFieldDefaults.colors()`
 * draws its container and placeholder greys from the Material baseline rather than from
 * `--muted`/`--muted-foreground`, so it is subtly off on a correctly-themed screen.
 *
 * ⛔ ONE COPY, HERE, ON PURPOSE. It used to be defined four times and inlined three more, and the
 * copies had already drifted: only one set [TextFieldColors.disabledTextColor], so a field disabled
 * mid-save read differently from screen to screen, and seven more files' fields used no token colours
 * at all. A token change is now one edit.
 */
@Composable
fun districtFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = DistrictTheme.colors.muted,
    unfocusedContainerColor = DistrictTheme.colors.muted,
    disabledContainerColor = DistrictTheme.colors.muted,
    focusedBorderColor = DistrictTheme.colors.district,
    unfocusedBorderColor = DistrictTheme.colors.border,
    focusedTextColor = DistrictTheme.colors.foreground,
    unfocusedTextColor = DistrictTheme.colors.foreground,
    disabledTextColor = DistrictTheme.colors.mutedForeground,
    cursorColor = DistrictTheme.colors.district,
)
