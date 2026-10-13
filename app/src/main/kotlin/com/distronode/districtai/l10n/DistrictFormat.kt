package com.distronode.districtai.l10n

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs

/**
 * Money, dates, times, durations, numbers, percentages and lists, in English and Canadian French,
 * exactly as the website writes them.
 *
 * ⛔ HAND-WRITTEN TABLES, NOT THE PLATFORM'S CLDR. `java.text` and `android.icu` answer differently on
 * different Android versions (the French thousands separator alone has been U+00A0, U+202F and a plain
 * space), so the same screen would read differently on two phones and differently from the web. Every
 * function here is pinned byte for byte by `contracts/i18n/format-vectors.json`, generated from the
 * website's `money.ts` and `format.ts` (DistrictFormatTest). The one non-breaking space is U+00A0.
 *
 * ⚠️ ENGLISH OUTPUT ON SCREEN IS NOT ALWAYS THIS MODULE'S ENGLISH. W1 changes no English, and some of
 * today's English differs from the web's (prices are not grouped, "$1204.25"; durations come from the
 * server as "0m 5s"). Call sites keep today's English for [AppLanguage.EN] and use this module for
 * French; each one says so where it does.
 */
// ⚠️ One function per thing the web formats, in one place on purpose: splitting them across objects
// would split the vector test that pins them all.
@Suppress("TooManyFunctions")
internal object DistrictFormat {

    /** A non-breaking space, U+00A0: the only one the web uses (it normalizes U+202F to it). */
    const val NBSP: Char = '\u00A0'

    /** How a currency is named in [money]. */
    enum class Display { SYMBOL, NARROW, CODE }

    // ── Numbers ─────────────────────────────────────────────────────────────────────────────────

    /** A number with grouping and up to three decimals: "1,204.25" / "1 204,25". */
    fun number(value: Double, language: AppLanguage): String =
        decimal(BigDecimal.valueOf(value), language, minFraction = 0, maxFraction = MAX_NUMBER_FRACTION)

    /** A ratio (0 to 1) as a percentage with up to one decimal: "38%" / "38 %". */
    fun percent(ratio: Double, language: AppLanguage): String {
        val digits = decimal(
            BigDecimal.valueOf(ratio).movePointRight(2),
            language,
            minFraction = 0,
            maxFraction = MAX_PERCENT_FRACTION,
        )
        return if (language == AppLanguage.FR) "$digits$NBSP%" else "$digits%"
    }

    // ── Money ───────────────────────────────────────────────────────────────────────────────────

    /**
     * An amount in minor units (cents) of [currency] (`usd`, `cad`, `eur`, any case).
     *
     * English puts the symbol first ("CA$249.00"), French after the number ("249,00 $ CA"). A currency
     * with no symbol here is written as its code, the way [isoAmount] writes it.
     *
     * @param disambiguateDollar name the US dollar "US$" in English, where "$" alone would be read as
     *   the reader's own dollar.
     */
    fun money(
        minorUnits: Long,
        currency: String,
        language: AppLanguage,
        display: Display = Display.SYMBOL,
        disambiguateDollar: Boolean = false,
    ): String {
        val code = currency.uppercase(Locale.ROOT)
        val symbol = SYMBOLS[code] ?: return isoAmount(BigDecimal.valueOf(minorUnits, 2), code, language)
        val amount = BigDecimal.valueOf(abs(minorUnits), 2)
        val sign = if (minorUnits < 0) "-" else ""
        val digits = decimal(amount, language, minFraction = 2, maxFraction = 2)
        val name = when {
            display == Display.CODE -> code
            display == Display.NARROW -> symbol.narrow
            disambiguateDollar && code == "USD" -> if (language == AppLanguage.FR) symbol.fr else "US$"
            language == AppLanguage.FR -> symbol.fr
            else -> symbol.en
        }
        return when {
            language == AppLanguage.FR -> "$sign$digits$NBSP$name"
            display == Display.CODE -> "$sign$name$NBSP$digits"
            else -> "$sign$name$digits"
        }
    }

    /** Minor units with no currency at all: "249.00" / "249,00". */
    fun moneyBare(minorUnits: Long, language: AppLanguage): String {
        val digits = decimal(BigDecimal.valueOf(abs(minorUnits), 2), language, minFraction = 2, maxFraction = 2)
        return if (minorUnits < 0) "-$digits" else digits
    }

