package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The caller's own scheduler user, as `me.get` and `me.patch` both answer it.
 *
 * ⛔ EVERY FIELD BUT THE AVATAR IS REQUIRED, AND THAT IS THE ONE PLACE ON THIS SURFACE WHERE
 * STRICTNESS IS THE PRODUCT. Seven notification booleans and four presentation settings decide
 * what this member is emailed and how their availability is drawn; a default would silently claim
 * they had opted out of something, or place their working day in the wrong hours.
 *
 * ⚠️ [timezone] IS AN IANA NAME AND THE ONE FIELD A `viewer` MOST NEEDS TO WRITE. That is why the
 * whole `me.*` namespace is viewer-level even though it writes: a viewer who cannot set their own
 * timezone is offered every booking window in the wrong hours.
 */
@Serializable
data class SchedulingMe(
    val id: String,
    val email: String,
    val name: String,
    val timezone: String,
    @SerialName("time_format") val timeFormat: String,
    @SerialName("week_start") val weekStart: Int,
    @SerialName("date_format") val dateFormat: String,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_owner") val isOwner: Boolean,
    val role: String,
    @SerialName("notify_confirmation") val notifyConfirmation: Boolean,
    @SerialName("notify_cancellation") val notifyCancellation: Boolean,
    @SerialName("notify_reschedule") val notifyReschedule: Boolean,
    @SerialName("notify_reminder") val notifyReminder: Boolean,
    @SerialName("notify_host_booking") val notifyHostBooking: Boolean,
    @SerialName("notify_host_cancel") val notifyHostCancel: Boolean,
    @SerialName("notify_host_reschedule") val notifyHostReschedule: Boolean,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** One locale the booking pages can be served in. */
@Serializable
data class SchedulingLocaleOption(
    val code: String,
    val name: String,
)

/**
 * The booking pages' branding.
 *
 * ⛔ THE FOUR URL FIELDS ARE REQUIRED AND AN EMPTY STRING IS HOW "UNSET" IS SPELLED. The fixture
 * sends `"privacy_url": ""`, not null and not an absent key, so a nullable type here would model
 * a shape the fork does not produce and the round trip would stop matching. Treat `""` as absent
 * at the presentation layer, never in this type.
 *
 * ⚠️ [supportedLocales] IS THE ONLY OPTIONAL FIELD, absent when the instance offers one locale.
 */
@Serializable
data class SchedulingBranding(
    @SerialName("business_name") val businessName: String,
    @SerialName("logo_url") val logoUrl: String,
    @SerialName("logo_height") val logoHeight: Int,
    @SerialName("logo_opacity") val logoOpacity: Int,
    @SerialName("banner_url") val bannerUrl: String,
    @SerialName("banner_opacity") val bannerOpacity: Int,
    @SerialName("privacy_url") val privacyUrl: String,
    @SerialName("terms_url") val termsUrl: String,
    @SerialName("fallback_locale") val fallbackLocale: String,
    @SerialName("supported_locales") val supportedLocales: List<SchedulingLocaleOption>? = null,
)

/**
 * The tenancy's note-generation settings.
 *
 * ⚠️ [extraInstructions] IS REQUIRED AND EMPTY-WHEN-UNSET, the same spelling [SchedulingBranding]
 * uses for its URLs. It is operator-authored text that reaches a model prompt, so it is never
 * defaulted here — a default would put words into a prompt nobody wrote.
 */
@Serializable
data class SchedulingLlmSettings(
    val enabled: Boolean,
    @SerialName("extra_instructions") val extraInstructions: String,
)

/**
 * What an image upload published.
 *
 * ⛔ ALL THREE KEYS ARE OPTIONAL AND EXACTLY ONE ARRIVES, decided by the `target` the upload sent.
 * A required field here would refuse two of the three uploads. [publishedUrl] is the accessor to
 * use rather than reaching for the key that matches the target, which puts the same decision in
 * two places.
 *
 * ⚠️ NOT A STORED PROPERTY, so it is never encoded and cannot add a key the server never sent —
 * which is what keeps it out of the strict gate's key-set walk.
 */
@Serializable
data class SchedulingUploadResult(
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
) {
    val publishedUrl: String?
        get() = logoUrl ?: bannerUrl ?: avatarUrl
}
