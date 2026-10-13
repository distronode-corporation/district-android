package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.model.SchedulingUpcomingBooking
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.core.model.SchedulingUserArchived
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive

// The tenancy's scheduler users, and its teams.
//
// ⚠️ THE MEMBER KEY IS `user_id` ON `teams.members.add` AND `userId` ON THE PATCH AND THE REMOVE,
// which is the catalog's spelling (a path key on the latter two), copied from district-core-swift.

/** ⛔ `users.list` ANSWERS A BARE ARRAY, the one read on the surface with no `{items}` wrapper. */
suspend fun SchedulingAdminRepository.schedulerUsers(
    workspaceId: String,
    includeArchived: Boolean = false,
): SchedulingAdminResult<List<SchedulingUser>> = perform(
    workspaceId,
    SchedulingAdminOp.USERS_LIST,
    schedulingParams("include_archived" to (if (includeArchived) JsonPrimitive(true) else null)),
    ListSerializer(SchedulingUser.serializer()),
)

suspend fun SchedulingAdminRepository.archiveSchedulerUser(
    workspaceId: String,
    userId: String,
): SchedulingAdminResult<SchedulingUserArchived> = perform(
    workspaceId,
    SchedulingAdminOp.USERS_ARCHIVE,
    schedulingParams("id" to JsonPrimitive(userId)),
    SchedulingUserArchived.serializer(),
)

suspend fun SchedulingAdminRepository.upcomingBookings(
    workspaceId: String,
    userId: String,
): SchedulingAdminResult<List<SchedulingUpcomingBooking>> = perform(
    workspaceId,
    SchedulingAdminOp.USERS_UPCOMING_BOOKINGS,
    schedulingParams("id" to JsonPrimitive(userId)),
    SchedulingItems.serializer(SchedulingUpcomingBooking.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.teams(
    workspaceId: String,
): SchedulingAdminResult<List<SchedulingTeam>> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_LIST,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingTeam.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.team(
    workspaceId: String,
    teamId: String,
): SchedulingAdminResult<SchedulingTeam> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_GET,
    schedulingParams("id" to JsonPrimitive(teamId)),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.createTeam(
    workspaceId: String,
    name: String,
    slug: String? = null,
): SchedulingAdminResult<SchedulingTeam> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_CREATE,
    schedulingParams("name" to JsonPrimitive(name), "slug" to slug.jsonOrNull()),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.patchTeam(
    workspaceId: String,
    teamId: String,
    name: String? = null,
    slug: String? = null,
): SchedulingAdminResult<SchedulingTeam> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_PATCH,
    schedulingParams("id" to JsonPrimitive(teamId), "name" to name.jsonOrNull(), "slug" to slug.jsonOrNull()),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.deleteTeam(
    workspaceId: String,
    teamId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_DELETE,
    schedulingParams("id" to JsonPrimitive(teamId)),
    SchedulingNoContent.serializer(),
)
