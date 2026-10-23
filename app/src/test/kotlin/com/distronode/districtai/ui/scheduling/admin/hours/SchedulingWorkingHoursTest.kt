package com.distronode.districtai.ui.scheduling.admin.hours

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingOverrideDraft
import com.distronode.districtai.core.model.SchedulingAvailabilityOverride
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

fun schedulingRule(id: String, dayOfWeek: Int, start: String, end: String, eventTypeId: String? = null) =
    SchedulingAvailabilityRule(
        id = id,
        eventTypeId = eventTypeId,
        dayOfWeek = dayOfWeek,
        startTime = start,
        endTime = end,
    )

fun schedulingOverride(
    id: String,
    date: String,
    isAvailable: Boolean = false,
    reason: String = "day_off",
    startTime: String? = null,
    endTime: String? = null,
    groupId: String? = null,
) = SchedulingAvailabilityOverride(
    id = id,
    date = date,
    isAvailable = isAvailable,
    reason = reason,
    startTime = startTime,
    endTime = endTime,
    groupId = groupId,
)

/**
 * The week and override logic, case for case against district-ios's `SchedulingWorkingHoursTests`
 * and district-core-swift's `SchedulingHoursFormatTests`.
 */
class SchedulingWorkingHoursTest {

    private var key = 100L
    private fun draft(ruleId: String?, start: String, end: String) = SchedulingHoursDraft(key++, ruleId, start, end)

    @Test
    fun `the week is the member's own rules, Monday first, sorted, with corrupt days dropped`() {
        val week = schedulingWeekFromRules(
            listOf(
                schedulingRule("sun", 0, "10:00", "12:00"),
                schedulingRule("mon-late", 1, "13:00", "17:00"),
                schedulingRule("mon-early", 1, "09:00", "12:00"),
                schedulingRule("event", 2, "09:00", "17:00", eventTypeId = "et-1"),
                schedulingRule("corrupt", 9, "09:00", "17:00"),
                schedulingRule("negative", -1, "09:00", "17:00"),
            ),
        )
        assertEquals(7, week.size)
        assertEquals(listOf("mon-early", "mon-late"), week[0].map { it.id })
        assertEquals(listOf("sun"), week[6].map { it.id })
        assertEquals(emptyList<String>(), week[1].map { it.id })
    }

    @Test
    fun `refusals are per window, and an overlap lands on the later window by clock order`() {
        assertEquals(
            listOf(
                SchedulingRangeError.FORMAT,
                SchedulingRangeError.FORMAT,
                SchedulingRangeError.RANGE,
                SchedulingRangeError.ORDER,
                null,
            ),
            schedulingRangeErrors(
                listOf(
                    draft(null, "9-00", "10:00"),
                    draft(null, "09:00", "1000"),
                    draft(null, "09:00", "25:00"),
                    draft(null, "12:00", "12:00"),
                    draft(null, "13:00", "14:00"),
                ),
            ),
        )
        // Listed later-first: the overlap is still the later window's.
        assertEquals(
            listOf(SchedulingRangeError.OVERLAP, null),
            schedulingRangeErrors(listOf(draft(null, "11:00", "13:00"), draft(null, "09:00", "12:00"))),
        )
        // A refused window is not also blamed for an overlap, nor is its neighbour.
        assertEquals(
            listOf(SchedulingRangeError.ORDER, null),
            schedulingRangeErrors(listOf(draft(null, "09:00", "08:00"), draft(null, "09:30", "10:00"))),
        )
        assertEquals(
            listOf(null, SchedulingRangeError.ORDER),
            schedulingRangeErrors(listOf(draft(null, "09:00", "12:00"), draft(null, "10:00", "10:00"))),
        )
        // ⚠️ Compared as `HH:mm`: as text, "9 h" would sort after "10 h 30" and read as backwards.
        assertEquals(listOf<SchedulingRangeError?>(null), schedulingRangeErrors(listOf(draft(null, "9 h", "10 h 30"))))
        assertTrue(isValidSchedulingWeek(listOf(emptyList(), listOf(draft(null, "09:00", "10:00")))))
        assertFalse(isValidSchedulingWeek(listOf(listOf(draft(null, "10:00", "09:00")))))
    }

