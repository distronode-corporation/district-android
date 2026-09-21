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

/*
 * The Developer tab's ten ops: API keys, connected OAuth apps, webhooks and their delivery log.
 *
 * ⛔ TWO OPS HERE RETURN A SECRET EXACTLY ONCE AND NEITHER MAY BE CACHED. [createApiKey]'s `key`
 * and [createWebhook]'s `secret` are never served again by any list op, so a screen either shows
 * them to the operator at that moment or they are gone — and anything that wrote one to a
 * diagnostic has published a live credential for the tenancy's whole scheduler API.
 */

suspend fun SchedulingAdminRepository.apiKeys(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingApiKey>> = perform(
    SchedulingAdminOp.API_KEYS_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingApiKey.serializer()),
).map { it.items }

/** ⛔ The only response carrying the key. See the file doc. */
suspend fun SchedulingAdminRepository.createApiKey(
    workspaceId: String,
    name: String,
): SchedulingAdminOutcome<SchedulingApiKeyCreated> = perform(
    SchedulingAdminOp.API_KEYS_CREATE,
    workspaceId,
    schedulingParams("name" to textParam(name)),
    SchedulingApiKeyCreated.serializer(),
)

suspend fun SchedulingAdminRepository.deleteApiKey(
    workspaceId: String,
    keyId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.API_KEYS_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(keyId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.oauthConnections(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingOAuthConnection>> = perform(
    SchedulingAdminOp.OAUTH_CONNECTIONS_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingOAuthConnection.serializer()),
).map { it.items }

suspend fun SchedulingAdminRepository.deleteOAuthConnection(
    workspaceId: String,
    connectionId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(connectionId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.webhooks(
    workspaceId: String,
): SchedulingAdminOutcome<List<SchedulingWebhook>> = perform(
    SchedulingAdminOp.WEBHOOKS_LIST,
    workspaceId,
    schedulingParams(),
    SchedulingItems.serializer(SchedulingWebhook.serializer()),
).map { it.items }

/**
 * ⛔ [events] IS THE CLOSED ENUM AND THE RESPONSE'S IS A LIST OF STRINGS, WHICH IS DELIBERATE
 * ASYMMETRY. A screen chooses from what the fork accepts today; an already-installed build must
 * still be able to READ a webhook subscribed to an event added after it shipped.
 */
suspend fun SchedulingAdminRepository.createWebhook(
    workspaceId: String,
    url: String,
    events: List<SchedulingWebhookEvent>,
    fields: List<String>? = null,
): SchedulingAdminOutcome<SchedulingWebhookCreated> = perform(
    SchedulingAdminOp.WEBHOOKS_CREATE,
    workspaceId,
    schedulingParams(
        "url" to textParam(url),
        "events" to textsParam(events.map { it.wire }),
        "fields" to textsParam(fields),
    ),
    SchedulingWebhookCreated.serializer(),
)

/**
 * ⚠️ ANSWERS NOTHING, unlike the create. The patched webhook is not echoed back, so a screen that
 * wants the new state re-reads [webhooks].
 *
 * ⚠️ [events] TAKES STRINGS HERE AND THE ENUM ON [createWebhook], because a patch can be built
 * from a webhook that was READ — whose events are strings, possibly including one this build does
 * not model. Forcing the enum would make "add one field to an existing webhook" impossible for
 * exactly the subscriptions a newer server created.
 */
suspend fun SchedulingAdminRepository.updateWebhook(
    workspaceId: String,
    webhookId: String,
    events: List<String>? = null,
    fields: List<String>? = null,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.WEBHOOKS_PATCH,
    workspaceId,
    schedulingParams(
        "id" to textParam(webhookId),
        "events" to textsParam(events),
        "fields" to textsParam(fields),
    ),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.deleteWebhook(
    workspaceId: String,
    webhookId: String,
): SchedulingAdminOutcome<SchedulingNoContent> = perform(
    SchedulingAdminOp.WEBHOOKS_DELETE,
    workspaceId,
    schedulingParams("id" to textParam(webhookId)),
    SchedulingNoContent.serializer(),
)

suspend fun SchedulingAdminRepository.webhookDeliveries(
    workspaceId: String,
    webhookId: String,
): SchedulingAdminOutcome<List<SchedulingWebhookDelivery>> = perform(
    SchedulingAdminOp.WEBHOOKS_DELIVERIES,
    workspaceId,
    schedulingParams("id" to textParam(webhookId)),
    SchedulingItems.serializer(SchedulingWebhookDelivery.serializer()),
).map { it.items }
