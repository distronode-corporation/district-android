package com.distronode.districtai.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/*
 * Text the SERVER formats in English, read in the app's language.
 *
 * ⛔ IN ENGLISH, EXACTLY WHAT THE SERVER SENT, AS ALWAYS. W1 changes no English, and these strings are
 * built on the server in the operator's timezone ("Aug 15, 14:30", "1m 5s"). In French each is rebuilt
 * with [DistrictFormat] from the raw field the same response already carries (an ISO instant, seconds,
 * a calendar date). A raw field that is missing or unparseable keeps the server's text rather than
 * inventing one.
 *
 * ⚠️ FRENCH TIMES ARE IN THE DEVICE'S ZONE, NOT THE OPERATOR'S. The raw instant carries no zone, and
 * the server's operator zone is not in these responses; the device's is the honest reading of the
 * instant.
 */

/** A call row's time: the server's "Aug 15, 14:30", or in French "15 août, 14 h 30" from [createdAt]. */
@Composable
@ReadOnlyComposable
internal fun callTimeText(
    serverText: String,
    createdAt: String?,
    language: AppLanguage = appLanguage(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (language == AppLanguage.EN) return serverText
    val instant = createdAt?.let(::instantOrNull) ?: return serverText
    return DistrictFormat.monthDayTime(instant, zone, language)
}

/** A call's duration: the server's "1m 5s", or in French "1 min 5 s" from [seconds]. */
@Composable
@ReadOnlyComposable
internal fun callDurationText(serverText: String, seconds: Int?, language: AppLanguage = appLanguage()): String =
    if (language == AppLanguage.EN || seconds == null) serverText else DistrictFormat.minutesSeconds(seconds, language)

/** A chart day: the server's "Aug 9", or in French "9 août" from [isoDate] ("2026-08-09"). */
@Composable
@ReadOnlyComposable
internal fun chartDateText(serverText: String, isoDate: String, language: AppLanguage = appLanguage()): String {
    if (language == AppLanguage.EN) return serverText
    val date = try {
        LocalDate.parse(isoDate)
    } catch (_: DateTimeParseException) {
        return serverText
    }
    return DistrictFormat.monthDay(date.dayOfMonth, date.monthValue, language)
}

/**
 * An ISO instant the app has always printed as sent (a thread message's stamp): unchanged in English,
 * in French a day, month and time in the device's zone ("15 août, 14 h 30"), as a call row reads.
 */
@Composable
@ReadOnlyComposable
internal fun stampText(
    iso: String,
    language: AppLanguage = appLanguage(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (language == AppLanguage.EN) return iso
    val instant = instantOrNull(iso) ?: return iso
    return DistrictFormat.monthDayTime(instant, zone, language)
}

/**
 * An ISO instant shown as sent beside a label ("Last active …", "Last checked …"): unchanged in
 * English, in French a medium date and a time in the device's zone ("15 août 2026, 14 h 30").
 */
@Composable
@ReadOnlyComposable
internal fun stampDateTimeText(
    iso: String,
    language: AppLanguage = appLanguage(),
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (language == AppLanguage.EN) return iso
    val instant = instantOrNull(iso) ?: return iso
    return DistrictFormat.dateTime(instant, zone, language)
}

/** A billing month the app has always printed as sent ("2026-08"): unchanged in English, "août 2026" in French. */
@Composable
@ReadOnlyComposable
internal fun yearMonthText(raw: String, language: AppLanguage = appLanguage()): String {
    if (language == AppLanguage.EN) return raw
    val match = YEAR_MONTH.matchEntire(raw.trim()) ?: return raw
    val month = match.groupValues[2].toInt()
    if (month !in 1..MONTHS) return raw
    return DistrictFormat.monthYear(month, match.groupValues[1].toInt(), language)
}

/**
 * A wall-clock time the scheduler stores as "HH:mm" and the app shows as "9:00" or "10:30":
 * unchanged in English, the French reading in French ("9 h", "10 h 30"). Anything else as sent.
 */
@Composable
@ReadOnlyComposable
internal fun clockText(raw: String, language: AppLanguage = appLanguage()): String {
    if (language == AppLanguage.EN) return raw
    val match = CLOCK.matchEntire(raw.trim()) ?: return raw
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > LAST_HOUR || minute > LAST_MINUTE) return raw
    return DistrictFormat.frenchClock(hour, minute)
}

private val YEAR_MONTH = Regex("""(\d{4})-(\d{2})""")
private val CLOCK = Regex("""(\d{1,2}):(\d{2})""")
private const val LAST_HOUR = 23
private const val LAST_MINUTE = 59
private const val MONTHS = 12

private fun instantOrNull(iso: String): Instant? = try {
    Instant.parse(iso)
} catch (_: DateTimeParseException) {
    null
}

/**
 * A count as the app has always printed it in English (plain digits, "1204"), grouped the French way
 * in French ("1 204").
 */
@Composable
@ReadOnlyComposable
internal fun countText(value: Long, language: AppLanguage = appLanguage()): String =
    if (language == AppLanguage.EN) value.toString() else DistrictFormat.number(value.toDouble(), language)
