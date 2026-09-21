package com.distronode.districtai.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The 4dp grid the web's spacing scale is built on (`--spacing: 0.25rem`).
 *
 * ⚠️ NAMED BY ROLE, NOT BY SIZE. `xs`/`sm`/`md` would just be a slower way of writing numbers.
 * These names say where a value belongs, so a screen that needs "the gap between cards" cannot
 * accidentally use the row's inner padding and drift from every other screen.
 */
@Immutable
data class DistrictSpacing(
    /** Between a label and the thing it labels. */
    val hairline: Dp = 4.dp,
    /** Between tightly-related items inside a row. */
    val tight: Dp = 8.dp,
    /** Between stacked text lines in a list row. */
    val row: Dp = 12.dp,
    /** The screen's horizontal inset, and a card's inner padding. */
    val gutter: Dp = 16.dp,
    /** Card padding on the web (`px-5`). */
    val card: Dp = 20.dp,
    /** Between sibling cards and list sections. */
    val section: Dp = 24.dp,
    /** Below a page header (`mb-8`). */
    val header: Dp = 32.dp,
)

/**
 * The web's radius conventions, which are consistent enough to be tokens.
 *
 * `rounded-md` 6dp badges · `rounded-lg` 8dp buttons, inputs, nav items · `rounded-xl` 12dp
 * cards and panels · `rounded-t-2xl` 16dp bottom sheets · `rounded-full` pills.
 *
 * ⚠️ Mapped onto Material's [Shapes] slots as well, because Material components read those.
 * `extraSmall` deliberately gets the badge radius and `medium` the card radius, so an
 * un-restyled `Card` or `AlertDialog` still lands on the right corner.
 */
internal val DistrictShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

internal val LocalDistrictColors = staticCompositionLocalOf { DistrictColors.Dark }
internal val LocalDistrictSpacing = staticCompositionLocalOf { DistrictSpacing() }
internal val LocalDistrictTextStyles = staticCompositionLocalOf { DistrictExtraTextStyles }

/**
 * Accessors for the tokens Material does not carry.
 *
 * ⚠️ An object with the same name as the [DistrictTheme] composable, which is legal and is
 * exactly how `MaterialTheme` does it — `MaterialTheme.colorScheme` beside
 * `MaterialTheme { }`. Keeping the shape familiar matters more than avoiding the shadowing.
 *
 * ⚠️ `@ReadOnlyComposable` on each: these only READ composition locals and never emit or
 * remember, so the compiler can skip generating a restart group for the call. On a token
 * accessor read dozens of times per frame that is not micro-optimisation.
 */
object DistrictTheme {
    val colors: DistrictColors
        @Composable @ReadOnlyComposable
        get() = LocalDistrictColors.current

    val spacing: DistrictSpacing
        @Composable @ReadOnlyComposable
        get() = LocalDistrictSpacing.current

    /** [DistrictTextStyles.eyebrow] and friends — the styles Material has no slot for. */
    val text: DistrictTextStyles
        @Composable @ReadOnlyComposable
        get() = LocalDistrictTextStyles.current
}

/**
 * The app theme.
 *
 * ⛔ NO DYNAMIC COLOUR, AND LEAVING IT OUT IS THE WHOLE POINT OF THIS MODULE.
 * `dynamicDarkColorScheme(context)` / `dynamicLightColorScheme(context)` on API 31+ derive a
 * palette from the USER'S WALLPAPER. On the emulator that produced a green-tinted near-black
 * page with a teal primary, so the app's principal action was a colour that appears nowhere in
 * District's identity, and a different colour on every device.
 * A brand cannot be a function of the wallpaper. See [DistrictColors].
 *
 * ⚠️ DARK-FIRST, MATCHING THE WEB. [darkTheme] follows the system so a light-mode user is not
 * ambushed, but dark is the designed state and the one every value was contrast-checked in.
 *
 * ⚠️ Both the Material scheme AND the extended tokens are provided. Material components read
 * `MaterialTheme.colorScheme`; our primitives read [DistrictTheme.colors]. Providing only one
 * would leave the other half of the UI on Material's baseline purple.
 */
@Composable
fun DistrictTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DistrictColors.Dark else DistrictColors.Light

    CompositionLocalProvider(
        LocalDistrictColors provides colors,
        LocalDistrictSpacing provides DistrictSpacing(),
        LocalDistrictTextStyles provides DistrictExtraTextStyles,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(dark = darkTheme),
            typography = DistrictTypography,
            shapes = DistrictShapes,
            content = content,
        )
    }
}
