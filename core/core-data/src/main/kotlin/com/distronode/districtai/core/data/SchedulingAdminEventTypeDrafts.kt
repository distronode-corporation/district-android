package com.distronode.districtai.core.data

/**
 * A new event type.
 *
 * ⛔ FOURTEEN FIELDS AND ONLY THREE REQUIRED, WHICH IS THE CREATE SCHEMA'S SHAPE. `slug`, `name`
 * and `duration_minutes` are what the fork insists on; everything else has a server-side default
 * that a client must not guess at, because the defaults are per-instance.
 *
 * ⚠️ `is_active`, `is_public`, `rr_strategy`, `reminders` AND THE MESSAGE/SUBJECT FIELDS ARE
 * ABSENT HERE AND PRESENT ON [SchedulingEventTypeChanges], which is the catalog's asymmetry rather
 * than an omission: `eventTypes.create` does not declare them and a 400 names the extra key
 * (`refuseEventTypeExtras`). Set them with a patch immediately after, or not at all.
 */
data class SchedulingEventTypeDraft(
    val slug: String,
    val name: String,
    val durationMinutes: Int,
    val description: String? = null,
    val slotIntervalMinutes: Int? = null,
    val locationType: String? = null,
    /**
     * ⚠️ REFUSED FOR A GENERATED LOCATION. `google_meet`, `teams` and `livekit` mint their own
     * value, and `custom_video`/`link` refuse anything that is not a URL — see
     * `SchedulingLocationType` on the iOS side, which is the vocabulary both clients share.
     */
    val locationValue: String? = null,
    val routingMode: String? = null,
    val bufferBeforeMinutes: Int? = null,
    val bufferAfterMinutes: Int? = null,
    val minNoticeMinutes: Int? = null,
    val maxFutureDays: Int? = null,
    val maxActiveBookings: Int? = null,
    val showTakenSlots: Boolean? = null,
)

/**
 * A partial update to an event type. ⚠️ Every null is "leave alone", never "clear".
 *
 * ⛔ `slug` IS NOT HERE AND MUST NOT BE ADDED. It ADDRESSES the row rather than changing it, so it
 * is a separate argument on the patch function; putting it on this type would make "rename the
 * slug" and "which row" the same field, and the op cannot do the first.
 */
data class SchedulingEventTypeChanges(
    val name: String? = null,
    val description: String? = null,
    val durationMinutes: Int? = null,
    val slotIntervalMinutes: Int? = null,
    val locationType: String? = null,
    val locationValue: String? = null,
    val routingMode: String? = null,
    val rrStrategy: String? = null,
    val bufferBeforeMinutes: Int? = null,
    val bufferAfterMinutes: Int? = null,
    val minNoticeMinutes: Int? = null,
    val maxFutureDays: Int? = null,
    val maxActiveBookings: Int? = null,
    val isActive: Boolean? = null,
    val isPublic: Boolean? = null,
    val showTakenSlots: Boolean? = null,
    val archived: Boolean? = null,
    val msgConfirmation: String? = null,
    val msgCancellation: String? = null,
    val msgReschedule: String? = null,
    val msgReminder: String? = null,
    val msgGreeting: String? = null,
    val subjConfirmation: String? = null,
    val subjCancellation: String? = null,
    val subjReschedule: String? = null,
    val subjReminder: String? = null,
    /** Minutes before the booking. ⚠️ An EMPTY list clears them; null leaves them alone. */
    val reminders: List<Int>? = null,
)
