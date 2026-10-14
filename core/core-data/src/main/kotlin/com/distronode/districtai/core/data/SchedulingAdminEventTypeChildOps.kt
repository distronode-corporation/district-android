package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingHost
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// An event type's hosts and booking questions.

suspend fun SchedulingAdminRepository.eventTypeHosts(
    workspaceId: String,
    slug: String,
): SchedulingAdminResult<List<SchedulingHost>> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_HOSTS_GET,
    schedulingParams("slug" to JsonPrimitive(slug)),
    SchedulingItems.serializer(SchedulingHost.serializer()),
).map { it.items }

/** ⛔ A FULL REPLACEMENT: see [SchedulingHostAssignment]. */
suspend fun SchedulingAdminRepository.putEventTypeHosts(
    workspaceId: String,
    slug: String,
    hosts: List<SchedulingHostAssignment>,
): SchedulingAdminResult<List<SchedulingHost>> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT,
    schedulingParams(
        "slug" to JsonPrimitive(slug),
        "hosts" to JsonArray(
            hosts.map { host ->
                schedulingParams(
                    "user_id" to JsonPrimitive(host.userId),
                    "role" to JsonPrimitive(host.role),
                    "priority" to JsonPrimitive(host.priority),
                )
            },
        ),
    ),
    SchedulingItems.serializer(SchedulingHost.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.eventTypeQuestions(
    workspaceId: String,
    slug: String,
): SchedulingAdminResult<List<SchedulingQuestion>> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST,
    schedulingParams("slug" to JsonPrimitive(slug)),
    SchedulingItems.serializer(SchedulingQuestion.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.createEventTypeQuestion(
    workspaceId: String,
    slug: String,
    draft: SchedulingQuestionDraft,
): SchedulingAdminResult<SchedulingQuestion> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE,
    schedulingParams(
        "slug" to JsonPrimitive(slug),
        "label" to JsonPrimitive(draft.label),
        "type" to JsonPrimitive(draft.type),
        "required" to JsonPrimitive(draft.required),
        "options" to draft.options.jsonStringsOrNull(),
        "position" to draft.position.jsonOrNull(),
    ),
    SchedulingQuestion.serializer(),
)

suspend fun SchedulingAdminRepository.patchEventTypeQuestion(
    workspaceId: String,
    slug: String,
    id: String,
    changes: SchedulingQuestionChanges,
): SchedulingAdminResult<SchedulingQuestion> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH,
    schedulingParams(
        "slug" to JsonPrimitive(slug),
        "id" to JsonPrimitive(id),
        "label" to changes.label.jsonOrNull(),
        "type" to changes.type.jsonOrNull(),
        "options" to changes.options.jsonStringsOrNull(),
        "required" to changes.required.jsonOrNull(),
        "position" to changes.position.jsonOrNull(),
    ),
    SchedulingQuestion.serializer(),
)

suspend fun SchedulingAdminRepository.deleteEventTypeQuestion(
    workspaceId: String,
    slug: String,
    id: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE,
    schedulingParams("slug" to JsonPrimitive(slug), "id" to JsonPrimitive(id)),
    SchedulingNoContent.serializer(),
)
