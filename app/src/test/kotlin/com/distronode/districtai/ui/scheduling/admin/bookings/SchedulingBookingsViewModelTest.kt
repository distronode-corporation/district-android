package com.distronode.districtai.ui.scheduling.admin.bookings

import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingBookingPage
import com.distronode.districtai.core.model.SchedulingBookingAnswer
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingSlots
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingBooking
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingBookingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private val now = Instant.parse("2026-10-02T12:00:00Z")

    private fun page(count: Int, from: Int = 0, total: Int? = null) = SchedulingBookingPage(
        items = (from until from + count).map { schedulingBooking(id = "b$it") },
        total = total,
    )

    private fun model(rig: SchedulingAdminTestRig) =
        SchedulingBookingsViewModel(rig.adminRepository, rig.scheduling, "ws") { now }

    @Test
    fun `the first page reads the context, then upcoming bookings for the whole tenancy`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = true)
        rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(isAdmin = true))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingItems(listOf(schedulingEventType())))
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, page(SchedulingBookingsViewModel.PAGE_SIZE, total = 30))
        val viewModel = model(rig)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(SectionState.Ready(Unit), state.list)
        assertEquals(25, state.rows.size)
        assertFalse(state.isEnd)
        assertEquals("America/Toronto", state.timezone)
        assertTrue(state.canManage)
        assertTrue(state.schedulerIsAdmin)
        assertEquals("intro", state.eventTypes.single().slug)
        val params = rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).single().params
        assertEquals(JsonPrimitive("all"), params["scope"])
        assertEquals(JsonPrimitive("upcoming"), params["when"])

        rig.admin.clear(SchedulingAdminOp.BOOKINGS_LIST)
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, page(5, from = 25, total = 30))
        viewModel.loadMore()
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(30, viewModel.state.value.rows.size)
        assertTrue(viewModel.state.value.isEnd)
        assertEquals(JsonPrimitive(25), rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).last().params["offset"])
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).size)
    }

    @Test
    fun `a failed profile reads as UTC and own bookings, and a failed first page is a failure`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = false)
        rig.admin.fail(SchedulingAdminOp.BOOKINGS_LIST, ApiResult.NetworkFailure(IOException("down")))
        val viewModel = model(rig)
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("UTC", state.timezone)
        assertFalse(state.schedulerIsAdmin)
        assertEquals(emptyList<Any>(), state.eventTypes)
        assertEquals(
            R.string.scheduling_admin_failure_offline,
            (state.list as SectionState.Failed).failure.message.resourceIdOrNull,
        )
        assertNull(rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).single().params["scope"])
        // Load more is ignored until a first page is in hand.
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).size)
    }

    @Test
    fun `a failed later page keeps the rows and says so above the button`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, page(25))
        val viewModel = model(rig)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isEnd)
        rig.admin.clear(SchedulingAdminOp.BOOKINGS_LIST)
        rig.admin.fail(SchedulingAdminOp.BOOKINGS_LIST, ApiResult.HttpFailure(503, "down"))
        viewModel.loadMore()
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals(25, state.rows.size)
        assertEquals(R.string.scheduling_admin_failure_unavailable, state.appendFailure?.message?.resourceIdOrNull)
        assertFalse(state.loadingMore)
    }

    @Test
    fun `switching views reads that view, and the same view again is ignored`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, page(2))
        val viewModel = model(rig)
        advanceUntilIdle()
        assertTrue(viewModel.state.value.isEnd)
        viewModel.selectView(SchedulingBookingView.UPCOMING)
        viewModel.selectView(SchedulingBookingView.CANCELLED)
        advanceUntilIdle()
        assertEquals(SchedulingBookingView.CANCELLED, viewModel.state.value.view)
        val last = rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).last()
        assertEquals(JsonPrimitive("cancelled"), last.params["status"])
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).size)
    }

    @Test
    fun `only a confirmed booking that has not ended is actionable, and a write re-reads the list`() =
        runTest(dispatcher) { actionable() }

    private suspend fun kotlinx.coroutines.test.TestScope.actionable() {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, page(1))
        rig.admin.value(SchedulingAdminOp.BOOKINGS_CANCEL, schedulingBooking(status = "cancelled"))
        val viewModel = model(rig)
        advanceUntilIdle()
        assertTrue(viewModel.isActionable(schedulingBooking(endAt = "2026-10-02T13:00:00Z")))
        assertFalse(viewModel.isActionable(schedulingBooking(endAt = "2026-10-02T11:00:00Z")))
        assertFalse(viewModel.isActionable(schedulingBooking(status = "cancelled")))
        assertFalse(viewModel.isActionable(schedulingBooking(endAt = "soon")))

        viewModel.writes.cancel.open(schedulingBooking())
        viewModel.writes.cancel.submit()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).size)
        // The reschedule dialog opens on the member's today (UTC here: the profile read failed).
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_SLOTS, SchedulingSlots(emptyList()))
        viewModel.writes.reschedule.open(schedulingBooking())
        advanceUntilIdle()
        val opened = viewModel.writes.dialog.value as BookingDialog.Reschedule
        assertEquals(java.time.LocalDate.of(2026, 10, 2), opened.day)
        assertEquals(JsonPrimitive("UTC"), rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_SLOTS).single().params["tz"])
    }

    @Test
    fun `one booking reads its answers and offers cancel to a member who may manage`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = true)
        val answers = listOf(SchedulingBookingAnswer("q1", "Company", "text", "Acme"))
        rig.admin.value(SchedulingAdminOp.BOOKINGS_ANSWERS, SchedulingItems(answers))
        rig.admin.value(SchedulingAdminOp.BOOKINGS_CANCEL, schedulingBooking(status = "cancelled"))
        val viewModel = SchedulingBookingDetailViewModel(rig.adminRepository, rig.scheduling, "ws", "b1")
        advanceUntilIdle()
        assertEquals(SectionState.Ready(answers), viewModel.state.value.answers)
        assertTrue(viewModel.state.value.canManage)
        assertEquals(JsonPrimitive("b1"), rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_ANSWERS).single().params["id"])

        viewModel.cancel()
        assertEquals("b1", viewModel.dialog.value?.bookingId)
        viewModel.cancelFlow.submit()
        advanceUntilIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_ANSWERS).size)
        viewModel.closeDialog()
        assertNull(viewModel.dialog.value)
    }
}
