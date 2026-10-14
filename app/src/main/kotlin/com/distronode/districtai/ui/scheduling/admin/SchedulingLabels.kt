package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R

// The scheduler's wire values, as labels.
//
// ⚠️ NONE OF THESE IS IN contracts/i18n/wire-vocabulary.json YET (booking status, calendar
// provider, and the rest each section adds), so they are labelled from this catalog with the
// value itself as the fallback for one this build does not know, the way district-ios does.
// The server batch is asked to add them to the vocabulary; until it does, an unknown value reads
// exactly as the server spelled it rather than as a guess.

/** A wire value this build has no label for, shown as sent. */
@Composable
@ReadOnlyComposable
fun schedulingUnknownValue(value: String): String =
    stringResource(R.string.scheduling_admin_common_unknown_value, value)

/** A calendar provider (`google`, `microsoft`, `apple`, `caldav`, `zoom`). */
@Composable
@ReadOnlyComposable
fun schedulingProviderLabel(provider: String): String = when (provider) {
    "google" -> stringResource(R.string.scheduling_admin_provider_google)
    "microsoft" -> stringResource(R.string.scheduling_admin_provider_microsoft)
    "apple" -> stringResource(R.string.scheduling_admin_provider_apple)
    "caldav" -> stringResource(R.string.scheduling_admin_provider_caldav)
    "zoom" -> stringResource(R.string.scheduling_admin_provider_zoom)
    else -> schedulingUnknownValue(provider)
}

/** A booking's status, with the colour it is drawn in. */
data class SchedulingStatusLabel(val label: String, val kind: SchedulingStatusKind)

/** `confirmed`, `cancelled` or `rescheduled`; anything else is shown as sent, in the info colour. */
@Composable
@ReadOnlyComposable
fun schedulingBookingStatus(status: String): SchedulingStatusLabel = when (status) {
    "confirmed" -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_bookings_status_confirmed),
        SchedulingStatusKind.SUCCESS,
    )
    "cancelled" -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_bookings_status_cancelled),
        SchedulingStatusKind.ERROR,
    )
    "rescheduled" -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_bookings_status_rescheduled),
        SchedulingStatusKind.INFO,
    )
    else -> SchedulingStatusLabel(schedulingUnknownValue(status), SchedulingStatusKind.INFO)
}

/**
 * The weekday names, Monday first, the order the working-hours screens list them in.
 *
 * ⚠️ THE SCHEDULER NUMBERS DAYS FROM SUNDAY = 0; convert with [schedulingDisplayIndex].
 */
private val WEEKDAYS_SHORT = listOf(
    R.string.scheduling_admin_weekday_short_mon,
    R.string.scheduling_admin_weekday_short_tue,
    R.string.scheduling_admin_weekday_short_wed,
    R.string.scheduling_admin_weekday_short_thu,
    R.string.scheduling_admin_weekday_short_fri,
    R.string.scheduling_admin_weekday_short_sat,
    R.string.scheduling_admin_weekday_short_sun,
)

private val WEEKDAYS = listOf(
    R.string.scheduling_admin_weekday_mon,
    R.string.scheduling_admin_weekday_tue,
    R.string.scheduling_admin_weekday_wed,
    R.string.scheduling_admin_weekday_thu,
    R.string.scheduling_admin_weekday_fri,
    R.string.scheduling_admin_weekday_sat,
    R.string.scheduling_admin_weekday_sun,
)

/** The scheduler's day number (Sunday = 0) as a Monday-first index (Monday = 0, Sunday = 6). */
fun schedulingDisplayIndex(dayOfWeek: Int): Int = Math.floorMod(dayOfWeek + DAYS_IN_WEEK - 1, DAYS_IN_WEEK)

/** The scheduler's day number for a Monday-first index; the inverse of [schedulingDisplayIndex]. */
fun schedulingDayOfWeek(displayIndex: Int): Int = (displayIndex + 1) % DAYS_IN_WEEK

/** "Mon" for the Monday-first index [displayIndex]. */
@Composable
@ReadOnlyComposable
fun schedulingWeekdayShort(displayIndex: Int): String = stringResource(WEEKDAYS_SHORT[displayIndex])

/** "Monday" for the Monday-first index [displayIndex]. */
@Composable
@ReadOnlyComposable
fun schedulingWeekday(displayIndex: Int): String = stringResource(WEEKDAYS[displayIndex])

/** Seven. */
const val DAYS_IN_WEEK: Int = 7
