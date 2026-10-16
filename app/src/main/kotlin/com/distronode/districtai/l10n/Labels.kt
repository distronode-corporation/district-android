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

/**
 * A conversation's channels, as the inbox chip shows them: every value the server writes as a
 * message's type. English is the word ("Email", "Voicemail", "WhatsApp"), and only the acronym SMS
 * is in capitals.
 *
 * ⚠️ VOICEMAIL IS A CHANNEL (3.0.2): the voice agent writes a message left on a call as type
 * `voicemail`, so a row that mixes it with SMS fell through to the unknown label, "VOICEMAIL" in
 * English and « Valeur inconnue : VOICEMAIL » in French. WhatsApp did the same.
 */
internal val INBOX_CHANNEL_LABELS: Map<String, Int> = mapOf(
    "email" to R.string.inbox_channel_email,
    "sms" to R.string.inbox_channel_sms,
    "voicemail" to R.string.inbox_channel_voicemail,
    "whatsapp" to R.string.inbox_channel_whatsapp,
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

/** A conversation's channel chip: "Email" / « Courriel », "SMS"; an unknown one upper-cased in English. */
@Composable
@ReadOnlyComposable
internal fun inboxChannelLabel(channel: String): String =
    valueLabel(INBOX_CHANNEL_LABELS, channel, channel.uppercase(Locale.ROOT))

/** A messaging channel: "sms" / « SMS », "voice" / « Voix ». */
@Composable
@ReadOnlyComposable
internal fun messagingChannelLabel(channel: String): String = valueLabel(MESSAGING_CHANNEL_LABELS, channel)

/**
 * The label for a support request's status, in the app's language: `stringResource` of this.
 *
 * ⛔ THE DESK'S RAW WORD IS NEVER SHOWN (3.0.1). It used to be, on the reasoning that the desk
 * localises its own workflow, but the desk answers in ITS language, not the user's: a French screen
 * printed "In Progress" and "Done". So the word is looked up ([SUPPORT_STATUS_LABELS],
 * case-insensitive), and a word the app does not know falls back to Atlassian's language-independent
 * status CATEGORY ([SUPPORT_CATEGORY_LABELS]), which is the one stable signal (the live desk has
 * answered in Chinese), and only then to a generic "Unknown".
 *
 * ⚠️ A RESOURCE ID, NOT A @Composable STRING: pure, so every branch is testable without a
 * composition, and the call sites keep the one `stringResource` they already had.
 */
@StringRes
internal fun supportStatusLabel(statusName: String, statusCategory: String): Int =
    SUPPORT_STATUS_LABELS[statusName.trim().lowercase(Locale.ROOT)]
        ?: SUPPORT_CATEGORY_LABELS[statusCategory.trim().uppercase(Locale.ROOT)]
        ?: R.string.support_status_unknown

/** What `lib/jira/helpdesk.ts` writes for a request the desk has not synced yet. */
internal const val SUPPORT_STATUS_RECEIVED = "Received"

/**
 * The desk's status words the app knows, lower-cased: the server's own [SUPPORT_STATUS_RECEIVED],
 * the live desk's workflow (Atlassian's customer-service template: Open, In Progress, Escalated,
 * Waiting for customer, Done), and the other Jira Service Management templates' customer-facing
 * names. ⚠️ Keyed by the desk's spelling, mapped to the app's: "In Progress" and "Work in progress"
 * are one state to a customer.
 */
internal val SUPPORT_STATUS_LABELS: Map<String, Int> = mapOf(
    SUPPORT_STATUS_RECEIVED.lowercase(Locale.ROOT) to R.string.support_status_received,
    "open" to R.string.support_status_open,
    "new" to R.string.support_status_open,
    "to do" to R.string.support_status_open,
    "in progress" to R.string.support_status_in_progress,
    "work in progress" to R.string.support_status_in_progress,
    "under review" to R.string.support_status_in_progress,
    "escalated" to R.string.support_status_escalated,
    "waiting for customer" to R.string.support_status_waiting_for_customer,
    "waiting for reply" to R.string.support_status_waiting_for_customer,
    "waiting for support" to R.string.support_status_waiting_for_support,
    "pending" to R.string.support_status_pending,
    "done" to R.string.support_status_done,
    "completed" to R.string.support_status_done,
    "resolved" to R.string.support_status_resolved,
    "closed" to R.string.support_status_closed,
    "canceled" to R.string.support_status_canceled,
    "cancelled" to R.string.support_status_canceled,
    "declined" to R.string.support_status_declined,
    "won't do" to R.string.support_status_declined,
)

/**
 * Atlassian's status categories, upper-cased as the server stores them (`lib/jira/status-category`):
 * NEW, INDETERMINATE and DONE from the desk, and the server's synthetic PENDING for a request that is
 * not filed yet.
 */
internal val SUPPORT_CATEGORY_LABELS: Map<String, Int> = mapOf(
    "NEW" to R.string.support_status_open,
    "INDETERMINATE" to R.string.support_status_in_progress,
    "DONE" to R.string.support_status_closed,
    "PENDING" to R.string.support_status_received,
)
