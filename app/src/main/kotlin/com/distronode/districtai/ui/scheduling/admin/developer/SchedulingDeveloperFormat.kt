package com.distronode.districtai.ui.scheduling.admin.developer

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusLabel
import com.distronode.districtai.ui.scheduling.admin.schedulingDateTime
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue
import java.net.URI
import java.net.URISyntaxException

// The developer screen's readings, ported from district-core-swift's `SchedulingDeveloperFormat`
// and district-ios's webhook URL check and field list.

/** The three tabs, in the web's order, with its `?tab=` ids. */
enum class SchedulingDeveloperTab(val id: String, @get:StringRes val label: Int) {
    KEYS("keys", R.string.scheduling_admin_developer_tab_keys),
    APPS("apps", R.string.scheduling_admin_developer_tab_apps),
    WEBHOOKS("webhooks", R.string.scheduling_admin_developer_tab_webhooks),
}

/**
 * `https://<host>/mcp`, or null while the tenancy has no public host.
 *
 * ⚠️ NULL RATHER THAN A GUESS: a tenancy still provisioning has no host, and the screen says so in
 * words instead of publishing an address that would not resolve.
 */
fun schedulingMcpUrl(publicHost: String?): String? =
    publicHost?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://$it/mcp" }

/**
 * The payload fields a webhook delivery may carry, in the fork's own order.
 *
 * ⛔ A HAND-KEPT COPY, AS ON district-ios: a response's `fields` carries whatever one row was
 * created with, so the form cannot be derived from it. ⚠️ ORDER IS PART OF IT: a request is
 * filtered through [ALL] so the stored list matches the one every other surface prints.
 */
object SchedulingWebhookFields {
    val ALL: List<String> = listOf(
        "id",
        "status",
        "start_at",
        "end_at",
        "created_at",
        "location_value",
        "cancellation_reason",
        "previous_start_at",
        "previous_end_at",
        "event_type_slug",
        "event_type_name",
        "host_id",
        "host_name",
        "host_email",
        "attendee_name",
        "attendee_email",
        "attendee_timezone",
        "answers",
        "payment_status",
        "amount_paid_cents",
        "amount_paid_currency",
        "hours_before",
    )

    /**
     * ⛔ THE BOOKER'S OWN DETAILS, OFF ON A NEW WEBHOOK: sending a stranger's name and address to a
     * third-party system should be a tick somebody made, not a default they inherited.
     */
    val ATTENDEE: Set<String> = setOf("attendee_name", "attendee_email", "attendee_timezone", "answers")

    /** The six that carry a person's details; the form badges them. Hosts' are on by default. */
    val PERSONAL_DATA: Set<String> = setOf("host_name", "host_email") + ATTENDEE

    /**
     * ⛔ NOT THE FORK'S `defaultFields`, which omits `event_type_name`, `host_name` and `host_email`
     * too: a webhook created with no list would arrive missing values this form never offered to
     * remove, so the list is always sent.
     */
    val DEFAULTS: Set<String> = ALL.toSet() - ATTENDEE
}

/**
 * What is wrong with a webhook URL, as a sentence, or null when it can be saved; district-ios's
 * `SchedulingWebhookURLCheckC`.
 *
 * ⛔ HTTPS ONLY, which the server's catalog enforces too: a delivery carries attendees' names and
 * answers. ⚠️ The private-host test is a courtesy for the spellings a person types; the fork
 * resolves the host and refuses private addresses at save and delivery time.
 */
@StringRes
fun schedulingWebhookUrlIssue(value: String): Int? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return R.string.scheduling_admin_developer_webhook_url_missing
    val parsed = try {
        URI(trimmed)
    } catch (_: URISyntaxException) {
        null
    }
    val scheme = parsed?.scheme?.lowercase()
    val host = parsed?.host
    return when {
        scheme == null || host == null -> R.string.scheduling_admin_developer_webhook_url_not_a_url
        scheme != "https" -> R.string.scheduling_admin_developer_webhook_url_not_https
        isPrivateWebhookHost(host) -> R.string.scheduling_admin_developer_webhook_url_private
        else -> null
    }
}

/** Loopback, link-local, private and `.local` spellings, after the browser's nine patterns. */
internal fun isPrivateWebhookHost(host: String): Boolean {
    val lower = host.lowercase()
    val named = lower == "localhost" || lower.endsWith(".local")
    val loopback = lower == "0.0.0.0" || lower == "::1" || lower == "[::1]"
    val prefixed = PRIVATE_PREFIXES.any { lower.startsWith(it) }
    return named || loopback || prefixed || isPrivate172(lower)
}

/**
 * `172.16.` through `172.31.`, the one pattern that is a range. ⚠️ Written out because `172.2.` is
 * public and `172.20.` is not.
 */
private fun isPrivate172(host: String): Boolean {
    val parts = host.split('.')
    val second = parts.getOrNull(1)?.toIntOrNull()
    return parts.first() == "172" && second != null && second in PRIVATE_172_FIRST..PRIVATE_172_LAST
}

private val PRIVATE_PREFIXES = listOf("127.", "10.", "192.168.", "169.254.")
private const val PRIVATE_172_FIRST = 16
private const val PRIVATE_172_LAST = 31

/**
 * A delivery's outcome: Delivered, Waiting, Rejected with 500, No answer, or the status as sent.
 *
 * ⛔ A FAILED ROW WITH NO RESPONSE STATUS MEANS NOBODY ANSWERED, a different fact from a rejection
 * and the whole diagnosis the row can give. ⚠️ A status of zero is no response, as the source's
 * truthiness test reads it.
 */
@Composable
@ReadOnlyComposable
fun schedulingDeliveryOutcome(status: String, responseStatus: Int?): SchedulingStatusLabel = when (status) {
    "success" -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_developer_delivery_delivered),
        SchedulingStatusKind.SUCCESS,
    )
    "pending" -> SchedulingStatusLabel(
        stringResource(R.string.scheduling_admin_developer_delivery_waiting),
        SchedulingStatusKind.INFO,
    )
    "failed" -> SchedulingStatusLabel(
        if (responseStatus == null || responseStatus == 0) {
            stringResource(R.string.scheduling_admin_developer_delivery_no_answer)
        } else {
            stringResource(R.string.scheduling_admin_developer_delivery_rejected, responseStatus.toString())
        },
        SchedulingStatusKind.ERROR,
    )
    else -> SchedulingStatusLabel(schedulingUnknownValue(status), SchedulingStatusKind.INFO)
}

/**
 * A timestamp in one of the developer lists, or [absent] when there is none.
 *
 * ⚠️ THE ABSENT MARKER IS PER COLUMN: "Never" for a key never used, "Not tried yet" for a delivery
 * not yet run. They are different statements and not interchangeable.
 */
@Composable
@ReadOnlyComposable
fun schedulingStamp(iso: String?, timezone: String, @StringRes absent: Int): String =
    if (iso.isNullOrEmpty()) stringResource(absent) else schedulingDateTime(iso, timezone)
