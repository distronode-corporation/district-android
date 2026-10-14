package com.distronode.districtai.ui.scheduling.admin.team

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.SchedulingTeam
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SectionState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The team screen as a manager and as a viewer: who is a host, who has left, and the teams. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingTeamScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val actions = RecordingTeamActions()

    private val rows = SchedulingTeamFormat.joinMembers(
        district = listOf(
            districtMember("ana@example.com", "agency"),
            districtMember("bo@example.com", "Owner"),
            districtMember("fay@example.com", "viewer"),
        ),
        scheduler = listOf(
            teamUser("u1", "ana@example.com", "Ana Lima"),
            teamUser("u3", "cy@example.com", "Cy"),
            teamUser("u4", "dee@example.com", "Dee"),
            teamUser("u5", "eve@example.com", "Eve", archived = true),
        ),
    )
    private val teams = listOf(
        schedulingTeam("t1", "Front desk", members = listOf(teamMember("u1", "Ana"))),
        schedulingTeam("t2", "Sales", members = null, memberCount = 0),
    )

    private fun manager(transform: (SchedulingTeamUiState) -> SchedulingTeamUiState = { it }) = transform(
        SchedulingTeamUiState(
            members = SectionState.Ready(rows),
            teams = SectionState.Ready(teams),
            canManage = true,
        ),
    )

    @Test
    fun `a manager sees roles, states, who has left, the teams, and every write`() {
        composeRule.setContent { DistrictTheme { SchedulingTeamScreen(manager(), actions) } }

        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_stranded_many, "Cy and Dee")).assertIsDisplayed()
        val agency = str(R.string.scheduling_admin_team_role_agency)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_member_subtitle, "ana@example.com", agency))
            .assertExists()
        // ⚠️ A role this build does not know is shown as the server sent it.
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_member_subtitle, "bo@example.com", "Owner"))
            .assertExists()
        val removed = str(R.string.scheduling_admin_team_role_removed)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_member_subtitle, "cy@example.com", removed))
            .assertExists()
        val viewer = str(R.string.scheduling_admin_team_role_viewer)
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_member_subtitle, "fay@example.com", viewer))
            .assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_state_archived)).assertExists()
        composeRule.onNodeWithText("1 member").assertExists()
        composeRule.onNodeWithText("0 members").assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_ARCHIVE_TAG-bo@example.com").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_ARCHIVE_TAG-eve@example.com").assertDoesNotExist()

        composeRule.onNodeWithTag("$SCHEDULING_TEAM_ARCHIVE_TAG-ana@example.com").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_EDIT_TAG-t1").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_HOSTS_TAG-t2").performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_CREATE_TAG).performClick()
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        assertEquals(
            listOf("archive:u1:Ana Lima", "edit:t1", "hosts:t2", "create", "back", "refresh"),
            actions.calls,
        )
    }

    @Test
    fun `a viewer gets no writes, and a roster that could not be read names no role and accuses nobody`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingTeamScreen(manager { it.copy(canManage = false, districtMembersFailed = true) }, actions)
            }
        }
        composeRule.onNodeWithTag(SCHEDULING_TEAM_DEGRADED_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_STRANDED_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("cy@example.com").assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_ARCHIVE_TAG-ana@example.com").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_EDIT_TAG-t1").assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_CREATE_TAG).assertDoesNotExist()
    }

    @Test
    fun `one person who left reads in the singular, and empty, loading and failed reads each say so`() {
        val cy = rows.filter { it.key == "cy@example.com" }
        composeRule.setContent {
            DistrictTheme {
                SchedulingTeamScreen(
                    manager { it.copy(members = SectionState.Ready(cy), teams = SectionState.Ready(emptyList())) },
                    actions,
                )
                SchedulingTeamScreen(
                    manager { it.copy(members = SectionState.Ready(emptyList())) },
                    actions,
                )
                SchedulingTeamScreen(SchedulingTeamUiState(), actions)
                SchedulingTeamScreen(
                    SchedulingTeamUiState(
                        members = SectionState.Failed(FailureText(UiText.Literal("People failed"))),
                        teams = SectionState.Failed(FailureText(UiText.Literal("Teams failed"))),
                    ),
                    actions,
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_stranded_one, "Cy")).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_teams_empty)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_members_empty)).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_MEMBERS_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_TEAMS_LOADING_TAG).assertExists()
        composeRule.onNodeWithText("People failed").assertExists()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        assertEquals(listOf("refresh"), actions.calls)
        composeRule.onNodeWithText("Teams failed").assertExists()
    }
}

/** Records what the team screen asked for. */
class RecordingTeamActions : SchedulingTeamActions {
    val calls = mutableListOf<String>()
    override fun back() {
        calls += "back"
    }
    override fun refresh() {
        calls += "refresh"
    }
    override fun archive(userId: String, name: String) {
        calls += "archive:$userId:$name"
    }
    override fun createTeam() {
        calls += "create"
    }
    override fun editTeam(team: SchedulingTeam) {
        calls += "edit:${team.id}"
    }
    override fun teamMembers(team: SchedulingTeam) {
        calls += "hosts:${team.id}"
    }
}
