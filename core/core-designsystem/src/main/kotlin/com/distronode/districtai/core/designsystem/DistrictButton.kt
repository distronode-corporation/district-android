package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The four button variants, ported from `buttonClasses.ts`.
 *
 * ⛔ `Primary` PUTS DARK TEXT ON THE INDIGO, WHICH LOOKS WRONG UNTIL YOU CHECK THE CONTRAST.
 * `--district-foreground` is `240 20% 8%` — near-black. The accent is a LIGHT indigo (70%
 * lightness), so white on it fails WCAG AA while near-black passes comfortably. Every filled
 * primary button on the web reads this way; it is a contrast decision, not a style flourish.
 *
 * ⚠️ `Danger` IS A TINTED OUTLINE, NOT A RED FILL. The web uses
 * `bg-destructive/10 text-destructive border-destructive/25`, because a solid red fill reads as
 * "this already went wrong" rather than "this action is destructive". Reserve the solid
 * destructive colour for actual error surfaces.
 */
enum class ButtonVariant { Primary, Secondary, Ghost, Danger }

/**
 * `Md` is the default (40dp, 14sp); `Sm` (32dp, 12sp) is for dense rows and toolbars.
 *
 * ⚠️ 32dp is BELOW Material's 48dp touch-target guidance, and it is on the web's spec, so `Sm` is
 * for pointer-dense surfaces only. Anything a thumb reaches for should be `Md`.
 */
enum class ButtonSize { Sm, Md }

/**
 * A District button.
 *
 * ⚠️ Wraps Material's [Button] rather than a bare `Box`, so it keeps the ripple, the disabled
 * semantics, the minimum touch target and the focus handling for free. Only the colours, shape
 * and metrics are overridden.
 */
@Composable
fun DistrictButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Md,
    enabled: Boolean = true,
) {
    val colors = DistrictTheme.colors

    val container: Color
    val ink: Color
    var border: BorderStroke? = null
    when (variant) {
        ButtonVariant.Primary -> {
            container = colors.district
            ink = colors.districtForeground
        }
        ButtonVariant.Secondary -> {
            container = colors.card
            ink = colors.foreground
            border = BorderStroke(1.dp, colors.border)
        }
        ButtonVariant.Ghost -> {
            container = Color.Transparent
            ink = colors.mutedForeground
        }
        ButtonVariant.Danger -> {
            container = colors.destructive.copy(alpha = 0.10f)
            ink = colors.destructive
            border = BorderStroke(1.dp, colors.destructive.copy(alpha = 0.25f))
        }
    }

    val height = if (size == ButtonSize.Sm) 32.dp else 40.dp
    val horizontal = if (size == ButtonSize.Sm) 12.dp else 16.dp
    val style = if (size == ButtonSize.Sm) {
        MaterialTheme.typography.labelMedium
    } else {
        MaterialTheme.typography.labelLarge
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = border,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = ink,
            // ⚠️ The web's `disabled:opacity-50` applied to BOTH halves. Material would otherwise
            // substitute its own greys, which on a near-black surface become invisible rather
            // than merely quiet.
            disabledContainerColor = container.copy(alpha = 0.5f),
            disabledContentColor = ink.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = horizontal),
        // ⚠️ `defaultMinSize` rather than `height`: a fixed height would clip a label that wraps
        // at a large font scale, and this app declares no maximum text size.
        modifier = modifier.defaultMinSize(minHeight = height),
    ) {
        Text(text = text, style = style)
    }
}
