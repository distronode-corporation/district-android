package com.distronode.districtai.ui.scheduling.admin.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictListRow
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingCard
import com.distronode.districtai.ui.scheduling.admin.SchedulingFailureLine
import com.distronode.districtai.ui.scheduling.admin.SchedulingHint
import com.distronode.districtai.ui.scheduling.admin.SchedulingReadOnlyRow
import com.distronode.districtai.ui.scheduling.admin.SchedulingSection
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionFailure
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionLoading
import com.distronode.districtai.ui.scheduling.admin.SchedulingSectionScaffold
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusBadge
import com.distronode.districtai.ui.scheduling.admin.SectionState

/** The team section, wired to its ViewModel. */
@Composable
fun SchedulingTeamRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingTeamViewModel = viewModel(
        key = "scheduling-team-${deps.workspaceId}",
        factory = SchedulingTeamViewModel.factory(deps.admin, deps.scheduling, deps.members, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dialog by viewModel.writes.dialog.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingTeamScreen(state, TeamRouteActions(viewModel, deps))
    SchedulingTeamDialogs(dialog, viewModel.writes, state.timezone)
}

/** The screen's requests, sent to the ViewModel. */
private class TeamRouteActions(
    private val viewModel: SchedulingTeamViewModel,
    private val deps: SchedulingAdminDeps,
) : SchedulingTeamActions {
    override fun back() = deps.onBack()
    override fun refresh() = viewModel.load()
    override fun archive(userId: String, name: String) = viewModel.writes.archive.open(userId, name)
    override fun createTeam() = viewModel.writes.editor.openCreate()
    override fun editTeam(team: SchedulingTeam) = viewModel.writes.editor.openEdit(team)
    override fun teamMembers(team: SchedulingTeam) = viewModel.writes.members.open(team)
}

/**
 * The hosts who can be booked and the teams they route through, ported from district-ios's
 * `SchedulingTeamView`.
 *
 * ⛔ EVERY WRITE CONTROL IS ABSENT, NOT DISABLED, FOR A MEMBER WHO MAY NOT MANAGE: `teams.*`,
 * `teams.members.*` and `users.archive` are `client`-level.
 */
@Composable
fun SchedulingTeamScreen(state: SchedulingTeamUiState, actions: SchedulingTeamActions) {
    SchedulingSectionScaffold(
        title = stringResource(SchedulingSection.TEAM.title),
        rootTag = SCHEDULING_TEAM_ROOT_TAG,
        onBack = @DontMemoize { actions.back() },
        onRefresh = @DontMemoize { actions.refresh() },
    ) {
        if (state.stranded.isNotEmpty()) Stranded(state)
        MembersCard(state, actions)
        TeamsCard(state, actions)
    }
}

/** ⚠️ The single most actionable thing this screen says: somebody left and still holds bookings. */
@Composable
private fun Stranded(state: SchedulingTeamUiState) {
    Column(modifier = Modifier.testTag(SCHEDULING_TEAM_STRANDED_TAG)) {
        Eyebrow(stringResource(R.string.scheduling_admin_team_stranded_eyebrow))
        Text(
            text = schedulingStrandedNotice(state.stranded),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.destructive,
        )
    }
}

@Composable
private fun MembersCard(state: SchedulingTeamUiState, actions: SchedulingTeamActions) {
    SchedulingCard(stringResource(R.string.scheduling_admin_team_members_eyebrow)) {
        if (state.districtMembersFailed) {
            Text(
                text = stringResource(R.string.scheduling_admin_team_members_degraded),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.destructive,
                modifier = Modifier.testTag(SCHEDULING_TEAM_DEGRADED_TAG),
            )
        }
        when (val members = state.members) {
            is SectionState.Failed -> SchedulingSectionFailure(
                failure = members.failure,
                onRetry = @DontMemoize { actions.refresh() },
                tag = SCHEDULING_TEAM_MEMBERS_FAILURE_TAG,
            )
            is SectionState.Ready -> if (members.value.isEmpty()) {
                SchedulingHint(stringResource(R.string.scheduling_admin_team_members_empty))
            } else {
                members.value.forEach { MemberRow(it, state, actions) }
            }
            // ⚠️ `else` IS LOADING: an exhaustive sealed `when` here leaves a branch no test reaches.
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_TEAM_MEMBERS_LOADING_TAG)
        }
    }
}

/**
 * ⛔ THE ROLE IS LEFT OFF WHEN THE ROSTER COULD NOT BE READ: "Removed from the workspace" beside
 * every host would be a false accusation drawn from an absent read. ⛔ Archive is offered only for a
 * live scheduler account; nobody else has anything to archive.
 */
