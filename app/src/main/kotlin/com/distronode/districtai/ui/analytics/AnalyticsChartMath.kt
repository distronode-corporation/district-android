package com.distronode.districtai.ui.analytics

/**
 * The arithmetic behind the analytics charts, deliberately kept OUT of the drawing code.
 *
 * ⛔ EXTRACTED SO THE DEGENERATE CASES CAN BE TESTED AT ALL. Every function here divides by
 * something derived from server data, and the analytics route is documented to return series that
 * are entirely zero — a workspace with no calls gets seven zero-valued points rather than an empty
 * list, so "no data" arrives as `[0, 0, 0, 0, 0, 0, 0]` and not as an absence. Inside a
 * `DrawScope` that division is reachable only by rendering, on a device, with exactly the wrong
 * data; the resulting `NaN` then propagates into a rect offset and either draws nothing or throws
 * far from its cause. As plain functions the same inputs are three lines of unit test.
 *
 * ⚠️ NO COMPOSE TYPES CROSS THIS BOUNDARY — not `Dp`, not `Color`, not `Size`. Floats and Longs
 * only, so these are ordinary JVM unit tests with no Robolectric environment and no composition.
 * That is why [parseHexArgb] returns a packed `Long` rather than a `Color`: the caller wraps it.
 */

/**
 * Scale a series into fractions of its own maximum, for a bar or line chart.
 *
 * ⛔ AN ALL-ZERO SERIES YIELDS ALL ZEROS RATHER THAN `NaN`, and that is the whole reason this is a
 * function. `value / max` with `max == 0` is the classic division-by-zero here, and in floating
 * point it does not throw — it produces `NaN`, which silently poisons every geometry calculation
 * downstream and draws an empty chart that looks like a rendering bug.
 *
 * ⚠️ NEGATIVES ARE CLAMPED TO ZERO rather than rejected. Nothing in the contract can produce a
 * negative call count, so a negative is corrupt data — and a chart that refuses to draw is a worse
 * answer than one that draws the corrupt point at the floor.
 */
fun normalizedHeights(values: List<Int>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.max().coerceAtLeast(0)
    if (max == 0) return values.map { 0f }
    return values.map { it.coerceAtLeast(0).toFloat() / max.toFloat() }
}

/**
 * Lay [count] evenly spaced bars across [width], separated by [gap].
 *
 * ⚠️ THE GAPS COME OUT OF THE AVAILABLE WIDTH, NOT OUT OF THE MARGINS. With `n` bars there are
 * `n - 1` gaps, so a 90-day window (13 weekly buckets) on a narrow phone can ask for more gap than
 * there is room for. That case yields ZERO-WIDTH bars rather than negative ones: a negative width
 * is not a drawing error in `DrawScope`, it simply draws nothing, so the failure would present as
 * a blank chart with no clue why.
 *
 * ⚠️ A non-finite or non-positive [width] yields zero-width slots at the origin. Compose measures
 * a Canvas before it has a size on the very first frame, so this is reached in normal operation
 * and not only under corruption.
 */
fun barSlots(count: Int, width: Float, gap: Float): List<BarSlot> {
    if (count <= 0) return emptyList()
    if (!width.isFinite() || width <= 0f) return List(count) { BarSlot(0f, 0f) }

    val safeGap = if (gap.isFinite()) gap.coerceAtLeast(0f) else 0f
    val totalGap = safeGap * (count - 1)
    val usable = width - totalGap
    val barWidth = if (usable > 0f) usable / count else 0f
    val stride = barWidth + safeGap
    return List(count) { index -> BarSlot(left = index * stride, width = barWidth) }
}

/**
 * Each value's share of the total, for a proportional (stacked) bar.
 *
 * ⛔ A TOTAL OF ZERO YIELDS ALL ZEROS, NOT AN EVEN SPLIT. The sentiment breakdown always carries
 * its three bands even for a workspace with no calls, so this is reached on every brand-new
 * account. An even split would be the tempting fallback and it would be a lie: it draws a
 * confident three-way sentiment analysis of nothing. Zeros let the screen render an empty track
 * instead, which is what "no calls yet" looks like.
 */
