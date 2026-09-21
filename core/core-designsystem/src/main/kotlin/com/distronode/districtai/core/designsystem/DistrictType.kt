package com.distronode.districtai.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Geist, the family the web actually ships.
 *
 * ⛔ BUNDLED AS STATIC TTFs RATHER THAN DOWNLOADED. The web loads Geist through
 * `next/font/google`, which produces woff2, a format Android cannot use. The website already
 * vendors the TTFs (for server-side card composition, because `next/og` cannot consume woff2
 * either), so these three files are a copy of an asset the project already vendors, not a new
 * dependency.
 *
 * ⚠️ THREE WEIGHTS, NOT THE VARIABLE FONT. next/font pulls Geist's full 100–900 axis because
 * no `weight` array is given; only Regular, SemiBold and Bold exist as static files. Compose
 * synthesises anything else, so asking for [FontWeight.Medium] gets faux-bold interpolation
 * rather than a real 500 cut. Every style below therefore uses 400/600/700 ONLY — if a design
 * wants Medium, add the TTF rather than letting the rasteriser invent it.
 *
 * ⚠️ OFL-1.1. `res/raw/license_geist.txt` ships the licence text with the binary, which is
 * what that licence requires. It is in `res/raw` and not `res/font` because aapt2 rejects a
 * non-font file in a font directory.
 */
private val Geist = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

/**
 * ⚠️ THE SYSTEM MONOSPACE, DELIBERATELY, AND IT IS THE ONE PLACE THIS PORT DIVERGES. The web's
 * eyebrow label uses Geist Mono, which the repo does NOT vendor — only the sans TTFs are
 * present. Shipping a second ~73 KB face for one 10sp micro-label was not worth it, and
 * downloading it at runtime would make the most recognisable element of the identity depend on
 * the network. Android's monospace (Roboto Mono on most devices) carries the same
 * wide-tracked-uppercase effect. Revisit if Geist Mono is ever vendored.
 */
private val Mono = FontFamily.Monospace

/**
 * The type styles the web has and Material 3 does not.
 *
 * ⚠️ A SEPARATE HOLDER RATHER THAN ABUSED MATERIAL SLOTS. `labelSmall` could have carried the
 * eyebrow, but then any Material component reaching for `labelSmall` would render as a
 * wide-tracked uppercase mono label. Material's slots keep Material's meanings; ours live here.
 */
@Immutable
data class DistrictTextStyles(
    /**
     * ⛔ THE SIGNATURE TYPOGRAPHIC MOVE OF THE BRAND, and the most copied string in the web
     * codebase: `font-mono text-[10px] uppercase tracking-[0.18em] text-muted-foreground`.
     * Used for every section and field label. `0.18em` is expressed in `em` here, exactly as
     * on the web, so it scales with the font size instead of being frozen at 1.8sp.
     *
     * ⚠️ UPPERCASING IS THE CALLER'S JOB — CSS `text-transform` has no Compose equivalent in a
     * TextStyle. The [Eyebrow] composable does it, which is the reason to use that rather than
     * this style directly.
     */
    val eyebrow: TextStyle,
    /** The large number on a metric card. Tight tracking, so big digits do not look loose. */
    val metric: TextStyle,
)

/**
 * Material 3's slots, filled with Geist and the web's scale.
 *
 * ⚠️ SIZES COME FROM REAL COMPONENTS, NOT FROM TAILWIND'S DEFAULT TABLE. The web sets no custom
 * type scale, so the anchors are the components themselves: `PageHeader` is
 * `text-2xl md:text-3xl font-semibold tracking-tight`, the login card title is
 * `text-3xl md:text-4xl font-semibold`, buttons are `text-sm`/`text-xs`, inputs are
 * `text-base font-light`, and the badge is `text-xs font-medium`.
 *
 * ⚠️ `tracking-tight` is -0.025em, applied to headings only. Compose takes `letterSpacing` as a
 * TextUnit, and `em` is used rather than `sp` so it tracks the size the way the CSS does.
 *
 * ⚠️ Where the web uses `font-light` (300) for input text, this uses Normal (400): 300 is not
 * one of the three vendored cuts and would be synthesised.
 */
internal val DistrictTypography = Typography(
    // Login-card scale headline. The web's md:text-4xl = 36px.
    displaySmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.025).em,
    ),
    // PageHeader at md — text-3xl = 30px.
    headlineMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.025).em,
    ),
    // PageHeader base — text-2xl = 24px. The most-used title size in the product.
    headlineSmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.025).em,
    ),
    titleLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.0125).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // text-base, the reading size. Inputs and body copy.
    bodyLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    // text-sm, the default for secondary copy and list subtitles.
    bodyMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // text-xs.
    bodySmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Button md: h-10 px-4 text-sm font-medium -> SemiBold here, see the family note.
    labelLarge = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Button sm and Badge: text-xs font-medium.
    labelMedium = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

internal val DistrictExtraTextStyles = DistrictTextStyles(
    eyebrow = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        // The web's tracking-[0.18em]. At 10sp that is 1.8sp of extra letter spacing.
        letterSpacing = 0.18.em,
    ),
    metric = TextStyle(
        fontFamily = Geist,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.025).em,
        textAlign = TextAlign.Start,
    ),
)
