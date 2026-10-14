package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.scheduling.admin.overview.schedulingDisplayTime
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// The working-hours screen's readings, ported from district-core-swift's `SchedulingHoursFormat`
// and district-ios's `SchedulingCopy.dayHours`. The words come from the catalog.

/** One day's windows, "9:00 to 12:00, 13:00 to 17:00", or "Not bookable". */
@Composable
@ReadOnlyComposable
fun schedulingDayHours(rules: List<SchedulingAvailabilityRule>): String {
    if (rules.isEmpty()) return stringResource(R.string.scheduling_admin_hours_day_not_bookable)
    return rules.map { schedulingTimeRange(it.startTime, it.endTime) }.joinToString(", ")
}

/**
 * An override's hours: "12:00 to 16:00", "Out of office" or "Unavailable".
 *
 * ⚠️ THE UNAVAILABLE WORDING IS CHOSEN BY THE STORED REASON: `out_of_office` reads as such, and
 * `day_off`, `custom_hours` and an empty reason read "Unavailable", as in district-ios. ⚠️ A reason
 * this build does not know reads as the server spelled it, where district-ios says "Unavailable".
 */
@Composable
@ReadOnlyComposable
fun schedulingOverrideHours(row: SchedulingOverrideRow): String {
    val start = row.startTime.orEmpty()
    val end = row.endTime.orEmpty()
    return when {
        row.isAvailable && start.isNotEmpty() && end.isNotEmpty() -> schedulingTimeRange(start, end)
        row.reason == OUT_OF_OFFICE -> stringResource(R.string.scheduling_admin_hours_out_of_office)
        row.reason.isEmpty() || row.reason in UNAVAILABLE_REASONS ->
            stringResource(R.string.scheduling_admin_hours_unavailable)
        else -> schedulingUnknownValue(row.reason)
    }
}

/**
 * "Sep 12, 2026", or "Sep 12 to Sep 14, 2026", or both years when they differ.
 *
 * ⛔ FORMATTED FROM THE DATE, NEVER THROUGH THE DEVICE'S ZONE: a bare `YYYY-MM-DD` has no zone, and
 * placing it in one lands a day early for anybody west of UTC. ⚠️ A value that is not a real ISO date
 * reads unchanged: it is still the truth, and rewriting it would hide a corrupt row.
 */
@Composable
@ReadOnlyComposable
fun schedulingOverrideDates(start: String, end: String): String {
    if (start == end) return schedulingOverrideDate(start)
    val first = schedulingIsoDateOrNull(start)
    val from = if (first != null && start.take(YEAR_LENGTH) == end.take(YEAR_LENGTH)) {
        DistrictFormat.monthDay(first.dayOfMonth, first.monthValue, appLanguage())
    } else {
        schedulingOverrideDate(start)
    }
    return stringResource(R.string.scheduling_admin_common_range, from, schedulingOverrideDate(end))
}

/** "Sep 12, 2026" for `2026-09-12`; anything else unchanged. */
@Composable
@ReadOnlyComposable
fun schedulingOverrideDate(value: String): String {
    val date = schedulingIsoDateOrNull(value) ?: return value
    return schedulingDate(date)
}

/** A calendar date as "Sep 12, 2026", read at midnight UTC so no zone moves it. */
@Composable
@ReadOnlyComposable
fun schedulingDate(date: LocalDate): String =
    DistrictFormat.date(
        date.atStartOfDay().toInstant(ZoneOffset.UTC),
        ZoneOffset.UTC,
        DistrictFormat.DateStyle.MEDIUM,
        appLanguage(),
    )

/** `YYYY-MM-DD` as a date, or null when it is not a real one. */
fun schedulingIsoDateOrNull(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (_: DateTimeParseException) {
    null
}

@Composable
@ReadOnlyComposable
private fun schedulingTimeRange(start: String, end: String): String =
    stringResource(R.string.scheduling_admin_common_range, schedulingDisplayTime(start), schedulingDisplayTime(end))

private const val OUT_OF_OFFICE = "out_of_office"
private val UNAVAILABLE_REASONS = setOf("day_off", "custom_hours")
private const val YEAR_LENGTH = 4
