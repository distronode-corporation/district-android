package com.distronode.districtai.ui.scheduling.admin.team

import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminError
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingUpcomingBooking
import com.distronode.districtai.core.model.SchedulingUserArchived
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.resourceIdOrNull
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Create, rename and delete a team; add, reprioritise and remove its hosts; archive a scheduler
 * account. What each sends, what each refuses first, and the one-request rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SchedulingTeamWritesTest {

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private var changed = 0
    private val writes = SchedulingTeamWrites(scope, rig.adminRepository, "ws") { changed++ }

    private val editor get() = writes.dialog.value as TeamDialog.Editor
    private val roster get() = writes.dialog.value as TeamDialog.Members
    private val archive get() = writes.dialog.value as TeamDialog.Archive

    private val ana = teamMember("u1", "Ana", priority = 1)
    private val nothingUpcoming = SchedulingItems(emptyList<SchedulingUpcomingBooking>())
    private val team = schedulingTeam(members = listOf(ana))

    private fun done(id: Int) = WriteState.Done(UiText.Resource(id))

    @Test
    fun `nothing open ignores every action`() {
        writes.editor.editName("x")
        writes.editor.submit()
        writes.editor.askDelete(true)
        writes.editor.delete()
        writes.members.loadUsers()
        writes.members.pick("u1")
        writes.members.addMember()
        writes.members.savePriority("u1")
        writes.members.removeMember()
        writes.archive.loadUpcoming()
        writes.archive.askConfirm(true)
        writes.archive.archive()
        writes.close()
        assertNull(writes.dialog.value)
        assertTrue(rig.admin.calls.isEmpty())
    }

    @Test
    fun `a new team needs a trimmed name of at most 200 characters and sends no slug`() {
        rig.admin.value(SchedulingAdminOp.TEAMS_CREATE, schedulingTeam())
        writes.editor.openCreate()
        writes.editor.editName("   ")
        writes.editor.submit()
        assertEquals(R.string.scheduling_admin_team_name_required, editor.nameRejected)
        writes.editor.editName("x".repeat(TeamEditorFlow.NAME_LIMIT + 1))
        assertNull(editor.nameRejected)
        writes.editor.submit()
        assertEquals(R.string.scheduling_admin_team_name_too_long, editor.nameRejected)
        writes.editor.askDelete(true)
        assertFalse(editor.confirmingDelete)
        writes.editor.delete()
        writes.editor.editName("  Front desk ")
        writes.editor.submit()
        writes.editor.submit()
        writes.editor.editName("ignored")
        writes.close()
        scope.advanceUntilIdle()

        val params = rig.admin.callsTo(SchedulingAdminOp.TEAMS_CREATE).single().params
        assertEquals(JsonPrimitive("Front desk"), params["name"])
        assertNull(params["slug"])
        assertEquals(done(R.string.scheduling_admin_team_create_done), editor.state)
        assertEquals(1, changed)
    }

    @Test
    fun `a rename patches only the name, and a failure clears when the name is edited`() {
        rig.admin.fail(SchedulingAdminOp.TEAMS_PATCH, ApiResult.Forbidden("Forbidden"))
        writes.editor.openEdit(team)
        assertEquals("Front desk", editor.name)
        writes.editor.editName("Reception")
        writes.editor.submit()
        scope.advanceUntilIdle()
        assertEquals(
            R.string.scheduling_admin_failure_forbidden,
            (editor.state as WriteState.Failed).failure.message.resourceIdOrNull,
        )
        writes.editor.editName("Reception!")
        assertEquals(WriteState.Idle, editor.state)

        rig.admin.clear(SchedulingAdminOp.TEAMS_PATCH)
        rig.admin.value(SchedulingAdminOp.TEAMS_PATCH, schedulingTeam(name = "Reception!"))
        writes.editor.submit()
        scope.advanceUntilIdle()
        val params = rig.admin.callsTo(SchedulingAdminOp.TEAMS_PATCH).last().params
        assertEquals(setOf("id", "name"), params.keys)
        assertEquals(done(R.string.scheduling_admin_team_rename_done), editor.state)
        writes.editor.editName("again")
        assertTrue(editor.state is WriteState.Done)
    }

    @Test
    fun `a delete asks first and sends once`() {
        rig.admin.value(SchedulingAdminOp.TEAMS_DELETE, SchedulingNoContent(ok = true))
        writes.editor.openEdit(team)
        writes.editor.delete()
        assertTrue(rig.admin.calls.isEmpty())
        writes.editor.askDelete(true)
        assertTrue(editor.confirmingDelete)
        writes.editor.askDelete(false)
        assertFalse(editor.confirmingDelete)
        writes.editor.askDelete(true)
        writes.editor.delete()
        writes.editor.delete()
        writes.editor.askDelete(true)
        assertFalse(editor.confirmingDelete)
        scope.advanceUntilIdle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.TEAMS_DELETE).size)
        assertEquals(done(R.string.scheduling_admin_team_delete_done), editor.state)
        assertEquals(1, changed)
    }

    @Test
    fun `the roster offers live hosts not already in it, adds with no priority, and adopts the answer`() {
        rig.admin.value(
            SchedulingAdminOp.USERS_LIST,
            listOf(
                teamUser("u1", "a@x.example"),
                teamUser("u2", "b@x.example"),
                teamUser("u3", "c@x.example", archived = true),
            ),
        )
        val bo = teamMember("u2", "Bo", priority = 5)
        rig.admin.value(SchedulingAdminOp.TEAMS_MEMBERS_ADD, schedulingTeam(members = listOf(ana, bo)))
        writes.members.open(team)
        assertEquals(mapOf("u1" to "1"), roster.priorityDrafts)
        assertEquals(emptyList<Any>(), roster.addable)
        scope.advanceUntilIdle()
        assertEquals(listOf("u2"), roster.addable.map { it.id })
        writes.members.addMember()
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.TEAMS_MEMBERS_ADD).isEmpty())
        writes.members.pick("u2")
        writes.members.addMember()
        writes.members.addMember()
        writes.members.pick("ignored while working")
        scope.advanceUntilIdle()

        val params = rig.admin.callsTo(SchedulingAdminOp.TEAMS_MEMBERS_ADD).single().params
        assertEquals(JsonPrimitive("u2"), params["user_id"])
        assertNull(params["routing_priority"])
        assertEquals(listOf("u1", "u2"), roster.roster.map { it.id })
        assertEquals(mapOf("u1" to "1", "u2" to "5"), roster.priorityDrafts)
        assertNull(roster.pickedUserId)
        assertEquals(emptyList<Any>(), roster.addable)
        assertEquals(done(R.string.scheduling_admin_team_add_done), roster.state)
        assertEquals(1, changed)
    }

    @Test
    fun `a priority must be a whole number, zero or more, and is sent under the path key`() {
        val reprioritised = schedulingTeam(members = listOf(ana.copy(routingPriority = 3)))
        rig.admin.value(SchedulingAdminOp.TEAMS_MEMBERS_PATCH, reprioritised)
        writes.members.open(schedulingTeam(members = null))
        assertEquals(emptyList<Any>(), roster.roster)
        writes.members.open(team)
        listOf("", "2.5", "-1").forEach {
            writes.members.editPriority("u1", it)
            assertFalse("u1" in roster.priorityRejected)
            writes.members.savePriority("u1")
            assertTrue(it, "u1" in roster.priorityRejected)
        }
        // A host with no draft at all is refused the same way.
        writes.members.savePriority("nobody")
        assertTrue("nobody" in roster.priorityRejected)
        writes.members.editPriority("u1", " 3 ")
        writes.members.savePriority("u1")
        scope.advanceUntilIdle()
        val params = rig.admin.callsTo(SchedulingAdminOp.TEAMS_MEMBERS_PATCH).single().params
        assertEquals(JsonPrimitive("u1"), params["userId"])
        assertEquals(JsonPrimitive(3), params["routing_priority"])
        assertEquals(mapOf("u1" to "3"), roster.priorityDrafts)
        assertEquals(emptySet<String>(), roster.priorityRejected)
    }

    @Test
    fun `a remove asks first, then reads the team again because the remove answers nothing`() {
        rig.admin.value(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.TEAMS_GET, schedulingTeam(members = emptyList()))
        writes.members.open(team)
        writes.members.removeMember()
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE).isEmpty())
        writes.members.askRemove(ana)
        assertEquals(ana, roster.removing)
        writes.members.askRemove(null)
        writes.members.askRemove(ana)
        writes.members.removeMember()
        assertNull(roster.removing)
        scope.advanceUntilIdle()
        val removed = rig.admin.callsTo(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE).single().params
        assertEquals(JsonPrimitive("u1"), removed["userId"])
        assertEquals(emptyList<Any>(), roster.roster)
        assertEquals(done(R.string.scheduling_admin_team_remove_done), roster.state)
    }

    @Test
    fun `a failed remove, and a re-read that fails after a remove that landed, keep the roster`() {
        rig.admin.fail(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE, ApiResult.NetworkFailure(IOException("x")))
        writes.members.open(team)
        writes.members.askRemove(ana)
        writes.members.removeMember()
        scope.advanceUntilIdle()
        assertTrue(roster.state is WriteState.Failed)
        assertTrue(roster.users is SectionState.Failed)
        writes.members.pick("u9")
        assertEquals(WriteState.Idle, roster.state)

        rig.admin.clear(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE)
        rig.admin.value(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE, SchedulingNoContent(ok = true))
        writes.members.askRemove(ana)
        writes.members.removeMember()
        scope.advanceUntilIdle()
        assertTrue(roster.state is WriteState.Failed)
        assertEquals(listOf(ana), roster.roster)
        assertEquals(0, changed)
    }

    @Test
    fun `a late roster answer lands only in the dialog that asked`() {
        writes.members.open(team)
        writes.editor.openCreate()
        scope.advanceUntilIdle()
        assertTrue(writes.dialog.value is TeamDialog.Editor)
        writes.members.open(team)
        writes.members.open(team)
        scope.advanceUntilIdle()
        assertTrue(roster.users is SectionState.Failed)
    }

    @Test
    fun `an archive waits for a read that found nothing, asks, and names the person`() {
        rig.admin.answer(
            SchedulingAdminOp.USERS_UPCOMING_BOOKINGS,
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(upcomingBooking()))),
            FakeSchedulingAnswer.Value(nothingUpcoming),
        )
        rig.admin.value(SchedulingAdminOp.USERS_ARCHIVE, SchedulingUserArchived(ok = true))
        writes.archive.open("u1", "Ana")
        assertFalse(archive.canArchive)
        scope.advanceUntilIdle()
        assertEquals(1, archive.upcoming.let { (it as SectionState.Ready).value.size })
        assertFalse(archive.canArchive)
        writes.archive.askConfirm(true)
        assertFalse(archive.confirming)
        writes.archive.archive()
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.USERS_ARCHIVE).isEmpty())

        writes.archive.loadUpcoming()
        scope.advanceUntilIdle()
        assertTrue(archive.canArchive)
        writes.archive.askConfirm(true)
        assertTrue(archive.confirming)
        writes.archive.askConfirm(false)
        assertFalse(archive.confirming)
        writes.archive.askConfirm(true)
        // A re-read while the question is up takes the button away until it answers.
        writes.archive.loadUpcoming()
        writes.archive.archive()
        assertTrue(rig.admin.callsTo(SchedulingAdminOp.USERS_ARCHIVE).isEmpty())
        scope.advanceUntilIdle()
        writes.archive.archive()
        scope.advanceUntilIdle()

        val archived = rig.admin.callsTo(SchedulingAdminOp.USERS_ARCHIVE).single().params
        assertEquals(JsonPrimitive("u1"), archived["id"])
        assertEquals(
            UiText.Resource(R.string.scheduling_admin_team_archive_done, "Ana"),
            (archive.state as WriteState.Done).message,
        )
        assertEquals(1, changed)
    }

    @Test
    fun `an archive the scheduler refuses names the reasons it could be, and cannot be re-read mid-flight`() {
        rig.admin.value(SchedulingAdminOp.USERS_UPCOMING_BOOKINGS, nothingUpcoming)
        rig.admin.refuse(SchedulingAdminOp.USERS_ARCHIVE, "conflict")
        writes.archive.open("u1", "Ana")
        scope.advanceUntilIdle()
        writes.archive.askConfirm(true)
        writes.archive.archive()
        assertFalse(archive.canArchive)
        writes.archive.loadUpcoming()
        writes.close()
        assertTrue(writes.dialog.value is TeamDialog.Archive)
        scope.advanceUntilIdle()
        val failed = archive.state as WriteState.Failed
        assertEquals(R.string.scheduling_admin_team_archive_refused, failed.failure.message.resourceIdOrNull)
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.USERS_UPCOMING_BOOKINGS).size)

        fun id(error: SchedulingAdminError) = UserArchiveFlow.refusal(error).message.resourceIdOrNull
        assertEquals(R.string.scheduling_admin_team_archive_refused, id(SchedulingAdminError.Unknown))
        assertEquals(R.string.scheduling_admin_failure_unknown, id(SchedulingAdminError.InvalidParams))
        assertEquals(R.string.scheduling_admin_failure_stale_build, id(SchedulingAdminError.Decoding))
        assertEquals(
            R.string.scheduling_admin_failure_unavailable,
            id(SchedulingAdminError.Refused(SchedulingAdminFailureCode.UNAVAILABLE)),
        )
        assertEquals(R.string.scheduling_admin_failure_forbidden, id(SchedulingAdminError.Forbidden))
        assertEquals(R.string.scheduling_admin_failure_not_ready, id(SchedulingAdminError.NotReady))
        assertEquals(R.string.scheduling_admin_failure_unavailable, id(SchedulingAdminError.Unavailable))
        assertEquals(R.string.scheduling_admin_failure_offline, id(SchedulingAdminError.Transport))
    }
}
