package com.distronode.districtai.ui.scheduling.admin.team

import androidx.annotation.StringRes
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminError
import com.distronode.districtai.core.data.SchedulingAdminFailureCode
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.archiveSchedulerUser
import com.distronode.districtai.core.data.createTeam
import com.distronode.districtai.core.data.deleteTeam
import com.distronode.districtai.core.data.patchTeam
import com.distronode.districtai.core.data.upcomingBookings
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.model.SchedulingTeamMember
import com.distronode.districtai.core.model.SchedulingUpcomingBooking
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.WriteState
import com.distronode.districtai.ui.scheduling.admin.isWorking
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import com.distronode.districtai.ui.scheduling.admin.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The team dialog that is open, if any. */
sealed interface TeamDialog {
    /** Which opening this is, so a late answer cannot land in a dialog opened after it. */
    val key: Long
    val state: WriteState

    /**
     * Create a team ([teamId] null), or rename and delete one.
     *
     * @property nameRejected why the name was refused before sending.
     * @property confirmingDelete the delete question is up.
     */
    data class Editor(
        override val key: Long,
        val teamId: String?,
        val teamName: String,
        val name: String,
        @get:StringRes val nameRejected: Int? = null,
        val confirmingDelete: Boolean = false,
        override val state: WriteState = WriteState.Idle,
    ) : TeamDialog

    /**
     * One team's roster, as the server last answered it.
     *
     * @property priorityDrafts keyed by scheduler user id. ⚠️ Text, not numbers: the field has to
     *   hold "" and "2.5" long enough to refuse them by name.
     * @property removing the member the remove question is about.
     */
    data class Members(
        override val key: Long,
        val team: SchedulingTeam,
        val users: SectionState<List<SchedulingUser>> = SectionState.Loading,
        val pickedUserId: String? = null,
        val priorityDrafts: Map<String, String> = priorityDraftsOf(team),
        val priorityRejected: Set<String> = emptySet(),
        val removing: SchedulingTeamMember? = null,
        override val state: WriteState = WriteState.Idle,
    ) : TeamDialog {
        /** ⚠️ `members` is null on a team with none, so this is "none we were told about". */
        val roster: List<SchedulingTeamMember> get() = team.members.orEmpty()

        /** Live hosts who are not already in this team. */
        val addable: List<SchedulingUser>
            get() {
                val present = roster.map { it.id }.toSet()
                return users.valueOrNull.orEmpty().filter { !it.archived && it.id !in present }
            }
    }

    /**
     * Archive one scheduler account, once its upcoming bookings have been read and there are none.
     *
     * @property confirming the final question is up.
     */
    data class Archive(
        override val key: Long,
        val userId: String,
        val userName: String,
        val upcoming: SectionState<List<SchedulingUpcomingBooking>> = SectionState.Loading,
        val confirming: Boolean = false,
        override val state: WriteState = WriteState.Idle,
    ) : TeamDialog {
        /**
         * ⛔ REQUIRES A SUCCESSFUL READ THAT FOUND NOTHING, not merely an empty list: "we could not
         * look" must not unlock the button.
         */
        val canArchive: Boolean get() = upcoming.valueOrNull?.isEmpty() == true && !state.isWorking
    }
}

/** The routing priorities a roster shows, as text, keyed by user id. */
internal fun priorityDraftsOf(team: SchedulingTeam): Map<String, String> =
    team.members.orEmpty().associate { it.id to it.routingPriority.toString() }

/** The one open team dialog. */
class TeamDialogHost {
    private val _dialog = MutableStateFlow<TeamDialog?>(null)
    val dialog: StateFlow<TeamDialog?> = _dialog.asStateFlow()

    val current: TeamDialog? get() = _dialog.value

    private var opened = 0L

    /** A key no dialog opened before has. */
    fun nextKey(): Long = ++opened

    fun show(dialog: TeamDialog) {
        _dialog.value = dialog
    }

    /** ⛔ Ignored while a write is on its way: the dialog cannot be closed under it. */
    fun close() {
        if (_dialog.value?.state?.isWorking == true) return
        _dialog.value = null
    }

