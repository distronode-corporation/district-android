package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the tenancy's API keys, as LISTED.
 *
 * ⛔ THERE IS NO `key` FIELD HERE AND NOTHING MAY ADD ONE. The secret exists on the wire exactly
 * once, in [SchedulingApiKeyCreated]; the listing carries only the metadata. A `key` appearing on
 * this shape would mean the fork had started re-serving a credential on every list.
 *
 * ⚠️ [lastUsedAt] IS EXPLICITLY NULL ON A KEY NEVER USED, which the fixture's second row sends.
 */
@Serializable
data class SchedulingApiKey(
    val id: String,
    val name: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
)

/**
 * The one and only time an API key's secret crosses the wire.
 *
 * ⛔ [key] MUST NEVER BE STORED, CACHED OR LOGGED. `apiKeys.list` will never send it again, so a
 * screen either shows it to the operator now or it is gone — and anything that wrote it to a
 * diagnostic has published a live credential for the tenancy's whole scheduler API.
 *
 * ⚠️ [note] IS THE FORK'S OWN SENTENCE SAYING EXACTLY THAT, carried so the warning cannot drift
 * between the two clients that render it.
 */
@Serializable
data class SchedulingApiKeyCreated(
    val id: String,
    val name: String,
    val key: String,
    @SerialName("created_at") val createdAt: String? = null,
    val note: String? = null,
)

/**
 * A third-party app holding an OAuth grant on the tenancy.
 *
 * ⚠️ [expiresAt] IS EXPLICITLY NULL FOR A GRANT THAT DOES NOT EXPIRE, which is a stronger
 * statement than "we do not know when". A screen that sorted by it must put those somewhere
 * deliberate rather than treating null as the epoch.
 */
@Serializable
data class SchedulingOAuthConnection(
    val id: String,
    @SerialName("client_name") val clientName: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/**
 * The seven events a webhook may subscribe to.
 *
 * ⛔ AN ENUM FOR THE REQUEST AND A `String` ON THE RESPONSE, WHICH IS DELIBERATE ASYMMETRY. This
 * type is what a caller CHOOSES from, so a closed set stops a screen offering an event the fork
 * would refuse. [SchedulingWebhook.events] stays a list of strings because an event added
 * server-side must not stop an already-installed build from reading the webhooks it already has.
 */
@Serializable
enum class SchedulingWebhookEvent(val wire: String) {
    @SerialName("booking.created")
    BOOKING_CREATED("booking.created"),

    @SerialName("booking.cancelled")
    BOOKING_CANCELLED("booking.cancelled"),

    @SerialName("booking.rescheduled")
    BOOKING_RESCHEDULED("booking.rescheduled"),

    @SerialName("booking.reminder")
    BOOKING_REMINDER("booking.reminder"),

    @SerialName("recording.completed")
    RECORDING_COMPLETED("recording.completed"),

    @SerialName("transcript.ready")
    TRANSCRIPT_READY("transcript.ready"),

    @SerialName("notes.ready")
    NOTES_READY("notes.ready"),
}

/**
 * A webhook, as listed and as patched.
 *
 * ⚠️ [fields] IS EXPLICITLY NULL FOR "SEND EVERYTHING", NOT EMPTY. An empty list would be a
 * webhook whose payload carries no attendee detail at all, which is a different subscription.
 */
@Serializable
data class SchedulingWebhook(
    val id: String,
    val url: String,
    val events: List<String>,
    val fields: List<String>? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/**
 * A freshly created webhook.
 *
 * ⛔ A SEPARATE TYPE FROM [SchedulingWebhook] FOR ONE KEY, AND THAT KEY IS A SECRET. `secret` is
 * sent once, at creation, and never again; folding it into the list type as an optional field
 * would put a credential-shaped key on the shape every listing decodes, which is how a client
 * ends up logging one by accident.
 */
@Serializable
data class SchedulingWebhookCreated(
    val id: String,
    val url: String,
    val events: List<String>,
    val fields: List<String>? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** ⛔ Shown once and never stored. See the class doc. */
    val secret: String? = null,
)

/**
 * One attempt to deliver one event.
 *
 * ⛔ [responseStatus] IS ABSENT WHEN THE REQUEST NEVER GOT AN ANSWER — DNS, TLS, timeout — which
 * is exactly the case an operator is debugging. The fixture's failed row omits it, so a `0`
 * default would report "the endpoint answered 0" for "the endpoint was never reached".
 *
 * ⚠️ [attemptCount] IS REQUIRED because a delivery row always has one; [bookingId] is absent for
 * the events that are not about a booking.
 */
@Serializable
data class SchedulingWebhookDelivery(
    val id: String,
    @SerialName("webhook_id") val webhookId: String,
    val event: String,
    val status: String,
    @SerialName("attempt_count") val attemptCount: Int,
    @SerialName("booking_id") val bookingId: String? = null,
    @SerialName("response_status") val responseStatus: Int? = null,
    @SerialName("last_attempted_at") val lastAttemptedAt: String? = null,
)
