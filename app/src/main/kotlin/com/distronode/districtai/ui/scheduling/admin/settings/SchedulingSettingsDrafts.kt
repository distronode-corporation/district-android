package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingBrandingUpdate
import com.distronode.districtai.core.data.SchedulingMeUpdate
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.ui.inbox.PickedAttachment
import com.distronode.districtai.ui.scheduling.admin.DAYS_IN_WEEK
import java.net.URI
import java.net.URISyntaxException
import java.time.ZoneId

// The settings forms' drafts and the checks made before a request, ported from district-ios's
// `SchedulingProfileModel`, `SchedulingBrandingModel`, `SchedulingAutomationModel` and
// `SchedulingWritesBImage`. ⚠️ Every one is also enforced by the catalog: these give a sentence a
// member can act on instead of a 400.

/**
 * The five profile fields the form owns.
 *
 * ⛔ ALL FIVE ARE SENT ON EVERY SAVE, as the web's profile tab does: `me.patch` is sparse, but a
 * stale draft beside a value somebody changed elsewhere is two answers to one question.
 */
data class SchedulingProfileDraft(
    val name: String,
    val timezone: String,
    val timeFormat: String,
    val weekStart: Int,
    val dateFormat: String,
) {
    /**
     * ⛔ `timezone`, NOT `iana_timezone`: [SchedulingMeUpdate] spells the wire field, and sending the
     * column name would be silently ignored.
     */
    fun toUpdate(): SchedulingMeUpdate = SchedulingMeUpdate(
        name = name.trim(),
        timezone = timezone.trim(),
        timeFormat = timeFormat,
        weekStart = weekStart,
        dateFormat = dateFormat,
    )

    companion object {
        fun from(me: SchedulingMe): SchedulingProfileDraft =
            SchedulingProfileDraft(me.name, me.timezone, me.timeFormat, me.weekStart, me.dateFormat)
    }
}

/**
 * Why the profile cannot be saved, in the web's order, or null.
 *
 * ⚠️ THE ZONE IS A TYPED FIELD HERE, where district-ios has a picker over the whole IANA list, so
 * a name this device does not know is refused, except the one already stored: a picker that
 * silently dropped the current value would rewrite it on the next save.
 */
@StringRes
fun SchedulingProfileDraft.rejection(storedZone: String): Int? {
    val trimmedName = name.trim()
    val zone = timezone.trim()
    return when {
        trimmedName.isEmpty() -> R.string.scheduling_admin_settings_name_required
        trimmedName.length > NAME_LIMIT -> R.string.scheduling_admin_settings_name_too_long
        zone.isEmpty() -> R.string.scheduling_admin_settings_timezone_required
        zone != storedZone && zone !in ZoneId.getAvailableZoneIds() ->
            R.string.scheduling_admin_settings_timezone_unknown
        SCHEDULING_TIME_FORMATS.none { it.first == timeFormat } ->
            R.string.scheduling_admin_settings_time_format_required
        weekStart !in 0 until DAYS_IN_WEEK -> R.string.scheduling_admin_settings_week_start_required
        SCHEDULING_DATE_FORMATS.none { it.first == dateFormat } ->
            R.string.scheduling_admin_settings_date_format_required
        else -> null
    }
}

/** The seven switches as `me.patch` takes them. ⚠️ All seven, always: a Bool has no "untouched". */
fun Map<SchedulingNotifySwitch, Boolean>.toUpdate(): SchedulingMeUpdate = SchedulingMeUpdate(
    notifyConfirmation = getValue(SchedulingNotifySwitch.CONFIRMATION),
    notifyCancellation = getValue(SchedulingNotifySwitch.CANCELLATION),
    notifyReschedule = getValue(SchedulingNotifySwitch.RESCHEDULE),
    notifyReminder = getValue(SchedulingNotifySwitch.REMINDER),
    notifyHostBooking = getValue(SchedulingNotifySwitch.HOST_BOOKING),
    notifyHostCancel = getValue(SchedulingNotifySwitch.HOST_CANCEL),
    notifyHostReschedule = getValue(SchedulingNotifySwitch.HOST_RESCHEDULE),
)

