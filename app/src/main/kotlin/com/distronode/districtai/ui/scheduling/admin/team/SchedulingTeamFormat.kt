package com.distronode.districtai.ui.scheduling.admin.team

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.core.model.WorkspaceMember
import com.distronode.districtai.l10n.DistrictFormat
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusKind
import com.distronode.districtai.ui.scheduling.admin.SchedulingStatusLabel
import com.distronode.districtai.ui.scheduling.admin.schedulingUnknownValue

/** Whether the scheduler has an account for a person, and how that is labelled and coloured. */
enum class SchedulingMemberState(@get:StringRes val label: Int, val kind: SchedulingStatusKind) {
    /** A live scheduler account: bookable. */
    HOST(R.string.scheduling_admin_team_state_host, SchedulingStatusKind.SUCCESS),

    /** In the workspace, with no scheduler account yet. */
    ABSENT(R.string.scheduling_admin_team_state_absent, SchedulingStatusKind.INFO),

    /** A scheduler account that has been archived. */
    ARCHIVED(R.string.scheduling_admin_team_state_archived, SchedulingStatusKind.STOPPED),
}

/**
 * One person as the team screen sees them: their District membership and their scheduler account,
 * joined. Ported from district-core-swift's `SchedulingMemberRow`.
 *
 * @property key the lower-cased address: the join key and the row's identity.
 * @property districtRole ⛔ null means NO DISTRICT ROW, a person who has left the workspace and
 *   still has a scheduler account. It is not "role unknown" and it is not "viewer".
 */
data class SchedulingMemberRow(
    val key: String,
    val email: String,
    val name: String,
    val districtRole: String?,
    val state: SchedulingMemberState,
    val schedulerUserId: String?,
) {
    /**
     * The scheduler user id an archive would address, or null. ⛔ Only a live account has one:
     * somebody with no scheduler row has nothing to archive, and an archived one answers a 404.
     */
    val archivableUserId: String? get() = schedulerUserId?.takeIf { state == SchedulingMemberState.HOST }
}

/**
 * The team screen's read half, ported from district-core-swift's `SchedulingTeamFormat`.
 *
 * ⛔ THE JOIN IS ON THE EMAIL ADDRESS AND THERE IS NOTHING ELSE TO JOIN ON. District membership and
 * the scheduler's user table are separate systems with separate ids. ⚠️ It is compared
 * case-insensitively: one side is what was typed into an invite, the other into the scheduler.
 */
object SchedulingTeamFormat {

    /** The local part of an address, or the whole address when that part is empty. */
    fun nameFromEmail(email: String): String {
        val trimmed = email.trim()
        val local = trimmed.substringBefore('@')
        return local.ifEmpty { trimmed }
    }

    /**
     * District's members and the scheduler's users, as one list.
     *
     * ⛔ DISTRICT'S ORDER FIRST, THEN SCHEDULER-ONLY ROWS. The second pass is what surfaces somebody
     * who has left the workspace and still holds bookings, the stranded host the screen warns about.
     *
     * ⚠️ A duplicate address is skipped after the first, and the scheduler-side lookup keeps the
     * LAST user for a duplicated address, both as the source does.
     */
    fun joinMembers(district: List<WorkspaceMember>, scheduler: List<SchedulingUser>): List<SchedulingMemberRow> {
        val byEmail = scheduler.associateBy { normalize(it.email) }
        val seen = mutableSetOf<String>()
        val rows = mutableListOf<SchedulingMemberRow>()
        district.forEach { member ->
            val key = normalize(member.email)
            if (!seen.add(key)) return@forEach
            val user = byEmail[key]
            rows += SchedulingMemberRow(
                key = key,
                email = member.email,
                name = preferredName(user?.name, member.email),
                districtRole = member.role,
                state = stateOf(user),
                schedulerUserId = user?.id,
            )
        }
        scheduler.forEach { user ->
            val key = normalize(user.email)
            if (!seen.add(key)) return@forEach
            rows += SchedulingMemberRow(
                key = key,
                email = user.email,
                name = preferredName(user.name, user.email),
                districtRole = null,
                state = stateOf(user),
                schedulerUserId = user.id,
            )
        }
        return rows
    }

    /** People who have left the workspace and still hold a live scheduler account. */
    fun strandedHosts(rows: List<SchedulingMemberRow>): List<SchedulingMemberRow> =
        rows.filter { it.districtRole == null && it.state == SchedulingMemberState.HOST }

    /**
     * How many people are on a team. ⚠️ A `member_count` of zero is honoured: the members array is
     * null on a list row the fork did not expand, so it cannot be read as "this team is empty".
     */
    fun teamMemberCount(team: SchedulingTeam): Int = team.memberCount ?: team.members?.size ?: 0

    private fun normalize(email: String): String = email.trim().lowercase()

    private fun stateOf(user: SchedulingUser?): SchedulingMemberState = when {
        user == null -> SchedulingMemberState.ABSENT
        user.archived -> SchedulingMemberState.ARCHIVED
        else -> SchedulingMemberState.HOST
    }

    /** ⚠️ The scheduler's name wins only when it is not blank, the source's `||`, not a `??`. */
    private fun preferredName(schedulerName: String?, fallbackEmail: String): String =
        schedulerName?.trim().orEmpty().ifEmpty { nameFromEmail(fallbackEmail) }
}

/** Host, Archived, or Not set up yet, with the colour it is drawn in. */
@Composable
@ReadOnlyComposable
fun schedulingMemberStateLabel(state: SchedulingMemberState): SchedulingStatusLabel =
    SchedulingStatusLabel(stringResource(state.label), state.kind)

/**
 * A District role (`agency`, `client`, `viewer`), or the sentence for somebody who has left.
 *
 * ⚠️ AN UNKNOWN ROLE IS SHOWN AS IT CAME, casing included, not as the normalised form the lookup
 * used: that is what the operator would quote in a support request.
 */
@Composable
@ReadOnlyComposable
fun schedulingDistrictRoleLabel(role: String?): String {
    if (role == null) return stringResource(R.string.scheduling_admin_team_role_removed)
    val known = DISTRICT_ROLE_LABELS[role.trim().lowercase()] ?: return schedulingUnknownValue(role)
    return stringResource(known)
}

/** District's three workspace roles, by wire value. */
private val DISTRICT_ROLE_LABELS: Map<String, Int> = mapOf(
    "agency" to R.string.scheduling_admin_team_role_agency,
    "client" to R.string.scheduling_admin_team_role_client,
    "viewer" to R.string.scheduling_admin_team_role_viewer,
)

/**
 * The warning for [rows], the stranded hosts.
 *
 * ⛔ SINGULAR AND PLURAL ARE SEPARATE SENTENCES, not one with a pluralised noun: `is`/`are`,
 * `holds`/`hold` and `account` twice all move with the count. ⚠️ The names are joined by
 * [DistrictFormat.list], this app's list rule in both languages.
 */
@Composable
@ReadOnlyComposable
fun schedulingStrandedNotice(rows: List<SchedulingMemberRow>): String {
    val names = rows.map { it.name }
    val subject = DistrictFormat.list(names, appLanguage())
    return if (names.size == 1) {
        stringResource(R.string.scheduling_admin_team_stranded_one, subject)
    } else {
        stringResource(R.string.scheduling_admin_team_stranded_many, subject)
    }
}
