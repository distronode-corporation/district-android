package com.distronode.districtai.ui.scheduling.admin.hours

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOverrideCreated
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The week editor and the override writes: what each sends, when it refuses, and the one-request rule.
 *
 * ⛔ THE SAME RULES AS district-ios: the week saves a diff against the editor's own read and re-reads
 * after every save, failed or not; an override is refused before sending without a date; a range is
 * one delete by its group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingHoursWritesTest {

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private var changed = 0
    private val editor = SchedulingHoursRulesEditor(scope, rig.adminRepository, "ws") { changed++ }
    private val writes = SchedulingHoursOverrideWrites(scope, rig.adminRepository, "ws") { changed++ }

    private val monday = schedulingRule("mon", 1, "09:00", "17:00")
    private val ok = SchedulingNoContent(ok = true)

    private fun rules(vararg rows: SchedulingAvailabilityRule) =
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_LIST, SchedulingItems(rows.toList()))

    private val open get() = editor.state.value!!

    @Test
    fun `the editor does nothing while closed, and nothing is edited until its read is in hand`() {
        editor.load()
        editor.addRange(0)
        editor.save()
        editor.close()
        assertNull(editor.state.value)

        rules(monday)
        editor.open()
        assertEquals(SectionState.Loading, open.load)
        assertFalse(open.canSave)
        editor.addRange(0)
        assertTrue(open.week.all { it.isEmpty() })
        scope.advanceUntilIdle()
        assertEquals(SectionState.Ready(Unit), open.load)
        assertEquals(listOf("mon"), open.week[0].map { it.ruleId })
        assertEquals(listOf(monday), open.stored)
        assertTrue(open.canSave)
    }

    @Test
    fun `adding, retiming, removing and copying edit the draft, and refusals turn Save off`() {
        rules(monday)
        editor.open()
        scope.advanceUntilIdle()
        editor.addRange(0)
        val added = open.week[0][1]
        assertEquals(null to ("09:00" to "17:00"), added.ruleId to (added.start to added.end))
        assertEquals(listOf(null, SchedulingRangeError.OVERLAP), open.errors[0])
        assertFalse(open.canSave)
        editor.save()
        assertEquals(1, rig.admin.calls.size)

        editor.setTimes(0, added.key, "17:00", "18:00")
        assertEquals("17:00" to "18:00", open.week[0][1].start to open.week[0][1].end)
        assertTrue(open.canSave)
        editor.copyToWeekdays(0)
        assertEquals(listOf(2, 2, 2, 2, 2, 0, 0), open.week.map { it.size })
        editor.removeRange(0, open.week[0][0].key)
        assertEquals(listOf("17:00"), open.week[0].map { it.start })
    }

    @Test
    fun `an unchanged week is not sent, and says so until the next edit`() {
        rules(monday)
        editor.open()
        scope.advanceUntilIdle()
        editor.save()
        val done = open.state as WriteState.Done
        assertEquals(R.string.scheduling_admin_hours_nothing_to_save, done.message.resourceIdOrNull)
        assertEquals(1, rig.admin.calls.size)
        editor.addRange(3)
        assertEquals(WriteState.Idle, open.state)
    }

    @Test
    fun `a save sends the diff once, cannot be closed under, then re-reads and tells the screen`() {
        rules(monday)
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_DELETE, ok)
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_CREATE, schedulingRule("new", 2, "10:00", "11:00"))
        editor.open()
        scope.advanceUntilIdle()
        editor.removeRange(0, open.week[0][0].key)
        editor.addRange(1)
        editor.setTimes(1, open.week[1][0].key, "10:00", "11:00")
        rig.admin.clear(SchedulingAdminOp.AVAILABILITY_RULES_LIST)
        rules(schedulingRule("new", 2, "10:00", "11:00"))
        editor.save()
        assertEquals(WriteState.Working, open.state)
        assertFalse(open.canSave)
        editor.save()
        editor.close()
        editor.addRange(4)
        assertTrue(open.week[4].isEmpty())
        scope.advanceUntilIdle()

        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_DELETE).size)
        val create = rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_CREATE).single().params
        assertEquals(JsonPrimitive(2), create["day_of_week"])
        assertEquals(JsonPrimitive("10:00"), create["start_time"])
        val done = open.state as WriteState.Done
        assertEquals(R.string.scheduling_admin_hours_saved, done.message.resourceIdOrNull)
        assertEquals(listOf("new"), open.week[1].map { it.ruleId })
        assertEquals(1, changed)
        editor.close()
        assertNull(editor.state.value)
    }

    @Test
    fun `a refused save says why, and still re-reads and discards the draft`() {
        rules(monday)
        rig.admin.fail(SchedulingAdminOp.AVAILABILITY_RULES_CREATE, ApiResult.Forbidden("no"))
        editor.open()
        scope.advanceUntilIdle()
        editor.addRange(2)
        editor.save()
        scope.advanceUntilIdle()
        val failed = open.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_failure_forbidden, failed.failure.message.resourceIdOrNull)
        assertTrue(open.week[2].isEmpty())
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_LIST).size)
        assertEquals(1, changed)
    }

    @Test
    fun `a failed read is shown with retry, and a read landing after close is dropped`() {
        rig.admin.fail(SchedulingAdminOp.AVAILABILITY_RULES_LIST, ApiResult.NetworkFailure(IOException("x")))
        editor.open()
        scope.advanceUntilIdle()
        val failed = open.load as SectionState.Failed
        assertEquals(R.string.scheduling_admin_failure_offline, failed.failure.message.resourceIdOrNull)
        assertFalse(open.canSave)

        rig.admin.clear(SchedulingAdminOp.AVAILABILITY_RULES_LIST)
        rules(monday)
        editor.load()
        assertEquals(SectionState.Loading, open.load)
        editor.close()
        scope.advanceUntilIdle()
        assertNull(editor.state.value)
    }

    @Test
    fun `an override is refused without a date, sent once, and re-reads the screen`() {
        writes.edit { it }
        writes.submit()
        writes.close()
        assertNull(writes.add.value)

        rig.admin.value(
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
            SchedulingOverrideCreated.Single(schedulingOverride("o", "2026-12-24")),
        )
        writes.open()
        writes.submit()
        assertEquals(R.string.scheduling_admin_hours_date_missing, writes.add.value?.rejection)
        assertEquals(0, rig.admin.calls.size)

        writes.edit { it.copy(date = LocalDate.of(2026, 12, 24), endDate = LocalDate.of(2026, 12, 31)) }
        assertNull(writes.add.value?.rejection)
        writes.submit()
        assertEquals(WriteState.Working, writes.add.value?.state)
        writes.submit()
        writes.edit { it.copy(date = null) }
        writes.close()
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE).single().params
        assertEquals(JsonPrimitive("2026-12-24"), sent["date"])
        assertEquals(JsonPrimitive("2026-12-31"), sent["end_date"])
        assertEquals(JsonPrimitive("day_off"), sent["reason"])
        val done = writes.add.value?.state as WriteState.Done
        assertEquals(R.string.scheduling_admin_hours_override_added, done.message.resourceIdOrNull)
        assertEquals(1, changed)
        writes.close()
        assertNull(writes.add.value)
    }

    @Test
    fun `a refused override keeps the form and says why`() {
        rig.admin.fail(SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE, ApiResult.Forbidden("no"))
        writes.open()
        writes.edit { it.copy(reason = SchedulingOverrideReason.CUSTOM_HOURS, date = LocalDate.of(2026, 12, 24)) }
        writes.submit()
        scope.advanceUntilIdle()
        val failed = writes.add.value?.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_failure_forbidden, failed.failure.message.resourceIdOrNull)
        assertEquals(SchedulingOverrideReason.CUSTOM_HOURS, writes.add.value?.form?.reason)
        assertEquals(0, changed)
    }

    @Test
    fun `a single day is deleted by its id and a range by its group`() {
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE, ok)
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE_GROUP, ok)
        val rows = schedulingUpcomingOverrides(
            listOf(
                schedulingOverride("one", "2026-12-01"),
                schedulingOverride("r1", "2026-12-24", groupId = "grp"),
                schedulingOverride("r2", "2026-12-25", groupId = "grp"),
            ),
            today = "2026-10-01",
        )
        writes.removal.ask(rows[0])
        writes.removal.confirm()
        scope.advanceUntilIdle()
        writes.removal.ask(rows[1])
        writes.removal.confirm()
        scope.advanceUntilIdle()
        assertEquals(
            JsonPrimitive("one"),
            rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE).single().params["id"],
        )
        assertEquals(
            JsonPrimitive("grp"),
            rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE_GROUP).single().params["groupId"],
        )
        assertEquals(rows[1], writes.removal.state.value.removed)
        assertEquals(2, changed)
    }
}