    /**
     * A major-unit amount in an ISO 4217 [isoCode]: "XYZ 249.00" / "249,00 XYZ" (both spaces U+00A0).
     *
     * ⚠️ A CODE THAT IS NOT THREE LETTERS IS NOT A CURRENCY, and the web then writes the code upper-cased
     * and the plain number, ungrouped, with a full stop, in both languages ("BOGUS 1204.25").
     */
    fun isoAmount(major: BigDecimal, isoCode: String, language: AppLanguage): String {
        val code = isoCode.uppercase(Locale.ROOT)
        if (!ISO_CODE.matches(code)) {
            return "$code$NBSP${major.setScale(2, RoundingMode.HALF_UP).toPlainString()}"
        }
        val digits = decimal(major, language, minFraction = 2, maxFraction = 2)
        return if (language == AppLanguage.FR) "$digits$NBSP$code" else "$code$NBSP$digits"
    }

    // ── Dates and times ─────────────────────────────────────────────────────────────────────────

    /** How much of a date [date] writes. */
    enum class DateStyle { SHORT, MEDIUM, LONG, FULL }

    /**
     * A calendar date in [zone]: SHORT "2026-08-01"; MEDIUM "Aug 1, 2026" / "1er août 2026"; LONG
     * "August 1, 2026" / "1er août 2026"; FULL with the weekday.
     */
    fun date(instant: Instant, zone: ZoneId, style: DateStyle, language: AppLanguage): String =
        date(instant.atZone(zone), style, language)

    /** A time of day in [zone]: "3:04 p.m." / "15 h 04". See [time] for when English is 24-hour. */
    fun time(instant: Instant, zone: ZoneId, language: AppLanguage): String = time(instant.atZone(zone), language)

    /** A medium date and a time: "Sep 21, 2026, 3:04 p.m." / "21 sept. 2026, 15 h 04". */
    fun dateTime(instant: Instant, zone: ZoneId, language: AppLanguage): String {
        val at = instant.atZone(zone)
        return "${date(at, DateStyle.MEDIUM, language)}, ${time(at, language)}"
    }

    /**
     * A day and month with no year, the chart axis's "Aug 9" / "9 août".
     *
     * ⚠️ NOT IN THE FORMAT VECTORS: the web's chart labels are built on the server. It is the MEDIUM
     * date without its year, which is how the web's own medium dates read.
     */
    fun monthDay(day: Int, month: Int, language: AppLanguage): String = if (language == AppLanguage.FR) {
        "${frenchDay(day)} ${FR_MONTHS_SHORT[month - 1]}"
    } else {
        "${EN_MONTHS_SHORT[month - 1]} $day"
    }

    /**
     * A month and year, the usage history's "Aug 2026" / "août 2026". Not in the format vectors; it
     * is the MEDIUM date without its day.
     */
    fun monthYear(month: Int, year: Int, language: AppLanguage): String =
        "${(if (language == AppLanguage.FR) FR_MONTHS_SHORT else EN_MONTHS_SHORT)[month - 1]} $year"

    /**
     * A day, month and time with no year, the way a call row reads ("Aug 15, 2:30 p.m." /
     * "15 août, 14 h 30"). Not in the format vectors: the server builds the English one itself
     * ("Aug 15, 14:30", always 24-hour), and this is the French that replaces it.
     */
    fun monthDayTime(instant: Instant, zone: ZoneId, language: AppLanguage): String {
        val at = instant.atZone(zone)
        return "${monthDay(at.dayOfMonth, at.monthValue, language)}, ${time(at, language)}"
    }

    private fun date(at: ZonedDateTime, style: DateStyle, language: AppLanguage): String {
        val day = at.dayOfMonth
        val month = at.monthValue - 1
        val year = at.year
        if (style == DateStyle.SHORT) {
            return "%04d-%02d-%02d".format(Locale.ROOT, year, month + 1, day)
        }
        val weekday = at.dayOfWeek.value - 1
        return if (language == AppLanguage.FR) {
            val months = if (style == DateStyle.MEDIUM) FR_MONTHS_SHORT else FR_MONTHS
            val core = "${frenchDay(day)} ${months[month]} $year"
            if (style == DateStyle.FULL) "${FR_WEEKDAYS[weekday]} $core" else core
        } else {
            val months = if (style == DateStyle.MEDIUM) EN_MONTHS_SHORT else EN_MONTHS
            val core = "${months[month]} $day, $year"
            if (style == DateStyle.FULL) "${EN_WEEKDAYS[weekday]}, $core" else core
        }
    }

