package com.distronode.districtai.l10n

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import java.util.Locale

/*
 * Labels for values the server sends that the wire vocabulary does not list: a device's platform, a
 * conversation's channels, a messaging channel, a routing rule's field and comparison.
 *
 * ⛔ AN UNKNOWN VALUE GETS THE GENERIC LABEL, NEVER THE RAW STRING ON A FRENCH SCREEN. In English the
 * generic label is the value itself, which is what the app has always shown; in French it names the
 * value as one the app does not know. Same rule as [wireLabel].
 */

/** "android" and the other platforms the server's device enum allows, by their product names. */
internal val DEVICE_PLATFORM_LABELS: Map<String, Int> = mapOf(
    "android" to R.string.devices_platform_android,
    "ios" to R.string.devices_platform_ios,
    "linux" to R.string.devices_platform_linux,
    "macos" to R.string.devices_platform_macos,
    "windows" to R.string.devices_platform_windows,
)

/** A conversation's channels, as the inbox chip shows them. English is the value upper-cased. */
internal val INBOX_CHANNEL_LABELS: Map<String, Int> = mapOf(
    "email" to R.string.inbox_channel_email,
    "sms" to R.string.inbox_channel_sms,
)

/** The channels a messaging account can be the default sender for (`MESSAGING_CHANNELS`). */
internal val MESSAGING_CHANNEL_LABELS: Map<String, Int> = mapOf(
    "sms" to R.string.messaging_channel_sms,
    "voice" to R.string.messaging_channel_voice,
    "whatsapp" to R.string.messaging_channel_whatsapp,
)

/** The contact fields a routing rule can test (`ROUTING_FIELDS`). */
internal val ROUTING_FIELD_LABELS: Map<String, Int> = mapOf(
    "industry" to R.string.routing_field_industry,
    "estimatedValue" to R.string.routing_field_estimated_value,
    "callerType" to R.string.routing_field_caller_type,
    "lineType" to R.string.routing_field_line_type,
    "isDecisionMaker" to R.string.routing_field_is_decision_maker,
    "seniority" to R.string.routing_field_seniority,
)

/** The comparisons a routing rule can make (`ROUTING_OPERATORS`). */
internal val ROUTING_OPERATOR_LABELS: Map<String, Int> = mapOf(
    "contains" to R.string.routing_operator_contains,
    "equals" to R.string.routing_operator_equals,
)

/** The label [labels] gives [value], else the generic label carrying [shown] (the value as sent). */
@Composable
@ReadOnlyComposable
internal fun valueLabel(labels: Map<String, Int>, value: String, shown: String = value): String {
    @StringRes val id = labels[value]
    return if (id != null) stringResource(id) else stringResource(R.string.labels_unknown_value, shown)
}

/** A device's platform: "Android", "iOS"; an unknown one as sent in English. */
@Composable
@ReadOnlyComposable
internal fun devicePlatformLabel(platform: String): String = valueLabel(DEVICE_PLATFORM_LABELS, platform)

/** A conversation's channel chip: "EMAIL" / « Courriel », "SMS"; an unknown one upper-cased in English. */
@Composable
@ReadOnlyComposable
internal fun inboxChannelLabel(channel: String): String =
    valueLabel(INBOX_CHANNEL_LABELS, channel, channel.uppercase(Locale.ROOT))

/** A messaging channel: "sms" / « SMS », "voice" / « Voix ». */
@Composable
@ReadOnlyComposable
internal fun messagingChannelLabel(channel: String): String = valueLabel(MESSAGING_CHANNEL_LABELS, channel)

/**
 * A support request's status. The desk's own word is shown as sent, as the portal shows it (the live
 * workflow is localised by the desk); the one status the SERVER writes, "Received" for a request the
 * desk has not synced yet, is the app's to translate.
 */
@Composable
@ReadOnlyComposable
internal fun supportStatusText(statusName: String): String =
    if (statusName == SUPPORT_STATUS_RECEIVED) stringResource(R.string.support_status_received) else statusName

/** What `lib/jira/helpdesk.ts` writes for a request the desk has not synced yet. */
internal const val SUPPORT_STATUS_RECEIVED = "Received"