@Composable
private fun MemberRow(row: SchedulingMemberRow, state: SchedulingTeamUiState, actions: SchedulingTeamActions) {
    val status = schedulingMemberStateLabel(row.state)
    DistrictListRow(
        title = row.name,
        subtitle = if (state.districtMembersFailed) {
            row.email
        } else {
            stringResource(
                R.string.scheduling_admin_team_member_subtitle,
                row.email,
                schedulingDistrictRoleLabel(row.districtRole),
            )
        },
        trailing = { SchedulingStatusBadge(status.label, status.kind) },
        modifier = Modifier.testTag("$SCHEDULING_TEAM_MEMBER_TAG-${row.key}"),
    )
    val userId = row.archivableUserId
    if (state.canManage && userId != null) {
        DistrictButton(
            text = stringResource(R.string.scheduling_admin_team_archive),
            onClick = @DontMemoize { actions.archive(userId, row.name) },
            variant = ButtonVariant.Ghost,
            size = ButtonSize.Sm,
            modifier = Modifier
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .testTag("$SCHEDULING_TEAM_ARCHIVE_TAG-${row.key}"),
        )
    }
}

/**
 * ⛔ THE ROW CONTROLS ARE DRAWN ONLY FROM A READ THAT WORKED: the member editor rewrites a routing
 * order, and one built over a failed read would reorder a rota it never saw.
 */
@Composable
private fun TeamsCard(state: SchedulingTeamUiState, actions: SchedulingTeamActions) {
    SchedulingCard(stringResource(R.string.scheduling_admin_team_teams_eyebrow)) {
        SchedulingHint(stringResource(R.string.scheduling_admin_team_teams_subtitle))
        when (val teams = state.teams) {
            is SectionState.Failed -> SchedulingFailureLine(teams.failure)
            is SectionState.Ready -> {
                if (teams.value.isEmpty()) SchedulingHint(stringResource(R.string.scheduling_admin_team_teams_empty))
                teams.value.forEach { TeamRow(it, state.canManage, actions) }
                if (state.canManage) {
                    DistrictButton(
                        text = stringResource(R.string.scheduling_admin_team_create),
                        onClick = @DontMemoize { actions.createTeam() },
                        modifier = Modifier.testTag(SCHEDULING_TEAM_CREATE_TAG),
                    )
                }
            }
            // ⚠️ `else` IS LOADING: an exhaustive sealed `when` here leaves a branch no test reaches.
            SectionState.Loading -> SchedulingSectionLoading(SCHEDULING_TEAM_TEAMS_LOADING_TAG, rows = 1)
        }
    }
}

@Composable
private fun TeamRow(team: SchedulingTeam, canManage: Boolean, actions: SchedulingTeamActions) {
    val count = SchedulingTeamFormat.teamMemberCount(team)
    SchedulingReadOnlyRow(
        label = team.name,
        value = pluralStringResource(R.plurals.scheduling_admin_team_member_count, count, count),
    )
    if (canManage) {
        Row(horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight)) {
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_team_edit),
                onClick = @DontMemoize { actions.editTeam(team) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$SCHEDULING_TEAM_EDIT_TAG-${team.id}"),
            )
            DistrictButton(
                text = stringResource(R.string.scheduling_admin_team_members_button),
                onClick = @DontMemoize { actions.teamMembers(team) },
                variant = ButtonVariant.Ghost,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag("$SCHEDULING_TEAM_HOSTS_TAG-${team.id}"),
            )
        }
    }
}

const val SCHEDULING_TEAM_ROOT_TAG: String = "district-scheduling-team"
const val SCHEDULING_TEAM_STRANDED_TAG: String = "district-scheduling-team-stranded"
const val SCHEDULING_TEAM_DEGRADED_TAG: String = "district-scheduling-team-degraded"
const val SCHEDULING_TEAM_MEMBERS_LOADING_TAG: String = "district-scheduling-team-members-loading"
const val SCHEDULING_TEAM_MEMBERS_FAILURE_TAG: String = "district-scheduling-team-members-failure"
const val SCHEDULING_TEAM_MEMBER_TAG: String = "district-scheduling-team-member"
const val SCHEDULING_TEAM_ARCHIVE_TAG: String = "district-scheduling-team-archive"
const val SCHEDULING_TEAM_TEAMS_LOADING_TAG: String = "district-scheduling-team-teams-loading"
const val SCHEDULING_TEAM_EDIT_TAG: String = "district-scheduling-team-edit"
const val SCHEDULING_TEAM_HOSTS_TAG: String = "district-scheduling-team-hosts"
const val SCHEDULING_TEAM_CREATE_TAG: String = "district-scheduling-team-create"
