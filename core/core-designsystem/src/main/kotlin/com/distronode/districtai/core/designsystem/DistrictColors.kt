package com.distronode.districtai.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Ledger palette, ported from the web.
 *
 * ⛔ THIS REPLACES `dynamicDarkColorScheme`/`dynamicLightColorScheme`, AND THAT SWAP IS THE
 * WHOLE POINT OF THIS FILE. Android's dynamic colour derives a palette from the USER'S
 * WALLPAPER. On the emulator that produced a green-tinted near-black with a teal accent, so
 * the app's primary action was a colour that appears nowhere in District's identity and
 * changes per device. A brand is not allowed to be a function of the wallpaper.
 *
 * ⛔ THERE IS EXACTLY ONE ACCENT AND IT IS [DISTRICT]. The Ledger's neutrals are a teal-slate
 * ramp and the single accent sits on top of them as indigo in both modes (`#3f36e2` light,
 * `#7d84f7` dark). Nothing else in the palette is a brand colour: the status four
 * (success/warning/destructive/info) are state, not identity. Do not introduce a second one.
 *
 * ⚠️ VALUES ARE THE WEB'S, NOT APPROXIMATIONS OF IT. The source of truth is the web design
 * system's token stylesheet (`tokens.css`: `:root` is light, `[data-theme="dark"]` is dark).
 * A value changes THERE first and is then carried to the consumers, of which this is one; if
 * these drift, tokens.css wins. Its contrast pairs are held to WCAG AA by the web's contrast
 * audit, so a value edited here in isolation is unmeasured.
 *
 * ⚠️ [BACKGROUND] HAS A SECOND DEFINITION OUTSIDE KOTLIN. `ic_launcher_background` in
 * `app/src/main/res/values/colors.xml` is the adaptive-icon ground and carries the same hex
 * by hand, because a resource XML cannot read a Compose token. Move both or neither.
 *
 * ⚠️ DARK IS THE REAL THEME. The web is dark-first and the light palette exists for parity;
 * the same is true here, and `LocalDistrictColors` defaults to [Dark]. Light is not the dark
 * values lightened — the Ledger's two modes are separately authored (its own DESIGN.md says
 * "dark is a real palette, not an inversion"), which is why both are written out.
 *
 * ⚠️ ONE WEB TOKEN IS DELIBERATELY ABSENT. `--filled` (the web's primary-button and
 * table-header fill, `#10282c` / `#4a6d76`) has no Android counterpart because nothing here
 * reads it: `DistrictButton`'s `Primary` variant and the Material `primary` role both take
 * [district]. Add it only alongside the component that needs it, not speculatively.
 */
