package com.distronode.districtai.ui.scheduling.admin

import androidx.annotation.StringRes
import com.distronode.districtai.DistrictSentry
import com.distronode.districtai.R
import com.distronode.districtai.core.network.SchedulingAdminOp

/**
 * The native scheduling admin's sections, in the order the booking-page screen lists them.
 *
 * ⛔ THE SAME EIGHT, IN THE SAME ORDER, AS district-ios's `SchedulingSection.listed`, and the path
 * segments are its spelling too, so an app link or a support thread names a section the same way
 * on every platform. Every section admits `viewer` for its reads; the writes inside each are drawn
 * only when the status read's `canManage` says the member may make them (see [SchedulingAccess]).
 */
enum class SchedulingSection(
    val pathSegment: String,
    @get:StringRes val title: Int,
    @get:StringRes val subtitle: Int,
) {
    OVERVIEW(
        "overview",
        R.string.scheduling_admin_section_title_overview,
        R.string.scheduling_admin_section_subtitle_overview,
    ),
    EVENT_TYPES(
        "event-types",
        R.string.scheduling_admin_section_title_event_types,
        R.string.scheduling_admin_section_subtitle_event_types,
    ),
    HOURS(
        "hours",
        R.string.scheduling_admin_section_title_hours,
        R.string.scheduling_admin_section_subtitle_hours,
    ),
    BOOKINGS(
        "bookings",
        R.string.scheduling_admin_section_title_bookings,
        R.string.scheduling_admin_section_subtitle_bookings,
    ),
    CALENDAR(
        "calendar",
        R.string.scheduling_admin_section_title_calendar,
        R.string.scheduling_admin_section_subtitle_calendar,
    ),
    TEAM(
        "team",
        R.string.scheduling_admin_section_title_team,
        R.string.scheduling_admin_section_subtitle_team,
    ),
    SETTINGS(
        "settings",
        R.string.scheduling_admin_section_title_settings,
        R.string.scheduling_admin_section_subtitle_settings,
    ),
    DEVELOPER(
        "developer",
        R.string.scheduling_admin_section_title_developer,
        R.string.scheduling_admin_section_subtitle_developer,
    ),
    ;

    companion object {
        /** The section a path segment names, or null for one this build does not know. */
        fun forPathSegment(segment: String?): SchedulingSection? = entries.firstOrNull { it.pathSegment == segment }
    }
}

/**
 * What the app does about a **400 `unknown_op`**: [SchedulingAdminOp] and the server's catalog have
 * diverged, which no member can act on, so it goes to the crash reporter as a warning.
 *
 * ⚠️ THE MESSAGE CARRIES THE OP NAME AND NOTHING ELSE. An op name is a fixed catalog string, never
 * an identifier, which is the bar `DistrictSentry.reportNonFatal` sets.
 */
fun reportUnknownSchedulingOp(op: SchedulingAdminOp) {
    DistrictSentry.reportNonFatal("Scheduling admin: the server does not know the op ${op.wire}")
}