    /**
     * ⚠️ ENGLISH READS A 12-HOUR CLOCK ONLY IN AN `America/` OR `Australia/` ZONE, the web's
     * `hourCycleFor`; elsewhere it is "15:04". French is always 24-hour, and drops ":00" ("9 h").
     */
    private fun time(at: ZonedDateTime, language: AppLanguage): String {
        val hour = at.hour
        val minute = at.minute
        if (language == AppLanguage.FR) {
            return if (minute == 0) "$hour h" else "$hour h %02d".format(Locale.ROOT, minute)
        }
        val zone = at.zone.id
        if (!zone.startsWith("America/") && !zone.startsWith("Australia/")) {
            return "%02d:%02d".format(Locale.ROOT, hour, minute)
        }
        val twelve = if (hour % HALF_DAY == 0) HALF_DAY else hour % HALF_DAY
        return "%d:%02d %s".format(Locale.ROOT, twelve, minute, if (hour < HALF_DAY) "a.m." else "p.m.")
    }

    // ── Durations ───────────────────────────────────────────────────────────────────────────────

    /**
     * The portal's reading of a duration: "1m 5s" / "1 min 5 s", "5s" under a minute, "60m" with no
     * seconds left over, "0m" for nothing.
     */
    fun minutesSeconds(seconds: Int, language: AppLanguage): String {
        val m = seconds / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        val (min, sec) = if (language == AppLanguage.FR) "${NBSP}min" to "${NBSP}s" else "m" to "s"
        return when {
            m == 0 && s == 0 -> "0$min"
            m == 0 -> "$s$sec"
            s == 0 -> "$m$min"
            else -> "$m$min $s$sec"
        }
    }

    /**
     * The `duration` string the server puts on a call row, the same in every language: "0s" for
     * nothing, otherwise "Xm Ys" ("0m 5s"). It is a wire value, not display copy; it is here so a
     * test can say which of the two readings a screen shows.
     */
    fun callDuration(seconds: Int): String =
        if (seconds == 0) "0s" else "${seconds / SECONDS_PER_MINUTE}m ${seconds % SECONDS_PER_MINUTE}s"

    // ── Lists ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A list read as one phrase: "A, B, and C" / "A, B et C".
     *
     * ⚠️ NOT IN THE FORMAT VECTORS (the web has no list vector yet); it is `Intl.ListFormat`'s
     * conjunction in en-CA and fr-CA, which puts a comma before "and" in English and never before
     * « et » in French.
     */
    fun list(items: List<String>, language: AppLanguage): String {
        val and = if (language == AppLanguage.FR) "et" else "and"
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} $and ${items[1]}"
            else -> {
                val head = items.dropLast(1).joinToString(", ")
                if (language == AppLanguage.FR) "$head $and ${items.last()}" else "$head, $and ${items.last()}"
            }
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────

    private fun decimal(value: BigDecimal, language: AppLanguage, minFraction: Int, maxFraction: Int): String {
        val rounded = value.setScale(maxFraction, RoundingMode.HALF_UP).stripTrailingZeros()
        val scaled = if (rounded.scale() < minFraction) rounded.setScale(minFraction) else rounded
        val plain = scaled.abs().toPlainString()
        val whole = plain.substringBefore('.')
        val fraction = plain.substringAfter('.', "")
        val (group, point) = if (language == AppLanguage.FR) NBSP.toString() to "," else "," to "."
        val grouped = whole.reversed().chunked(GROUP).joinToString(group).reversed()
        val sign = if (scaled.signum() < 0) "-" else ""
        return if (fraction.isEmpty()) "$sign$grouped" else "$sign$grouped$point$fraction"
    }

    private fun frenchDay(day: Int): String = if (day == 1) "1er" else day.toString()

    private class Symbol(val en: String, val fr: String, val narrow: String)

    private val SYMBOLS = mapOf(
        "USD" to Symbol(en = "$", fr = "\$${NBSP}US", narrow = "$"),
        "CAD" to Symbol(en = "CA$", fr = "\$${NBSP}CA", narrow = "$"),
        "EUR" to Symbol(en = "€", fr = "€", narrow = "€"),
    )

    private val ISO_CODE = Regex("[A-Z]{3}")

    private val EN_MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val EN_MONTHS_SHORT = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
    private val EN_WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val FR_MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    private val FR_MONTHS_SHORT = listOf(
        "janv.", "févr.", "mars", "avr.", "mai", "juin", "juill.", "août", "sept.", "oct.", "nov.", "déc.",
    )
    private val FR_WEEKDAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")

    private const val GROUP = 3
    private const val HALF_DAY = 12
    private const val SECONDS_PER_MINUTE = 60
    private const val MAX_NUMBER_FRACTION = 3
    private const val MAX_PERCENT_FRACTION = 1
}
