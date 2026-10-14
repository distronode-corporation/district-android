package com.distronode.districtai.ui.scheduling.admin.team

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.addTeamMember
import com.distronode.districtai.core.data.removeTeamMember
import com.distronode.districtai.core.data.schedulerUsers
import com.distronode.districtai.core.data.setTeamMemberPriority
import com.distronode.districtai.core.data.team
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.model.SchedulingTeamMember
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One team's roster: add a host, set a routing priority, take somebody out.
 *
 * ⛔ THE ROSTER IS REPLACED, NEVER PATCHED LOCALLY. `teams.members.add` and `.patch` answer the
 * whole team, which is adopted as it came; `.remove` answers a bare `{ok}`, so the team is read
 * again. A locally sorted rotation is a rotation the fork does not agree with.
 *
 * ⛔ AND THE ADD SENDS NO PRIORITY: omitting it lets the fork choose, where a literal 0 would put
 * every new member at the front of every rotation. The web omits it for the same reason.
 */
class TeamMembersFlow(
    private val host: TeamDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    /**
     * ⛔ Opened only from a team the screen read: an editor over a failed read would reorder a rota
     * it never saw.
     */
    fun open(team: SchedulingTeam) {
        host.show(TeamDialog.Members(host.nextKey(), team))
        loadUsers()
    }

    fun loadUsers() {
        val dialog = host.current as? TeamDialog.Members ?: return
        host.show(dialog.copy(users = SectionState.Loading))
        scope.launch {
            val users = admin.schedulerUsers(workspaceId).toSectionState()
            host.update<TeamDialog.Members>(dialog.key) { it.copy(users = users) }
        }
    }

    fun pick(userId: String) = change { it.copy(pickedUserId = userId) }

    fun editPriority(userId: String, value: String) = change {
        it.copy(
            priorityDrafts = it.priorityDrafts + (userId to value),
            priorityRejected = it.priorityRejected - userId,
        )
    }

    /** Ask before removing [member]; null closes the question. */
    fun askRemove(member: SchedulingTeamMember?) = change { it.copy(removing = member) }

    private fun change(edit: (TeamDialog.Members) -> TeamDialog.Members) {
        val dialog = host.current as? TeamDialog.Members ?: return
        if (dialog.state.isWorking) return
        val state = if (dialog.state is WriteState.Failed) WriteState.Idle else dialog.state
        host.show(edit(dialog).copy(state = state))
    }

    fun addMember() {
        val dialog = host.current as? TeamDialog.Members ?: return
        val userId = dialog.pickedUserId ?: return
        send(dialog, R.string.scheduling_admin_team_add_done) {
            admin.addTeamMember(workspaceId, dialog.team.id, userId)
        }
    }

    /** ⛔ Refuses an empty field and a decimal rather than coercing either, the web's `parsePriority`. */
    fun savePriority(userId: String) {
        val dialog = host.current as? TeamDialog.Members ?: return
        val priority = dialog.priorityDrafts[userId].orEmpty().trim().toIntOrNull()
        if (priority == null || priority < 0) {
            change { it.copy(priorityRejected = it.priorityRejected + userId) }
            return
        }
        send(dialog, R.string.scheduling_admin_team_priority_done) {
            admin.setTeamMemberPriority(workspaceId, dialog.team.id, userId, priority)
        }
    }

    /**
     * ⚠️ A RE-READ THAT FAILS AFTER A REMOVAL THAT LANDED keeps the roster on screen and reports the
     * read's failure: the change is stored, and blanking the list would lose a usable roster.
     */
    fun removeMember() {
        val dialog = host.current as? TeamDialog.Members ?: return
        val member = dialog.removing ?: return
        send(dialog.copy(removing = null), R.string.scheduling_admin_team_remove_done) {
            when (val removed = admin.removeTeamMember(workspaceId, dialog.team.id, member.id)) {
                is SchedulingAdminResult.Ok -> admin.team(workspaceId, dialog.team.id)
                is SchedulingAdminResult.Failed -> removed
            }
        }
    }

    /**
     * One write that answers the team. ⚠️ The drafts are reseeded from the server's roster on every
     * adoption: a field still holding what was typed beside a value the fork normalised is two
     * answers to one question.
     */
    private fun send(
        dialog: TeamDialog.Members,
        @StringRes done: Int,
        write: suspend () -> SchedulingAdminResult<SchedulingTeam>,
    ) {
        if (dialog.state.isWorking) return
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val result = write()
            host.update<TeamDialog.Members>(dialog.key) {
                when (result) {
                    is SchedulingAdminResult.Ok -> it.copy(
                        team = result.value,
                        pickedUserId = null,
                        priorityDrafts = priorityDraftsOf(result.value),
                        priorityRejected = emptySet(),
                        state = WriteState.Done(UiText.Resource(done)),
                    )
                    is SchedulingAdminResult.Failed ->
                        it.copy(state = WriteState.Failed(result.error.toFailureText()))
                }
            }
            if (result is SchedulingAdminResult.Ok) onChanged()
        }
    }
}
