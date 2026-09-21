package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A team a scheduler user belongs to, as listed on the user row.
 *
 * ⚠️ THE THIN HALF OF [SchedulingTeam] AND NOT AN ABBREVIATION OF IT. The fork sends two fields
 * here and the strict gate would fail on a third, so this cannot be merged with the team type
 * even though every field it has is also a field there.
 */
@Serializable
data class SchedulingUserTeam(
    val id: String,
    val name: String,
)

/**
 * One of the TENANCY'S SCHEDULER USERS.
 *
 * ⛔ NOT A DISTRONODE WORKSPACE MEMBER, AND CONFLATING THE TWO IS THE MISTAKE THIS TYPE EXISTS TO
 * PREVENT. A scheduler user is a host inside the booking fork with its own id, its own role
 * vocabulary and its own archive state; [WorkspaceRole] describes a different population with a
 * different lifecycle. The one place they touch is that archiving a scheduler user is what closes
 * out a member whose platform removal was refused.
 *
 * ⛔ [archived] IS REQUIRED AND [archivedAt] IS NOT, WHICH IS NOT REDUNDANCY. The flag is the
 * state; the timestamp is absent on a user archived before the fork recorded one. A screen that
 * derived the state from the timestamp would show a former host as current.
 *
 * ⚠️ [teams] IS EXPLICITLY NULL FOR A USER IN NO TEAM, which the fixture's second row sends. Null
 * and `[]` mean the same thing here and both must decode.
 */
@Serializable
data class SchedulingUser(
    val id: String,
    val email: String,
    val name: String,
    val timezone: String? = null,
    @SerialName("is_admin") val isAdmin: Boolean,
    @SerialName("is_owner") val isOwner: Boolean,
    val role: String,
    @SerialName("email_login") val emailLogin: Boolean? = null,
    val provider: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val archived: Boolean,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("archived_by_name") val archivedByName: String? = null,
    val teams: List<SchedulingUserTeam>? = null,
)

/**
 * The answer to `users.archive`.
 *
 * ⚠️ [ok] IS THE OP'S OWN FLAG AND IS NOT THE ENVELOPE'S. It sits INSIDE `data`, so a body reads
 * `{"ok":true,"data":{"ok":true,"archived_at":…}}` — two flags that mean different things, the
 * same shape as [SchedulingNoContent].
 */
@Serializable
data class SchedulingUserArchived(
    val ok: Boolean,
    @SerialName("archived_at") val archivedAt: String? = null,
)

/**
 * What a host has coming up, as `users.upcomingBookings` answers it.
 *
 * ⛔ EVERY FIELD IS REQUIRED, WHICH IS UNUSUAL ON THIS SURFACE AND IS THE CATALOG'S DOING. This
 * shape is declared inline in `admin-ops.ts` rather than reusing the booking schema, and it
 * exists to answer one question — "can this host be archived" — where a row with no attendee and
 * no time would be no answer at all.
 */
@Serializable
data class SchedulingUpcomingBooking(
    val id: String,
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("event_type_name") val eventTypeName: String,
    @SerialName("event_type_slug") val eventTypeSlug: String,
    @SerialName("attendee_name") val attendeeName: String,
    @SerialName("attendee_email") val attendeeEmail: String,
)

/**
 * A member of a team, with the priority that decides who a round-robin offers first.
 *
 * ⚠️ [routingPriority] IS REQUIRED AND [SchedulingHost.priority] IS TOO, and they are different
 * numbers on different objects — a team member's standing inside the team, and a host's standing
 * on one event type. Neither derives from the other.
 */
@Serializable
data class SchedulingTeamMember(
    val id: String,
    val name: String,
    val email: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("routing_priority") val routingPriority: Int,
    val archived: Boolean,
)

/**
 * A team.
 *
 * ⛔ [members] IS EXPLICITLY NULL ON A TEAM WITH NONE AND ALSO ON A LIST ROW THE FORK DID NOT
 * EXPAND, so it cannot be read as "this team is empty". [memberCount] is the count to show; the
 * fixture's second team sends `"member_count": 0` with `"members": null` precisely so a client
 * cannot get away with inferring one from the other.
 */
@Serializable
data class SchedulingTeam(
    val id: String,
    val name: String,
    val slug: String,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
    val members: List<SchedulingTeamMember>? = null,
)
