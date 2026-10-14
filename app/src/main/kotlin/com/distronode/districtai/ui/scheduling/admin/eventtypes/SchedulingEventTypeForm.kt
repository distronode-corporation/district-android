package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingEventTypeChanges
import com.distronode.districtai.core.data.SchedulingEventTypeDraft
import com.distronode.districtai.core.model.SchedulingEventType

/**
 * The location types the catalog permits, with what each one's `location_value` means; after
 * district-core-swift's `SchedulingLocationType`.
 *
 * ⛔ SEVEN, AND `zoom` IS ABSENT DELIBERATELY: `admin-ops.ts` withholds it, so a picker offering it
 * would be a 400 on every save. A stored row may still carry a value this build does not know,
 * which is why the model's field is a raw string and this is reached through [known].
 *
 * @property field what the location value field is called, or null when the scheduler generates
 * the join link itself and no field is drawn.
 */
enum class SchedulingLocationType(
    val wire: String,
    @get:StringRes val label: Int,
    @get:StringRes val field: Int?,
) {
    GOOGLE_MEET("google_meet", R.string.scheduling_admin_event_types_location_google_meet, null),
    TEAMS("teams", R.string.scheduling_admin_event_types_location_teams, null),
    CUSTOM_VIDEO(
        "custom_video",
        R.string.scheduling_admin_event_types_location_custom_video,
        R.string.scheduling_admin_event_types_meeting_link,
    ),
    PHONE(
        "phone",
        R.string.scheduling_admin_event_types_location_phone,
        R.string.scheduling_admin_event_types_phone_number,
    ),
    IN_PERSON(
        "in_person",
        R.string.scheduling_admin_event_types_location_in_person,
        R.string.scheduling_admin_event_types_address,
    ),
    LINK(
        "link",
        R.string.scheduling_admin_event_types_location_link,
        R.string.scheduling_admin_event_types_meeting_link,
    ),
    LIVEKIT("livekit", R.string.scheduling_admin_event_types_location_livekit, null),
    ;

    companion object {
        /**
         * The four the web offers, in its order. ⚠️ `livekit` is the default and the one location
         * the platform provisions itself, so a create that never touched the choice is still bookable.
         */
        val OFFERED: List<SchedulingLocationType> = listOf(LIVEKIT, PHONE, IN_PERSON, LINK)

        /** The type [wire] names, or null for one this build does not know. */
        fun known(wire: String?): SchedulingLocationType? = entries.firstOrNull { it.wire == wire }

        /**
         * The wire values the editor offers. ⚠️ A STORED TYPE OUTSIDE THE FOUR IS ADDED, so editing a
         * `google_meet` row does not silently re-home it on `livekit`.
         */
        fun choices(stored: String?): List<String> {
            val offered = OFFERED.map { it.wire }
            return if (stored == null || stored in offered) offered else offered + stored
        }
    }
}

/**
 * One event-type form's contents, and the rules that decide whether it may be sent; ported from
 * district-ios's `SchedulingEventTypeForm`.
 *
 * ⛔ EVERY NUMBER IS HELD AS A STRING, WHICH IS THE WEB'S ARRANGEMENT: a field bound to an Int cannot
 * hold "", so clearing the duration to retype it would snap to 0, and 0 is legal for five of these.
 * [whole] is where the string becomes a number or a refusal.
 *
 * ⚠️ NOTHING HERE VALIDATES WHAT ONLY THE SERVER VALIDATES; it mirrors the set the web mirrors.
 */
