package com.distronode.districtai.ui.scheduling.admin.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The join across District's roster and the scheduler's users, after district-core-swift's tests. */
class SchedulingTeamFormatTest {

    @Test
    fun `District's order first, case-insensitive, duplicates skipped, then scheduler-only rows`() {
        val rows = SchedulingTeamFormat.joinMembers(
            district = listOf(
                districtMember(" Ana@Example.com ", "agency"),
                districtMember("bo@example.com", "viewer"),
                districtMember("ANA@example.com", "client"),
                districtMember("dee@example.com", null),
            ),
            scheduler = listOf(
                teamUser("u1", "ana@example.com", "   "),
                teamUser("u3", "cy@example.com", "Cy", archived = true),
                teamUser("u4", "eve@example.com"),
                teamUser("u5", "@example.com"),
                teamUser("u6", "Ana@example.com", "Ana Lima"),
                teamUser("u7", "bo@example.com", "Bo", archived = true),
            ),
        )
        assertEquals(
            listOf(
                "ana@example.com",
                "bo@example.com",
                "dee@example.com",
                "cy@example.com",
                "eve@example.com",
                "@example.com",
            ),
            rows.map { it.key },
        )
        // The LAST scheduler user on a duplicated address wins, and a blank name falls back.
        assertEquals("Ana Lima", rows[0].name)
        assertEquals("u6", rows[0].schedulerUserId)
        assertEquals(" Ana@Example.com ", rows[0].email)
        assertEquals(
            listOf(
                SchedulingMemberState.HOST,
                SchedulingMemberState.ARCHIVED,
                SchedulingMemberState.ABSENT,
                SchedulingMemberState.ARCHIVED,
                SchedulingMemberState.HOST,
                SchedulingMemberState.HOST,
            ),
            rows.map { it.state },
        )
        assertEquals("dee", rows[2].name)
        assertEquals("eve", rows[4].name)
        assertEquals("@example.com", rows[5].name)
        assertEquals(listOf("u6", null, null, null, "u4", "u5"), rows.map { it.archivableUserId })
        val stranded = SchedulingTeamFormat.strandedHosts(rows).map { it.key }
        assertEquals(listOf("eve@example.com", "@example.com"), stranded)
        assertNull(rows[3].districtRole)
    }

    @Test
    fun `a team's count trusts member_count, then the members it carries, then zero`() {
        val one = listOf(teamMember("a", "A"))
        assertEquals(0, SchedulingTeamFormat.teamMemberCount(schedulingTeam(memberCount = 0, members = one)))
        assertEquals(1, SchedulingTeamFormat.teamMemberCount(schedulingTeam(memberCount = null, members = one)))
        assertEquals(0, SchedulingTeamFormat.teamMemberCount(schedulingTeam(members = null)))
        assertEquals("ana", SchedulingTeamFormat.nameFromEmail(" ana@example.com "))
        assertEquals("ana", SchedulingTeamFormat.nameFromEmail("ana"))
    }
}