fun proportions(values: List<Int>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val total = values.sumOf { it.coerceAtLeast(0) }
    if (total <= 0) return values.map { 0f }
    return values.map { it.coerceAtLeast(0).toFloat() / total.toFloat() }
}

/**
 * Parse a server-supplied hex colour into packed ARGB, or null if it cannot be read.
 *
 * ⛔ LENIENT AND NULLABLE ON PURPOSE. `SentimentSlice.color` is chosen server-side and nothing
 * constrains its format; the caller falls back to a theme token on null. Throwing — or modelling
 * this as a colour in the DTO — would let a presentational detail fail the whole analytics
 * response, losing every metric on the screen over a shade.
 *
 * Accepts `#rgb`, `#rrggbb` and `#aarrggbb`, with or without the leading `#`. A six-digit value is
 * assumed fully opaque, which is what every value the server currently sends is.
 */
fun parseHexArgb(hex: String): Long? {
    val digits = hex.trim().removePrefix("#")
    if (digits.isEmpty() || !digits.all { it.isDigit() || it.lowercaseChar() in HEX_LETTERS }) {
        return null
    }
    val expanded = when (digits.length) {
        SHORTHAND_LENGTH -> digits.map { "$it$it" }.joinToString("")
        RGB_LENGTH, ARGB_LENGTH -> digits
        else -> return null
    }
    // ⚠️ `toLong`, not `toLongOrNull`: every character was checked as a hex digit above and there
    // are at most eight of them, which always fits, so a null here could never happen.
    val value = expanded.toLong(HEX_RADIX)
    return if (expanded.length == ARGB_LENGTH) value else value or OPAQUE_ALPHA
}

/**
 * Seconds as the SHORT duration form, matching the web's KPI tiles.
 *
 * ⛔ TWO DURATION FORMATS SHIP IN THIS PRODUCT AND THEY DISAGREE ON THE SAME INPUT: a tile omits a
 * zero minutes component ("45s") while a call row always emits one ("0m 45s"). This is the TILE
 * form, because that is what `AnalyticsMetrics.avgDuration` feeds. The call feed does not use this
 * — its durations arrive pre-formatted from the server precisely so the client cannot pick the
 * wrong one.
 *
 * ⚠️ A negative reads as zero rather than as "-1m 30s". The value is a rounded average of
 * non-negative durations, so a negative is corrupt input, and a minus sign in a KPI tile reads as
 * a measurement rather than as a fault.
 */
fun formatDurationSeconds(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val minutes = safe / SECONDS_PER_MINUTE
    val remainder = safe % SECONDS_PER_MINUTE
    return if (minutes > 0) "${minutes}m ${remainder}s" else "${remainder}s"
}

/**
 * A metered amount, as a quantity rather than as a raw `Double`.
 *
 * ⛔ THESE ARE GENUINELY FRACTIONAL — call minutes are summed as floats server-side — so a naive
 * `toInt()` would truncate a billing figure, and a bare `toString()` renders "412.0" for a whole
 * number of SMS. Whole values therefore print whole and fractional ones keep two decimals.
 *
 * ⚠️ Built from `Double.toString`, which always emits a `.` regardless of locale, rather than from
 * `String.format`, which does not. A comma decimal separator here would be defensible for display
 * and is not what the assertions or the web console show.
 */
fun formatUsageAmount(value: Double): String {
    if (!value.isFinite()) return UNKNOWN_AMOUNT
    val rounded = Math.round(value * CENTS) / CENTS
    return if (rounded == Math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        rounded.toString().trimEnd('0').trimEnd('.')
    }
}