    /** Change the open dialog only if it is still a [D] from the opening [key]. */
    inline fun <reified D : TeamDialog> update(key: Long, change: (D) -> TeamDialog) {
        val open = current as? D ?: return
        if (open.key != key) return
        show(change(open))
    }
}

/**
 * The team screen's writes, ported from district-ios's `SchedulingTeamEditorModel`,
 * `SchedulingTeamMembersModel` and `SchedulingUserArchiveModel`.
 *
 * @param onChanged told after every successful write, so the screen re-reads.
 */
class SchedulingTeamWrites(
    scope: CoroutineScope,
    admin: SchedulingAdminRepository,
    workspaceId: String,
    onChanged: () -> Unit,
) {
    private val host = TeamDialogHost()
    val dialog: StateFlow<TeamDialog?> = host.dialog

    val editor = TeamEditorFlow(host, scope, admin, workspaceId, onChanged)
    val members = TeamMembersFlow(host, scope, admin, workspaceId, onChanged)
    val archive = UserArchiveFlow(host, scope, admin, workspaceId, onChanged)

    /** Close whatever is open. Ignored while a write is on its way. */
    fun close() = host.close()
}

/**
 * Create a team, or rename and delete one.
 *
 * ⛔ THE SLUG IS NEVER SENT, ON EITHER OP. `teams.create` derives one from the name, and a slug on
 * `teams.patch` re-points every public team booking link already handed out; the web sends
 * `{id, name}` and nothing else.
 */
class TeamEditorFlow(
    private val host: TeamDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    fun openCreate() = host.show(TeamDialog.Editor(host.nextKey(), teamId = null, teamName = "", name = ""))

    fun openEdit(team: SchedulingTeam) =
        host.show(TeamDialog.Editor(host.nextKey(), teamId = team.id, teamName = team.name, name = team.name))

    fun editName(value: String) {
        val dialog = host.current as? TeamDialog.Editor ?: return
        if (dialog.state.isWorking) return
        val state = if (dialog.state is WriteState.Failed) WriteState.Idle else dialog.state
        host.show(dialog.copy(name = value, nameRejected = null, state = state))
    }

    /** ⛔ Trimmed, then measured, in that order: a lone space is not a name. */
    fun submit() {
        val dialog = host.current as? TeamDialog.Editor ?: return
        if (dialog.state.isWorking) return
        val name = dialog.name.trim()
        val rejected = when {
            name.isEmpty() -> R.string.scheduling_admin_team_name_required
            name.length > NAME_LIMIT -> R.string.scheduling_admin_team_name_too_long
            else -> null
        }
        if (rejected != null) {
            host.show(dialog.copy(nameRejected = rejected))
            return
        }
        host.show(dialog.copy(state = WriteState.Working))
        scope.launch {
            val teamId = dialog.teamId
            val result = if (teamId == null) {
                admin.createTeam(workspaceId, name)
            } else {
                admin.patchTeam(workspaceId, teamId, name = name)
            }
            val done = if (teamId == null) {
                R.string.scheduling_admin_team_create_done
            } else {
                R.string.scheduling_admin_team_rename_done
            }
            finish(dialog.key, result, done)
        }
    }

    /** Ask before deleting; [ask] false closes the question. */
    fun askDelete(ask: Boolean) {
        val dialog = host.current as? TeamDialog.Editor ?: return
        if (dialog.teamId == null || dialog.state.isWorking) return
        host.show(dialog.copy(confirmingDelete = ask))
    }

    /** ⚠️ `teams.delete` answers a 200 `{ok:true}`, not a 204; it is read and discarded. */
    fun delete() {
        val dialog = host.current as? TeamDialog.Editor ?: return
        val teamId = dialog.teamId ?: return
        if (!dialog.confirmingDelete) return
        host.show(dialog.copy(confirmingDelete = false, state = WriteState.Working))
        scope.launch {
            finish(dialog.key, admin.deleteTeam(workspaceId, teamId), R.string.scheduling_admin_team_delete_done)
        }
    }

    private fun finish(key: Long, result: SchedulingAdminResult<*>, @StringRes done: Int) {
        host.update<TeamDialog.Editor>(key) {
            it.copy(
                state = when (result) {
                    is SchedulingAdminResult.Ok -> WriteState.Done(UiText.Resource(done))
                    is SchedulingAdminResult.Failed -> WriteState.Failed(result.error.toFailureText())
                },
            )
        }
        if (result is SchedulingAdminResult.Ok) onChanged()
    }

    companion object {
        /** The catalog's `z.string().min(1).max(200)`. */
        const val NAME_LIMIT: Int = 200
    }
}

