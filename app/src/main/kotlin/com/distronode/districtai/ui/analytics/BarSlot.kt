package com.distronode.districtai.ui.analytics

/**
 * A bar's horizontal position and width, in whatever units the caller passed to [barSlots].
 *
 * ⚠️ ITS OWN FILE ONLY BECAUSE detekt's `MatchingDeclarationName` REQUIRES IT — a file with a
 * single top-level classifier has to be named after it. It belongs with `AnalyticsChartMath.kt`
 * conceptually and is the only type that crosses that boundary.
 *
 * ⚠️ NOT A `Pair<Float, Float>`, which is what it started as. Two anonymous floats at a call site
 * that also handles heights, gaps and offsets is exactly where a left and a width get swapped, and
 * the result is a chart that draws — just wrongly.
 */
data class BarSlot(val left: Float, val width: Float)
