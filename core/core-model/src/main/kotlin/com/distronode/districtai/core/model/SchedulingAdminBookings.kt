package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who is coming.
 *
 * ⛔ BOTH FIELDS ARE OPTIONAL AND THAT IS THE FORK'S SHAPE RATHER THAN CAUTION. An attendee added
 * by a host from a phone call has a name and no address; one that came from a calendar invite can
 * have the reverse. Requiring either would refuse a booking that exists.
 */
@Serializable
data class SchedulingBookingAttendee(
    val name: String? = null,
    val email: String? = null,
)

/**
 * One booking.
 *
 * ⛔ FOUR REQUIRED FIELDS, AND THE FIXTURE'S SECOND ROW IS WHY IT IS EXACTLY FOUR. A cancelled
 * booking carries [id], [startAt], [endAt] and [status] and NOTHING else — no event type, no
 * host, no attendees — so anything else made required would refuse the row that a cancellation
 * screen exists to show.
 *
 * ⚠️ [cancellationReason] IS ABSENT ON A CONFIRMED BOOKING AND ON A CANCELLED ONE WITH NO REASON
 * GIVEN, so its presence is evidence and its absence is not. Branch on [status].
 *
 * ⚠️ TIMESTAMPS ARE ISO-8601 STRINGS, NOT INSTANTS, for the reason every timestamp on this
 * surface is: one decoder strategy would have to be right for every timestamp here and they do
 * not all agree.
 */
@Serializable
data class SchedulingBooking(
    val id: String,
    @SerialName("event_type_id") val eventTypeId: String? = null,
    @SerialName("event_type_slug") val eventTypeSlug: String? = null,
    @SerialName("host_id") val hostId: String? = null,
    @SerialName("host_name") val hostName: String? = null,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    val status: String,
    @SerialName("cancellation_reason") val cancellationReason: String? = null,
    @SerialName("location_value") val locationValue: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val attendees: List<SchedulingBookingAttendee>? = null,
)

/**
 * The tallies beside a booking page.
 *
 * ⚠️ THEY ARE NOT DERIVED FROM [SchedulingBookingPage.items] AND MUST NOT BE RECOMPUTED FROM IT.
 * The page is one window of a filtered query; these two count the whole tenancy either side of
 * now, which is what the tab labels show.
 */
@Serializable
data class SchedulingBookingCounts(
    val upcoming: Int,
    val past: Int,
)

/**
 * One page of `bookings.list`.
 *
 * ⛔ [items] IS THE ONLY REQUIRED FIELD, WHICH IS WHAT STOPS AN EMPTY BODY READING AS "THIS
 * WORKSPACE HAS NO BOOKINGS". That conflation ("we could not look" rendered as "there is
 * nothing") would, on this surface, tell an operator their calendar is empty.
 *
 * ⚠️ [total], [limit] and [offset] are absent on an unpaginated answer, so paging controls have
 * to tolerate not knowing how much there is.
 */
@Serializable
data class SchedulingBookingPage(
    val items: List<SchedulingBooking>,
    val total: Int? = null,
    val counts: SchedulingBookingCounts? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

/**
 * What a booker typed into one booking question.
 *
 * ⚠️ [value] IS A STRING FOR EVERY [type], INCLUDING THE CHOICES, and an EMPTY string is a real
 * answer meaning "left blank" — the fixture carries one. Do not collapse it to null.
 */
@Serializable
data class SchedulingBookingAnswer(
    @SerialName("question_id") val questionId: String,
    val label: String,
    val type: String,
    val value: String,
)

/**
 * The meeting notes for one booking.
 *
 * ⛔ [exists] IS NOT `content != null`, AND THE TWO ANSWER DIFFERENT QUESTIONS. A notes row can
 * exist with [status] `pending` and no content yet — the generation is running — which a screen
 * words as "being written" rather than as "none". Reading absence as "no notes" would offer a
 * Generate button that starts a second run of the one already in flight.
 */
@Serializable
data class SchedulingBookingNotes(
    val exists: Boolean,
    val content: String? = null,
    val status: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * The answer to `bookings.notes.regenerate`.
 *
 * ⚠️ A NEAR-TWIN OF [SchedulingBookingNotes] WITH NO `updated_at`, AND THE MISSING KEY IS THE
 * CATALOG'S RATHER THAN AN OVERSIGHT HERE. The regenerate schema does not declare it, so a shared
 * type would model a key the route strips and the strict gate would fail on it. The near-miss is
 * the reason both are spelled out.
 */
@Serializable
data class SchedulingBookingNotesRegenerated(
    val exists: Boolean,
    val content: String? = null,
    val status: String? = null,
)

/**
 * The transcript of one booking's call.
 *
 * ⚠️ [text] IS ABSENT RATHER THAN EMPTY WHEN THERE IS NONE, and [exists] is the field to branch
 * on — same rule as [SchedulingBookingNotes].
 */
@Serializable
data class SchedulingBookingTranscript(
    val exists: Boolean,
    val text: String? = null,
)
