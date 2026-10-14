package com.distronode.districtai.ui.scheduling.admin.team

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingUpcomingBooking
import com.distronode.districtai.core.model.SchedulingUserArchived
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_RETRY_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The team editor, a team's roster and the archive, as dialogs. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingTeamDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private val writes = SchedulingTeamWrites(scope, rig.adminRepository, "ws") {}
    private val ana = teamMember("u1", "Ana", priority = 1)

    private fun show() {
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingTeamDialogs(dialog, writes, "UTC") }
        }
    }

    /** Draw the write on its way (every control disabled), then let it answer. */
    private fun settle() {
        composeRule.waitForIdle()
        scope.advanceUntilIdle()
        composeRule.waitForIdle()
    }

    @Test
    fun `a new team needs a name, and an existing one is renamed or deleted behind its own question`() {
        rig.admin.value(SchedulingAdminOp.TEAMS_CREATE, schedulingTeam())
        rig.admin.value(SchedulingAdminOp.TEAMS_DELETE, SchedulingNoContent(true))
        writes.editor.openCreate()
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_create_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_DELETE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_EDITOR_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_NAME_TAG).performTextInput("Front desk")
        composeRule.onNodeWithTag(SCHEDULING_TEAM_EDITOR_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_create_done)).assertIsDisplayed()

        writes.editor.openEdit(schedulingTeam(name = "Sales"))
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_slug_read_only_hint)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_DELETE_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_delete_title, "Sales")).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_delete_keep)).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_DELETE_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_DELETE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_DELETE_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_delete_done)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_EDITOR_DIALOG_TAG).assertDoesNotExist()

        rig.admin.fail(SchedulingAdminOp.TEAMS_PATCH, ApiResult.NetworkFailure(IOException("x")))
        writes.editor.openEdit(schedulingTeam(name = "Sales"))
        composeRule.onNodeWithTag(SCHEDULING_TEAM_EDITOR_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        settle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.TEAMS_PATCH).size)
    }

    @Test
    fun `the roster refuses a bad priority by name, removes behind a question, and adds a picked host`() {
        rig.admin.answer(
            SchedulingAdminOp.USERS_LIST,
            FakeSchedulingAnswer.Value(listOf(teamUser("u1", "a@x.example"), teamUser("u2", "bo@x.example"))),
        )
        rig.admin.value(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE, SchedulingNoContent(true))
        rig.admin.value(SchedulingAdminOp.TEAMS_GET, schedulingTeam(members = emptyList()))
        rig.admin.value(SchedulingAdminOp.TEAMS_MEMBERS_ADD, schedulingTeam(members = listOf(ana)))
        writes.members.open(schedulingTeam(members = listOf(ana, teamMember("u3", ""))))
        show()
        composeRule.onNodeWithText("u3@example.com", ignoreCase = true).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_USERS_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_MEMBERS_ADD_TAG).assertIsNotEnabled()
        settle()

        composeRule.onNodeWithTag("$SCHEDULING_TEAM_PRIORITY_TAG-u1").performTextClearance()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_PRIORITY_TAG-u1").performTextInput("2.5")
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_SAVE_PRIORITY_TAG-u1").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_priority_invalid)).assertIsDisplayed()

        composeRule.onNodeWithTag("$SCHEDULING_TEAM_REMOVE_TAG-u1").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_remove_keep)).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_REMOVE_DIALOG_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_TEAM_REMOVE_TAG-u1").performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_REMOVE_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_members_empty_roster)).assertIsDisplayed()

        composeRule.onNodeWithTag("$SCHEDULING_TEAM_PICK_TAG-u2").performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_MEMBERS_ADD_TAG).assertIsEnabled().performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_add_done)).assertIsDisplayed()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.TEAMS_MEMBERS_ADD).size)
    }

    @Test
    fun `every host already in the team, and a host list that could not be read`() {
        rig.admin.value(SchedulingAdminOp.USERS_LIST, listOf(teamUser("u1", "a@x.example")))
        writes.members.open(schedulingTeam(members = listOf(ana)))
        show()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_add_none)).assertIsDisplayed()
        rig.admin.clear(SchedulingAdminOp.USERS_LIST)
        writes.members.loadUsers()
        settle()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_USERS_FAILURE_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        settle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.USERS_LIST).size)
    }

    @Test
    fun `an archive lists what stands in the way, then asks, then names the person`() {
        rig.admin.answer(
            SchedulingAdminOp.USERS_UPCOMING_BOOKINGS,
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(upcomingBooking()))),
            FakeSchedulingAnswer.Value(SchedulingItems(emptyList<SchedulingUpcomingBooking>())),
        )
        rig.admin.value(SchedulingAdminOp.USERS_ARCHIVE, SchedulingUserArchived(ok = true))
        writes.archive.open("u1", "Ana")
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_archive_title, "Ana")).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_archive_counting)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_CONFIRM_TAG).assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_archive_blocked)).assertIsDisplayed()
        composeRule.onNodeWithText("Bo Chen · Oct 2, 2026, 13:00").assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_CONFIRM_TAG).assertIsNotEnabled()

        writes.archive.loadUpcoming()
        settle()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_QUESTION_TAG).assertExists()
        val inQuestion = hasAnyAncestor(hasTestTag(SCHEDULING_TEAM_ARCHIVE_QUESTION_TAG))
        composeRule.onNode(hasTestTag(SCHEDULING_WRITE_CANCEL_TAG) and inQuestion).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_QUESTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_ARCHIVE_QUESTION_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_team_archive_done, "Ana")).assertIsDisplayed()

        rig.admin.clear(SchedulingAdminOp.USERS_UPCOMING_BOOKINGS)
        writes.archive.open("u2", "Bo")
        settle()
        composeRule.onNodeWithTag(SCHEDULING_TEAM_UPCOMING_FAILURE_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        settle()
        assertEquals(4, rig.admin.callsTo(SchedulingAdminOp.USERS_UPCOMING_BOOKINGS).size)
    }
}
