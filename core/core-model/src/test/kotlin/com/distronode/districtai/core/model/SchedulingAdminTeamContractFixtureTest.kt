package com.distronode.districtai.core.model

import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `users.*` and `teams.*` families.
 *
 * ⛔ `users.list` ANSWERS A BARE ARRAY, WHICH IS THE ONE READ ON THIS WHOLE SURFACE WITH NO WRAPPER
 * AT ALL — not `{items}`, not `{users}`. Every sibling list uses the catalog's shared wrapper, so
 * this is the one a client writes by habit and gets wrong; a test below pins it.
 *
 * ⛔ AND THESE ARE THE TENANCY'S SCHEDULER USERS, NOT DISTRONODE'S WORKSPACE MEMBERS. A scheduler
 * user has its own id, its own role vocabulary and its own archive state; [WorkspaceRole] describes
 * a different population with a different lifecycle.
 */
class SchedulingAdminTeamContractFixtureTest {

    private fun users(): List<SchedulingUser> = SchedulingAdminFixtures.data(
        "district-scheduling-users.json",
        ListSerializer(SchedulingUser.serializer()),
    )

    @Test
    fun `the user list is a bare array rather than the shared items wrapper`() {
        assertEquals(2, users().size)

        // ⛔ THE MISTAKE THIS PINS. Reading it through `SchedulingItems` fails rather than quietly
        // reporting an empty roster, which is the better of the two wrong answers and still wrong.
        assertTrue(
            runCatching {
                SchedulingAdminFixtures.data(
                    "district-scheduling-users.json",
                    SchedulingItems.serializer(SchedulingUser.serializer()),
                )
            }.isFailure,
        )
    }

    @Test
    fun `an active user decodes its teams and an archived one decodes its archival`() {
        val rows = users()

        assertEquals("sched-user-contract", rows[0].id)
        assertEquals("owner", rows[0].role)
        assertTrue(rows[0].isOwner)
        assertTrue(rows[0].isAdmin)
        assertEquals("America/Toronto", rows[0].timezone)
        assertEquals("oidc", rows[0].provider)
        assertEquals(false, rows[0].emailLogin)

        // ⚠️ THE NESTED COLLECTION, and it is the THIN team shape rather than the full one.
        assertEquals(1, rows[0].teams?.size)
        assertEquals("Sales", rows[0].teams?.first()?.name)
        assertFalse(rows[0].archived)
        assertNull("an active user has no archival stamp", rows[0].archivedAt)

        // ⛔ THE ARCHIVED ROW. `archived` is REQUIRED and `archived_at` is not — a screen that
        // derived the state from the timestamp would show a former host as current. Here both are
        // present, and the point is that the flag is the state.
        assertTrue(rows[1].archived)
        assertEquals("2026-09-05T12:00:00Z", rows[1].archivedAt)
        assertEquals("Contract Member", rows[1].archivedByName)

        // ⛔ AND `teams: null` IS EXPLICIT, NOT ABSENT. Null and `[]` mean the same thing here and
        // both have to decode.
        assertNull(rows[1].teams)
        assertNull("the archived row states no timezone", rows[1].timezone)
        assertNull(rows[1].provider)
        assertNull(rows[1].avatarUrl)
    }

    @Test
    fun `a user with no archive flag is rejected rather than assumed active`() {
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingUser.serializer(),
                    """{"id":"u","email":"a@b.test","name":"A","is_admin":false,
                       |"is_owner":false,"role":"member"}
                    """.trimMargin(),
                )
            }.isFailure,
        )
    }

    @Test
    fun `the archive acknowledgement nests its own flag inside the envelope's`() {
        val archived = SchedulingAdminFixtures.data(
            "district-scheduling-user-archive.json",
            SchedulingUserArchived.serializer(),
        )

        // ⚠️ TWO FLAGS AGAIN: this `ok` sits INSIDE `data`, so the body is
        // `{"ok":true,"data":{"ok":true,…}}` — the same shape as the no-content ops.
        assertTrue(archived.ok)
        assertEquals("2026-09-05T12:00:00Z", archived.archivedAt)
    }

    @Test
    fun `upcoming bookings are the archive check and every field is required`() {
        val upcoming = SchedulingAdminFixtures.data(
            "district-scheduling-user-upcoming.json",
            SchedulingItems.serializer(SchedulingUpcomingBooking.serializer()),
        ).items

        assertEquals(1, upcoming.size)

        // ⛔ THIS SHAPE IS DECLARED INLINE IN THE CATALOG rather than reusing the booking schema,
        // and every field is required because it exists to answer one question — "can this host be
        // archived" — where a row with no attendee and no time would be no answer at all.
        assertEquals("bk-contract-3", upcoming[0].id)
        assertEquals("site-visit", upcoming[0].eventTypeSlug)
        assertEquals("Site visit", upcoming[0].eventTypeName)
        assertEquals("Dana Booker", upcoming[0].attendeeName)
        assertEquals("dana@contract.test", upcoming[0].attendeeEmail)
        assertEquals("2026-09-18T15:00:00Z", upcoming[0].startAt)
    }

    @Test
    fun `a populated team decodes its members and an empty one states a count without them`() {
        val teams = SchedulingAdminFixtures.data(
            "district-scheduling-teams.json",
            SchedulingItems.serializer(SchedulingTeam.serializer()),
        ).items

        assertEquals(2, teams.size)

        // ⚠️ THE NESTED COLLECTION, with the present-and-absent avatar pair inside it.
        assertEquals(2, teams[0].memberCount)
        assertEquals(2, teams[0].members?.size)
        assertEquals(
            "https://book.example.com/media/avatars/contract.png",
            teams[0].members?.get(0)?.avatarUrl,
        )
        assertNull(teams[0].members?.get(1)?.avatarUrl)
        assertEquals(0, teams[0].members?.get(0)?.routingPriority)
        assertEquals(5, teams[0].members?.get(1)?.routingPriority)

        // ⛔ `member_count: 0` WITH `members: null` IS THE FIXTURE'S POINT. The count is what a
        // screen shows; the list being null means "not expanded", and a client that inferred one
        // from the other would be right here and wrong on a list row the fork did not expand.
        assertEquals(0, teams[1].memberCount)
        assertNull(teams[1].members)
    }

    @Test
    fun `the single team read is the same row the list carries`() {
        val single = SchedulingAdminFixtures.data(
            "district-scheduling-team.json",
            SchedulingTeam.serializer(),
        )
        val fromList = SchedulingAdminFixtures.data(
            "district-scheduling-teams.json",
            SchedulingItems.serializer(SchedulingTeam.serializer()),
        ).items[1]

        assertEquals(fromList, single)
        assertEquals("support", single.slug)
        assertNull(single.members)
    }

    @Test
    fun `every team fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-users.json",
            ListSerializer(SchedulingUser.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-user-archive.json",
            SchedulingUserArchived.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-user-upcoming.json",
            SchedulingItems.serializer(SchedulingUpcomingBooking.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-teams.json",
            SchedulingItems.serializer(SchedulingTeam.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-team.json",
            SchedulingTeam.serializer(),
        )
    }
}
