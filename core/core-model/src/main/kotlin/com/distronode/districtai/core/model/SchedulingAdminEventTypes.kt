package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The scheduling admin's event-type row.
 *
 * ⛔ SNAKE_CASE ON THE WIRE THROUGHOUT, AND EVERY KEY IS SPELLED OUT WITH [SerialName] RATHER
 * THAN CONVERTED. These rows pass THROUGH our RPC from a scheduler fork that is not ours; every
 * other DTO in this package mirrors a Distronode route and is camelCase. Reaching for a global
 * naming strategy on `DistrictApiClient.DEFAULT_JSON` to "fix" it would re-map the whole District
 * surface to chase this one family.
 *
 * ⛔ FOUR REQUIRED FIELDS AND THIRTY OPTIONAL ONES, AND THE SPLIT IS THE FIXTURE'S RATHER THAN A
 * PREFERENCE. `district-scheduling-event-types.json` carries a second row that OMITS twelve keys
 * and sends thirteen more as explicit `null`; [id], [slug], [name] and [durationMinutes] are the
 * four it always sends, and they are what reject a `{}` body. Widening any of them to a default
 * would let a structurally wrong 200 decode into an event type with no name.
 *
 * ⚠️ THE SHAPE HERE IS THE CATALOG'S ALLOWLIST, NOT THE FORK'S RESPONSE. `admin-ops.ts`
 * re-declares the body as a zod schema and the route parses through it, so a field the fork sends
 * and the schema omits never reaches this client at all. Adding a property to "match the fork"
 * models a key that is stripped one hop away, and the strict gate fails on the added key.
 */
@Serializable
data class SchedulingEventType(
    val id: String,
    val slug: String,
    val name: String,
    val description: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("slot_interval_minutes") val slotIntervalMinutes: Int? = null,
    /** `google_meet`, `teams`, `custom_video`, `phone`, `in_person`, `link` or `livekit`. */
    @SerialName("location_type") val locationType: String? = null,
    /**
     * ⚠️ NULL FOR A GENERATED LOCATION AND THAT IS NOT AN EMPTY FIELD. `google_meet`, `teams` and
     * `livekit` mint their value at booking time, so a screen that rendered "no location" here
     * would be describing a call that will have one.
     */
    @SerialName("location_value") val locationValue: String? = null,
    @SerialName("routing_mode") val routingMode: String? = null,
    @SerialName("rr_strategy") val rrStrategy: String? = null,
    @SerialName("buffer_before_minutes") val bufferBeforeMinutes: Int? = null,
    @SerialName("buffer_after_minutes") val bufferAfterMinutes: Int? = null,
    @SerialName("min_notice_minutes") val minNoticeMinutes: Int? = null,
    @SerialName("max_future_days") val maxFutureDays: Int? = null,
    @SerialName("max_active_bookings") val maxActiveBookings: Int? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    @SerialName("show_taken_slots") val showTakenSlots: Boolean? = null,
    @SerialName("is_public") val isPublic: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("msg_confirmation") val msgConfirmation: String? = null,
    @SerialName("msg_cancellation") val msgCancellation: String? = null,
    @SerialName("msg_reschedule") val msgReschedule: String? = null,
    @SerialName("msg_reminder") val msgReminder: String? = null,
    @SerialName("msg_greeting") val msgGreeting: String? = null,
    @SerialName("subj_confirmation") val subjConfirmation: String? = null,
    @SerialName("subj_cancellation") val subjCancellation: String? = null,
    @SerialName("subj_reschedule") val subjReschedule: String? = null,
    @SerialName("subj_reminder") val subjReminder: String? = null,
    /** Minutes before the booking. ⚠️ Explicitly `null` when the host set none, never `[]`. */
    val reminders: List<Int>? = null,
    val archived: Boolean? = null,
    /**
     * Whether the CALLER owns this event type.
     *
     * ⚠️ ABSENT RATHER THAN FALSE ON A LIST ROW THE FORK DID NOT CLASSIFY, which is why this is
     * nullable and not `Boolean = false`. A default would tell a screen that somebody else's
     * event type is somebody else's when the server never said so.
     */
    val owned: Boolean? = null,
    @SerialName("owner_name") val ownerName: String? = null,
    @SerialName("owner_email") val ownerEmail: String? = null,
)

/**
 * A host assigned to an event type.
 *
 * ⚠️ [role] IS THE FORK'S ROUTING VOCABULARY (`required`, `rotation`, `optional`) AND HAS NOTHING
 * TO DO WITH [WorkspaceRole] OR WITH THE OP'S `minRole`. Three role vocabularies meet on this
 * surface and only this one decides who gets offered a slot.
 */
@Serializable
data class SchedulingHost(
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String,
    val priority: Int,
    val archived: Boolean,
)

/**
 * One booking question on an event type.
 *
 * ⚠️ [options] IS NULL FOR EVERY TYPE THAT IS NOT A CHOICE, AND THE FIXTURE PROVES IT: the `text`
 * row sends an explicit `null` rather than `[]`. An empty list would render a select with no
 * choices; null is "this is not a select".
 */
@Serializable
data class SchedulingQuestion(
    val id: String,
    @SerialName("event_type_id") val eventTypeId: String,
    val label: String,
    val type: String,
    val options: List<String>? = null,
    val required: Boolean,
    val position: Int,
)

/**
 * One offered (or taken) window.
 *
 * ⚠️ [hostIds] IS NULL ON A FIXED-HOST EVENT TYPE, not empty. Round-robin types name the hosts a
 * slot could be assigned to; a fixed one has nothing to choose between.
 */
@Serializable
data class SchedulingSlot(
    val start: String,
    val end: String,
    @SerialName("host_ids") val hostIds: List<String>? = null,
)

/** The display half of a slot's host, keyed by user id in [SchedulingSlots.hosts]. */
@Serializable
data class SchedulingSlotHost(
    val name: String,
    @SerialName("avatar_url") val avatarUrl: String,
)

/**
 * The answer to `eventTypes.slots`.
 *
 * ⛔ [taken] IS NOT THE COMPLEMENT OF [slots] AND MUST NOT BE RENDERED AS ONE. It is sent only
 * when the event type has `show_taken_slots`, and it is what the booking page greys out; a
 * client that inferred "taken" from absence would grey out every hour outside the availability
 * rules as well.
 *
 * ⚠️ [hosts] IS A MAP KEYED BY USER ID, which is the one place on this surface where the wire
 * carries an object rather than a list. kotlinx.serialization decodes it into a `Map<String, …>`
 * without a custom serializer because the keys are strings.
 */
@Serializable
data class SchedulingSlots(
    val slots: List<SchedulingSlot>,
    val hosts: Map<String, SchedulingSlotHost>? = null,
    val taken: List<SchedulingSlot>? = null,
)

/**
 * The acknowledgement of `eventTypes.testEmail`.
 *
 * ⚠️ [sent] IS THE FORK'S ANSWER THAT IT HANDED THE MESSAGE TO A TRANSPORT, never that anybody
 * received it. There is no delivery result on this route and a screen must not word one.
 */
@Serializable
data class SchedulingTestEmailResult(
    val sent: Boolean,
    val to: String,
)