@Immutable
data class DistrictColors(
    /** The page. Deep teal ink in dark, pale slate in light — never pure black or white. */
    val background: Color,
    /** Default text. */
    val foreground: Color,
    /** Page chrome — app bars, sheets. One step above [background]. */
    val surface: Color,
    /** Panels and cards. One step above [surface]. */
    val card: Color,
    /** Solid subtle fill — input backgrounds, inert chips. */
    val muted: Color,
    /** Secondary text. Readable, not decorative. */
    val mutedForeground: Color,
    /** The calm 1px border that separates surfaces instead of a shadow. */
    val border: Color,
    /** Input border/fill, marginally lighter than [border]. */
    val input: Color,
    /** Raised/pressed surface above [card]. */
    val elevated: Color,
    /** ⛔ The single brand accent. Indigo in both modes. */
    val district: Color,
    /** Text ON [district]. ⚠️ Mode-dependent, and not by symmetry — see [DISTRICT_FOREGROUND]. */
    val districtForeground: Color,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val destructive: Color,
    val onDestructive: Color,
    val info: Color,
    val onInfo: Color,
) {
    companion object {
        // ── Dark, the primary theme ──────────────────────────────────────────
        // Every value below is `[data-theme="dark"]` in tokens.css, verbatim.

        /** `--background: #0e1c1f`. Also `ic_launcher_background` in `colors.xml`. */
        val BACKGROUND = Color(0xFF0E1C1F)

        /** `--foreground: #e9f0f1`. */
        val FOREGROUND = Color(0xFFE9F0F1)

        /** `--surface: #152528`. */
        val SURFACE = Color(0xFF152528)

        /** `--card: #16292d`. */
        val CARD = Color(0xFF16292D)

        /** `--muted: #1d3236`. */
        val MUTED = Color(0xFF1D3236)

        /** `--muted-foreground: #93a8ab`. */
        val MUTED_FOREGROUND = Color(0xFF93A8AB)

        /** `--border: #24393d`. */
        val BORDER = Color(0xFF24393D)

        /** `--input: #2a4146`. */
        val INPUT = Color(0xFF2A4146)

        /** `--elevated: #1f373b`. */
        val ELEVATED = Color(0xFF1F373B)

        /**
         * `--district: #7d84f7`.
         *
         * ⛔ The only brand colour in the app. The Material scheme's `ring`-equivalent
         * (focus indicator) resolves to this too, matching the web.
         */
        val DISTRICT = Color(0xFF7D84F7)

        /**
         * `--district-foreground: #0f1020`.
         *
         * ⚠️ DARK TEXT ON THE INDIGO, WHICH LOOKS WRONG UNTIL YOU TRY IT, and it is
         * mode-dependent rather than symmetric: dark mode's accent is a LIGHT indigo, so it
         * takes this near-black, while light mode's accent is a deep indigo and takes white
         * ([LIGHT_DISTRICT_FOREGROUND]). Both pairs are AA in the web's contrast audit.
         * Copying one mode's choice to the other inverts a measured decision.
         */
        val DISTRICT_FOREGROUND = Color(0xFF0F1020)

        /** `--success: #3fbf7f`. */
        val SUCCESS = Color(0xFF3FBF7F)

        /** `--warning: #fbbf24`. */
        val WARNING = Color(0xFFFBBF24)

        /** `--destructive: #f87171`. */
        val DESTRUCTIVE = Color(0xFFF87171)

        /** `--info: #60a5fa`. */
        val INFO = Color(0xFF60A5FA)

        /**
         * ⚠️ NOT A LEDGER TOKEN — THIS FILE'S OWN CONVENTION, KEPT. tokens.css defines no
         * `--success-foreground` or siblings, because the marketing site never puts text on
         * a status fill; Android does (badges, snackbars). The four dark status colours are
         * light saturated fills, so they take near-black ink and white on them is
         * unreadable. If tokens.css ever names these, it wins and this goes.
         */
        val ON_SEMANTIC_DARK = Color(0xFF000000)

        // ── Light, for parity ────────────────────────────────────────────────
        // Every value below is `:root` in tokens.css, verbatim.
        private val LIGHT_BACKGROUND = Color(0xFFE8EDEE)
        private val LIGHT_FOREGROUND = Color(0xFF10282C)
        private val LIGHT_SURFACE = Color(0xFFD6DFE1)
        private val LIGHT_CARD = Color(0xFFFFFFFF)
        private val LIGHT_MUTED = Color(0xFFF4F7F7)
        private val LIGHT_MUTED_FOREGROUND = Color(0xFF3F5A5E)
        private val LIGHT_BORDER = Color(0xFFC9D6D8)
        private val LIGHT_INPUT = Color(0xFFBFCED0)
        private val LIGHT_ELEVATED = Color(0xFFF7F9F9)

        /** `--district: #3f36e2` in light. Same family as [DISTRICT], far deeper for AA on white. */
        private val LIGHT_DISTRICT = Color(0xFF3F36E2)

        /** `--district-foreground: #ffffff` in light. */
        private val LIGHT_DISTRICT_FOREGROUND = Color(0xFFFFFFFF)
        private val LIGHT_SUCCESS = Color(0xFF167A4B)
        private val LIGHT_WARNING = Color(0xFF985305)
        private val LIGHT_DESTRUCTIVE = Color(0xFFC51F1F)
        private val LIGHT_INFO = Color(0xFF1B4FCA)

        /**
         * The light-mode half of the [ON_SEMANTIC_DARK] convention: the four light status
         * colours are dark saturated fills and take white ink.
         *
         * ⚠️ Held SEPARATELY from [LIGHT_DISTRICT_FOREGROUND] even though both are white
         * today. They answer different questions ("what goes on the accent" vs "what goes on
         * a status fill"), only one of them is a Ledger token, and sharing a constant would
         * make a future change to the accent silently repaint every badge.
         */
        private val ON_SEMANTIC_LIGHT = Color(0xFFFFFFFF)

        val Dark = DistrictColors(
            background = BACKGROUND,
            foreground = FOREGROUND,
            surface = SURFACE,
            card = CARD,
            muted = MUTED,
            mutedForeground = MUTED_FOREGROUND,
            border = BORDER,
            input = INPUT,
            elevated = ELEVATED,
            district = DISTRICT,
            districtForeground = DISTRICT_FOREGROUND,
            success = SUCCESS,
            onSuccess = ON_SEMANTIC_DARK,
            warning = WARNING,
            onWarning = ON_SEMANTIC_DARK,
            destructive = DESTRUCTIVE,
            onDestructive = ON_SEMANTIC_DARK,
            info = INFO,
            onInfo = ON_SEMANTIC_DARK,
        )

        val Light = DistrictColors(
            background = LIGHT_BACKGROUND,
            foreground = LIGHT_FOREGROUND,
            surface = LIGHT_SURFACE,
            card = LIGHT_CARD,
            muted = LIGHT_MUTED,
            mutedForeground = LIGHT_MUTED_FOREGROUND,
            border = LIGHT_BORDER,
            input = LIGHT_INPUT,
            elevated = LIGHT_ELEVATED,
            district = LIGHT_DISTRICT,
            districtForeground = LIGHT_DISTRICT_FOREGROUND,
            success = LIGHT_SUCCESS,
            onSuccess = ON_SEMANTIC_LIGHT,
            warning = LIGHT_WARNING,
            onWarning = ON_SEMANTIC_LIGHT,
            destructive = LIGHT_DESTRUCTIVE,
            onDestructive = ON_SEMANTIC_LIGHT,
            info = LIGHT_INFO,
            onInfo = ON_SEMANTIC_LIGHT,
        )
    }
}

