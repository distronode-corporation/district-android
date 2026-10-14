package com.distronode.districtai.ui.scheduling.admin.bookings

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.cancelBooking
import com.distronode.districtai.core.data.eventTypeSlots
import com.distronode.districtai.core.data.map
import com.distronode.districtai.core.data.reassignBooking
import com.distronode.districtai.core.data.rescheduleBooking
import com.distronode.districtai.core.data.schedulerUsers
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingSlot
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isSlotTaken
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The booking dialog that is open, if any, with everything it shows. */
sealed interface BookingDialog {
    val bookingId: String
    val state: WriteState

    /** Cancel, with an optional reason that goes in the attendee's email. */
    data class Cancel(
        override val bookingId: String,
        val reason: String = "",
        val reasonTooLong: Boolean = false,
        override val state: WriteState = WriteState.Idle,
    ) : BookingDialog

    /**
     * Move to another slot of the same event type, one day at a time.
     *
     * @property earliest the member's today; a booking cannot move into the past.
     * @property movedTo the booking's new start, once it has moved; the confirmation names it.
     */
    data class Reschedule(
        override val bookingId: String,
        val eventTypeSlug: String,
        val day: LocalDate,
        val earliest: LocalDate,
        val slots: SectionState<List<SchedulingSlot>> = SectionState.Loading,
        val selectedStart: String? = null,
        val pickRejected: Boolean = false,
        val movedTo: String? = null,
        override val state: WriteState = WriteState.Idle,
    ) : BookingDialog

    /** Give the booking to another active scheduler user. */
    data class Reassign(
        override val bookingId: String,
        val currentHostId: String?,
        val hosts: SectionState<List<SchedulingUser>> = SectionState.Loading,
        val selectedHostId: String? = null,
        override val state: WriteState = WriteState.Idle,
    ) : BookingDialog
}

/**
 * Cancel, reschedule and reassign, shared by the bookings list and one booking's screen; ported
 * from district-ios's `SchedulingBooking{Cancel,Reschedule,Reassign}Model`.
 *
 * ⛔ EVERY SUBMIT IS ONE REQUEST: a submit while one is on its way is ignored, the dialog cannot be
 * closed under it, and nothing retries on its own.
 *
 * @param timezone the member's zone: slots are asked for and shown in it.
 * @param today the member's today, the earliest day a booking can move to.
 * @param onChanged told after every successful write, so the screen behind re-reads.
 */
class SchedulingBookingWrites(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    timezone: () -> String,
    today: () -> LocalDate,
    onChanged: () -> Unit,
) {
    private val host = BookingDialogHost()
    private val context = BookingWriteContext(scope, admin, workspaceId, onChanged)
    val dialog: StateFlow<BookingDialog?> = host.dialog

    val cancel = BookingCancelFlow(host, context)
    val reschedule = BookingRescheduleFlow(host, context, timezone, today)
    val reassign = BookingReassignFlow(host, context)

    /** Close whatever is open. Ignored while a write is on its way. */
    fun close() = host.close()
}

/** What every booking write needs: where to run, what to call, and whom to tell after a success. */
class BookingWriteContext(
    val scope: CoroutineScope,
    val admin: SchedulingAdminRepository,
    val workspaceId: String,
    val onChanged: () -> Unit,
)

/** The one open dialog, shared by the three flows. */
class BookingDialogHost {
    private val _dialog = MutableStateFlow<BookingDialog?>(null)
    val dialog: StateFlow<BookingDialog?> = _dialog.asStateFlow()

    val current: BookingDialog? get() = _dialog.value

    fun show(dialog: BookingDialog) {
        _dialog.value = dialog
    }

    fun close() {
        if (_dialog.value?.state?.isWorking == true) return
        _dialog.value = null
    }

    /**
     * Change the open dialog if it is still a [D] for [bookingId]. ⚠️ A closed dialog, or one
     * reopened for another booking, is left alone, so a late answer cannot land in the wrong one.
     */
    inline fun <reified D : BookingDialog> update(bookingId: String, change: (D) -> BookingDialog) {
        val open = current as? D ?: return
        if (open.bookingId != bookingId) return
        show(change(open))
    }
}

/** Cancel one booking. */
class BookingCancelFlow(private val host: BookingDialogHost, private val context: BookingWriteContext) {
    fun open(booking: SchedulingBooking) = host.show(BookingDialog.Cancel(booking.id))

    fun editReason(value: String) {
        val dialog = host.current as? BookingDialog.Cancel ?: return
        host.show(dialog.copy(reason = value, reasonTooLong = false, state = idleAfterFailure(dialog.state)))
    }

