package com.distronode.districtai.ui.scheduling.admin.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.scheduling.admin.schedulingDisplayIndex
import com.distronode.districtai.ui.scheduling.admin.schedulingInstantOrNull
import com.distronode.districtai.ui.scheduling.admin.schedulingWeekdayShort
import com.distronode.districtai.ui.scheduling.admin.schedulingZone
import java.time.Instant
import java.time.temporal.ChronoUnit

// The booking desk's one-line readings, ported from district-core-swift's
// `SchedulingOverviewSummary`. The decisions (which rules count, how days group, when a range is a
// range) are the reference's; the words come from the catalog.

/** The event types a customer can book: active and public. */
fun bookableEventTypes(items: List<SchedulingEventType>): List<SchedulingEventType> =
    items.filter { it.isActive != false && it.isPublic != false }

/** "9:00" for the scheduler's zero-padded "09:00"; anything else as sent. */
fun schedulingDisplayTime(value: String): String {
    val hour = value.substringBefore(':', missingDelimiterValue = "")
    val rest = value.substringAfter(':', missingDelimiterValue = "")
    if (hour.isEmpty() || rest.isEmpty()) return value
    return "${hour.toIntOrNull() ?: hour}:$rest"
}

/**
 * The weekly hours in one line, "Mon to Fri, 9:00 to 17:00; Sat, 10:00 to 12:00", or null when no
 * day is bookable.
 *
 * ⚠️ ONLY THE MEMBER'S OWN RULES COUNT (no `event_type_id`): an event type's own rules narrow that
 * one event type, and folding them in would show hours nobody can book generally. Days with the
 * same windows group; three or more consecutive days read as a range.
 */
@Composable
@ReadOnlyComposable
fun workingHoursSummary(rules: List<SchedulingAvailabilityRule>): String? {
    val byDay = rules.filter { it.eventTypeId == null }
        .groupBy { schedulingDisplayIndex(it.dayOfWeek) }
        .toSortedMap()
    if (byDay.isEmpty()) return null
    val phrases = linkedMapOf<String, MutableList<Int>>()
    byDay.forEach { (day, windows) ->
        val phrase = DistrictFormat.list(
            windows.sortedBy { it.startTime }.map { rule ->
                stringResource(
                    R.string.scheduling_admin_common_range,
                    schedulingDisplayTime(rule.startTime),
                    schedulingDisplayTime(rule.endTime),
                )
            },
            appLanguage(),
        )
        phrases.getOrPut(phrase) { mutableListOf() } += day
    }
    return phrases.entries
        .map { (hours, days) ->
            stringResource(R.string.scheduling_admin_overview_hours_clause, daysPhrase(days), hours)
        }
        .joinToString("; ")
}

/** "Mon to Fri" for three or more consecutive days, else "Mon, Wed". */
@Composable
@ReadOnlyComposable
private fun daysPhrase(days: List<Int>): String {
    val consecutive = days.zipWithNext().all { (a, b) -> b == a + 1 }
    return if (days.size >= RANGE_MIN_DAYS && consecutive) {
        stringResource(
            R.string.scheduling_admin_common_range,
            schedulingWeekdayShort(days.first()),
            schedulingWeekdayShort(days.last()),
        )
    } else {
        days.map { schedulingWeekdayShort(it) }.joinToString(", ")
    }
}

/** "3 event types, 2 active", or null when there are none. */
@Composable
@ReadOnlyComposable
fun eventTypesSummary(items: List<SchedulingEventType>): String? {
    if (items.isEmpty()) return null
    val active = items.count { it.isActive != false }
    val types = pluralStringResource(R.plurals.scheduling_admin_overview_event_types_count, items.size, items.size)
    return stringResource(R.string.scheduling_admin_overview_event_types, types, active)
}

/** Who booked: the first attendee's name, else their email, else "Someone". */
@Composable
@ReadOnlyComposable
fun bookingWho(booking: SchedulingBooking): String {
    val first = booking.attendees?.firstOrNull()
    return first?.name?.trim()?.takeIf { it.isNotEmpty() }
        ?: first?.email?.trim()?.takeIf { it.isNotEmpty() }
        ?: stringResource(R.string.scheduling_admin_overview_someone)
}

/** Which event type a booking is for: its name, else its slug, else "Booking". */
@Composable
@ReadOnlyComposable
fun bookingEventTypeName(booking: SchedulingBooking, eventTypes: List<SchedulingEventType>): String {
    val slug = booking.eventTypeSlug?.takeIf { it.isNotEmpty() }
        ?: return stringResource(R.string.scheduling_admin_overview_booking)
    return eventTypes.firstOrNull { it.slug == slug }?.name ?: slug
}

/**
 * When a booking starts, read close: "Today 9:30", "Tomorrow 9:30", else "Oct 2, 9:30", or null
 * when the instant cannot be read. ⚠️ "Today" is today in the member's zone, not the device's.
 */
@Composable
@ReadOnlyComposable
fun bookingWhen(startAt: String, timezone: String, now: Instant): String? {
    val start = schedulingInstantOrNull(startAt) ?: return null
    val zone = schedulingZone(timezone)
    val language = appLanguage()
    val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), start.atZone(zone).toLocalDate())
    val clock = DistrictFormat.time(start, zone, language)
    return when (days) {
        0L -> stringResource(R.string.scheduling_admin_overview_today, clock)
        1L -> stringResource(R.string.scheduling_admin_overview_tomorrow, clock)
        else -> DistrictFormat.monthDayTime(start, zone, language)
    }
}

private const val RANGE_MIN_DAYS = 3