/**
 * Why the booking page cannot be saved, in the web's order, or null.
 *
 * ⚠️ THE RANGES ARE CHECKED THOUGH THE SLIDERS ARE CLAMPED: a stored value from before the clamp
 * is outside them, and a control cannot be the guarantee.
 */
@StringRes
fun SchedulingBrandingUpdate.rejection(): Int? = when {
    businessName.trim().length > NAME_LIMIT -> R.string.scheduling_admin_settings_business_name_too_long
    logoHeight !in LOGO_HEIGHT_RANGE -> R.string.scheduling_admin_settings_logo_height_range
    logoOpacity !in OPACITY_RANGE || bannerOpacity !in OPACITY_RANGE ->
        R.string.scheduling_admin_settings_opacity_range
    else -> legalUrlRejection(privacyUrl)
        ?: legalUrlRejection(termsUrl)
        ?: R.string.scheduling_admin_settings_fallback_locale_required.takeIf { fallbackLocale.isBlank() }
}

/**
 * A footer link: empty is the legitimate "no link"; anything else must be an absolute http(s) URL.
 * ⛔ A bare `example.com` parses as a relative reference, so the scheme and host are tested.
 */
@StringRes
internal fun legalUrlRejection(raw: String): Int? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.length > LEGAL_URL_LIMIT) return R.string.scheduling_admin_settings_url_too_long
    val parsed = try {
        URI(trimmed)
    } catch (_: URISyntaxException) {
        return R.string.scheduling_admin_settings_url_not_absolute
    }
    val scheme = parsed.scheme?.lowercase()
    val absolute = (scheme == "http" || scheme == "https") && parsed.host != null
    return if (absolute) null else R.string.scheduling_admin_settings_url_not_absolute
}

/** A picked image: send it, or refuse it with the sentence that says why. */
sealed interface SchedulingImageCheck {
    data class Send(val file: PickedAttachment) : SchedulingImageCheck
    data class Refuse(@get:StringRes val reason: Int) : SchedulingImageCheck
}

/**
 * Whether a picked image is sent.
 *
 * ⛔ THREE REFUSALS KEPT APART: nothing readable, too large, not a type the route takes. Each calls
 * for a different next step. ⚠️ Size before type, matching the web uploader. Advisory, never the
 * boundary: the fork sniffs the bytes.
 */
fun PickedAttachment?.imageCheck(): SchedulingImageCheck = when {
    this == null || bytes.isEmpty() -> SchedulingImageCheck.Refuse(R.string.scheduling_admin_settings_image_unreadable)
    bytes.size > SchedulingAdminRepository.MAX_UPLOAD_BYTES ->
        SchedulingImageCheck.Refuse(R.string.scheduling_admin_settings_image_too_large)
    mimeType !in SchedulingAdminRepository.ACCEPTED_UPLOAD_TYPES ->
        SchedulingImageCheck.Refuse(R.string.scheduling_admin_settings_image_wrong_type)
    else -> SchedulingImageCheck.Send(this)
}

/** The longest name and business name the scheduler takes. */
const val NAME_LIMIT: Int = 200

/** The longest footer link. */
const val LEGAL_URL_LIMIT: Int = 500

/** The longest extra instructions for the booking assistant. */
const val ASSISTANT_INSTRUCTIONS_LIMIT: Int = 4000

private const val LOGO_HEIGHT_MIN = 16
private const val LOGO_HEIGHT_MAX = 64
private const val OPACITY_MIN = 20
private const val OPACITY_MAX = 100

/** The logo height, in pixels, the fork accepts. */
val LOGO_HEIGHT_RANGE: IntRange = LOGO_HEIGHT_MIN..LOGO_HEIGHT_MAX

/** An opacity, in percent, the fork accepts. */
val OPACITY_RANGE: IntRange = OPACITY_MIN..OPACITY_MAX
