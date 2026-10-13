package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingBookingAnswer
import com.distronode.districtai.core.model.SchedulingBookingPage
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonPrimitive

// The five `bookings.*` ops.

suspend fun SchedulingAdminRepository.bookings(
    workspaceId: String,
    filter: SchedulingBookingFilter,
): SchedulingAdminResult<SchedulingBookingPage> = perform(
    workspaceId,
    SchedulingAdminOp.BOOKINGS_LIST,
    schedulingParams(
        "status" to filter.status.blankAsNull().jsonOrNull(),
        "when" to filter.whenFilter.blankAsNull().jsonOrNull(),
        "from" to filter.from.blankAsNull().jsonOrNull(),
        "to" to filter.to.blankAsNull().jsonOrNull(),
        "event_type" to filter.eventTypeSlug.blankAsNull().jsonOrNull(),
        "host" to filter.host.blankAsNull().jsonOrNull(),
        "team" to filter.team.blankAsNull().jsonOrNull(),
        "limit" to filter.limit.jsonOrNull(),
        "offset" to filter.offset.jsonOrNull(),
        "scope" to (if (filter.allHosts) JsonPrimitive("all") else null),
        "order" to filter.order.blankAsNull().jsonOrNull(),
    ),
    SchedulingBookingPage.serializer(),
)

suspend fun SchedulingAdminRepository.bookingAnswers(
    workspaceId: String,
    bookingId: String,
): SchedulingAdminResult<List<SchedulingBookingAnswer>> = perform(
    workspaceId,
    SchedulingAdminOp.BOOKINGS_ANSWERS,
    schedulingParams("id" to JsonPrimitive(bookingId)),
    SchedulingItems.serializer(SchedulingBookingAnswer.serializer()),
).map { it.items }

/** Cancel a booking. ⚠️ A blank reason is sent as no reason at all. */
suspend fun SchedulingAdminRepository.cancelBooking(
    workspaceId: String,
    bookingId: String,
    reason: String?,
): SchedulingAdminResult<SchedulingBooking> = perform(
    workspaceId,
    SchedulingAdminOp.BOOKINGS_CANCEL,
    schedulingParams("id" to JsonPrimitive(bookingId), "reason" to reason.blankAsNull().jsonOrNull()),
    SchedulingBooking.serializer(),
)

/** Move a booking to [startAt], an instant taken from a slot the server offered. */
suspend fun SchedulingAdminRepository.rescheduleBooking(
    workspaceId: String,
    bookingId: String,
    startAt: String,
): SchedulingAdminResult<SchedulingBooking> = perform(
    workspaceId,
    SchedulingAdminOp.BOOKINGS_RESCHEDULE,
    schedulingParams("id" to JsonPrimitive(bookingId), "start_at" to JsonPrimitive(startAt)),
    SchedulingBooking.serializer(),
)

/** Give a booking to another scheduler user. ⚠️ [hostId] is the scheduler's user id. */
suspend fun SchedulingAdminRepository.reassignBooking(
    workspaceId: String,
    bookingId: String,
    hostId: String,
): SchedulingAdminResult<SchedulingBooking> = perform(
    workspaceId,
    SchedulingAdminOp.BOOKINGS_REASSIGN,
    schedulingParams("id" to JsonPrimitive(bookingId), "host_id" to JsonPrimitive(hostId)),
    SchedulingBooking.serializer(),
)

internal fun String?.blankAsNull(): String? = this?.takeIf { it.isNotBlank() }
