package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingAvailabilityOverride
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOverrideCreated
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonPrimitive

// The `availability.*` ops.
//
// ⛔ TWO SHAPES OF AVAILABILITY AND THEY ARE NOT LAYERS OF ONE THING. A RULE is a weekly window
// that repeats forever; an OVERRIDE is a dated exception to it. `eventTypes.slots` is the op that
// composes them.
//
// ⚠️ `availability.overrides.patch` HAS NO WRAPPER, as in district-core-swift: the overrides
// screen only creates, lists and deletes, so editing a day is a delete and a create.

/** The weekly rules; with [eventTypeId], one event type's own rules. */
suspend fun SchedulingAdminRepository.availabilityRules(
    workspaceId: String,
    eventTypeId: String? = null,
): SchedulingAdminResult<List<SchedulingAvailabilityRule>> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_RULES_LIST,
    schedulingParams("event_type_id" to eventTypeId.jsonOrNull()),
    SchedulingItems.serializer(SchedulingAvailabilityRule.serializer()),
).map { it.items }

/**
 * Add a weekly window.
 *
 * @param dayOfWeek 0 is Sunday, the scheduler's numbering.
 * @param startTime zero-padded `HH:MM`.
 */
suspend fun SchedulingAdminRepository.createAvailabilityRule(
    workspaceId: String,
    eventTypeId: String?,
    dayOfWeek: Int,
    startTime: String,
    endTime: String,
): SchedulingAdminResult<SchedulingAvailabilityRule> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_RULES_CREATE,
    schedulingParams(
        "event_type_id" to eventTypeId.jsonOrNull(),
        "day_of_week" to JsonPrimitive(dayOfWeek),
        "start_time" to JsonPrimitive(startTime),
        "end_time" to JsonPrimitive(endTime),
    ),
    SchedulingAvailabilityRule.serializer(),
)

suspend fun SchedulingAdminRepository.patchAvailabilityRule(
    workspaceId: String,
    id: String,
    dayOfWeek: Int? = null,
    startTime: String? = null,
    endTime: String? = null,
): SchedulingAdminResult<SchedulingAvailabilityRule> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_RULES_PATCH,
    schedulingParams(
        "id" to JsonPrimitive(id),
        "day_of_week" to dayOfWeek.jsonOrNull(),
        "start_time" to startTime.jsonOrNull(),
        "end_time" to endTime.jsonOrNull(),
    ),
    SchedulingAvailabilityRule.serializer(),
)

suspend fun SchedulingAdminRepository.deleteAvailabilityRule(
    workspaceId: String,
    id: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_RULES_DELETE,
    schedulingParams("id" to JsonPrimitive(id)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.availabilityOverrides(
    workspaceId: String,
): SchedulingAdminResult<List<SchedulingAvailabilityOverride>> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingAvailabilityOverride.serializer()),
).map { it.items }

/** Add a dated override. ⛔ A draft with an end date answers a range summary, not a row. */
suspend fun SchedulingAdminRepository.createAvailabilityOverride(
    workspaceId: String,
    draft: SchedulingOverrideDraft,
): SchedulingAdminResult<SchedulingOverrideCreated> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
    schedulingParams(
        "date" to JsonPrimitive(draft.date),
        "reason" to JsonPrimitive(draft.reason),
        "end_date" to draft.endDate.jsonOrNull(),
        "start_time" to draft.startTime.jsonOrNull(),
        "end_time" to draft.endTime.jsonOrNull(),
    ),
    SchedulingOverrideCreated.serializer(),
)

suspend fun SchedulingAdminRepository.deleteAvailabilityOverride(
    workspaceId: String,
    id: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE,
    schedulingParams("id" to JsonPrimitive(id)),
    SchedulingNoContent.serializer(),
)

/** Delete every day of a range at once. ⚠️ The key is `groupId`, camel case, as the catalog has it. */
suspend fun SchedulingAdminRepository.deleteAvailabilityOverrideGroup(
    workspaceId: String,
    groupId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE_GROUP,
    schedulingParams("groupId" to JsonPrimitive(groupId)),
    SchedulingNoContent.serializer(),
)
