package com.distronode.districtai.ui.scheduling.admin.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingApiKey
import com.distronode.districtai.core.model.SchedulingOAuthConnection
import com.distronode.districtai.core.model.SchedulingWebhook
import com.distronode.districtai.core.model.SchedulingWebhookDelivery
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingRemoval
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingDateTime
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue

/** The keys this tenancy has minted: names and dates only, never key material. */
@Composable
internal fun DeveloperKeysTab(
    state: SchedulingDeveloperUiState,
    revoke: SchedulingRemoval<SchedulingApiKey>,
    writes: SchedulingDeveloperWrites,
    onRetry: () -> Unit,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_developer_keys_eyebrow)) {
        SchedulingHint(stringResource(R.string.scheduling_admin_developer_keys_subtitle))
        DeveloperRead(state.keys, onRetry) { keys ->
            if (keys.isEmpty()) {
                DeveloperEmptyLine(
                    stringResource(R.string.scheduling_admin_developer_keys_empty),
                    DEVELOPER_KEYS_EMPTY_TAG,
                )
            }
            keys.forEachIndexed { index, key ->
                if (index > 0) DistrictRowDivider()
                KeyRow(key, state, revoke)
            }
            DeveloperRemovalFailure(revoke)
            if (state.canManage) {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_developer_key_create),
                    onClick = @DontMemoize { writes.keys.open() },
                    modifier = Modifier.testTag(DEVELOPER_KEY_CREATE_TAG),
                )
            }
        }
    }
}

@Composable
private fun KeyRow(
    key: SchedulingApiKey,
    state: SchedulingDeveloperUiState,
    revoke: SchedulingRemoval<SchedulingApiKey>,
) {
    val removal by revoke.state.collectAsState()
    Box(Modifier.testTag("$DEVELOPER_KEY_ROW_TAG-${key.id}")) {
        SchedulingReadOnlyRow(
            label = key.name,
            value = stringResource(
                R.string.scheduling_admin_developer_key_value,
                schedulingDateTime(key.createdAt, state.timezone),
                schedulingStamp(key.lastUsedAt, state.timezone, R.string.scheduling_admin_developer_never),
            ),
        )
    }
    if (state.canManage) {
        DeveloperRowButton(
            text = stringResource(R.string.scheduling_admin_developer_key_revoke_action),
            tag = "$DEVELOPER_KEY_REVOKE_TAG-${key.id}",
            enabled = !removal.busy,
        ) @DontMemoize { revoke.ask(key) }
    }
}

/**
 * The MCP address for an AI assistant, and the apps holding an OAuth grant.
 *
 * ⛔ THE HEADER VALUE IS A PLACEHOLDER AND NEVER A REAL KEY: the snippet is copyable, and a copied
 * snippet ends up in files and screenshots. ⚠️ No purchase entry point here, so it is ported whole.
 */
@Composable
internal fun DeveloperAppsTab(
    state: SchedulingDeveloperUiState,
    revoke: SchedulingRemoval<SchedulingOAuthConnection>,
    onRetry: () -> Unit,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_developer_mcp_eyebrow)) {
        val mcpUrl = schedulingMcpUrl(state.publicHost)
        if (mcpUrl == null) {
            SchedulingHint(stringResource(R.string.scheduling_admin_developer_mcp_no_host))
        } else {
            Box(Modifier.testTag(DEVELOPER_MCP_URL_TAG)) {
                SchedulingReadOnlyRow(
                    label = stringResource(R.string.scheduling_admin_developer_mcp_url_label),
                    value = mcpUrl,
                )
            }
            SchedulingReadOnlyRow(
                label = stringResource(R.string.scheduling_admin_developer_mcp_auth_label),
                value = stringResource(R.string.scheduling_admin_developer_mcp_auth_value),
            )
        }
        SchedulingHint(stringResource(R.string.scheduling_admin_developer_mcp_note))
    }
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_developer_apps_eyebrow)) {
        SchedulingHint(stringResource(R.string.scheduling_admin_developer_apps_subtitle))
        DeveloperRead(state.apps, onRetry) { apps ->
            if (apps.isEmpty()) {
                DeveloperEmptyLine(
                    stringResource(R.string.scheduling_admin_developer_apps_empty),
                    DEVELOPER_APPS_EMPTY_TAG,
                )
            }
            apps.forEachIndexed { index, app ->
                if (index > 0) DistrictRowDivider()
                AppRow(app, state, revoke)
            }
            DeveloperRemovalFailure(revoke)
        }
    }
}

