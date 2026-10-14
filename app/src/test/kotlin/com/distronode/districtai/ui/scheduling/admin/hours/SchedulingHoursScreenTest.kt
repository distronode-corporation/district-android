package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.RemovalState
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_DISMISS_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_FAILURE_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_NOTICE_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_RETRY_TAG
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.wordsBrokenAcrossLines
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The working-hours screen as a member sees it: the week, the overrides, and who gets the editors. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingHoursScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val actions = SchedulingHoursActions(
        onBack = { calls += "back" },
        onRefresh = { calls += "refresh" },
        onEditHours = { calls += "edit" },
        onAddOverride = { calls += "add" },
        onDeleteOverride = { calls += "delete:${it.key}" },
        onDismissRemoval = { calls += "dismiss" },
    )

    private val overrides = schedulingUpcomingOverrides(
        listOf(
            schedulingOverride("r1", "2026-10-20", reason = "out_of_office", groupId = "g"),
            schedulingOverride("r2", "2026-10-22", reason = "out_of_office", groupId = "g"),
            schedulingOverride(
                "c",
                "2026-10-23",
                isAvailable = true,
                reason = "custom_hours",
                startTime = "12:00",
                endTime = "16:00",
            ),
            schedulingOverride("odd", "2026-10-24", reason = "sick_leave"),
            schedulingOverride("blank", "2026-10-25", reason = ""),
            schedulingOverride("y1", "2026-12-31", groupId = "y"),
            schedulingOverride("y2", "2027-01-02", groupId = "y"),
            schedulingOverride("bad", "2026-13-01", isAvailable = true, reason = "custom_hours", startTime = "12:00"),
            schedulingOverride("nostart", "2026-10-26", isAvailable = true, reason = "custom_hours", endTime = "16:00"),
            schedulingOverride("m1", "2026-13-01", groupId = "m"),
            schedulingOverride("m2", "2026-13-02", groupId = "m"),
        ),
        today = "2026-10-20",
    )

    private fun state(canManage: Boolean = true) = SchedulingHoursUiState(
        rules = SectionState.Ready(
            listOf(schedulingRule("m1", 1, "09:00", "12:00"), schedulingRule("m2", 1, "13:00", "17:00")),
        ),
        overrides = SectionState.Ready(overrides),
        timezone = "America/Toronto",
        today = LocalDate.of(2026, 10, 20),
        canManage = canManage,
    )

    private fun show(state: SchedulingHoursUiState, removal: RemovalState<SchedulingOverrideRow> = RemovalState()) {
        composeRule.setContent { DistrictTheme { SchedulingHoursScreen(state, removal, actions) } }
    }

    @Test
    fun `a manager sees all seven days, every override labelled, and the editors`() {
        show(state())
        composeRule.onNodeWithText("Mon, 9:00 to 12:00 and 13:00 to 17:00").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_common_times_in, "America/Toronto")).assertExists()
        composeRule.onNodeWithText("9:00 to 12:00, 13:00 to 17:00").assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_DAY_TAG-6").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_weekday_sun)).assertExists()

        composeRule.onNodeWithText("Oct 20 to Oct 22, 2026").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_out_of_office)).assertExists()
        composeRule.onNodeWithText("Oct 23, 2026").assertExists()
        composeRule.onNodeWithText("12:00 to 16:00").assertExists()
        composeRule.onNodeWithText("sick_leave").assertExists()
        composeRule.onNodeWithText("Dec 31, 2026 to Jan 2, 2027").assertExists()
        composeRule.onNodeWithText("2026-13-01").assertExists()
        composeRule.onNodeWithText("2026-13-01 to 2026-13-02").assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_OVERRIDE_TAG-id:nostart").assertExists()

        composeRule.onNodeWithTag(SCHEDULING_HOURS_EDIT_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_ADD_OVERRIDE_TAG).performClick()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_OVERRIDE_DELETE_TAG-group:g").performClick()
        assertEquals(listOf("edit", "add", "delete:group:g"), calls)
    }

    @Test
    fun `a viewer reads without editors, and an empty week and list say so`() {
        show(
            state(canManage = false).copy(
                rules = SectionState.Ready(emptyList()),
                overrides = SectionState.Ready(emptyList()),
            ),
        )
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_none_at_all)).assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_DAY_TAG-0").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_overrides_empty)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_HOURS_EDIT_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_OVERRIDE_TAG-group:g").assertDoesNotExist()
    }

    @Test
    fun `a viewer sees the rows but no delete`() {
        show(state(canManage = false))
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_OVERRIDE_TAG-group:g").assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_HOURS_OVERRIDE_DELETE_TAG-group:g").assertDoesNotExist()
    }

    @Test
    fun `the two reads load and fail apart, and a manager's empty list invites an override`() {
        val offline = FailureText(UiText.Resource(R.string.scheduling_admin_failure_offline), retryable = true)
        show(
            state().copy(
                rules = SectionState.Failed(offline),
                overrides = SectionState.Ready(emptyList()),
            ),
        )
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_overrides_empty_manage)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_failure_offline)).assertExists()
    }

    @Test
    fun `loading shows skeletons, and a failed override read offers retry`() {
        val offline = FailureText(UiText.Resource(R.string.scheduling_admin_failure_offline), retryable = true)
        show(state().copy(rules = SectionState.Loading, overrides = SectionState.Failed(offline)))
        composeRule.onNodeWithTag(SCHEDULING_HOURS_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        assertEquals(listOf("refresh"), calls)
    }

    @Test
    fun `the override list loading, and a delete that worked or failed is said`() {
        val refused = FailureText(UiText.Resource(R.string.scheduling_admin_failure_forbidden), retryable = false)
        show(
            state().copy(overrides = SectionState.Loading),
            RemovalState(removed = overrides.first(), failure = refused),
        )
        composeRule.onNodeWithTag(SCHEDULING_HOURS_OVERRIDES_LOADING_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_NOTICE_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_hours_override_deleted)).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_FAILURE_TAG).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_DISMISS_TAG).performClick()
        assertEquals(listOf("dismiss"), calls)
    }

    /**
     * ⛔ A SQUEEZED CONTROL SPLITS ITS LABEL MID-WORD. French runs about 30% longer than English and
     * this build carries no French, so English at a narrow width stands in for French at phone width
     * (the W4 French QA found this row split on a 411dp phone in fr-CA and en-XA).
     */
    @Test
    // ⚠️ NATIVE GRAPHICS: the legacy mode measures no real glyphs, so it never wraps a label at all.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w200dp-h1600dp")
    fun `the two editors wrap onto another line rather than splitting a word`() {
        show(state())

        val editors = listOf(SCHEDULING_HOURS_EDIT_TAG, SCHEDULING_HOURS_ADD_OVERRIDE_TAG)
        assertEquals(emptyList<String>(), composeRule.wordsBrokenAcrossLines(editors))
    }
}