data class SchedulingEventTypeForm(
    val name: String = "",
    val description: String = "",
    val duration: String = DEFAULT_MINUTES,
    val interval: String = DEFAULT_MINUTES,
    val location: String = SchedulingLocationType.LIVEKIT.wire,
    val locationValue: String = "",
    val isActive: Boolean = true,
    val isPublic: Boolean = true,
    val showTakenSlots: Boolean = false,
    val bufferBefore: String = ZERO,
    val bufferAfter: String = ZERO,
    val minNotice: String = ZERO,
    val maxFutureDays: String = ZERO,
    val maxActiveBookings: String = ZERO,
) {
    val trimmedName: String get() = name.trim()

    /** What the location value field is called, or null when this location has none. */
    @get:StringRes
    val locationField: Int? get() = SchedulingLocationType.known(location)?.field

    /** The create dialog's rules: a name and a duration, and nothing else. */
    @get:StringRes
    val createError: Int?
        get() = when {
            trimmedName.isEmpty() -> R.string.scheduling_admin_event_types_name_missing
            whole(duration, atLeast = 1) == null -> R.string.scheduling_admin_event_types_create_duration_invalid
            else -> null
        }

    /** The editor's rules. ⚠️ First fault wins and is reported once: the dialog has one line for it. */
    @get:StringRes
    val editError: Int?
        get() {
            val counts = listOf(bufferBefore, bufferAfter, minNotice, maxFutureDays, maxActiveBookings)
            return when {
                trimmedName.isEmpty() -> R.string.scheduling_admin_event_types_name_missing
                whole(duration, atLeast = 1) == null || whole(interval, atLeast = 1) == null ->
                    R.string.scheduling_admin_event_types_at_least_one_minute
                counts.any { whole(it, atLeast = 0) == null } -> R.string.scheduling_admin_event_types_at_least_zero
                else -> null
            }
        }

    /**
     * The keys `eventTypes.create` is given: slug, name, duration and location.
     *
     * ⛔ FOUR OF THE FOURTEEN THE SCHEMA ACCEPTS, THE WEB'S CHOICE: everything else has a default at
     * the fork and is editable a tap later. ⚠️ Null when [createError] is not, so an unvalidated
     * caller cannot compose a body from a refused form.
     */
    fun createDraft(slug: String): SchedulingEventTypeDraft? {
        val minutes = whole(duration, atLeast = 1) ?: return null
        if (trimmedName.isEmpty()) return null
        return SchedulingEventTypeDraft(
            slug = slug,
            name = trimmedName,
            durationMinutes = minutes,
            locationType = location,
        )
    }

    /**
     * The editor's patch.
     *
     * ⛔ EVERY FIELD IS SENT EVERY TIME, AN EMPTY `location_value` INCLUDED, as the web does: null is
     * "leave alone" on this schema, so a diff could not express the clear the form plainly did.
     * ⛔ `archived` is never sent from here; archiving is the actions dialog's.
     */
    fun changes(): SchedulingEventTypeChanges? {
        if (editError != null) return null
        return SchedulingEventTypeChanges(
            name = trimmedName,
            description = description,
            durationMinutes = whole(duration, atLeast = 1),
            slotIntervalMinutes = whole(interval, atLeast = 1),
            locationType = location,
            locationValue = locationValue,
            isActive = isActive,
            isPublic = isPublic,
            showTakenSlots = showTakenSlots,
            bufferBeforeMinutes = whole(bufferBefore, atLeast = 0),
            bufferAfterMinutes = whole(bufferAfter, atLeast = 0),
            minNoticeMinutes = whole(minNotice, atLeast = 0),
            maxFutureDays = whole(maxFutureDays, atLeast = 0),
            maxActiveBookings = whole(maxActiveBookings, atLeast = 0),
        )
    }

    companion object {
        private const val DEFAULT_MINUTES = "30"
        private const val ZERO = "0"

        /** A whole number at or above [atLeast], or null. */
        fun whole(raw: String, atLeast: Int): Int? = raw.trim().toIntOrNull()?.takeIf { it >= atLeast }

        /**
         * The form for a stored row.
         *
         * ⚠️ THE FLAGS COLLAPSE THE WEB'S WAY (`is_active !== false`): an ABSENT flag reads as on, or
         * opening and saving an event type the fork never stamped would turn it off. ⚠️ An absent
         * interval reads as the duration, which is what the fork does with one.
         */
        fun from(row: SchedulingEventType): SchedulingEventTypeForm = SchedulingEventTypeForm(
            name = row.name,
            description = row.description.orEmpty(),
            duration = row.durationMinutes.toString(),
            interval = (row.slotIntervalMinutes ?: row.durationMinutes).toString(),
            location = row.locationType ?: SchedulingLocationType.LIVEKIT.wire,
            locationValue = row.locationValue.orEmpty(),
            isActive = row.isActive != false,
            isPublic = row.isPublic != false,
            showTakenSlots = row.showTakenSlots == true,
            bufferBefore = (row.bufferBeforeMinutes ?: 0).toString(),
            bufferAfter = (row.bufferAfterMinutes ?: 0).toString(),
            minNotice = (row.minNoticeMinutes ?: 0).toString(),
            maxFutureDays = (row.maxFutureDays ?: 0).toString(),
            maxActiveBookings = (row.maxActiveBookings ?: 0).toString(),
        )
    }
}
