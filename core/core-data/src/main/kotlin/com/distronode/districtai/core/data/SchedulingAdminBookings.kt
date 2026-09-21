package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingBookingAnswer
import com.distronode.districtai.core.model.SchedulingBookingNotes
import com.distronode.districtai.core.model.SchedulingBookingNotesRegenerated
import com.distronode.districtai.core.model.SchedulingBookingPage
import com.distronode.districtai.core.model.SchedulingBookingTranscript
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.network.SchedulingAdminOp

/*
 * The eight `bookings.*` ops, typed.
 *
 * ⛔ FOUR OF THE EIGHT ARE `viewer` READS AND FOUR ARE `client` WRITES, AND THE SCHEDULER GATES
 * THEM AGAIN ON ITS OWN TERMS. `bookings.notes`, `bookings.transcript` and `bookings.reassign`
 * carry a `requireAdmin` at the fork, and the cancel/reschedule pair carry host-ownership checks
 * against the MEMBER's key — so a District `client` can still come back 403 on a booking that is
 * not theirs. The op's `minRole` decides whether to DRAW the control, never whether it will work.
 */

/**
 * One page of bookings.
 *
 * ⚠️ [SchedulingBookingQuery.allHosts] IS SENT AS `scope=all` AND OMITTED WHEN FALSE. The server
 * reads presence rather than value, so `scope=false` would be a filter it does not know.
 */
suspend fun SchedulingAdminRepository.bookings(
    workspaceId: String,
    query: SchedulingBookingQuery = SchedulingBookingQuery(),
): SchedulingAdminOutcome<SchedulingBookingPage> = perform(
    SchedulingAdminOp.BOOKINGS_LIST,
    workspaceId,
    schedulingParams(
        "status" to textParam(query.status),
        "when" to textParam(query.whenFilter),
        "from" to textParam(query.from),
        "to" to textParam(query.to),
        // ⚠️ `event_type`, NOT `event_type_slug`. The catalog names the param after the resource
        // and takes a SLUG in it; the row's own key is `event_type_slug`, one hop away.
        "event_type" to textParam(query.eventTypeSlug),
        "host" to textParam(query.host),
        "team" to textParam(query.team),
        "limit" to intParam(query.limit),
        "offset" to intParam(query.offset),
        "scope" to textParam(if (query.allHosts) "all" else null),
        "order" to textParam(query.order),
    ),
    SchedulingBookingPage.serializer(),
)

suspend fun SchedulingAdminRepository.bookingAnswers(
    workspaceId: String,
    bookingId: String,
): SchedulingAdminOutcome<List<SchedulingBookingAnswer>> = perform(
    SchedulingAdminOp.BOOKINGS_ANSWERS,
    workspaceId,
    schedulingParams("id" to textParam(bookingId)),
    SchedulingItems.serializer(SchedulingBookingAnswer.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.cancelBooking(
    workspaceId: String,
    bookingId: String,
    reason: String? = null,
): SchedulingAdminOutcome<SchedulingBooking> = perform(
    SchedulingAdminOp.BOOKINGS_CANCEL,
    workspaceId,
    schedulingParams("id" to textParam(bookingId), "reason" to textParam(reason)),
    SchedulingBooking.serializer(),
)

/**
 * ⛔ THE ONE OP THAT CAN ANSWER [SchedulingAdminFailureCode.SLOT_TAKEN], and the only code whose
 * recovery is "choose something else" rather than "try again". A retry here books nothing and
 * tells the operator the same thing twice.
 */
suspend fun SchedulingAdminRepository.rescheduleBooking(
    workspaceId: String,
    bookingId: String,
    startAt: String,
): SchedulingAdminOutcome<SchedulingBooking> = perform(
    SchedulingAdminOp.BOOKINGS_RESCHEDULE,
    workspaceId,
    schedulingParams("id" to textParam(bookingId), "start_at" to textParam(startAt)),
    SchedulingBooking.serializer(),
)

suspend fun SchedulingAdminRepository.reassignBooking(
    workspaceId: String,
    bookingId: String,
    hostId: String,
): SchedulingAdminOutcome<SchedulingBooking> = perform(
    SchedulingAdminOp.BOOKINGS_REASSIGN,
    workspaceId,
    schedulingParams("id" to textParam(bookingId), "host_id" to textParam(hostId)),
    SchedulingBooking.serializer(),
)

suspend fun SchedulingAdminRepository.bookingNotes(
    workspaceId: String,
    bookingId: String,
): SchedulingAdminOutcome<SchedulingBookingNotes> = perform(
    SchedulingAdminOp.BOOKINGS_NOTES,
    workspaceId,
    schedulingParams("id" to textParam(bookingId)),
    SchedulingBookingNotes.serializer(),
)

/**
 * ⛔ A BILLED GENERATION AND NOT A REFRESH. It spends a model call at the fork and is budgeted as
 * a write; a screen that called it to poll for `status: pending` becoming `ready` would pay for a
 * new generation each time. Re-read [bookingNotes] instead.
 */
suspend fun SchedulingAdminRepository.regenerateBookingNotes(
    workspaceId: String,
    bookingId: String,
): SchedulingAdminOutcome<SchedulingBookingNotesRegenerated> = perform(
    SchedulingAdminOp.BOOKINGS_NOTES_REGENERATE,
    workspaceId,
    schedulingParams("id" to textParam(bookingId)),
    SchedulingBookingNotesRegenerated.serializer(),
)

suspend fun SchedulingAdminRepository.bookingTranscript(
    workspaceId: String,
    bookingId: String,
): SchedulingAdminOutcome<SchedulingBookingTranscript> = perform(
    SchedulingAdminOp.BOOKINGS_TRANSCRIPT,
    workspaceId,
    schedulingParams("id" to textParam(bookingId)),
    SchedulingBookingTranscript.serializer(),
)
