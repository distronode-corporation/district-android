package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.createAvailabilityRule
import com.distronode.districtai.core.data.deleteAvailabilityRule
import com.distronode.districtai.core.data.patchAvailabilityRule
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.ui.scheduling.admin.DAYS_IN_WEEK
import com.distronode.districtai.ui.scheduling.admin.schedulingDayOfWeek
import com.distronode.districtai.ui.scheduling.admin.schedulingDisplayIndex

// The weekly working hours as data, ported from district-ios's `SchedulingWorkingHours` and
// district-core-swift's `SchedulingHoursFormat.weekFromRules`.
//
// ⛔ MONDAY FIRST ON SCREEN, SUNDAY = 0 ON THE WIRE. Every conversion goes through
// [schedulingDisplayIndex] and [schedulingDayOfWeek]; a row index used as a `day_of_week` publishes
// somebody's Monday hours on Sunday.
//
// ⛔ ONLY THE MEMBER'S OWN RULES (no `event_type_id`) ARE READ OR EDITED HERE. A rule with an event
// type governs that one event type; folding it in would promote it to everybody's week, and a save
// here would rewrite or delete it.

/** The window a new range opens with. */
const val SCHEDULING_DEFAULT_START: String = "09:00"
const val SCHEDULING_DEFAULT_END: String = "17:00"

/** Monday to Friday, as display indexes: the rows "Copy to all weekdays" writes. */
val SCHEDULING_WEEKDAY_ROWS: Set<Int> = (0 until WORKING_DAYS).toSet()

/**
 * One window on one day while it is being edited.
 *
 * @property key a local handle for the list; a new window has no server id yet.
 * @property ruleId the stored rule, or null for a window not stored yet (a create at save time).
 */
data class SchedulingHoursDraft(val key: Long, val ruleId: String?, val start: String, val end: String)

/** A stored window whose day or times changed. */
data class SchedulingHoursPatch(val id: String, val dayOfWeek: Int, val start: String, val end: String)

/** A window to store. */
data class SchedulingHoursCreate(val dayOfWeek: Int, val start: String, val end: String)

/**
 * What one save has to send.
 *
 * ⛔ DELETES, THEN PATCHES, THEN CREATES, AND THE ORDER IS NOT COSMETIC: the scheduler refuses
 * overlapping windows on a day, so a create sent before the delete it replaces is refused against a
 * window on its way out. The web and district-ios run the same three phases in the same order.
 */
data class SchedulingHoursDiff(
    val deletes: List<String> = emptyList(),
    val patches: List<SchedulingHoursPatch> = emptyList(),
    val creates: List<SchedulingHoursCreate> = emptyList(),
) {
    val isEmpty: Boolean get() = deletes.isEmpty() && patches.isEmpty() && creates.isEmpty()
}

/** Why a window is refused before anything is sent. */
enum class SchedulingRangeError(@get:StringRes val message: Int) {
    FORMAT(R.string.scheduling_admin_hours_time_format_error),
    ORDER(R.string.scheduling_admin_hours_time_order_error),
    OVERLAP(R.string.scheduling_admin_hours_time_overlap_error),
}

/**
 * The member's own rules as seven days, Monday first, each sorted by start time.
 *
 * ⚠️ THE RANGE CHECK IS ON THE WIRE VALUE, BEFORE THE CONVERSION: [schedulingDisplayIndex] folds every
 * integer into 0 to 6, so a corrupt `day_of_week` of 9 would otherwise land on a real day.
 */
fun schedulingWeekFromRules(rules: List<SchedulingAvailabilityRule>): List<List<SchedulingAvailabilityRule>> {
    val week = List(DAYS_IN_WEEK) { mutableListOf<SchedulingAvailabilityRule>() }
    rules.filter { it.eventTypeId == null && it.dayOfWeek in 0 until DAYS_IN_WEEK }
        .forEach { week[schedulingDisplayIndex(it.dayOfWeek)] += it }
    return week.map { day -> day.sortedBy { it.startTime } }
}

/**
 * Zero-padded `HH:MM`. ⚠️ FORMAT ONLY, NOT A CLOCK CHECK: `25:99` passes, as it does in the browser
 * and in district-ios. The scheduler is the validator; what this catches is the shape it refuses for
 * certain, a dropped leading zero.
 */
fun isWellFormedSchedulingTime(value: String): Boolean = TIME_SHAPE.matches(value)

/**
 * One day's per-window complaints, null where a window is fine.
 *
 * ⚠️ AN OVERLAP IS REPORTED ON THE LATER WINDOW BY CLOCK ORDER, not by position in the list, and only
 * between two windows that are otherwise fine: reporting it on both would double every message.
 */