@Composable
private fun AppRow(
    app: SchedulingOAuthConnection,
    state: SchedulingDeveloperUiState,
    revoke: SchedulingRemoval<SchedulingOAuthConnection>,
) {
    val removal by revoke.state.collectAsState()
    Box(Modifier.testTag("$DEVELOPER_APP_ROW_TAG-${app.id}")) {
        SchedulingReadOnlyRow(
            label = app.clientName,
            value = schedulingStamp(app.lastUsedAt, state.timezone, R.string.scheduling_admin_developer_never),
        )
    }
    if (state.canManage) {
        DeveloperRowButton(
            text = stringResource(R.string.scheduling_admin_developer_app_revoke_action),
            tag = "$DEVELOPER_APP_REVOKE_TAG-${app.id}",
            enabled = !removal.busy,
        ) @DontMemoize { revoke.ask(app) }
    }
}

/** The tenancy's webhooks, each with its deliveries on demand. */
@Composable
internal fun DeveloperWebhooksTab(
    state: SchedulingDeveloperUiState,
    delete: SchedulingRemoval<SchedulingWebhook>,
    writes: SchedulingDeveloperWrites,
    onRetry: () -> Unit,
    onToggleDeliveries: (String) -> Unit,
    onRetryDeliveries: (String) -> Unit,
) {
    SchedulingCard(eyebrow = stringResource(R.string.scheduling_admin_developer_webhooks_eyebrow)) {
        SchedulingHint(stringResource(R.string.scheduling_admin_developer_webhooks_subtitle))
        DeveloperRead(state.webhooks, onRetry) { hooks ->
            if (hooks.isEmpty()) {
                DeveloperEmptyLine(
                    stringResource(R.string.scheduling_admin_developer_webhooks_empty),
                    DEVELOPER_WEBHOOKS_EMPTY_TAG,
                )
            }
            hooks.forEachIndexed { index, hook ->
                if (index > 0) DistrictRowDivider()
                WebhookRow(hook, state, delete, writes, onToggleDeliveries)
                state.deliveries?.takeIf { it.webhookId == hook.id }?.let { open ->
                    Deliveries(open.read, state.timezone) @DontMemoize { onRetryDeliveries(hook.id) }
                }
            }
            DeveloperRemovalFailure(delete)
            if (state.canManage) {
                DistrictButton(
                    text = stringResource(R.string.scheduling_admin_developer_webhook_add_action),
                    onClick = @DontMemoize { writes.webhooks.openCreate() },
                    modifier = Modifier.testTag(DEVELOPER_WEBHOOK_ADD_TAG),
                )
            }
        }
    }
}

/**
 * ⚠️ THE EVENT NAMES ARE SHOWN AS SENT (`booking.created`): they are protocol identifiers a
 * receiver is written against, as district-ios shows them.
 */
