package com.distronode.districtai.ui.scheduling.admin.hours

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOverrideCreated
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingHoursViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    /** 02:00 UTC on Oct 21: still Oct 20 in Toronto. */
    private val now = Instant.parse("2026-10-21T02:00:00Z")

    private fun model(rig: SchedulingAdminTestRig) =
        SchedulingHoursViewModel(rig.adminRepository, rig.scheduling, "ws") { now }

    @Test
    fun `today is the member's, so a day off that has not ended there is still listed`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = true)
        rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "America/Toronto"))
        rig.admin.value(
            SchedulingAdminOp.AVAILABILITY_RULES_LIST,
            SchedulingItems(listOf(schedulingRule("mon", 1, "09:00", "17:00"))),
        )
        rig.admin.value(
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST,
            SchedulingItems(listOf(schedulingOverride("o", "2026-10-20"), schedulingOverride("old", "2026-10-19"))),
        )
        val viewModel = model(rig)
        assertEquals(LocalDate.of(2026, 10, 21), viewModel.state.value.today)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("America/Toronto", state.timezone)
        assertEquals(LocalDate.of(2026, 10, 20), state.today)
        assertTrue(state.canManage)
        assertEquals("mon", (state.rules as SectionState.Ready).value.single().id)
        assertEquals(listOf("id:o"), (state.overrides as SectionState.Ready).value.map { it.key })
    }

    @Test
    fun `a failed profile reads as UTC, and each read fails on its own`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = false)
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_LIST, SchedulingItems(emptyList<Nothing>()))
        rig.admin.fail(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST, ApiResult.HttpFailure(503, "down"))
        val viewModel = model(rig)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("UTC", state.timezone)
        assertEquals(LocalDate.of(2026, 10, 21), state.today)
        assertFalse(state.canManage)
        assertEquals(SectionState.Ready(emptyList<Nothing>()), state.rules)
        val failed = state.overrides as SectionState.Failed
        assertEquals(R.string.scheduling_admin_failure_unavailable, failed.failure.message.resourceIdOrNull)
    }

    @Test
    fun `a write that works re-reads the screen, and a refresh drops the read before it`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_LIST, SchedulingItems(emptyList<Nothing>()))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST, SchedulingItems(emptyList<Nothing>()))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE, SchedulingNoContent(ok = true))
        val viewModel = model(rig)
        viewModel.load()
        assertEquals(SectionState.Loading, viewModel.state.value.rules)
        advanceUntilIdle()
        // The first load was cancelled before it started: one read of each.
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST).size)

        val row = schedulingUpcomingOverrides(listOf(schedulingOverride("o", "2026-12-01")), today = "").single()
        viewModel.overrideWrites.removal.ask(row)
        viewModel.overrideWrites.removal.confirm()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST).size)

        viewModel.rulesEditor.open()
        advanceUntilIdle()
        viewModel.rulesEditor.addRange(0)
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_CREATE, schedulingRule("n", 1, "09:00", "17:00"))
        viewModel.rulesEditor.save()
        advanceUntilIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST).size)

        viewModel.overrideWrites.open()
        viewModel.overrideWrites.edit { it.copy(date = LocalDate.of(2026, 12, 1)) }
        rig.admin.value(
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
            SchedulingOverrideCreated.Single(schedulingOverride("x", "2026-12-01")),
        )
        viewModel.overrideWrites.submit()
        advanceUntilIdle()
        assertEquals(4, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST).size)
    }
}
