package com.distronode.districtai.ui.scheduling.admin.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.map
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.data.schedulerUsers
import com.distronode.districtai.core.data.teams
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.UTC
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.displayTimezone
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import com.distronode.districtai.ui.scheduling.admin.valueOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @property members District's members joined with the scheduler's users, archived ones included.
 * @property districtMembersFailed the District roster could not be read. ⛔ Every scheduler user then
 *   has no District role, which would read as "removed from the workspace"; the screen says the
 *   roles could not be read instead, and draws no stranded-host warning.
 * @property canManage whether the write controls are drawn: `teams.*`, `teams.members.*` and
 *   `users.archive` are `client`-level.
 * @property timezone the member's own zone, for the times of a host's upcoming bookings.
 */
data class SchedulingTeamUiState(
    val members: SectionState<List<SchedulingMemberRow>> = SectionState.Loading,
    val districtMembersFailed: Boolean = false,
    val teams: SectionState<List<SchedulingTeam>> = SectionState.Loading,
    val canManage: Boolean = false,
    val timezone: String = UTC,
) {
    /** People who have left and still hold a live scheduler account; none when the roster failed. */
    val stranded: List<SchedulingMemberRow>
        get() = if (districtMembersFailed) {
            emptyList()
        } else {
            SchedulingTeamFormat.strandedHosts(members.valueOrNull.orEmpty())
        }
}

/** Everything the team screen can ask for. */
interface SchedulingTeamActions {
    fun back()
    fun refresh()
    fun archive(userId: String, name: String)
    fun createTeam()
    fun editTeam(team: SchedulingTeam)
    fun teamMembers(team: SchedulingTeam)
}

/**
 * The hosts who can be booked and the teams they route through, ported from district-ios's
 * `SchedulingTeamModel`.
 *
 * ⛔ `includeArchived` IS TRUE, the web's choice: an archived scheduler account is the evidence that
 * somebody was dealt with, and hiding it would make a resolved departure look unresolved.
 */
class SchedulingTeamViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val members: MembersRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingTeamUiState())
    val state: StateFlow<SchedulingTeamUiState> = _state.asStateFlow()

    val writes = SchedulingTeamWrites(viewModelScope, admin, workspaceId, onChanged = ::load)

    private var reading: Job? = null

    init {
        load()
    }

    /** Read the people and the teams together; each fails on its own. */
    fun load() {
        reading?.cancel()
        _state.value = _state.value.copy(members = SectionState.Loading, teams = SectionState.Loading)
        reading = viewModelScope.launch {
            val profile = async { admin.me(workspaceId).valueOrNull() }
            val users = async { admin.schedulerUsers(workspaceId, includeArchived = true) }
            val district = async { members.members(workspaceId) }
            val teams = async { admin.teams(workspaceId).toSectionState() }
            val access = scheduling.access(workspaceId)
            // ⚠️ A FAILED ROSTER DEGRADES TO AN EMPTY ONE and the join still runs, so the hosts are
            // still listed; the flag is what keeps the screen from calling them all departed.
            val roster = when (val read = district.await()) {
                is ApiResult.Success -> read.value
                is ApiResult.Failure -> null
            }
            val rows = users.await().map { SchedulingTeamFormat.joinMembers(roster.orEmpty(), it) }
            _state.value = SchedulingTeamUiState(
                members = rows.toSectionState(),
                districtMembersFailed = roster == null,
                teams = teams.await(),
                canManage = access.canManage,
                timezone = profile.await()?.displayTimezone ?: UTC,
            )
        }
    }

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            members: MembersRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingTeamViewModel(admin, scheduling, members, workspaceId) }
        }
    }
}
