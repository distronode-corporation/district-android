@file:Suppress("TooManyFunctions")

package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingHost
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.core.model.SchedulingSlots
import com.distronode.districtai.core.model.SchedulingTestEmailResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonObject

/*
 * The thirteen `eventTypes.*` ops, typed.
 *
 * ⛔ `@file:Suppress("TooManyFunctions")` AND NOT A SPLIT, WHICH IS A DECISION RATHER THAN A
 * SILENCING. detekt stops at eleven top-level functions; this namespace has thirteen, and cutting
 * it at eleven would put `eventTypes.questions.*` half in one file and half in another purely to
 * satisfy a count. The file boundary here mirrors the SERVER's namespace and iOS's extension
 * split, which is the boundary that means something.
 *
 * ⛔ THE PATH KEYS STAY IN THE BODY. `slug` and `id` are BOTH the address and a required member of
 * the op's params schema: the route validates the whole object and strips the path keys
 * afterwards, so a client that removed one first gets a 400 naming the field it was being tidy
 * about. Every function below puts them back.
 */

suspend fun SchedulingAdminRepository.listEventTypes(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingEventType>> = perform(
    SchedulingAdminOp.EVENT_TYPES_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingEventType.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.eventType(
    workspaceId: String,
    slug: String,
): SchedulingAdminOutcome<SchedulingEventType> = perform(
    SchedulingAdminOp.EVENT_TYPES_GET,
    workspaceId,
    schedulingParams("slug" to textParam(slug)),
    SchedulingEventType.serializer(),
)

suspend fun SchedulingAdminRepository.createEventType(
    workspaceId: String,
    draft: SchedulingEventTypeDraft,
): SchedulingAdminOutcome<SchedulingEventType> = perform(
    SchedulingAdminOp.EVENT_TYPES_CREATE,
    workspaceId,
    schedulingParams(
        "slug" to textParam(draft.slug),
        "name" to textParam(draft.name),
        "duration_minutes" to intParam(draft.durationMinutes),
        "description" to textParam(draft.description),
        "slot_interval_minutes" to intParam(draft.slotIntervalMinutes),
        "location_type" to textParam(draft.locationType),
        "location_value" to textParam(draft.locationValue),
        "routing_mode" to textParam(draft.routingMode),
        "buffer_before_minutes" to intParam(draft.bufferBeforeMinutes),
        "buffer_after_minutes" to intParam(draft.bufferAfterMinutes),
        "min_notice_minutes" to intParam(draft.minNoticeMinutes),
        "max_future_days" to intParam(draft.maxFutureDays),
        "max_active_bookings" to intParam(draft.maxActiveBookings),
        "show_taken_slots" to boolParam(draft.showTakenSlots),
    ),
    SchedulingEventType.serializer(),
)

suspend fun SchedulingAdminRepository.patchEventType(
    workspaceId: String,
    slug: String,
    changes: SchedulingEventTypeChanges,
): SchedulingAdminOutcome<SchedulingEventType> = perform(
    SchedulingAdminOp.EVENT_TYPES_PATCH,
    workspaceId,
    eventTypePatchParams(slug, changes),
    SchedulingEventType.serializer(),
)

suspend fun SchedulingAdminRepository.deleteEventType(
    workspaceId: String,
    slug: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.EVENT_TYPES_DELETE,
    workspaceId,
    schedulingParams("slug" to textParam(slug)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.eventTypeHosts(
    workspaceId: String,
    slug: String,
): SchedulingAdminOutcome<List<SchedulingHost>> = perform(
    SchedulingAdminOp.EVENT_TYPES_HOSTS_GET,
    workspaceId,
    schedulingParams("slug" to textParam(slug)),
    SchedulingItems.serializer(SchedulingHost.serializer()),
).map { it.items }

/**
 * ⛔ A PUT, SO THE LIST IS THE WHOLE ASSIGNMENT AND NOT AN ADDITION. A host absent from [hosts] is
 * REMOVED from the event type; sending one new entry replaces the roster.
 */
suspend fun SchedulingAdminRepository.putEventTypeHosts(
    workspaceId: String,
    slug: String,
    hosts: List<SchedulingHostAssignment>,
): SchedulingAdminOutcome<List<SchedulingHost>> = perform(
    SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT,
    workspaceId,
    schedulingParams(
        "slug" to textParam(slug),
        "hosts" to objectsParam(
            hosts.map { host ->
                schedulingParams(
                    "user_id" to textParam(host.userId),
                    "role" to textParam(host.role),
                    "priority" to intParam(host.priority),
                )
            },
        ),
    ),
    SchedulingItems.serializer(SchedulingHost.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.sendEventTypeTestEmail(
    workspaceId: String,
    slug: String,
    type: String,
): SchedulingAdminOutcome<SchedulingTestEmailResult> = perform(
    SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL,
    workspaceId,
    schedulingParams("slug" to textParam(slug), "type" to textParam(type)),
    SchedulingTestEmailResult.serializer(),
)

suspend fun SchedulingAdminRepository.eventTypeQuestions(
    workspaceId: String,
    slug: String,
): SchedulingAdminOutcome<List<SchedulingQuestion>> = perform(
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST,
    workspaceId,
    schedulingParams("slug" to textParam(slug)),
    SchedulingItems.serializer(SchedulingQuestion.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.createEventTypeQuestion(
    workspaceId: String,
    slug: String,
    draft: SchedulingQuestionDraft,
): SchedulingAdminOutcome<SchedulingQuestion> = perform(
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE,
    workspaceId,
    schedulingParams(
        "slug" to textParam(slug),
        "label" to textParam(draft.label),
        "type" to textParam(draft.type),
        "required" to boolParam(draft.required),
        "options" to textsParam(draft.options),
        "position" to intParam(draft.position),
    ),
    SchedulingQuestion.serializer(),
)

suspend fun SchedulingAdminRepository.patchEventTypeQuestion(
    workspaceId: String,
    slug: String,
    id: String,
    changes: SchedulingQuestionChanges,
): SchedulingAdminOutcome<SchedulingQuestion> = perform(
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH,
    workspaceId,
    schedulingParams(
        "slug" to textParam(slug),
        "id" to textParam(id),
        "label" to textParam(changes.label),
        "type" to textParam(changes.type),
        "options" to textsParam(changes.options),
        "required" to boolParam(changes.required),
        "position" to intParam(changes.position),
    ),
    SchedulingQuestion.serializer(),
)

suspend fun SchedulingAdminRepository.deleteEventTypeQuestion(
    workspaceId: String,
    slug: String,
    id: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE,
    workspaceId,
    schedulingParams("slug" to textParam(slug), "id" to textParam(id)),
    SchedulingNoContent.serializer(),
)

/**
 * ⚠️ THE ONE READ ON THIS SURFACE THAT COMPOSES TWO OTHERS. Slots are the weekly rules AND the
 * dated overrides AND the existing bookings, resolved server-side; neither
 * `availability.rules.list` nor `availability.overrides.list` answers "is this host free" alone.
 */
suspend fun SchedulingAdminRepository.eventTypeSlots(
    workspaceId: String,
    slug: String,
    from: String? = null,
    to: String? = null,
    timezone: String? = null,
): SchedulingAdminOutcome<SchedulingSlots> = perform(
    SchedulingAdminOp.EVENT_TYPES_SLOTS,
    workspaceId,
    schedulingParams(
        "slug" to textParam(slug),
        "from" to textParam(from),
        "to" to textParam(to),
        "tz" to textParam(timezone),
    ),
    SchedulingSlots.serializer(),
)

/**
 * ⚠️ EXTRACTED BECAUSE IT IS TWENTY-EIGHT PAIRS, not because it is shared. Inlining it puts
 * `patchEventType` over detekt's 80-line `LongMethod` ceiling, and the list is the catalog's
 * order so a missing key is visible by diffing against `admin-ops.ts`.
 */
private fun eventTypePatchParams(
    slug: String,
    changes: SchedulingEventTypeChanges,
): JsonObject = schedulingParams(
    "slug" to textParam(slug),
    "name" to textParam(changes.name),
    "description" to textParam(changes.description),
    "duration_minutes" to intParam(changes.durationMinutes),
    "slot_interval_minutes" to intParam(changes.slotIntervalMinutes),
    "location_type" to textParam(changes.locationType),
    "location_value" to textParam(changes.locationValue),
    "routing_mode" to textParam(changes.routingMode),
    "rr_strategy" to textParam(changes.rrStrategy),
    "buffer_before_minutes" to intParam(changes.bufferBeforeMinutes),
    "buffer_after_minutes" to intParam(changes.bufferAfterMinutes),
    "min_notice_minutes" to intParam(changes.minNoticeMinutes),
    "max_future_days" to intParam(changes.maxFutureDays),
    "max_active_bookings" to intParam(changes.maxActiveBookings),
    "is_active" to boolParam(changes.isActive),
    "is_public" to boolParam(changes.isPublic),
    "show_taken_slots" to boolParam(changes.showTakenSlots),
    "archived" to boolParam(changes.archived),
    "msg_confirmation" to textParam(changes.msgConfirmation),
    "msg_cancellation" to textParam(changes.msgCancellation),
    "msg_reschedule" to textParam(changes.msgReschedule),
    "msg_reminder" to textParam(changes.msgReminder),
    "msg_greeting" to textParam(changes.msgGreeting),
    "subj_confirmation" to textParam(changes.subjConfirmation),
    "subj_cancellation" to textParam(changes.subjCancellation),
    "subj_reschedule" to textParam(changes.subjReschedule),
    "subj_reminder" to textParam(changes.subjReminder),
    "reminders" to intsParam(changes.reminders),
)