    /** ⚠️ The reason is trimmed, a blank one is no reason, and one over [REASON_LIMIT] is refused here. */
    fun submit() {
        val dialog = host.current as? BookingDialog.Cancel ?: return
        if (dialog.state.isWorking) return
        val reason = dialog.reason.trim()
        if (reason.length > REASON_LIMIT) {
            host.show(dialog.copy(reasonTooLong = true))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        context.scope.launch {
            val result = context.admin.cancelBooking(context.workspaceId, dialog.bookingId, reason)
            host.update<BookingDialog.Cancel>(dialog.bookingId) {
                it.copy(state = outcome(result, R.string.scheduling_admin_bookings_cancel_done))
            }
            if (result is SchedulingAdminResult.Ok) context.onChanged()
        }
    }

    companion object {
        /** The longest cancellation reason the scheduler takes. */
        const val REASON_LIMIT: Int = 1000
    }
}

/**
 * Move one booking to another free slot of its event type.
 *
 * ⚠️ A SLOT TAKEN IN THE MEANTIME RE-READS THE DAY, so the member picks again from what is really
 * free, as the reference does.
 */
class BookingRescheduleFlow(
    private val host: BookingDialogHost,
    private val context: BookingWriteContext,
    private val timezone: () -> String,
    private val today: () -> LocalDate,
) {
    /** Open on the member's today. ⚠️ Only a booking with an event type can move: slots belong to one. */
    fun open(booking: SchedulingBooking) {
        val slug = booking.eventTypeSlug ?: return
        val start = today()
        host.show(BookingDialog.Reschedule(booking.id, slug, day = start, earliest = start))
        loadSlots()
    }

    fun previousDay() = moveDay(-1)

    fun nextDay() = moveDay(1)

    private fun moveDay(by: Long) {
        val dialog = host.current as? BookingDialog.Reschedule ?: return
        val day = dialog.day.plusDays(by)
        if (dialog.state.isWorking || day < dialog.earliest) return
        host.show(dialog.copy(day = day, selectedStart = null, pickRejected = false))
        loadSlots()
    }

    fun select(start: String) {
        val dialog = host.current as? BookingDialog.Reschedule ?: return
        host.show(dialog.copy(selectedStart = start, pickRejected = false, state = idleAfterFailure(dialog.state)))
    }

    /** Read the free slots of the dialog's day, in the member's zone. */
    fun loadSlots() {
        val dialog = host.current as? BookingDialog.Reschedule ?: return
        host.show(dialog.copy(slots = SectionState.Loading, selectedStart = null))
        val day = dialog.day.toString()
        context.scope.launch {
            val slots = context.admin.eventTypeSlots(
                context.workspaceId,
                dialog.eventTypeSlug,
                from = day,
                to = day,
                tz = timezone(),
            )
            host.update<BookingDialog.Reschedule>(dialog.bookingId) { current ->
                // ⚠️ A day the member has already left behind does not overwrite the one on screen.
                if (current.day != dialog.day) return@update current
                current.copy(slots = slots.map { it.slots }.toSectionState())
            }
        }
    }

    fun submit() {
        val dialog = host.current as? BookingDialog.Reschedule ?: return
        if (dialog.state.isWorking) return
        val start = dialog.selectedStart
        if (start == null) {
            host.show(dialog.copy(pickRejected = true))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        context.scope.launch {
            val result = context.admin.rescheduleBooking(context.workspaceId, dialog.bookingId, start)
            host.update<BookingDialog.Reschedule>(dialog.bookingId) {
                it.copy(
                    state = outcome(result, R.string.scheduling_admin_bookings_reschedule_done),
                    movedTo = (result as? SchedulingAdminResult.Ok)?.value?.startAt,
                )
            }
            if (result is SchedulingAdminResult.Ok) context.onChanged()
            if (result.isSlotTaken()) loadSlots()
        }
    }
}

/** Give one booking to another host. Offered only to a scheduler admin (`me.is_admin`). */
class BookingReassignFlow(private val host: BookingDialogHost, private val context: BookingWriteContext) {
    fun open(booking: SchedulingBooking) {
        host.show(BookingDialog.Reassign(booking.id, booking.hostId))
        loadHosts()
    }

    /** The scheduler users who could take it: not archived, and not the host it already has. */
    fun loadHosts() {
        val dialog = host.current as? BookingDialog.Reassign ?: return
        host.show(dialog.copy(hosts = SectionState.Loading))
        context.scope.launch {
            val users = context.admin.schedulerUsers(context.workspaceId)
                .map { rows -> rows.filter { !it.archived && it.id != dialog.currentHostId } }
            host.update<BookingDialog.Reassign>(dialog.bookingId) { it.copy(hosts = users.toSectionState()) }
        }
    }

    fun select(userId: String) {
        val dialog = host.current as? BookingDialog.Reassign ?: return
        host.show(dialog.copy(selectedHostId = userId, state = idleAfterFailure(dialog.state)))
    }

    fun submit() {
        val dialog = host.current as? BookingDialog.Reassign ?: return
        val hostId = dialog.selectedHostId ?: return
        if (dialog.state.isWorking) return
        host.show(dialog.copy(state = WriteState.Working))
        context.scope.launch {
            val result = context.admin.reassignBooking(context.workspaceId, dialog.bookingId, hostId)
            host.update<BookingDialog.Reassign>(dialog.bookingId) {
                it.copy(state = outcome(result, R.string.scheduling_admin_bookings_reassign_done))
            }
            if (result is SchedulingAdminResult.Ok) context.onChanged()
        }
    }
}

private fun outcome(result: SchedulingAdminResult<*>, @StringRes done: Int): WriteState = when (result) {
    is SchedulingAdminResult.Ok -> WriteState.Done(UiText.Resource(done))
    is SchedulingAdminResult.Failed -> WriteState.Failed(result.error.toFailureText())
}

/** Editing after a failure clears it, so the next press is a fresh attempt. */
private fun idleAfterFailure(state: WriteState): WriteState = if (state is WriteState.Failed) WriteState.Idle else state
