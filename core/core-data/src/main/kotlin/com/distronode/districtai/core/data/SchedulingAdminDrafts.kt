package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBranding

/*
 * The request-side shapes the scheduling admin's writes take.
 *
 * ⛔ NOT `@Serializable`, AND THAT IS DELIBERATE RATHER THAN AN OVERSIGHT. The wire form of a
 * write is an op's `params` object, built by [schedulingParams] so a null pair is DROPPED; a
 * generated serializer would either write explicit nulls (which the catalog's non-nullable schemas
 * refuse) or need `explicitNulls = false` set on a decoder that also reads responses. One builder,
 * one rule, in one place.
 *
 * ⛔ AND THEY ARE NOT THE ROW TYPES. `SchedulingEventType` describes what the server sent;
 * [SchedulingEventTypeDraft] describes what a form is asking for, and the two differ in every way
 * that matters — no id, no timestamps, no ownership, and "absent" meaning "you choose" rather than
 * "not set". Re-using a row type for a create is how a client ends up POSTing a `created_at`.
 */

/** A host's place on one event type, as sent by `eventTypes.hosts.put`. */
data class SchedulingHostAssignment(
    val userId: String,
    /** ⚠️ `required`, `rotation` or `optional` — the FORK's routing vocabulary. Not a District role. */
    val role: String,
    val priority: Int,
)

/** A new booking question. */
data class SchedulingQuestionDraft(
    val label: String,
    val type: String,
    val required: Boolean,
    /** ⚠️ Null for every type that is not a choice. An empty list would be a select with none. */
    val options: List<String>? = null,
    /** Null lets the server append it last. */
    val position: Int? = null,
)

/** A partial update to a booking question. ⚠️ Every null is "leave alone". */
data class SchedulingQuestionChanges(
    val label: String? = null,
    val type: String? = null,
    val options: List<String>? = null,
    val required: Boolean? = null,
    val position: Int? = null,
)

/**
 * A dated availability override to create.
 *
 * ⛔ [endDate] IS WHAT DECIDES THE RESPONSE SHAPE. With it the op answers a
 * [com.distronode.districtai.core.model.SchedulingOverrideCreated.Range] summary and creates one
 * row per day; without it a single
 * [com.distronode.districtai.core.model.SchedulingOverrideCreated.Single]. One op, two shapes.
 */
data class SchedulingOverrideDraft(
    val date: String,
    val reason: String,
    val endDate: String? = null,
    /** ⚠️ Sent together or not at all: times without the pair are custom hours with no end. */
    val startTime: String? = null,
    val endTime: String? = null,
)

/**
 * A partial update to the caller's own scheduler user.
 *
 * ⚠️ VIEWER-LEVEL AND BILLED TO THE PER-MEMBER BUDGET, not the workspace's 120/hour. See
 * [com.distronode.districtai.core.network.SchedulingAdminOp.isWrite].
 */
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
 * The branding settings, as a WHOLE.
 *
 * ⛔ EVERY FIELD IS REQUIRED HERE WHILE EVERY OTHER UPDATE TYPE IN THIS FILE IS PARTIAL, AND THAT
 * IS THE CATALOG'S SHAPE RATHER THAN A CHOICE. `settings.branding.patch` declares all seven, so a
 * caller that sent three would blank the other four. Build one from the current
 * [com.distronode.districtai.core.model.SchedulingBranding] with [from] and change what the form
 * changed.
 *
 * ⚠️ THE IMAGE URLs ARE ABSENT ON PURPOSE. `logo_url` and `banner_url` are set by the multipart
 * upload and cleared by their own delete ops; they are not patchable text.
 */
data class SchedulingBrandingUpdate(
    val businessName: String,
    val logoHeight: Int,
    val logoOpacity: Int,
    val bannerOpacity: Int,
    /** ⚠️ `""` is how "unset" is spelled on this surface, never null. */
    val privacyUrl: String,
    val termsUrl: String,
    val fallbackLocale: String,
) {
    companion object {
        /** Seed an update from what the server currently holds, so a partial form cannot blank a field. */
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
 * The filters `bookings.list` accepts.
 *
 * ⛔ A TYPE RATHER THAN TWELVE ARGUMENTS, WHICH IS A DELIBERATE DIVERGENCE FROM iOS AND IS FORCED
 * BY A LOCAL GATE: detekt's `LongParameterList` stops at eight, and a suppression on the one
 * function that reads a query is a worse trade than a named shape. It also makes the call site
 * readable — twelve defaulted `String?` positional arguments is exactly the shape where a `host`
 * ends up in the `team` slot with no compiler objection.
 *
 * ⚠️ [allHosts] IS SPELLED `scope=all` ON THE WIRE AND IS OMITTED WHEN FALSE, not sent as `false`.
 * The server reads presence, not value.
 */
data class SchedulingBookingQuery(
    val status: String? = null,
    val whenFilter: String? = null,
    val from: String? = null,
    val to: String? = null,
    val eventTypeSlug: String? = null,
    val host: String? = null,
    val team: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    /** ⛔ Every host's bookings, not just the caller's. The scheduler gates this itself. */
    val allHosts: Boolean = false,
    val order: String? = null,
)
