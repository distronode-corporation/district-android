package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.network.SchedulingAdminOp

/*
 * The eight `teams.*` ops, typed. The three `users.*` ops are in `SchedulingAdminUsers.kt`; see
 * that file for why a scheduler user is not a workspace member.
 */

suspend fun SchedulingAdminRepository.teams(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingTeam>> = perform(
    SchedulingAdminOp.TEAMS_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingTeam.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.team(
    workspaceId: String,
    teamId: String,
): SchedulingAdminOutcome<SchedulingTeam> = perform(
    SchedulingAdminOp.TEAMS_GET,
    workspaceId,
    schedulingParams("id" to textParam(teamId)),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.createTeam(
    workspaceId: String,
    name: String,
    slug: String? = null,
): SchedulingAdminOutcome<SchedulingTeam> = perform(
    SchedulingAdminOp.TEAMS_CREATE,
    workspaceId,
    schedulingParams("name" to textParam(name), "slug" to textParam(slug)),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.patchTeam(
    workspaceId: String,
    teamId: String,
    name: String? = null,
    slug: String? = null,
): SchedulingAdminOutcome<SchedulingTeam> = perform(
    SchedulingAdminOp.TEAMS_PATCH,
    workspaceId,
    schedulingParams(
        "id" to textParam(teamId),
        "name" to textParam(name),
        "slug" to textParam(slug),
    ),
    SchedulingTeam.serializer(),
)

/**
 * ⚠️ ANSWERS `{ok}` RATHER THAN NO CONTENT, which is the catalog's shape for this op and for
 * [removeTeamMember]. The two are structurally identical to a `NO_CONTENT` answer on the wire and
 * are declared separately server-side, so the near-miss is deliberate rather than a shared type.
 */
suspend fun SchedulingAdminRepository.deleteTeam(
    workspaceId: String,
    teamId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.TEAMS_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(teamId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.addTeamMember(
    workspaceId: String,
    teamId: String,
    userId: String,
    routingPriority: Int? = null,
): SchedulingAdminOutcome<SchedulingTeam> = perform(
    SchedulingAdminOp.TEAMS_MEMBERS_ADD,
    workspaceId,
    // ⚠️ `user_id` HERE AND `userId` ON THE PATCH AND REMOVE OPS BELOW. The add op takes the user
    // in its BODY (snake_case, like every other body key); the other two take it as a PATH key,
    // and the catalog spells path keys in camelCase. Copied, not normalised.
    schedulingParams(
        "id" to textParam(teamId),
        "user_id" to textParam(userId),
        "routing_priority" to intParam(routingPriority),
    ),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.setTeamMemberPriority(
    workspaceId: String,
    teamId: String,
    userId: String,
    routingPriority: Int,
): SchedulingAdminOutcome<SchedulingTeam> = perform(
    SchedulingAdminOp.TEAMS_MEMBERS_PATCH,
    workspaceId,
    schedulingParams(
        "id" to textParam(teamId),
        "userId" to textParam(userId),
        "routing_priority" to intParam(routingPriority),
    ),
    SchedulingTeam.serializer(),
)

suspend fun SchedulingAdminRepository.removeTeamMember(
    workspaceId: String,
    teamId: String,
    userId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.TEAMS_MEMBERS_REMOVE,
    workspaceId,
    schedulingParams("id" to textParam(teamId), "userId" to textParam(userId)),
    SchedulingNoContent.serializer(),
)
