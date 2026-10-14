package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingOverrideDraft
import com.distronode.districtai.core.model.SchedulingAvailabilityOverride
import java.time.LocalDate

// The dated overrides as data, ported from district-core-swift's `SchedulingHoursFormat` (the
// fold) and district-ios's `SchedulingOverrideForm` (the add form).

/** A single day's row, or a range's rows folded into one. */
enum class SchedulingOverrideKind { SINGLE, GROUP }

/**
 * One row of the overrides list: a single day, or a whole range folded into one.
 *
 * ⛔ A RANGE IS ONE ROW AND ONE DELETE. The scheduler stores a range as one row PER DAY sharing a
 * `group_id`, so a fortnight away is fourteen rows; deleting one of them is thirteen ways to
 * half-cancel a holiday. [days] counts the stored rows, not the distance between the ends.
 *
 * @property key `id:<row>` or `group:<group>`, prefixed because nothing promises the two never collide.
 * @property target what a delete names: the row's id for [SchedulingOverrideKind.SINGLE], the
 *   group's for [SchedulingOverrideKind.GROUP].
 */
data class SchedulingOverrideRow(
    val key: String,
    val kind: SchedulingOverrideKind,
    val target: String,
    val start: String,
    val end: String,
    val days: Int,
    val isAvailable: Boolean,
    val reason: String,
    val startTime: String?,
    val endTime: String?,
)

/**
 * Every override not yet over on [today] (`YYYY-MM-DD` in the member's zone), earliest first, with
 * ranges folded.
 *
 * ⚠️ A RANGE TAKES ITS REASON AND HOURS FROM THE FIRST ROW SEEN FOR ITS GROUP; later rows only widen
 * the ends and add to the count, as the web does. A range that started in the past and ends later is
 * kept: the filter is on its last day. ⚠️ String comparison is date comparison for fixed-width ISO
 * dates, and avoids the zone a date round trip would bring. The sort is stable, so two overrides on
 * one date keep the scheduler's order, as `Array.prototype.sort` keeps it on the web.
 */
fun schedulingUpcomingOverrides(
    overrides: List<SchedulingAvailabilityOverride>,
    today: String,
): List<SchedulingOverrideRow> {
    val rows = linkedMapOf<String, SchedulingOverrideRow>()
    overrides.forEach { item ->
        val group = item.groupId?.takeIf { it.isNotEmpty() }
        val key = if (group == null) "id:${item.id}" else "group:$group"
        val seen = rows[key]
        rows[key] = if (seen == null) {
            SchedulingOverrideRow(
                key = key,
                kind = if (group == null) SchedulingOverrideKind.SINGLE else SchedulingOverrideKind.GROUP,
                target = group ?: item.id,
                start = item.date,
                end = item.date,
                days = 1,
                isAvailable = item.isAvailable,
                reason = item.reason,
                startTime = item.startTime,
                endTime = item.endTime,
            )
        } else {
            seen.copy(start = minOf(seen.start, item.date), end = maxOf(seen.end, item.date), days = seen.days + 1)
        }
    }
    return rows.values.filter { it.end >= today }.sortedBy { it.start }
}

/**
 * The two kinds of override this client writes.
 *
 * ⛔ `out_of_office` IS READ-ONLY HERE, AS IN district-ios AND ON THE WEB. The catalog takes three
 * reasons; both reference clients offer these two, and a row that arrives with the third still reads
 * "Out of office". Offering it would be a third choice nobody on either client can tell apart.
 */
enum class SchedulingOverrideReason(val wire: String, @get:StringRes val label: Int) {
    DAY_OFF("day_off", R.string.scheduling_admin_hours_kind_unavailable),
    CUSTOM_HOURS("custom_hours", R.string.scheduling_admin_hours_kind_custom_hours),
}

/**
 * The add-an-override form.
 *
 * ⛔ THE KIND DECIDES WHICH FIELDS EXIST. A day off may span a range and carries no times; custom
 * hours are one day and carry both, because the scheduler refuses a range of custom hours. A last
 * day left over from switching kinds is dropped from the draft rather than sent to be refused.
 *
 * ⚠️ THE DATES ARE PICKED, NOT TYPED, so a malformed date cannot reach here; the only date refusals
 * left are "none picked" and "last before first".
 */
data class SchedulingOverrideForm(
    val reason: SchedulingOverrideReason = SchedulingOverrideReason.DAY_OFF,
    val date: LocalDate? = null,
    val endDate: LocalDate? = null,
    val start: String = SCHEDULING_DEFAULT_START,
    val end: String = SCHEDULING_DEFAULT_END,
) {
    val isCustomHours: Boolean get() = reason == SchedulingOverrideReason.CUSTOM_HOURS

    /** Why the form is refused, or null when it may be sent. */
    @get:StringRes
    val error: Int?
        get() = when {
            date == null -> R.string.scheduling_admin_hours_date_missing
            isCustomHours && !(isWellFormedSchedulingTime(start) && isWellFormedSchedulingTime(end)) ->
                R.string.scheduling_admin_hours_time_format_error
            isCustomHours && end <= start -> R.string.scheduling_admin_hours_time_order_error
            !isCustomHours && endDate != null && endDate < date -> R.string.scheduling_admin_hours_end_before_start
            else -> null
        }

    /**
     * What `availability.overrides.create` takes, or null when the form is refused.
     *
     * ⚠️ A LAST DAY EQUAL TO THE FIRST IS DROPPED, as on the web: a one-day "range" would answer a
     * group summary and give that day a `group_id` for no reason.
     */
    fun draft(): SchedulingOverrideDraft? {
        val first = date ?: return null
        if (error != null) return null
        return if (isCustomHours) {
            SchedulingOverrideDraft(first.toString(), reason.wire, startTime = start, endTime = end)
        } else {
            val last = endDate?.takeIf { it != first }
            SchedulingOverrideDraft(first.toString(), reason.wire, endDate = last?.toString())
        }
    }
}
