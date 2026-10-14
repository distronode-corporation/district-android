package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBranding

// The request bodies of the scheduling admin's writes, typed.
//
// ⛔ A null FIELD IS "LEAVE ALONE", NEVER "CLEAR". Each becomes an absent key (see
// `schedulingParams`); there is no way through these types to send an explicit null, which is the
// same limit district-core-swift's drafts have.

/** A new event type, as `eventTypes.create` takes it. Slug, name and duration are required. */
data class SchedulingEventTypeDraft(
    val slug: String,
    val name: String,
    val durationMinutes: Int,
    val description: String? = null,
    val slotIntervalMinutes: Int? = null,
    val locationType: String? = null,
    val locationValue: String? = null,
    val routingMode: String? = null,
    val bufferBeforeMinutes: Int? = null,
    val bufferAfterMinutes: Int? = null,
    val minNoticeMinutes: Int? = null,
    val maxFutureDays: Int? = null,
    val maxActiveBookings: Int? = null,
    val showTakenSlots: Boolean? = null,
)

/** The fields `eventTypes.patch` may change. */
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
    val reminders: List<Int>? = null,
)

/**
 * One host assignment, as `eventTypes.hosts.put` takes it.
 *
 * ⛔ THE PUT IS A FULL REPLACEMENT: sending one assignment REMOVES every other host, and an empty
 * list is a 400, so the last host cannot be removed.
 *
 * @property role `required`, `rotation` or `optional`.
 */
data class SchedulingHostAssignment(val userId: String, val role: String, val priority: Int)

/**
 * A new booking question.
 *
 * @property type `text`, `select` or `checkbox`. ⚠️ [options] means something only for a select.
 * @property position null appends, which is what "Add question" means; a literal 0 inserts at the top.
 */
data class SchedulingQuestionDraft(
    val label: String,
    val type: String,
    val required: Boolean,
    val options: List<String>? = null,
    val position: Int? = null,
)

/** The fields `eventTypes.questions.patch` may change. */
data class SchedulingQuestionChanges(
    val label: String? = null,
    val type: String? = null,
    val options: List<String>? = null,
    val required: Boolean? = null,
    val position: Int? = null,
)

/**
 * A dated exception to the weekly rules.
 *
 * ⛔ [endDate] DECIDES WHICH OF TWO SHAPES COMES BACK: with it a range summary, without it one row.
 *
 * @property date `YYYY-MM-DD`.
 * @property reason `day_off`, `out_of_office` or `custom_hours`: a closed set, not a note.
 * @property startTime zero-padded `HH:MM`, only under `custom_hours`.
 */
data class SchedulingOverrideDraft(
    val date: String,
    val reason: String,
    val endDate: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
)

/** The caller's own profile and notification switches, as `me.patch` takes them. */
data class SchedulingMeUpdate(
    val name: String? = null,
    val timezone: String? = null,
    val timeFormat: String? = null,
    val weekStart: Int? = null,
    val dateFormat: String? = null,
    val notifyConfirmation: Boolean? = null,
    val notifyCancellation: Boolean? = null,
    val notifyReschedule: Boolean? = null,
    val notifyReminder: Boolean? = null,
    val notifyHostBooking: Boolean? = null,
    val notifyHostCancel: Boolean? = null,
    val notifyHostReschedule: Boolean? = null,
)

/**
 * The booking page's branding, as `settings.branding.patch` takes it.
 *
 * ⛔ EVERY FIELD IS SENT, unlike the other updates: the form edits the whole record, and the
 * images are not here because they have their own upload and delete ops.
 */
data class SchedulingBrandingUpdate(
    val businessName: String,
    val logoHeight: Int,
    val logoOpacity: Int,
    val bannerOpacity: Int,
    val privacyUrl: String,
    val termsUrl: String,
    val fallbackLocale: String,
) {
    companion object {
        /** The update that would save [branding] unchanged. */
        fun from(branding: SchedulingBranding): SchedulingBrandingUpdate = SchedulingBrandingUpdate(
            businessName = branding.businessName,
            logoHeight = branding.logoHeight,
            logoOpacity = branding.logoOpacity,
            bannerOpacity = branding.bannerOpacity,
            privacyUrl = branding.privacyUrl,
            termsUrl = branding.termsUrl,
            fallbackLocale = branding.fallbackLocale,
        )
    }
}

/**
 * The filters `bookings.list` takes. Blank and null are both "no filter".
 *
 * @property allHosts sends `scope: all`, the whole tenancy rather than the caller's own bookings.
 *   ⚠️ The scheduler still checks the member's own admin bit; this only asks.
 */
data class SchedulingBookingFilter(
    val status: String? = null,
    val whenFilter: String? = null,
    val from: String? = null,
    val to: String? = null,
    val eventTypeSlug: String? = null,
    val host: String? = null,
    val team: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val allHosts: Boolean = false,
    val order: String? = null,
)
