package com.distronode.districtai.ui.scheduling.admin

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.distronode.districtai.AppContainer
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.ui.ARG_WORKSPACE_ID
import com.distronode.districtai.ui.pathArgument
import com.distronode.districtai.ui.scheduling.admin.bookings.SchedulingBookingDetailRoute
import com.distronode.districtai.ui.scheduling.admin.bookings.SchedulingBookingsRoute
import com.distronode.districtai.ui.scheduling.admin.calendar.SchedulingCalendarRoute
import com.distronode.districtai.ui.scheduling.admin.developer.SchedulingDeveloperRoute
import com.distronode.districtai.ui.scheduling.admin.eventtypes.SchedulingEventTypeRoute
import com.distronode.districtai.ui.scheduling.admin.eventtypes.SchedulingEventTypesRoute
import com.distronode.districtai.ui.scheduling.admin.hours.SchedulingHoursRoute
import com.distronode.districtai.ui.scheduling.admin.overview.SchedulingOverviewRoute
import com.distronode.districtai.ui.scheduling.admin.settings.SchedulingSettingsRoute
import com.distronode.districtai.ui.scheduling.admin.team.SchedulingTeamRoute

/**
 * The scheduling admin's routes, beside the booking-page route in [com.distronode.districtai.ui.Routes].
 *
 * ⛔ DECLARED IN THIS PACKAGE, NOT IN `Routes`, for the reason the booking-page destination lives
 * in `SchedulingDestination.kt`: `DistrictNavHost.kt` is at detekt's per-file ceilings, and the
 * whole admin is one seam. Like the booking-page route, none carries a role: the status read's
 * `canManage` decides which writes are drawn (see [SchedulingAccess]).
 */
object SchedulingAdminRoutes {
    const val ARG_SECTION: String = "section"
    const val ARG_BOOKING_ID: String = "bookingId"
    const val ARG_SLUG: String = "slug"

    const val SECTION: String = "workspace/{$ARG_WORKSPACE_ID}/scheduling/section/{$ARG_SECTION}"
    const val BOOKING: String = "workspace/{$ARG_WORKSPACE_ID}/scheduling/booking/{$ARG_BOOKING_ID}"
    const val EVENT_TYPE: String = "workspace/{$ARG_WORKSPACE_ID}/scheduling/event-type/{$ARG_SLUG}"

    fun section(workspaceId: String, section: SchedulingSection): String =
        "workspace/${Uri.encode(workspaceId)}/scheduling/section/${section.pathSegment}"

    fun booking(workspaceId: String, bookingId: String): String =
        "workspace/${Uri.encode(workspaceId)}/scheduling/booking/${Uri.encode(bookingId)}"

    fun eventType(workspaceId: String, slug: String): String =
        "workspace/${Uri.encode(workspaceId)}/scheduling/event-type/${Uri.encode(slug)}"
}

/**
 * What every scheduling admin screen is given.
 *
 * @property navigate push a route from [SchedulingAdminRoutes].
 * @property openUrl hand a URL to a browser; answers whether one took it.
 * @property sessionEpoch changes when the session does; a section re-reads on a change.
 */
@Suppress("LongParameterList") // A parameter object: its fields ARE the parameters.
class SchedulingAdminDeps(
    val admin: SchedulingAdminRepository,
    val scheduling: SchedulingRepository,
    val workspaceId: String,
    val sessionEpoch: Int,
    val onBack: () -> Unit,
    val navigate: (String) -> Unit,
    val openUrl: (String) -> Boolean,
)

/**
 * Register the admin's three routes: a section, one booking, one event type.
 *
 * ⚠️ AN UNKNOWN SECTION SEGMENT GOES BACK rather than drawing an empty screen; it can only come
 * from a link a newer build wrote.
 */
internal fun NavGraphBuilder.schedulingAdminDestinations(
    container: AppContainer,
    navController: NavHostController,
    sessionEpoch: Int,
    openUrl: (String) -> Boolean,
) {
    fun deps(workspaceId: String) = SchedulingAdminDeps(
        admin = container.schedulingAdminRepository,
        scheduling = container.schedulingRepository,
        workspaceId = workspaceId,
        sessionEpoch = sessionEpoch,
        onBack = { navController.popBackStack() },
        navigate = { navController.navigate(it) },
        openUrl = openUrl,
    )

    composable(SchedulingAdminRoutes.SECTION) { entry ->
        val section = SchedulingSection.forPathSegment(entry.pathArgument(SchedulingAdminRoutes.ARG_SECTION))
        SchedulingSectionRoute(section, deps(entry.pathArgument(ARG_WORKSPACE_ID)))
    }
    composable(SchedulingAdminRoutes.BOOKING) { entry ->
        SchedulingBookingDetailRoute(
            deps(entry.pathArgument(ARG_WORKSPACE_ID)),
            entry.pathArgument(SchedulingAdminRoutes.ARG_BOOKING_ID),
        )
    }
    composable(SchedulingAdminRoutes.EVENT_TYPE) { entry ->
        SchedulingEventTypeRoute(
            deps(entry.pathArgument(ARG_WORKSPACE_ID)),
            entry.pathArgument(SchedulingAdminRoutes.ARG_SLUG),
        )
    }
}

/** One section, by its path segment. */
@Composable
internal fun SchedulingSectionRoute(section: SchedulingSection?, deps: SchedulingAdminDeps) {
    when (section) {
        SchedulingSection.OVERVIEW -> SchedulingOverviewRoute(deps)
        SchedulingSection.EVENT_TYPES -> SchedulingEventTypesRoute(deps)
        SchedulingSection.HOURS -> SchedulingHoursRoute(deps)
        SchedulingSection.BOOKINGS -> SchedulingBookingsRoute(deps)
        SchedulingSection.CALENDAR -> SchedulingCalendarRoute(deps)
        SchedulingSection.TEAM -> SchedulingTeamRoute(deps)
        SchedulingSection.SETTINGS -> SchedulingSettingsRoute(deps)
        SchedulingSection.DEVELOPER -> SchedulingDeveloperRoute(deps)
        else -> UnknownSection(deps.onBack)
    }
}

@Composable
private fun UnknownSection(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
