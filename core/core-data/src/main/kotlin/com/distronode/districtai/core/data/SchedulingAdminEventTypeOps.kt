package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingSlots
import com.distronode.districtai.core.model.SchedulingTestEmailResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonPrimitive

// The event types themselves. Their hosts and questions are in SchedulingAdminEventTypeChildOps.kt.

suspend fun SchedulingAdminRepository.eventTypes(
    workspaceId: String,
): SchedulingAdminResult<List<SchedulingEventType>> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_LIST,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingEventType.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.eventType(
    workspaceId: String,
    slug: String,
): SchedulingAdminResult<SchedulingEventType> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_GET,
    schedulingParams("slug" to JsonPrimitive(slug)),
    SchedulingEventType.serializer(),
)

suspend fun SchedulingAdminRepository.createEventType(
    workspaceId: String,
    draft: SchedulingEventTypeDraft,
): SchedulingAdminResult<SchedulingEventType> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_CREATE,
    schedulingParams(
        "slug" to JsonPrimitive(draft.slug),
        "name" to JsonPrimitive(draft.name),
        "duration_minutes" to JsonPrimitive(draft.durationMinutes),
        "description" to draft.description.jsonOrNull(),
        "slot_interval_minutes" to draft.slotIntervalMinutes.jsonOrNull(),
        "location_type" to draft.locationType.jsonOrNull(),
        "location_value" to draft.locationValue.jsonOrNull(),
        "routing_mode" to draft.routingMode.jsonOrNull(),
        "buffer_before_minutes" to draft.bufferBeforeMinutes.jsonOrNull(),
        "buffer_after_minutes" to draft.bufferAfterMinutes.jsonOrNull(),
        "min_notice_minutes" to draft.minNoticeMinutes.jsonOrNull(),
        "max_future_days" to draft.maxFutureDays.jsonOrNull(),
        "max_active_bookings" to draft.maxActiveBookings.jsonOrNull(),
        "show_taken_slots" to draft.showTakenSlots.jsonOrNull(),
    ),
    SchedulingEventType.serializer(),
)

suspend fun SchedulingAdminRepository.patchEventType(
    workspaceId: String,
    slug: String,
    changes: SchedulingEventTypeChanges,
): SchedulingAdminResult<SchedulingEventType> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_PATCH,
    schedulingParams(
        "slug" to JsonPrimitive(slug),
        "name" to changes.name.jsonOrNull(),
        "description" to changes.description.jsonOrNull(),
        "duration_minutes" to changes.durationMinutes.jsonOrNull(),
        "slot_interval_minutes" to changes.slotIntervalMinutes.jsonOrNull(),
        "location_type" to changes.locationType.jsonOrNull(),
        "location_value" to changes.locationValue.jsonOrNull(),
        "routing_mode" to changes.routingMode.jsonOrNull(),
        "rr_strategy" to changes.rrStrategy.jsonOrNull(),
        "buffer_before_minutes" to changes.bufferBeforeMinutes.jsonOrNull(),
        "buffer_after_minutes" to changes.bufferAfterMinutes.jsonOrNull(),
        "min_notice_minutes" to changes.minNoticeMinutes.jsonOrNull(),
        "max_future_days" to changes.maxFutureDays.jsonOrNull(),
        "max_active_bookings" to changes.maxActiveBookings.jsonOrNull(),
        "is_active" to changes.isActive.jsonOrNull(),
        "is_public" to changes.isPublic.jsonOrNull(),
        "show_taken_slots" to changes.showTakenSlots.jsonOrNull(),
        "archived" to changes.archived.jsonOrNull(),
        "msg_confirmation" to changes.msgConfirmation.jsonOrNull(),
        "msg_cancellation" to changes.msgCancellation.jsonOrNull(),
        "msg_reschedule" to changes.msgReschedule.jsonOrNull(),
        "msg_reminder" to changes.msgReminder.jsonOrNull(),
        "msg_greeting" to changes.msgGreeting.jsonOrNull(),
        "subj_confirmation" to changes.subjConfirmation.jsonOrNull(),
        "subj_cancellation" to changes.subjCancellation.jsonOrNull(),
        "subj_reschedule" to changes.subjReschedule.jsonOrNull(),
        "subj_reminder" to changes.subjReminder.jsonOrNull(),
        "reminders" to changes.reminders.jsonIntsOrNull(),
    ),
    SchedulingEventType.serializer(),
)

suspend fun SchedulingAdminRepository.deleteEventType(
    workspaceId: String,
    slug: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_DELETE,
    schedulingParams("slug" to JsonPrimitive(slug)),
    SchedulingNoContent.serializer(),
)

/** Send the member a test email of one kind (`confirmation`, `cancellation`, …). */
suspend fun SchedulingAdminRepository.sendEventTypeTestEmail(
    workspaceId: String,
    slug: String,
    type: String,
): SchedulingAdminResult<SchedulingTestEmailResult> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL,
    schedulingParams("slug" to JsonPrimitive(slug), "type" to JsonPrimitive(type)),
    SchedulingTestEmailResult.serializer(),
)

/**
 * The bookable times of one event type.
 *
 * @param from `YYYY-MM-DD`, read in [tz].
 * @param tz an IANA zone; the slots come back as instants either way.
 */
suspend fun SchedulingAdminRepository.eventTypeSlots(
    workspaceId: String,
    slug: String,
    from: String? = null,
    to: String? = null,
    tz: String? = null,
): SchedulingAdminResult<SchedulingSlots> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_SLOTS,
    schedulingParams(
        "slug" to JsonPrimitive(slug),
        "from" to from.jsonOrNull(),
        "to" to to.jsonOrNull(),
        "tz" to tz.jsonOrNull(),
    ),
    SchedulingSlots.serializer(),
)