@Composable
private fun WebhookRow(
    hook: SchedulingWebhook,
    state: SchedulingDeveloperUiState,
    delete: SchedulingRemoval<SchedulingWebhook>,
    writes: SchedulingDeveloperWrites,
    onToggleDeliveries: (String) -> Unit,
) {
    val removal by delete.state.collectAsState()
    Box(Modifier.testTag("$DEVELOPER_WEBHOOK_ROW_TAG-${hook.id}")) {
        SchedulingReadOnlyRow(
            label = hook.url,
            value = if (hook.isActive != false) {
                stringResource(R.string.scheduling_admin_developer_webhook_on)
            } else {
                stringResource(R.string.scheduling_admin_developer_webhook_off)
            },
        )
    }
    SchedulingHint(schedulingUnknownValue(hook.events.joinToString(", ")))
    Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        DeveloperRowButton(
            text = stringResource(R.string.scheduling_admin_developer_deliveries),
            tag = "$DEVELOPER_WEBHOOK_DELIVERIES_TAG-${hook.id}",
        ) @DontMemoize { onToggleDeliveries(hook.id) }
        if (state.canManage) {
            DeveloperRowButton(
                text = stringResource(R.string.scheduling_admin_developer_webhook_edit_action),
                tag = "$DEVELOPER_WEBHOOK_EDIT_TAG-${hook.id}",
            ) @DontMemoize { writes.webhooks.openEdit(hook) }
            DeveloperRowButton(
                text = stringResource(R.string.scheduling_admin_developer_webhook_delete_action),
                tag = "$DEVELOPER_WEBHOOK_DELETE_TAG-${hook.id}",
                enabled = !removal.busy,
            ) @DontMemoize { delete.ask(hook) }
        }
    }
}

/** One webhook's delivery attempts: when, and how each went. */
@Composable
private fun Deliveries(read: SectionState<List<SchedulingWebhookDelivery>>, timezone: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
        when (read) {
            is SectionState.Failed -> SchedulingFailureLine(read.failure, onRetry)
            is SectionState.Ready -> {
                if (read.value.isEmpty()) {
                    DeveloperEmptyLine(
                        stringResource(R.string.scheduling_admin_developer_deliveries_empty),
                        DEVELOPER_DELIVERIES_EMPTY_TAG,
                    )
                }
                read.value.forEach { delivery ->
                    Box(Modifier.testTag("$DEVELOPER_DELIVERY_TAG-${delivery.id}")) {
                        SchedulingReadOnlyRow(
                            label = schedulingStamp(
                                delivery.lastAttemptedAt,
                                timezone,
                                R.string.scheduling_admin_developer_not_tried_yet,
                            ),
                            value = schedulingDeliveryOutcome(delivery.status, delivery.responseStatus).label,
                        )
                    }
                }
            }
            SectionState.Loading -> SchedulingSectionLoading(DEVELOPER_DELIVERIES_LOADING_TAG, rows = 1)
        }
    }
}

const val DEVELOPER_KEYS_EMPTY_TAG: String = "district-scheduling-developer-keys-empty"
const val DEVELOPER_KEY_ROW_TAG: String = "district-scheduling-developer-key"
const val DEVELOPER_KEY_REVOKE_TAG: String = "district-scheduling-developer-key-revoke"
const val DEVELOPER_KEY_CREATE_TAG: String = "district-scheduling-developer-key-create"
const val DEVELOPER_MCP_URL_TAG: String = "district-scheduling-developer-mcp-url"
const val DEVELOPER_APPS_EMPTY_TAG: String = "district-scheduling-developer-apps-empty"
const val DEVELOPER_APP_ROW_TAG: String = "district-scheduling-developer-app"
const val DEVELOPER_APP_REVOKE_TAG: String = "district-scheduling-developer-app-revoke"
const val DEVELOPER_WEBHOOKS_EMPTY_TAG: String = "district-scheduling-developer-webhooks-empty"
const val DEVELOPER_WEBHOOK_ROW_TAG: String = "district-scheduling-developer-webhook"
const val DEVELOPER_WEBHOOK_ADD_TAG: String = "district-scheduling-developer-webhook-add"
const val DEVELOPER_WEBHOOK_EDIT_TAG: String = "district-scheduling-developer-webhook-edit"
const val DEVELOPER_WEBHOOK_DELETE_TAG: String = "district-scheduling-developer-webhook-delete"
const val DEVELOPER_WEBHOOK_DELIVERIES_TAG: String = "district-scheduling-developer-webhook-deliveries"
const val DEVELOPER_DELIVERIES_LOADING_TAG: String = "district-scheduling-developer-deliveries-loading"
const val DEVELOPER_DELIVERIES_EMPTY_TAG: String = "district-scheduling-developer-deliveries-empty"
const val DEVELOPER_DELIVERY_TAG: String = "district-scheduling-developer-delivery"
