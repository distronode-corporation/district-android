package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingUpcomingBooking
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.core.model.SchedulingUserArchived
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.builtins.ListSerializer

/*
 * The three `users.*` ops, typed. Split from the eight `teams.*` ops in
 * `SchedulingAdminTeam.kt` along the seam the server already draws, which also keeps either file
 * under detekt's per-file function ceiling.
 *
 * ⛔ THESE ARE THE TENANCY'S SCHEDULER USERS, NOT DISTRONODE'S WORKSPACE MEMBERS. A scheduler user
 * is a host inside the booking fork with its own id, its own role vocabulary and its own archive
 * state; `WorkspaceRole` describes a different population with a different lifecycle. The one
 * place they touch is that archiving a scheduler user is what closes out a member whose platform
 * removal was refused.
 *
 * ⛔ `users.list` ANSWERS A BARE ARRAY, WHICH IS THE ONE READ ON THIS SURFACE WITH NO WRAPPER AT
 * ALL — not `{items}`, not `{users}`. Reading it through `SchedulingItems` is a decode failure
 * rather than an empty list, which is the better of the two wrong answers and still wrong.
 */

/*
 * The three `users.*` and eight `teams.*` ops, typed.
 *
 * ⛔ THESE ARE THE TENANCY'S SCHEDULER USERS, NOT DISTRONODE'S WORKSPACE MEMBERS. A scheduler user
 * is a host inside the booking fork with its own id, its own role vocabulary and its own archive
 * state; `WorkspaceRole` describes a different population with a different lifecycle. The one
 * place they touch is that archiving a scheduler user is what closes out a member whose platform
 * removal was refused.
 *
 * ⛔ `users.list` ANSWERS A BARE ARRAY, WHICH IS THE ONE READ ON THIS SURFACE WITH NO WRAPPER AT
 * ALL — not `{items}`, not `{users}`. Reading it through `SchedulingItems` is a decode failure
 * rather than an empty list, which is the better of the two wrong answers and still wrong.
 */

suspend fun SchedulingAdminRepository.schedulerUsers(
    workspaceId: String,
    includeArchived: Boolean = false,
): SchedulingAdminOutcome<List<SchedulingUser>> = perform(
    SchedulingAdminOp.USERS_LIST,
    workspaceId,
    // ⚠️ OMITTED WHEN FALSE rather than sent as `false`: the catalog's param is a presence flag
    // that the fork turns into a query string, and `include_archived=false` is not the same URL as
    // no parameter at all on every fork build.
    schedulingParams("include_archived" to boolParam(true.takeIf { includeArchived })),
    ListSerializer(SchedulingUser.serializer()),
)

/**
 * ⛔ ARCHIVE IS NOT DELETE, AND THE DIFFERENCE IS VISIBLE TO CUSTOMERS. An archived host keeps
 * their past bookings and stops being offered new ones; the fork has no delete. Check
 * [upcomingBookings] first — archiving somebody with a booked call leaves the attendee with an
 * invitation nobody will answer.
 */
suspend fun SchedulingAdminRepository.archiveSchedulerUser(
    workspaceId: String,
    userId: String,
): SchedulingAdminOutcome<SchedulingUserArchived> = perform(
    SchedulingAdminOp.USERS_ARCHIVE,
    workspaceId,
    schedulingParams("id" to textParam(userId)),
    SchedulingUserArchived.serializer(),
)

suspend fun SchedulingAdminRepository.upcomingBookings(
    workspaceId: String,
    userId: String,
): SchedulingAdminOutcome<List<SchedulingUpcomingBooking>> = perform(
    SchedulingAdminOp.USERS_UPCOMING_BOOKINGS,
    workspaceId,
    schedulingParams("id" to textParam(userId)),
    SchedulingItems.serializer(SchedulingUpcomingBooking.serializer()),
).map { it.items }