/**
 * The Material 3 scheme derived from [DistrictColors].
 *
 * ⚠️ EXISTS BECAUSE MATERIAL COMPONENTS READ `MaterialTheme.colorScheme`, NOT OUR TOKENS. A
 * `TextField`, `Snackbar` or `AlertDialog` used anywhere in the app resolves its colours
 * from the scheme, so leaving it at the Material baseline would produce purple-tinted
 * defaults next to correctly-themed custom components — the worst of both. Mapping our
 * tokens onto the scheme means an un-restyled Material component still lands in the palette.
 *
 * ⚠️ The mapping is deliberately lossy in one direction only: `surfaceVariant`/`outline` and
 * friends get our nearest equivalent rather than a new invented value, because inventing one
 * would create a token that exists on Android and nowhere on the web.
 */
internal fun DistrictColors.toMaterialScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = district,
        onPrimary = districtForeground,
        // A 12%-alpha accent fill is the web's container treatment for badges and the
        // active sidebar item, so the "container" role maps to that rather than to a
        // separate tone Material would otherwise generate.
        primaryContainer = district.copy(alpha = ACCENT_CONTAINER_ALPHA),
        onPrimaryContainer = district,
        secondary = district,
        onSecondary = districtForeground,
        tertiary = district,
        onTertiary = districtForeground,
        background = background,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        surfaceVariant = muted,
        onSurfaceVariant = mutedForeground,
        // Material's `surfaceContainer*` family drives Card/Sheet/Menu backgrounds.
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = card,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = elevated,
        outline = border,
        outlineVariant = border,
        error = destructive,
        onError = onDestructive,
        errorContainer = destructive.copy(alpha = ACCENT_CONTAINER_ALPHA),
        onErrorContainer = destructive,
        inverseSurface = foreground,
        inverseOnSurface = background,
    )
}

/**
 * The web's `/12` alpha, used for every tinted container: `bg-district/12`,
 * `bg-success/12`, `bg-destructive/12`. 12% of 255 ≈ 31.
 */
internal const val ACCENT_CONTAINER_ALPHA = 0.12f