    @Test
    fun `the diff sends only what changed, deletes what left, and never touches an event type's rules`() {
        val stored = listOf(
            schedulingRule("same", 1, "09:00", "12:00"),
            schedulingRule("moved", 1, "13:00", "17:00"),
            schedulingRule("retimed", 2, "09:00", "17:00"),
            schedulingRule("restarted", 3, "09:00", "17:00"),
            schedulingRule("gone", 4, "09:00", "17:00"),
            schedulingRule("event", 5, "09:00", "17:00", eventTypeId = "et-1"),
        )
        val week = List(7) { mutableListOf<SchedulingHoursDraft>() }
        week[0] += draft("same", "09:00", "12:00")
        week[1] += draft("moved", "13:00", "17:00")
        week[1] += draft("retimed", "09:00", "16:00")
        week[2] += draft("restarted", "10:00", "17:00")
        week[5] += draft(null, "10:00", "14:00")
        week[6] += draft("unknown-id", "10:00", "11:00")
        week[6] += draft("event", "09:00", "17:00")
        val diff = schedulingHoursDiff(week, stored)
        assertEquals(listOf("gone"), diff.deletes)
        assertEquals(
            listOf(
                SchedulingHoursPatch("moved", 2, "13:00", "17:00"),
                SchedulingHoursPatch("retimed", 2, "09:00", "16:00"),
                SchedulingHoursPatch("restarted", 3, "10:00", "17:00"),
            ),
            diff.patches,
        )
        assertEquals(
            listOf(
                SchedulingHoursCreate(6, "10:00", "14:00"),
                SchedulingHoursCreate(0, "10:00", "11:00"),
                SchedulingHoursCreate(0, "09:00", "17:00"),
            ),
            diff.creates,
        )
        assertFalse(diff.isEmpty)
        assertTrue(schedulingHoursDiff(List(7) { emptyList() }, emptyList()).isEmpty)
        assertFalse(SchedulingHoursDiff(deletes = listOf("x")).isEmpty)
        assertFalse(SchedulingHoursDiff(patches = listOf(SchedulingHoursPatch("x", 1, "a", "b"))).isEmpty)
    }

    @Test
    fun `copy to weekdays reuses each target's ids by position and leaves the weekend alone`() {
        val week = List(7) { mutableListOf<SchedulingHoursDraft>() }
        week[0] += draft("mon", "08:00", "12:00")
        week[1] += draft("tue-1", "09:00", "10:00")
        week[1] += draft("tue-2", "11:00", "12:00")
        week[2] += draft("wed", "10:00", "11:00")
        week[5] += draft("sat", "10:00", "11:00")
        week[4] += draft("fri", "09:00", "17:00")
        val copied = copySchedulingWeekdays(2, week) { key++ }
        assertEquals(listOf("mon"), copied[0].map { it.ruleId })
        assertEquals(listOf("tue-1"), copied[1].map { it.ruleId })
        assertEquals(week[2], copied[2])
        assertEquals(listOf(null), copied[3].map { it.ruleId })
        assertEquals(listOf("10:00" to "11:00"), copied[4].map { it.start to it.end })
        assertEquals(week[5], copied[5])
        // tue-2 is gone from the week, so a save deletes it.
        val stored = week.flatten().map { schedulingRule(it.ruleId!!, 1, it.start, it.end) }
        assertEquals(listOf("tue-2"), schedulingHoursDiff(copied, stored).deletes)
    }