/**
 * Archive one scheduler account.
 *
 * ⛔ BLOCKED UNTIL THE UPCOMING BOOKINGS HAVE BEEN READ AND THERE ARE NONE, which is the fork's
 * design: `users.archive` refuses while the person still hosts anything, so reassign or cancel,
 * then archive. ⚠️ Soft: the account and its links are kept, the person cannot sign in, is skipped
 * in routing, and their event types stop taking bookings.
 */
class UserArchiveFlow(
    private val host: TeamDialogHost,
    private val scope: CoroutineScope,
    private val admin: SchedulingAdminRepository,
    private val workspaceId: String,
    private val onChanged: () -> Unit,
) {
    /** ⛔ Offered only for a live scheduler account, which the screen decides from the row's state. */
    fun open(userId: String, userName: String) {
        host.show(TeamDialog.Archive(host.nextKey(), userId, userName))
        loadUpcoming()
    }

    fun loadUpcoming() {
        val dialog = host.current as? TeamDialog.Archive ?: return
        if (dialog.state.isWorking) return
        host.show(dialog.copy(upcoming = SectionState.Loading))
        scope.launch {
            val upcoming = admin.upcomingBookings(workspaceId, dialog.userId).toSectionState()
            host.update<TeamDialog.Archive>(dialog.key) { it.copy(upcoming = upcoming) }
        }
    }

    /** Ask the final question; [ask] false closes it. ⚠️ Re-checks [TeamDialog.Archive.canArchive]. */
    fun askConfirm(ask: Boolean) {
        val dialog = host.current as? TeamDialog.Archive ?: return
        if (ask && !dialog.canArchive) return
        host.show(dialog.copy(confirming = ask))
    }

    fun archive() {
        val dialog = host.current as? TeamDialog.Archive ?: return
        if (!dialog.confirming || !dialog.canArchive) return
        host.show(dialog.copy(confirming = false, state = WriteState.Working))
        scope.launch {
            val result = admin.archiveSchedulerUser(workspaceId, dialog.userId)
            host.update<TeamDialog.Archive>(dialog.key) {
                it.copy(
                    state = when (result) {
                        is SchedulingAdminResult.Ok -> WriteState.Done(
                            UiText.Resource(R.string.scheduling_admin_team_archive_done, dialog.userName),
                        )
                        is SchedulingAdminResult.Failed -> WriteState.Failed(refusal(result.error))
                    },
                )
            }
            if (result is SchedulingAdminResult.Ok) onChanged()
        }
    }

    companion object {
        /**
         * ⛔ THE ONE PLACE THE ENUMERATED SENTENCE BEATS THE SHARED GENERIC. The fork's refusals here
         * (still hosting, not the owner, the owner, already archived or gone) all arrive as an
         * unnamed refusal, so that sentence names all of them; ⚠️ a request we sent badly or a
         * response we could not read is not a refusal, and every other code keeps the shared mapping.
         */
        internal fun refusal(error: SchedulingAdminError): FailureText = when (error) {
            SchedulingAdminError.Unknown, SchedulingAdminError.Refused(SchedulingAdminFailureCode.UNKNOWN) ->
                FailureText(UiText.Resource(R.string.scheduling_admin_team_archive_refused), retryable = false)
            else -> error.toFailureText()
        }
    }
}
