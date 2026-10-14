package com.distronode.districtai.ui.scheduling.admin.team

import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.model.SchedulingTeamMember
import com.distronode.districtai.core.model.SchedulingUpcomingBooking
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.core.model.WorkspaceMember

/** A scheduler user shaped like the contract fixture's. */
fun teamUser(id: String, email: String, name: String = "", archived: Boolean = false) = SchedulingUser(
    id = id,
    email = email,
    name = name,
    isAdmin = false,
    isOwner = false,
    role = "member",
    archived = archived,
)

/** A District workspace member. */
fun districtMember(email: String, role: String? = "client") = WorkspaceMember(email = email, role = role)

/** A team member at [priority]. */
fun teamMember(id: String, name: String, priority: Int = 0) = SchedulingTeamMember(
    id = id,
    name = name,
    email = "$id@example.com",
    routingPriority = priority,
    archived = false,
)

/** A team; [members] null is a list row the fork did not expand. */
fun schedulingTeam(
    id: String = "t1",
    name: String = "Front desk",
    members: List<SchedulingTeamMember>? = null,
    memberCount: Int? = members?.size,
) = SchedulingTeam(id = id, name = name, slug = "front-desk", memberCount = memberCount, members = members)

/** One upcoming booking standing in the way of an archive. */
fun upcomingBooking(id: String = "b1") = SchedulingUpcomingBooking(
    id = id,
    startAt = "2026-10-02T13:00:00Z",
    endAt = "2026-10-02T13:30:00Z",
    eventTypeName = "Intro call",
    eventTypeSlug = "intro",
    attendeeName = "Bo Chen",
    attendeeEmail = "bo@example.com",
)