    @Test
    fun `a diff goes deletes, patches, creates, and stops at the first refusal`() = runTest {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_PATCH, schedulingRule("p", 2, "10:00", "11:00"))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_CREATE, schedulingRule("c", 3, "10:00", "11:00"))
        val diff = SchedulingHoursDiff(
            deletes = listOf("d"),
            patches = listOf(SchedulingHoursPatch("p", 2, "10:00", "11:00")),
            creates = listOf(SchedulingHoursCreate(3, "10:00", "11:00")),
        )
        assertTrue(rig.adminRepository.applyHoursDiff("ws", diff) is SchedulingAdminResult.Ok)
        assertEquals(
            listOf("availability.rules.delete", "availability.rules.patch", "availability.rules.create"),
            rig.admin.calls.map { it.op.wire },
        )
        val create = rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_CREATE).single().params
        assertEquals(JsonPrimitive(3), create["day_of_week"])
        assertEquals(JsonNull, create["event_type_id"] ?: JsonNull)

        rig.admin.calls.clear()
        rig.admin.clear(SchedulingAdminOp.AVAILABILITY_RULES_PATCH)
        rig.admin.refuse(SchedulingAdminOp.AVAILABILITY_RULES_PATCH, "forbidden")
        assertTrue(rig.adminRepository.applyHoursDiff("ws", diff) is SchedulingAdminResult.Failed)
        assertEquals(0, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_CREATE).size)
    }

    @Test
    fun `overrides fold ranges into one row, keep ones not over, and read earliest first`() {
        val rows = schedulingUpcomingOverrides(
            listOf(
                schedulingOverride("late", "2026-12-01"),
                schedulingOverride("r2", "2026-10-21", groupId = "g", reason = "out_of_office"),
                schedulingOverride("r1", "2026-10-20", groupId = "g"),
                schedulingOverride("r3", "2026-10-22", groupId = "g"),
                schedulingOverride("past", "2026-10-01"),
                schedulingOverride("empty-group", "2026-10-25", groupId = ""),
                schedulingOverride("same-day", "2026-10-25", isAvailable = true, reason = "custom_hours"),
            ),
            today = "2026-10-21",
        )
        assertEquals(listOf("group:g", "id:empty-group", "id:same-day", "id:late"), rows.map { it.key })
        val range = rows.first()
        assertEquals(SchedulingOverrideKind.GROUP, range.kind)
        assertEquals("g", range.target)
        assertEquals("2026-10-20" to "2026-10-22", range.start to range.end)
        assertEquals(3, range.days)
        assertEquals("out_of_office", range.reason)
        assertEquals(SchedulingOverrideKind.SINGLE, rows[1].kind)
        assertEquals("empty-group", rows[1].target)
    }

    @Test
    fun `the add form refuses what the reference refuses and drops a one-day range`() {
        val day = LocalDate.of(2026, 12, 24)
        assertEquals(R.string.scheduling_admin_hours_date_missing, SchedulingOverrideForm().error)
        assertNull(SchedulingOverrideForm().draft())
        assertEquals(SchedulingOverrideDraft("2026-12-24", "day_off"), SchedulingOverrideForm(date = day).draft())
        assertEquals(
            SchedulingOverrideDraft("2026-12-24", "day_off"),
            SchedulingOverrideForm(date = day, endDate = day).draft(),
        )
        assertEquals(
            SchedulingOverrideDraft("2026-12-24", "day_off", endDate = "2026-12-31"),
            SchedulingOverrideForm(date = day, endDate = day.plusDays(7)).draft(),
        )
        val backwards = SchedulingOverrideForm(date = day, endDate = day.minusDays(1))
        assertEquals(R.string.scheduling_admin_hours_end_before_start, backwards.error)
        assertNull(backwards.draft())

        val custom = SchedulingOverrideForm(
            reason = SchedulingOverrideReason.CUSTOM_HOURS,
            date = day,
            endDate = day.minusDays(1),
        )
        assertTrue(custom.isCustomHours)
        assertEquals(
            SchedulingOverrideDraft("2026-12-24", "custom_hours", startTime = "09:00", endTime = "17:00"),
            custom.draft(),
        )
        assertEquals(R.string.scheduling_admin_hours_time_format_error, custom.copy(start = "9-00").error)
        assertEquals(R.string.scheduling_admin_hours_time_range_error, custom.copy(end = "24 h").error)
        assertEquals(R.string.scheduling_admin_hours_time_order_error, custom.copy(end = "09:00").error)
        assertEquals(R.string.scheduling_admin_hours_time_order_error, custom.copy(start = "9 h", end = "8 h 30").error)
        // ⛔ Whatever was typed, the scheduler is sent `HH:mm`.
        assertEquals(
            SchedulingOverrideDraft("2026-12-24", "custom_hours", startTime = "09:30", endTime = "17:00"),
            custom.copy(start = "9 h 30", end = "5 pm").draft(),
        )
    }

    @Test
    fun `a picked date is read in UTC, and an ISO date that is not real is none`() {
        assertEquals(LocalDate.of(2026, 10, 20), schedulingPickedDate(1_792_454_400_000L))
        assertNull(schedulingPickedDate(null))
        assertEquals(LocalDate.of(2026, 2, 28), schedulingIsoDateOrNull("2026-02-28"))
        assertNull(schedulingIsoDateOrNull("2026-02-30"))
    }
}