fun schedulingRangeErrors(ranges: List<SchedulingHoursDraft>): List<SchedulingRangeError?> {
    val errors = ranges.map { range ->
        when {
            !isWellFormedSchedulingTime(range.start) || !isWellFormedSchedulingTime(range.end) ->
                SchedulingRangeError.FORMAT
            range.end <= range.start -> SchedulingRangeError.ORDER
            else -> null
        }
    }.toMutableList()
    val order = ranges.indices.sortedBy { ranges[it].start }
    order.zipWithNext().forEach { (earlier, later) ->
        val bothFine = errors[earlier] == null && errors[later] == null
        if (bothFine && ranges[later].start < ranges[earlier].end) errors[later] = SchedulingRangeError.OVERLAP
    }
    return errors
}

/** True when no window in [week] is refused. */
fun isValidSchedulingWeek(week: List<List<SchedulingHoursDraft>>): Boolean =
    week.all { day -> schedulingRangeErrors(day).all { it == null } }

/**
 * What the edited [week] changed about the [stored] rules.
 *
 * ⛔ AN UNCHANGED WINDOW SENDS NOTHING: re-sending it spends a write from the workspace's hourly
 * budget to store what is already there. ⚠️ A stored rule no longer anywhere in the week is a
 * delete, which is how Remove and a copy over a longer day both reach the wire. A window carrying an
 * id nothing stores is a create rather than dropped: losing it would silently delete what is on
 * screen.
 */
fun schedulingHoursDiff(
    week: List<List<SchedulingHoursDraft>>,
    stored: List<SchedulingAvailabilityRule>,
): SchedulingHoursDiff {
    val own = stored.filter { it.eventTypeId == null }.associateBy { it.id }
    val patches = mutableListOf<SchedulingHoursPatch>()
    val creates = mutableListOf<SchedulingHoursCreate>()
    week.forEachIndexed { row, ranges ->
        val day = schedulingDayOfWeek(row)
        ranges.forEach { range ->
            val before = range.ruleId?.let(own::get)
            when {
                before == null -> creates += SchedulingHoursCreate(day, range.start, range.end)
                before.dayOfWeek != day || before.startTime != range.start || before.endTime != range.end ->
                    patches += SchedulingHoursPatch(before.id, day, range.start, range.end)
            }
        }
    }
    val surviving = week.flatten().mapNotNull { it.ruleId }.toSet()
    return SchedulingHoursDiff(own.keys.filterNot { it in surviving }, patches, creates)
}

/**
 * Copy row [from]'s windows onto Monday to Friday.
 *
 * ⚠️ EACH TARGET'S OWN RULE IDS ARE REUSED BY POSITION, which turns the copy into patches rather
 * than a delete and recreate of the whole working week; a target with more windows than the source
 * loses the extras, and they leave as deletes.
 */
fun copySchedulingWeekdays(
    from: Int,
    week: List<List<SchedulingHoursDraft>>,
    newKey: () -> Long,
): List<List<SchedulingHoursDraft>> {
    val source = week[from]
    return week.mapIndexed { row, ranges ->
        if (row == from || row !in SCHEDULING_WEEKDAY_ROWS) {
            ranges
        } else {
            source.mapIndexed { index, range ->
                SchedulingHoursDraft(newKey(), ranges.getOrNull(index)?.ruleId, range.start, range.end)
            }
        }
    }
}

/**
 * Send [diff] in its three phases, stopping at the first refusal.
 *
 * ⛔ A PARTIAL SAVE IS A REAL OUTCOME: the alternative is sending the creates into a week whose
 * deletes did not happen, where every one of them overlaps something. The caller re-reads either way.
 */
suspend fun SchedulingAdminRepository.applyHoursDiff(
    workspaceId: String,
    diff: SchedulingHoursDiff,
): SchedulingAdminResult<*> {
    val steps = diff.deletes.map { id -> suspend { deleteAvailabilityRule(workspaceId, id) } } +
        diff.patches.map { patch ->
            suspend { patchAvailabilityRule(workspaceId, patch.id, patch.dayOfWeek, patch.start, patch.end) }
        } +
        diff.creates.map { create ->
            suspend { createAvailabilityRule(workspaceId, null, create.dayOfWeek, create.start, create.end) }
        }
    var last: SchedulingAdminResult<*> = SchedulingAdminResult.Ok(Unit)
    for (step in steps) {
        last = step()
        if (last is SchedulingAdminResult.Failed) break
    }
    return last
}

private const val WORKING_DAYS = 5

/** The scheduler's own pattern (`admin-ops.ts`): ASCII digits only, as JavaScript's `\d` is. */
private val TIME_SHAPE = Regex("[0-9]{2}:[0-9]{2}")
