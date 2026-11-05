package com.distronode.districtai.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.ConversationAssignmentRepository
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A thread's owner, and the picker that changes it.
 *
 * @param loaded the owner read answered. ⚠️ Until then (and after a failed read) nothing is drawn:
 *   "Unassigned" shown for a thread whose owner could not be read would be a claim, not a fact.
 * @param members who the picker offers besides "Unassigned": the workspace's members who are not
 *   viewers, plus the current owner when they are not among them (a viewer who already owns a
 *   thread keeps showing until it is changed).
 * @param assignments how many assignments this screen has made, so the Inbox behind it knows to
 *   read its list again when the operator goes back.
 */
data class ThreadOwnerState(
    val loaded: Boolean = false,
    val owner: String? = null,
    val canAssign: Boolean = false,
    val members: List<String> = emptyList(),
    /** ⛔ An assignment is on its way: the picker is disabled, so a second pick cannot race it. */
    val assigning: Boolean = false,
    val failure: FailureText? = null,
    val assignments: Int = 0,
)

/**
 * The open thread's owner (app parity 3).
 *
 * ⛔ ONLY `agency` AND `client` MAY ASSIGN, and only they read the members: a viewer sees the owner
 * and nothing else, matching the route, which refuses their write.
 *
 * ⛔ NOBODY CAN BE GIVEN A THREAD WHILE A VIEWER (Sean, 2026-11-04): the picker leaves viewers out,
 * as the web picker does, and the route answers `assignee_is_viewer` if one is sent anyway.
 *
 * ⚠️ A REFUSAL KEEPS THE OLD OWNER and shows the server's sentence: nothing changed server-side.
 */
class ThreadOwnerViewModel(
    private val assignments: ConversationAssignmentRepository,
    private val membersRepository: MembersRepository,
    private val workspaceId: String,
    private val threadKey: String,
    role: WorkspaceRole?,
) : ViewModel() {

    private val _state = MutableStateFlow(ThreadOwnerState(canAssign = role.allowsMutation()))
    val state: StateFlow<ThreadOwnerState> = _state.asStateFlow()

    /** The assignable members as read, before the current owner is folded in. */
    private var roster: List<String> = emptyList()

    init {
        viewModelScope.launch {
            val result = assignments.owner(workspaceId, threadKey)
            if (result is ApiResult.Success) {
                _state.value = _state.value.copy(loaded = true, owner = result.value).withMembers()
            }
        }
        if (_state.value.canAssign) loadMembers()
    }

    /**
     * Give the thread to [email], or to nobody with null.
     *
     * ⛔ ONE AT A TIME, and a pick of the current owner sends nothing: each assignment pushes the new
     * owner's phone.
     */
    fun assign(email: String?) {
        val current = _state.value
        if (!current.canAssign || !current.loaded) return
        if (current.assigning || email == current.owner) return
        _state.value = current.copy(assigning = true, failure = null)
        viewModelScope.launch {
            val result = assignments.assign(workspaceId, threadKey, email)
            val latest = _state.value
            _state.value = when (result) {
                is ApiResult.Success -> latest.copy(
                    owner = result.value,
                    assigning = false,
                    assignments = latest.assignments + 1,
                ).withMembers()
                is ApiResult.Failure -> latest.copy(assigning = false, failure = result.toFailureText())
            }
        }
    }

    fun dismissFailure() {
        _state.value = _state.value.copy(failure = null)
    }

    /**
     * ⚠️ FAIL-SOFT: unreadable members leave the picker with "Unassigned" and the current owner,
     * which is still a working control.
     */
    private fun loadMembers() {
        viewModelScope.launch {
            val result = membersRepository.members(workspaceId)
            if (result is ApiResult.Success) {
                roster = assignableMembers(result.value)
                _state.value = _state.value.withMembers()
            }
        }
    }

    private fun ThreadOwnerState.withMembers(): ThreadOwnerState =
        copy(members = (roster + listOfNotNull(owner)).distinct())

    class Factory(
        private val assignments: ConversationAssignmentRepository,
        private val members: MembersRepository,
        private val workspaceId: String,
        private val threadKey: String,
        private val role: WorkspaceRole?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ThreadOwnerViewModel(assignments, members, workspaceId, threadKey, role) as T
        }
    }
}

/**
 * The members a thread may be given to: everyone but a viewer, as the web picker filters
 * (`useThreadAssignments`, which compares the role case-insensitively and keeps a member whose
 * role is absent, as the server defaults that member to client).
 */
internal fun assignableMembers(members: List<WorkspaceMember>): List<String> =
    members
        .filterNot { it.role?.lowercase() == VIEWER_ROLE }
        .map { it.email }
        .filter { it.isNotBlank() }

private const val VIEWER_ROLE = "viewer"
