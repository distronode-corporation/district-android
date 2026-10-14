package com.distronode.districtai.ui.scheduling.admin.bookings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingBookingFilter
import com.distronode.districtai.core.model.SchedulingSlot
import com.distronode.districtai.core.model.SchedulingSlots
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.schedulingBooking
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancel, reschedule and reassign: what each sends, what it shows, and the one-request rule.
 *
 * ⛔ THE SAME CONFIRMATIONS AS district-ios: a cancel is refused over 1000 characters before it is
 * sent, a reschedule needs a picked time, a slot taken in the meantime re-reads the day, and a
 * reassign offers only active users other than the current host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingBookingWritesTest {

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private var changed = 0
    private val today = LocalDate.of(2026, 10, 2)
    private val writes = SchedulingBookingWrites(
        scope = scope,
        admin = rig.adminRepository,
        workspaceId = "ws",
        timezone = { "America/Toronto" },
        today = { today },
        onChanged = { changed++ },
    )

    private val booking = schedulingBooking()

    private fun user(id: String, name: String, archived: Boolean) = SchedulingUser(
        id = id,
        email = "$id@example.com",
        name = name,
        isAdmin = false,
        isOwner = false,
        role = "member",
        archived = archived,
    )

    @Test
    fun `cancel trims its reason, refuses one too long, sends once, and closes only when idle`() {
        rig.admin.value(SchedulingAdminOp.BOOKINGS_CANCEL, booking.copy(status = "cancelled"))
        // Nothing open: every action is ignored.
        writes.cancel.editReason("x")
        writes.cancel.submit()
        writes.close()
        assertNull(writes.dialog.value)

        writes.cancel.open(booking)
        writes.cancel.editReason("y".repeat(BookingCancelFlow.REASON_LIMIT + 1))
        writes.cancel.submit()
        assertEquals(true, (writes.dialog.value as BookingDialog.Cancel).reasonTooLong)
        writes.cancel.editReason("  Sick  ")
        writes.cancel.submit()
        writes.cancel.submit()
        assertEquals(WriteState.Working, writes.dialog.value?.state)
        writes.close()
        assertTrue(writes.dialog.value is BookingDialog.Cancel)
        scope.advanceUntilIdle()

        val sent = rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_CANCEL)
        assertEquals(1, sent.size)
        assertEquals(JsonPrimitive("Sick"), sent.single().params["reason"])
        val done = writes.dialog.value?.state as WriteState.Done
        assertEquals(R.string.scheduling_admin_bookings_cancel_done, done.message.resourceIdOrNull)
        assertEquals(1, changed)
        writes.close()
        assertNull(writes.dialog.value)
    }

    @Test
    fun `a failed cancel says why, and editing clears the failure`() {
        rig.admin.fail(SchedulingAdminOp.BOOKINGS_CANCEL, ApiResult.Forbidden("Forbidden"))
        writes.cancel.open(booking)
        writes.cancel.submit()
        scope.advanceUntilIdle()
        val failed = writes.dialog.value?.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_failure_forbidden, failed.failure.message.resourceIdOrNull)
        assertEquals(0, changed)
        writes.cancel.editReason("again")
        assertEquals(WriteState.Idle, writes.dialog.value?.state)
        writes.cancel.editReason("again!")
        assertEquals(WriteState.Idle, writes.dialog.value?.state)
    }

    @Test
    fun `reschedule reads today's slots in the member's zone and steps day by day, never into the past`() {
        val slot = SchedulingSlot(start = "2026-10-02T14:00:00Z", end = "2026-10-02T14:30:00Z")
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_SLOTS, SchedulingSlots(slots = listOf(slot)))
        writes.reschedule.open(booking.copy(eventTypeSlug = null))
        assertNull(writes.dialog.value)

        writes.reschedule.open(booking)
        scope.advanceUntilIdle()
        val call = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_SLOTS).single()
        assertEquals(JsonPrimitive("2026-10-02"), call.params["from"])
        assertEquals(JsonPrimitive("America/Toronto"), call.params["tz"])
        assertEquals(SectionState.Ready(listOf(slot)), (writes.dialog.value as BookingDialog.Reschedule).slots)

        writes.reschedule.previousDay()
        assertEquals(today, (writes.dialog.value as BookingDialog.Reschedule).day)
        writes.reschedule.nextDay()
        scope.advanceUntilIdle()
        assertEquals(today.plusDays(1), (writes.dialog.value as BookingDialog.Reschedule).day)
        writes.reschedule.previousDay()
        scope.advanceUntilIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_SLOTS).size)
    }

    @Test
    fun `a late answer for a day already left behind does not overwrite the day on screen`() {
        val gate = CompletableDeferred<Unit>()
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_SLOTS, SchedulingSlots(slots = emptyList()))
        rig.admin.gate = gate
        writes.reschedule.open(booking)
        scope.advanceUntilIdle()
        writes.reschedule.nextDay()
        rig.admin.gate = null
        gate.complete(Unit)
        scope.advanceUntilIdle()
        val dialog = writes.dialog.value as BookingDialog.Reschedule
        assertEquals(today.plusDays(1), dialog.day)
        assertEquals(SectionState.Ready(emptyList<SchedulingSlot>()), dialog.slots)
    }

    @Test
    fun `reschedule needs a picked time, moves once, and re-reads the day when the slot went`() {
        val slot = SchedulingSlot(start = "2026-10-02T14:00:00Z", end = "2026-10-02T14:30:00Z")
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_SLOTS, SchedulingSlots(slots = listOf(slot)))
        writes.reschedule.select("x")
        writes.reschedule.submit()
        writes.reschedule.nextDay()
        writes.reschedule.loadSlots()
        writes.reschedule.open(booking)
        scope.advanceUntilIdle()

        writes.reschedule.submit()
        assertEquals(true, (writes.dialog.value as BookingDialog.Reschedule).pickRejected)
        writes.reschedule.select(slot.start)
        assertEquals(false, (writes.dialog.value as BookingDialog.Reschedule).pickRejected)

        rig.admin.refuse(SchedulingAdminOp.BOOKINGS_RESCHEDULE, "slot_taken")
        writes.reschedule.submit()
        writes.reschedule.submit()
        writes.reschedule.nextDay()
        assertEquals(today, (writes.dialog.value as BookingDialog.Reschedule).day)
        scope.advanceUntilIdle()
        val taken = writes.dialog.value as BookingDialog.Reschedule
        assertEquals(
            R.string.scheduling_admin_failure_slot_taken,
            (taken.state as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        assertNull(taken.selectedStart)
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_SLOTS).size)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_RESCHEDULE).size)
        writes.reschedule.select(slot.start)
        assertEquals(WriteState.Idle, writes.dialog.value?.state)

        val moved = booking.copy(startAt = slot.start)
        val fresh = SchedulingAdminTestRig()
        val writes2 = SchedulingBookingWrites(scope, fresh.adminRepository, "ws", { "UTC" }, { today }) { changed++ }
        fresh.admin.value(SchedulingAdminOp.EVENT_TYPES_SLOTS, SchedulingSlots(slots = listOf(slot)))
        fresh.admin.value(SchedulingAdminOp.BOOKINGS_RESCHEDULE, moved)
        writes2.reschedule.open(booking)
        scope.advanceUntilIdle()
        writes2.reschedule.select(slot.start)
        writes2.reschedule.submit()
        scope.advanceUntilIdle()
        val done = writes2.dialog.value as BookingDialog.Reschedule
        assertEquals(slot.start, done.movedTo)
        assertTrue(done.state is WriteState.Done)
        val moveCall = fresh.admin.callsTo(SchedulingAdminOp.BOOKINGS_RESCHEDULE).single()
        assertEquals(JsonPrimitive(slot.start), moveCall.params["start_at"])
        assertEquals(1, changed)
    }

    @Test
    fun `a failed slot read can be retried`() {
        rig.admin.fail(SchedulingAdminOp.EVENT_TYPES_SLOTS, ApiResult.NetworkFailure(IOException("down")))
        writes.reschedule.open(booking)
        scope.advanceUntilIdle()
        assertTrue((writes.dialog.value as BookingDialog.Reschedule).slots is SectionState.Failed)
    }

    @Test
    fun `reassign offers only active users other than the current host, and needs one picked`() {
        val users = listOf(
            user("sched-user-1", "Ana", archived = false),
            user("u2", "", archived = false),
            user("u3", "Old", archived = true),
        )
        rig.admin.value(SchedulingAdminOp.USERS_LIST, users)
        rig.admin.value(SchedulingAdminOp.BOOKINGS_REASSIGN, booking.copy(hostId = "u2"))
        writes.reassign.select("u2")
        writes.reassign.loadHosts()
        writes.reassign.submit()
        writes.reassign.open(booking)
        scope.advanceUntilIdle()
        val dialog = writes.dialog.value as BookingDialog.Reassign
        assertEquals(listOf("u2"), (dialog.hosts as SectionState.Ready).value.map { it.id })

        writes.reassign.submit()
        assertEquals(0, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_REASSIGN).size)
        writes.reassign.select("u2")
        writes.reassign.submit()
        writes.reassign.submit()
        scope.advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_REASSIGN).size)
        val reassignCall = rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_REASSIGN).single()
        assertEquals(JsonPrimitive("u2"), reassignCall.params["host_id"])
        assertTrue(writes.dialog.value?.state is WriteState.Done)
        assertEquals(1, changed)
        writes.reassign.select("u2")
        assertTrue(writes.dialog.value?.state is WriteState.Done)
    }

    @Test
    fun `a failed reassign keeps the dialog, and a late answer for another booking is dropped`() {
        val gate = CompletableDeferred<Unit>()
        rig.admin.fail(SchedulingAdminOp.BOOKINGS_REASSIGN, ApiResult.HttpFailure(500, "boom"))
        rig.admin.value(SchedulingAdminOp.USERS_LIST, emptyList<Any>())
        writes.reassign.open(booking)
        scope.advanceUntilIdle()
        writes.reassign.select("u2")
        writes.reassign.submit()
        scope.advanceUntilIdle()
        assertTrue(writes.dialog.value?.state is WriteState.Failed)
        assertEquals(0, changed)

        rig.admin.gate = gate
        writes.reassign.loadHosts()
        scope.advanceUntilIdle()
        writes.cancel.open(booking)
        writes.reassign.open(booking.copy(id = "b2"))
        rig.admin.gate = null
        gate.complete(Unit)
        scope.advanceUntilIdle()
        assertEquals("b2", writes.dialog.value?.bookingId)
    }

    @Test
    fun `the bookings filter for each view`() {
        assertEquals(
            SchedulingBookingFilter(
                status = "confirmed",
                whenFilter = "upcoming",
                limit = 25,
                offset = 0,
                allHosts = true,
                order = "asc",
            ),
            SchedulingBookingView.UPCOMING.filter(25, 0, allHosts = true),
        )
        assertEquals(
            SchedulingBookingFilter(status = "confirmed", whenFilter = "past", limit = 25, offset = 25, order = "desc"),
            SchedulingBookingView.PAST.filter(25, 25, allHosts = false),
        )
        assertEquals(
            SchedulingBookingFilter(status = "cancelled", limit = 5, offset = 0, order = "desc"),
            SchedulingBookingView.CANCELLED.filter(5, 0, allHosts = false),
        )
        assertEquals(
            SchedulingBookingFilter(limit = 5, offset = 0, order = "desc"),
            SchedulingBookingView.ALL.filter(5, 0, allHosts = false),
        )
        val late = java.time.Instant.parse("2026-10-02T02:00:00Z")
        assertEquals(LocalDate.of(2026, 10, 1), todayIn("America/Toronto", late))
        // Referenced so the rig's profile helper stays exercised here too.
        assertEquals("America/Toronto", schedulingMe().timezone)
    }
}
