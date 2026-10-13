package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingApiKey
import com.distronode.districtai.core.model.SchedulingApiKeyCreated
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOAuthConnection
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookCreated
import com.distronode.districtai.core.model.SchedulingWebhookDelivery
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

// The integrations the tenancy owns: API keys, OAuth apps and webhooks.
//
// ⛔ A CREATED KEY AND A WEBHOOK'S SECRET ARE SHOWN ONCE AND KEPT NOWHERE. Nothing here logs them,
// and the screen that shows one holds it only until it is dismissed.

suspend fun SchedulingAdminRepository.apiKeys(
    workspaceId: String,
): SchedulingAdminResult<List<SchedulingApiKey>> = perform(
    workspaceId,
    SchedulingAdminOp.API_KEYS_LIST,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingApiKey.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.createApiKey(
    workspaceId: String,
    name: String,
): SchedulingAdminResult<SchedulingApiKeyCreated> = perform(
    workspaceId,
    SchedulingAdminOp.API_KEYS_CREATE,
    schedulingParams("name" to JsonPrimitive(name)),
    SchedulingApiKeyCreated.serializer(),
)

suspend fun SchedulingAdminRepository.deleteApiKey(
    workspaceId: String,
    keyId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.API_KEYS_DELETE,
    schedulingParams("id" to JsonPrimitive(keyId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.oauthConnections(
    workspaceId: String,
): SchedulingAdminResult<List<SchedulingOAuthConnection>> = perform(
    workspaceId,
    SchedulingAdminOp.OAUTH_CONNECTIONS_LIST,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingOAuthConnection.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.deleteOAuthConnection(
    workspaceId: String,
    connectionId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE,
    schedulingParams("id" to JsonPrimitive(connectionId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.webhooks(
    workspaceId: String,
): SchedulingAdminResult<List<SchedulingWebhook>> = perform(
    workspaceId,
    SchedulingAdminOp.WEBHOOKS_LIST,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingWebhook.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.createWebhook(
    workspaceId: String,
    url: String,
    events: List<SchedulingWebhookEvent>,
    fields: List<String>? = null,
): SchedulingAdminResult<SchedulingWebhookCreated> = perform(
    workspaceId,
    SchedulingAdminOp.WEBHOOKS_CREATE,
    schedulingParams(
        "url" to JsonPrimitive(url),
        "events" to JsonArray(events.map { JsonPrimitive(it.wire) }),
        "fields" to fields.jsonStringsOrNull(),
    ),
    SchedulingWebhookCreated.serializer(),
)

suspend fun SchedulingAdminRepository.updateWebhook(
    workspaceId: String,
    webhookId: String,
    events: List<String>? = null,
    fields: List<String>? = null,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.WEBHOOKS_PATCH,
    schedulingParams(
        "id" to JsonPrimitive(webhookId),
        "events" to events.jsonStringsOrNull(),
        "fields" to fields.jsonStringsOrNull(),
    ),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.deleteWebhook(
    workspaceId: String,
    webhookId: String,
): SchedulingAdminResult<SchedulingNoContent> = perform(
    workspaceId,
    SchedulingAdminOp.WEBHOOKS_DELETE,
    schedulingParams("id" to JsonPrimitive(webhookId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.webhookDeliveries(
    workspaceId: String,
    webhookId: String,
): SchedulingAdminResult<List<SchedulingWebhookDelivery>> = perform(
    workspaceId,
    SchedulingAdminOp.WEBHOOKS_DELIVERIES,
    schedulingParams("id" to JsonPrimitive(webhookId)),
    SchedulingItems.serializer(SchedulingWebhookDelivery.serializer()),
).map { it.items }