/**
 * Scale a series of METERED amounts into fractions of its own maximum.
 *
 * ⛔ THE `Double?` IS THE WHOLE REASON THIS EXISTS BESIDE [normalizedHeights]. A usage metric that
 * was never metered has NO KEY on the wire, which is a different fact from one measured at zero —
 * see `UsageData`. Both draw a zero-width bar, because there is no honest bar to draw for an
 * absence; what must not happen is the ABSENCE being carried forward as a number. The caller
 * therefore renders the LABEL from the nullable value and only the geometry from this.
 *
 * ⛔ AND AN ALL-ABSENT (or all-zero) SERIES YIELDS ZEROS RATHER THAN `NaN`. A workspace whose only
 * metered month recorded nothing but a phone-number count produces exactly that input, and
 * `value / 0.0` does not throw in floating point — it poisons every offset downstream and draws a
 * chart that looks like a rendering bug.
 *
 * ⚠️ NON-FINITE VALUES ARE TREATED AS ABSENT, in both the maximum and the output. A NaN admitted
 * into the maximum makes every comparison against it false, so the entire series would scale to
 * zero and the card would silently show flat bars over real usage.
 */
fun normalizedAmounts(values: List<Double?>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val max = values.mapNotNull { it?.takeIf(Double::isFinite) }.maxOrNull()?.coerceAtLeast(0.0)
        ?: 0.0
    if (max <= 0.0) return values.map { 0f }
    return values.map { value ->
        val safe = value?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        (safe / max).toFloat()
    }
}

/**
 * Add up several metered amounts, keeping "none of them was metered" distinct from "they summed
 * to zero".
 *
 * ⛔ NULL IN EVERY POSITION IS NULL OUT, NOT `0.0`, AND THAT IS THE ENTIRE POINT. Defaulting each
 * absent metric to zero would let a month with no messaging rows at all render "0 messages" — a
 * billing-shaped claim that was never measured. Null propagates to the "—" the card draws instead.
 *
 * ⚠️ A PARTIAL SUM IS A REAL ANSWER AND IS LABELLED AS ONE. If inbound minutes are metered and
 * outbound are absent, the total is the inbound figure — which is why the caller's label says
 * METERED rather than "total call minutes". An absent side contributes nothing; it is not being
 * asserted to have been zero.
 */
fun sumMetered(vararg values: Double?): Double? {
    var total: Double? = null
    for (value in values) {
        if (value == null || !value.isFinite()) continue
        total = (total ?: 0.0) + value
    }
    return total
}

/**
 * A `YYYY-MM` usage month as a short display label.
 *
 * ⛔ A FIXED LOOKUP, NOT `java.time` AND NOT A LOCALE FORMATTER. `UsageData.month` is a calendar
 * key the server builds from UTC month boundaries — it is not an instant and it has no timezone —
 * so parsing it into a date and formatting it back is how a month silently shifts by one for a
 * reader west of UTC. It also cannot fail: a formatter throws on a key it does not recognise and
 * would take out the whole card.
 *
 * ⚠️ ANYTHING THIS DOES NOT RECOGNISE IS RETURNED VERBATIM. A month key the server changed the
 * shape of should render as itself rather than as a blank or a guess — the raw `2026-08` is still
 * a true statement about which month the row is, which "Unknown" is not.
 */
fun monthLabel(month: String): String {
    val parts = month.split('-')
    if (parts.size != MONTH_KEY_PARTS) return month
    val (year, ordinal) = parts
    if (year.length != YEAR_DIGITS || !year.all { it.isDigit() }) return month
    val index = ordinal.toIntOrNull() ?: return month
    if (index !in 1..MONTH_NAMES.size) return month
    return "${MONTH_NAMES[index - 1]} $year"
}

private const val UNKNOWN_AMOUNT = "—"
private const val CENTS = 100.0
private const val MONTH_KEY_PARTS = 2
private const val YEAR_DIGITS = 4

/**
 * ⚠️ IN THIS FILE RATHER THAN IN `strings.xml`, deliberately: this is a wire-format decoding table
 * keyed by an integer the SERVER chose, not user-facing copy with a translation. The app ships one
 * locale (`values/` only), and a month name pulled through a `Context` would drag composition into
 * a pure function that is unit-tested without one.
 */
private val MONTH_NAMES = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)
private const val HEX_RADIX = 16
private const val SHORTHAND_LENGTH = 3
private const val RGB_LENGTH = 6
private const val ARGB_LENGTH = 8
private const val OPAQUE_ALPHA = 0xFF000000L
private const val SECONDS_PER_MINUTE = 60
private val HEX_LETTERS = 'a'..'f'
