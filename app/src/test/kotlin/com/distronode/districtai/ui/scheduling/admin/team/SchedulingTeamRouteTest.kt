package com.distronode.districtai.ui.scheduling.admin.team

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.MemberListResponse
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The team route against its real ViewModel: every control opens the right dialog.
 *
 * ⚠️ AN UNCONFINED MAIN DISPATCHER, so every fake answer lands before the next line.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingTeamRouteTest {

    @get:Rule(order = 0)
    val mainDispatcher = MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig(canManage = true)
    private val calls = mutableListOf<String>()

    @Test
    fun `each control opens its dialog, and Back and Refresh do what they say`() {
        rig.district.memberListResult = ApiResult.Success(
            MemberListResponse(success = true, members = listOf(districtMember("ana@example.com"))),
        )
        rig.admin.value(SchedulingAdminOp.USERS_LIST, listOf(teamUser("u1", "ana@example.com", "Ana")))
        rig.admin.value(SchedulingAdminOp.TEAMS_LIST, SchedulingItems(listOf(schedulingTeam())))
        var epoch by mutableIntStateOf(0)
        fun deps(sessionEpoch: Int) = SchedulingAdminDeps(
            admin = rig.adminRepository,
            scheduling = rig.scheduling,
            workspaceId = "ws",
            sessionEpoch = sessionEpoch,
            onBack = { calls += "back" },
            navigate = { calls += "navigate:$it" },
            openUrl = { false },
            members = MembersRepository(rig.district),
        )
        composeRule.setContent { DistrictTheme { SchedulingTeamRoute(deps(epoch)) } }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$SCHEDULING_TEAM_ARCHIVE_TAG-ana@example.com").performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_EDIT_TAG-t1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_EDITOR_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_HOSTS_TAG-t1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_MEMBERS_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_CREATE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_EDITOR_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()

        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        assertEquals(listOf("back"), calls)
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.TEAMS_LIST).size)
        // A new session re-reads.
        epoch = 1
        composeRule.waitForIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.TEAMS_LIST).size)
    }
}
