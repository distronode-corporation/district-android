package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonPrimitive

// A team's members. See SchedulingAdminTeamOps.kt for the `user_id` / `userId` split.

/** Add a member. ⚠️ A null priority lets the scheduler choose. */
suspend fun SchedulingAdminRepository.addTeamMember(
    workspaceId: String,
    teamId: String,
    userId: String,
    routingPriority: Int? = null,
): SchedulingAdminResult<SchedulingTeam> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_MEMBERS_ADD,
    schedulingParams(
        "id" to JsonPrimitive(teamId),
        "user_id" to JsonPrimitive(userId),
        "routing_priority" to routingPriority.jsonOrNull(),
    ),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.setTeamMemberPriority(
    workspaceId: String,
    teamId: String,
    userId: String,
    routingPriority: Int,
): SchedulingAdminResult<SchedulingTeam> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_MEMBERS_PATCH,
    schedulingParams(
        "id" to JsonPrimitive(teamId),
        "userId" to JsonPrimitive(userId),
        "routing_priority" to JsonPrimitive(routingPriority),
    ),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.removeTeamMember(
    workspaceId: String,
    teamId: String,
    userId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.TEAMS_MEMBERS_REMOVE,
    schedulingParams("id" to JsonPrimitive(teamId), "userId" to JsonPrimitive(userId)),
    SchedulingNoContent.serializer(),
)
