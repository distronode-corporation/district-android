package com.distronode.districtai.ui.scheduling.admin.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingLocaleOption
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.ui.scheduling.admin.DAYS_IN_WEEK
import com.distronode.districtai.ui.scheduling.admin.schedulingDisplayIndex
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue
import com.distronode.districtai.ui.scheduling.admin.schedulingWeekday

// The settings screen's readings, ported from district-core-swift's `SchedulingSettingsFormat`
// and district-ios's `SchedulingSettingsWriteCopy` option lists.

/**
 * The four tabs, in the web's order, with its `?tab=` ids.
 *
 * ⚠️ `assistant` REPLACED `recordings` when meeting recording was retired: the tab holds only the
 * booking assistant (`settings.llm.*`).
 */
enum class SchedulingSettingsTab(val id: String, @get:StringRes val label: Int) {
    BOOKING_PAGE("booking-page", R.string.scheduling_admin_settings_tab_booking_page),
    ASSISTANT("assistant", R.string.scheduling_admin_settings_tab_assistant),
    PROFILE("profile", R.string.scheduling_admin_settings_tab_profile),
    NOTIFICATIONS("notifications", R.string.scheduling_admin_settings_tab_notifications),
}

/**
 * The time formats `me.patch` takes, wire value to label. ⛔ The value is the catalog's `z.enum`;
 * the label (with its sample time) is never what is sent.
 */
val SCHEDULING_TIME_FORMATS: List<Pair<String, Int>> = listOf(
    "12h" to R.string.scheduling_admin_settings_time_format_12h,
    "24h" to R.string.scheduling_admin_settings_time_format_24h,
)

/** The date formats, labelled by an example date rather than a pattern, as the web does. */
val SCHEDULING_DATE_FORMATS: List<Pair<String, Int>> = listOf(
    "dmy" to R.string.scheduling_admin_settings_date_format_dmy,
    "mdy" to R.string.scheduling_admin_settings_date_format_mdy,
    "ymd" to R.string.scheduling_admin_settings_date_format_ymd,
)

/** `12h` as "12-hour (2:30 pm)"; a value this build does not know is shown as sent. */
@Composable
@ReadOnlyComposable
fun schedulingTimeFormatLabel(value: String): String =
    SCHEDULING_TIME_FORMATS.firstOrNull { it.first == value }?.let { stringResource(it.second) }
        ?: schedulingUnknownValue(value)

/** `dmy` as "31/12/2026"; a value this build does not know is shown as sent. */
@Composable
@ReadOnlyComposable
fun schedulingDateFormatLabel(value: String): String =
    SCHEDULING_DATE_FORMATS.firstOrNull { it.first == value }?.let { stringResource(it.second) }
        ?: schedulingUnknownValue(value)

/**
 * `0` as "Sunday".
 *
 * ⚠️ SUNDAY-INDEXED, LIKE THE WIRE'S `week_start`, so it goes through [schedulingDisplayIndex] to the
 * Monday-first weekday names; a number outside 0 to 6 is shown as sent.
 */
@Composable
@ReadOnlyComposable
fun schedulingWeekStartLabel(value: Int): String =
    if (value in 0 until DAYS_IN_WEEK) {
        schedulingWeekday(schedulingDisplayIndex(value))
    } else {
        schedulingUnknownValue(value.toString())
    }

/**
 * The language a visitor gets when theirs is not offered.
 *
 * ⚠️ THE CODE IS SHOWN WHEN THE SUPPORTED LIST DOES NOT NAME IT, rather than being blanked: a
 * fallback the tenancy no longer supports is a real configuration problem.
 */
@Composable
@ReadOnlyComposable
fun schedulingLocaleLabel(code: String, supported: List<SchedulingLocaleOption>?): String {
    val trimmed = code.trim()
    if (trimmed.isEmpty()) return stringResource(R.string.scheduling_admin_common_not_set)
    // ⚠️ The two languages District speaks are named in the app's language ("Anglais" on a French
    // screen); any other comes from the server's list, which names each language in itself.
    SCHEDULING_LANGUAGE_NAMES[trimmed]?.let { return stringResource(it) }
    val name = supported.orEmpty().firstOrNull { it.code == trimmed }?.name.orEmpty()
    return name.ifEmpty { trimmed }
}

/** The booking page languages the app names itself, by code. */
internal val SCHEDULING_LANGUAGE_NAMES: Map<String, Int> = mapOf(
    "en" to R.string.scheduling_admin_settings_language_en,
    "fr" to R.string.scheduling_admin_settings_language_fr,
)

/** Whose meeting a notification switch is about. */
enum class SchedulingNotifyGroup(@get:StringRes val heading: Int, @get:StringRes val description: Int) {
    ATTENDEE(
        R.string.scheduling_admin_settings_notify_attendee_heading,
        R.string.scheduling_admin_settings_notify_attendee_description,
    ),
    HOST(
        R.string.scheduling_admin_settings_notify_host_heading,
        R.string.scheduling_admin_settings_notify_host_description,
    ),
}

/**
 * The seven `notify_*` switches, in the web's order, grouped by whose meeting it is.
 *
 * ⚠️ `notify_host_cancel` AND `notify_cancellation` ARE ONE WORD APART AND MAIL TWO DIFFERENT
 * PEOPLE, which is why the groups are labelled on screen.
 */
enum class SchedulingNotifySwitch(
    val group: SchedulingNotifyGroup,
    @get:StringRes val label: Int,
    val read: (SchedulingMe) -> Boolean,
) {
    CONFIRMATION(
        SchedulingNotifyGroup.ATTENDEE,
        R.string.scheduling_admin_settings_notify_confirmation,
        SchedulingMe::notifyConfirmation,
    ),
    CANCELLATION(
        SchedulingNotifyGroup.ATTENDEE,
        R.string.scheduling_admin_settings_notify_cancellation,
        SchedulingMe::notifyCancellation,
    ),
    RESCHEDULE(
        SchedulingNotifyGroup.ATTENDEE,
        R.string.scheduling_admin_settings_notify_reschedule,
        SchedulingMe::notifyReschedule,
    ),
    REMINDER(
        SchedulingNotifyGroup.ATTENDEE,
        R.string.scheduling_admin_settings_notify_reminder,
        SchedulingMe::notifyReminder,
    ),
    HOST_BOOKING(
        SchedulingNotifyGroup.HOST,
        R.string.scheduling_admin_settings_notify_host_booking,
        SchedulingMe::notifyHostBooking,
    ),
    HOST_CANCEL(
        SchedulingNotifyGroup.HOST,
        R.string.scheduling_admin_settings_notify_host_cancel,
        SchedulingMe::notifyHostCancel,
    ),
    HOST_RESCHEDULE(
        SchedulingNotifyGroup.HOST,
        R.string.scheduling_admin_settings_notify_host_reschedule,
        SchedulingMe::notifyHostReschedule,
    ),
}
