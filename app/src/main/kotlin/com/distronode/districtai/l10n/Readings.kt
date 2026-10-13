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
