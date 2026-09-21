package com.distronode.districtai.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Motion, ported from the web.
 *
 * ⚠️ The easing is the curve `DistrictLayoutShell` uses for page transitions,
 * `cubic-bezier(0.16, 1, 0.3, 1)` — a strong expo-out. It starts fast and settles slowly, which
 * is what makes a transition feel responsive rather than merely animated.
 *
 * ⚠️ The control points are named constants rather than inline literals. detekt's `MagicNumber`
 * rule is active here and would reject them inline, which is the right call for once: `0.16f` and
 * `0.3f` are meaningless without the name, and someone tuning the curve needs to know which
 * number is the OUT control point.
 */
object DistrictMotion {

    // The two Bézier control points of the web's expo-out curve.
    private const val EASE_OUT_X1 = 0.16f
    private const val EASE_OUT_Y1 = 1.0f
    private const val EASE_OUT_X2 = 0.3f
    private const val EASE_OUT_Y2 = 1.0f

    /** The app's one easing curve. Anything that moves uses this. */
    val Emphasized: Easing = CubicBezierEasing(EASE_OUT_X1, EASE_OUT_Y1, EASE_OUT_X2, EASE_OUT_Y2)

    /** The web's page-transition duration. */
    const val TRANSITION_MS: Int = 400

    /** Colour and state changes — the web's `transition-colors duration-200`. */
    const val STATE_MS: Int = 200
}
