package com.distronode.districtai.ui.scheduling.admin

import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingBookingAttendee
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.model.SchedulingStatusResponse
import com.distronode.districtai.core.model.SchedulingTenant
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.FakeSchedulingAdminApi

/**
 * Fixtures shared by the scheduling admin's tests: a fake admin API, a fake status API, and rows
 * shaped like the committed contract fixtures.
 */
class SchedulingAdminTestRig(canManage: Boolean = true, publicHost: String? = "acme-book.example") {
    val admin = FakeSchedulingAdminApi()
    val district = FakeDistrictApi().apply {
        schedulingApi.schedulingStatusResult = ApiResult.Success(
            SchedulingStatusResponse(
                eligible = true,
                canManage = canManage,
                tenant = publicHost?.let {
                    SchedulingTenant(status = "ready", publicHost = it, region = "us", hasCredentials = true)
                },
            ),
        )
    }
    val unknownOps = mutableListOf<String>()
    val adminRepository = SchedulingAdminRepository(admin) { unknownOps += it.wire }
    val scheduling = SchedulingRepository(district, ApiEnvironment.baseUrl)
}

fun schedulingMe(timezone: String = "America/Toronto", isAdmin: Boolean = true) = SchedulingMe(
    id = "sched-user-1",
    email = "ana@example.com",
    name = "Ana Lima",
    timezone = timezone,
    timeFormat = "12h",
    weekStart = 1,
    dateFormat = "mdy",
    isAdmin = isAdmin,
    isOwner = isAdmin,
    role = if (isAdmin) "owner" else "member",
    notifyConfirmation = true,
    notifyCancellation = true,
    notifyReschedule = true,
    notifyReminder = true,
    notifyHostBooking = true,
    notifyHostCancel = true,
    notifyHostReschedule = true,
)

fun schedulingEventType(
    slug: String = "intro",
    name: String = "Intro call",
    isActive: Boolean? = true,
    isPublic: Boolean? = true,
) = SchedulingEventType(
    id = "et-$slug",
    slug = slug,
    name = name,
    durationMinutes = 30,
    isActive = isActive,
    isPublic = isPublic,
)

fun schedulingBooking(
    id: String = "b1",
    startAt: String = "2026-10-02T13:00:00Z",
    endAt: String = "2026-10-02T13:30:00Z",
    status: String = "confirmed",
    eventTypeSlug: String? = "intro",
    hostId: String? = "sched-user-1",
    hostName: String? = "Ana Lima",
    attendee: SchedulingBookingAttendee? = SchedulingBookingAttendee(name = "Bo Chen", email = "bo@example.com"),
) = SchedulingBooking(
    id = id,
    eventTypeSlug = eventTypeSlug,
    hostId = hostId,
    hostName = hostName,
    startAt = startAt,
    endAt = endAt,
    status = status,
    attendees = attendee?.let { listOf(it) },
)
