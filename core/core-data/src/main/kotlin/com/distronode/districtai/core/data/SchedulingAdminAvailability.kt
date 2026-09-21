package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingAvailabilityOverride
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOverrideCreated
import com.distronode.districtai.core.network.SchedulingAdminOp

/*
 * The nine `availability.*` ops, typed.
 *
 * ⛔ TWO SHAPES OF AVAILABILITY AND THEY ARE NOT LAYERS OF ONE THING. A RULE is a weekly window
 * that repeats forever; an OVERRIDE is a dated exception to it. Reading one to answer a question
 * about the other gives a plausible wrong answer — "is this member free on the 24th" is both, and
 * neither op answers it alone. `eventTypes.slots` is the op that composes them, which is why it
 * lives with the event types and not here.
 *
 * ⛔ THE PATH KEYS STAY IN THE BODY, for the reason `SchedulingAdminEventTypes.kt` states: the
 * route validates the whole params object against a schema that REQUIRES them and strips them
 * afterwards.
 */

suspend fun SchedulingAdminRepository.availabilityRules(
    workspaceId: String,
    eventTypeId: String? = null,
): SchedulingAdminOutcome<List<SchedulingAvailabilityRule>> = perform(
    SchedulingAdminOp.AVAILABILITY_RULES_LIST,
    workspaceId,
    // ⚠️ OMITTED WHEN NULL, WHICH IS "EVERY RULE" RATHER THAN "THE TENANCY-WIDE ONES". The server
    // filters only when the key is present; sending an explicit null would be a value it validates.
    schedulingParams("event_type_id" to textParam(eventTypeId)),
    SchedulingItems.serializer(SchedulingAvailabilityRule.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.createAvailabilityRule(
    workspaceId: String,
    eventTypeId: String?,
    dayOfWeek: Int,
    startTime: String,
    endTime: String,
): SchedulingAdminOutcome<SchedulingAvailabilityRule> = perform(
    SchedulingAdminOp.AVAILABILITY_RULES_CREATE,
    workspaceId,
    schedulingParams(
        "event_type_id" to textParam(eventTypeId),
        "day_of_week" to intParam(dayOfWeek),
        "start_time" to textParam(startTime),
        "end_time" to textParam(endTime),
    ),
    SchedulingAvailabilityRule.serializer(),
)

suspend fun SchedulingAdminRepository.patchAvailabilityRule(
    workspaceId: String,
    id: String,
    dayOfWeek: Int? = null,
    startTime: String? = null,
    endTime: String? = null,
): SchedulingAdminOutcome<SchedulingAvailabilityRule> = perform(
    SchedulingAdminOp.AVAILABILITY_RULES_PATCH,
    workspaceId,
    schedulingParams(
        "id" to textParam(id),
        "day_of_week" to intParam(dayOfWeek),
        "start_time" to textParam(startTime),
        "end_time" to textParam(endTime),
    ),
    SchedulingAvailabilityRule.serializer(),
)

suspend fun SchedulingAdminRepository.deleteAvailabilityRule(
    workspaceId: String,
    id: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.AVAILABILITY_RULES_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(id)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.availabilityOverrides(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingAvailabilityOverride>> = perform(
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingAvailabilityOverride.serializer()),
).map { it.items }

/**
 * ⛔ THE ONE OP ON THIS SURFACE WITH TWO RESPONSE SHAPES, decided by whether [draft] carries an
 * `endDate`. See [SchedulingOverrideCreated]; a caller that expected a single row gets a decode
 * failure on every range it creates.
 */
suspend fun SchedulingAdminRepository.createAvailabilityOverride(
    workspaceId: String,
    draft: SchedulingOverrideDraft,
): SchedulingAdminOutcome<SchedulingOverrideCreated> = perform(
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
    workspaceId,
    schedulingParams(
        "date" to textParam(draft.date),
        "reason" to textParam(draft.reason),
        "end_date" to textParam(draft.endDate),
        "start_time" to textParam(draft.startTime),
        "end_time" to textParam(draft.endTime),
    ),
    SchedulingOverrideCreated.serializer(),
)

suspend fun SchedulingAdminRepository.patchAvailabilityOverride(
    workspaceId: String,
    id: String,
    reason: String? = null,
    startTime: String? = null,
    endTime: String? = null,
): SchedulingAdminOutcome<SchedulingAvailabilityOverride> = perform(
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_PATCH,
    workspaceId,
    schedulingParams(
        "id" to textParam(id),
        "reason" to textParam(reason),
        "start_time" to textParam(startTime),
        "end_time" to textParam(endTime),
    ),
    SchedulingAvailabilityOverride.serializer(),
)

suspend fun SchedulingAdminRepository.deleteAvailabilityOverride(
    workspaceId: String,
    id: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(id)),
    SchedulingNoContent.serializer(),
)

/**
 * ⚠️ `groupId` IS CAMELCASE ON THE WIRE HERE AND NOWHERE ELSE IN THIS NAMESPACE. The catalog
 * declares `z.object({ groupId: … })` for this one op while every sibling uses snake_case, so a
 * "consistent" `group_id` is a 400 naming a missing required field. Copied from `admin-ops.ts`
 * rather than inferred.
 */
suspend fun SchedulingAdminRepository.deleteAvailabilityOverrideGroup(
    workspaceId: String,
    groupId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE_GROUP,
    workspaceId,
    schedulingParams("groupId" to textParam(groupId)),
    SchedulingNoContent.serializer(),
)
