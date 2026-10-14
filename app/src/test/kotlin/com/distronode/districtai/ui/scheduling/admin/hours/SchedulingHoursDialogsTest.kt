package com.distronode.districtai.ui.scheduling.admin.hours

import com.distronode.districtai.core.data.MembersRepository
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingOverrideCreated
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_DISMISS_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_NOTICE_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The week editor, the add-an-override form with its calendar, the delete question, and the route. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingHoursDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope(StandardTestDispatcher())
    private val editor = SchedulingHoursRulesEditor(scope, rig.adminRepository, "ws") {}
    private val writes = SchedulingHoursOverrideWrites(scope, rig.adminRepository, "ws") {}
    private val today = LocalDate.of(2026, 10, 20)

    private fun rules(vararg rows: SchedulingAvailabilityRule) =
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_LIST, SchedulingItems(rows.toList()))

    private fun settle() {
        composeRule.runOnIdle { scope.advanceUntilIdle() }
        composeRule.waitForIdle()
    }

    private fun showEditor() = composeRule.setContent {
        DistrictTheme {
            val state by editor.state.collectAsState()
            state?.let { SchedulingHoursRulesDialog(it, editor) }
        }
    }

    private fun showAdd() = composeRule.setContent {
        DistrictTheme {
            val state by writes.add.collectAsState()
            state?.let { SchedulingOverrideAddDialog(it, writes, today) }
            val removal by writes.removal.state.collectAsState()
            SchedulingOverrideDeleteDialog(removal, writes.removal)
        }
    }

    @Test
    fun `the week editor loads, retries, refuses a bad time per window, and saves`() {
        rig.admin.fail(SchedulingAdminOp.AVAILABILITY_RULES_LIST, ApiResult.NetworkFailure(IOException("x")))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_DELETE, SchedulingNoContent(ok = true))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_PATCH, schedulingRule("mon", 1, "10:00", "17:00"))
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_CREATE, schedulingRule("n", 1, "10:00", "17:00"))
        showEditor()
        composeRule.runOnIdle { editor.open() }
        composeRule.onNodeWithTag(SCHEDULING_HOURS_RULES_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_RULES_SAVE_TAG).assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_failure_offline)).assertExists()
        rig.admin.clear(SchedulingAdminOp.AVAILABILITY_RULES_LIST)
        rules(schedulingRule("mon", 1, "09:00", "17:00"), schedulingRule("tue", 2, "09:00", "17:00"))
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        settle()

        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_DAY_TAG-6").assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_START_TAG-0-0").performTextReplacement("9:00")
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG)
            .assertTextEquals(str(R.string.scheduling_admin_hours_time_format_error))
        composeRule.onNodeWithTag(SCHEDULING_HOURS_RULES_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_START_TAG-0-0").performTextReplacement("10:00")
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_END_TAG-0-0").performTextReplacement("18:00")
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_END_TAG-0-0").performTextReplacement("17:00")
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_COPY_TAG-5").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_ADD_TAG-5").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_COPY_TAG-5").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_REMOVE_TAG-5-0").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_REMOVE_TAG-1-0").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_COPY_TAG-1").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_COPY_TAG-0").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_RULES_START_TAG-4-0").assertExists()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_RULES_SAVE_TAG).assertIsEnabled().performClick()
        settle()

        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_saved)).assertExists()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_PATCH).size)
        assertEquals(4, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_RULES_CREATE).size)
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_RULES_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `a day off is picked from the calendar, for one day or a range`() {
        rig.admin.value(
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
            SchedulingOverrideCreated.Single(schedulingOverride("o", "2026-10-22")),
        )
        showAdd()
        composeRule.runOnIdle { writes.open() }
        val notSet = str(R.string.scheduling_admin_common_not_set)
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_DATE_TAG-value").assertTextEquals(notSet)
        val firstDay = str(R.string.scheduling_admin_hours_first_day_label)
        composeRule.onNodeWithText(firstDay, ignoreCase = true).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_ADD_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG)
            .assertTextEquals(str(R.string.scheduling_admin_hours_date_missing))

        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_DATE_TAG-pick").performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_TAG).assertExists()
        pickDay("October 22, 2026")
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_DATE_TAG-value").assertTextEquals("Oct 22, 2026")

        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-clear").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-pick").performClick()
        pickDay("October 24, 2026")
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-value").assertTextEquals("Oct 24, 2026")
        // The calendar opens on the last day picked; cancelling it changes nothing.
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-pick").performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-value").assertTextEquals("Oct 24, 2026")
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-clear").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-value").assertTextEquals(notSet)
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_DATE_TAG-pick").performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-pick").performClick()
        pickDay("October 23, 2026")
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG).performClick()

        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_ADD_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_override_added)).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_ADD_CONFIRM_TAG).assertIsNotEnabled()
        val sent = rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE).single().params
        assertEquals(JsonPrimitive("2026-10-22"), sent["date"])
        assertEquals(JsonPrimitive("2026-10-23"), sent["end_date"])
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        assertNull(writes.add.value)
    }

    @Test
    fun `custom hours are one day with two times, refused when the end is not after the start`() {
        showAdd()
        composeRule.runOnIdle { writes.open() }
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_KIND_TAG-CUSTOM_HOURS").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_date_label), ignoreCase = true).assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_END_DATE_TAG-pick").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_OVERRIDE_DATE_TAG-pick").performClick()
        pickDay("October 21, 2026")
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_PICKER_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_START_TAG).performTextReplacement("12:00")
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_END_TAG).performTextReplacement("11:00")
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_ADD_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG)
            .assertTextEquals(str(R.string.scheduling_admin_hours_time_order_error))
        assertEquals(0, rig.admin.calls.size)
    }

    @Test
    fun `deleting asks first, naming the days a range gives back`() {
        rig.admin.value(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE, SchedulingNoContent(ok = true))
        val rows = schedulingUpcomingOverrides(
            listOf(
                schedulingOverride("one", "2026-12-01"),
                schedulingOverride("r1", "2026-12-24", groupId = "grp"),
                schedulingOverride("r2", "2026-12-25", groupId = "grp"),
                schedulingOverride("r3", "2026-12-26", groupId = "grp"),
            ),
            today = "2026-10-01",
        )
        showAdd()
        composeRule.runOnIdle { writes.removal.ask(rows[1]) }
        composeRule.onNodeWithText("All 3 days go back to your weekly hours.").assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_DELETE_DIALOG_TAG).assertDoesNotExist()
        composeRule.runOnIdle { writes.removal.ask(rows[0]) }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_override_delete_single_body)).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_DELETE_CONFIRM_TAG).performClick()
        settle()
        assertEquals(1, rig.admin.callsTo(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE).size)
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_DELETE_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun `the route reads the section and opens each editor and the delete question`() {
        rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "UTC"))
        rules(schedulingRule("mon", 1, "09:00", "17:00"))
        rig.admin.value(
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST,
            SchedulingItems(listOf(schedulingOverride("o", "2099-01-01"))),
        )
        var epoch by mutableIntStateOf(0)
        composeRule.setContent {
            val deps = SchedulingAdminDeps(
                admin = rig.adminRepository,
                scheduling = rig.scheduling,
                workspaceId = "ws",
                sessionEpoch = epoch,
                onBack = {},
                navigate = {},
                openUrl = { false },
                members = MembersRepository(rig.district),
            )
            DistrictTheme { SchedulingHoursRoute(deps) }
        }
        composeRule.waitUntil { hasNode("$SCHEDULING_HOURS_OVERRIDE_DELETE_TAG-id:o") }
        composeRule.onNodeWithTag(SCHEDULING_HOURS_EDIT_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_RULES_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_ADD_OVERRIDE_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_ADD_DIALOG_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_OVERRIDE_DELETE_TAG-id:o").performClick()
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_DELETE_DIALOG_TAG).assertExists()
        // Nothing is queued for the delete, so it fails offline; the failure is dismissed.
        composeRule.onNodeWithTag(SCHEDULING_OVERRIDE_DELETE_CONFIRM_TAG).performClick()
        composeRule.waitUntil { hasNode(SCHEDULING_WRITE_DISMISS_TAG) }
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_NOTICE_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(2, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)

        // A new session re-reads the section.
        composeRule.runOnIdle { epoch = 1 }
        composeRule.waitForIdle()
        assertEquals(3, rig.admin.callsTo(SchedulingAdminOp.ME_GET).size)
    }

    private fun hasNode(tag: String) =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    /** Click the calendar's day whose spoken name contains [date] ("October 22, 2026"). */
    private fun pickDay(date: String) {
        composeRule.onNode(hasText(date, substring = true) and hasClickAction()).performClick()
    }
}
