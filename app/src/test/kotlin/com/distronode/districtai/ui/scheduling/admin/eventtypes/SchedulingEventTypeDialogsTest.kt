package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingNoContent
import com.distronode.districtai.core.model.SchedulingTestEmailResult
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_RETRY_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The four event-type dialogs, drawn from their flows. ⚠️ The flows run on a [TestScope] that each
 * test advances by hand, so no assertion waits on a live coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingEventTypeDialogsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val offline = ApiResult.NetworkFailure(IOException("down"))
    private val rig = SchedulingAdminTestRig()
    private val scope = TestScope()
    private var deleted = 0
    private val writes = SchedulingEventTypeWrites(scope, rig.adminRepository, "ws", {}, { deleted++ })

    private fun show() {
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingEventTypeDialogs(dialog, writes) }
        }
    }

    private fun settle() {
        scope.advanceUntilIdle()
        composeRule.waitForIdle()
    }

    private fun tag(tag: String) = composeRule.onNodeWithTag(tag)

    @Test
    fun `the create dialog refuses an empty name, then creates`() {
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_CREATE, schedulingEventType())
        writes.editor.openCreate(emptyList())
        show()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_SAVE_TAG).performClick()
        tag(SCHEDULING_WRITE_REJECTION_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_name_missing)).assertExists()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_NAME_TAG).performTextInput("Intro")
        tag(SCHEDULING_EVENT_TYPE_EDITOR_DURATION_TAG).performTextReplacement("45")
        tag("$SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_TAG-phone").performClick()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_VALUE_TAG).assertDoesNotExist()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_SAVE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_NAME_TAG).assertIsNotEnabled()
        settle()
        assertNull(writes.dialog.value)
        val sent = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_CREATE).single().params
        assertEquals(JsonPrimitive(45), sent["duration_minutes"])
        assertEquals(JsonPrimitive("phone"), sent["location_type"])
    }

    @Test
    fun `the edit dialog shows the slug, every field, and the value field for the location chosen`() {
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_PATCH, fullEventType())
        writes.editor.openEdit(fullEventType().copy(locationType = "google_meet"))
        show()
        composeRule.onNodeWithText("intro").assertExists()
        tag("$SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_TAG-google_meet").assertExists()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_VALUE_TAG).assertDoesNotExist()
        tag("$SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_TAG-link").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_meeting_link).uppercase()).assertExists()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_LOCATION_VALUE_TAG).performTextReplacement("https://meet.example/x")
        tag(SCHEDULING_EVENT_TYPE_EDITOR_DESCRIPTION_TAG).performTextReplacement("New")
        tag(SCHEDULING_EVENT_TYPE_EDITOR_INTERVAL_TAG).performTextReplacement("20")
        tag(SCHEDULING_EVENT_TYPE_EDITOR_DURATION_TAG).performTextReplacement("40")
        listOf("buffer-before", "buffer-after", "notice", "future", "active").forEach {
            tag("$SCHEDULING_EVENT_TYPE_EDITOR_LIMIT_TAG-$it").performTextReplacement("1")
        }
        tag(SCHEDULING_EVENT_TYPE_EDITOR_ACTIVE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_LISTED_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_TAKEN_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_EDITOR_SAVE_TAG).performClick()
        settle()
        val sent = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_PATCH).single().params
        assertEquals(JsonPrimitive("https://meet.example/x"), sent["location_value"])
        assertEquals(JsonPrimitive(false), sent["is_active"])
        assertEquals(JsonPrimitive(true), sent["is_public"])
        assertEquals(JsonPrimitive(false), sent["show_taken_slots"])
        assertEquals(JsonPrimitive(1), sent["max_active_bookings"])
        assertEquals(JsonPrimitive(20), sent["slot_interval_minutes"])
    }

    @Test
    fun `actions toggle, archive, send a test email, and delete only after asking`() {
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_PATCH, fullEventType().copy(isActive = false, archived = true))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL, SchedulingTestEmailResult(true, "ana@example.com"))
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_DELETE,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingNoContent(ok = true)),
        )
        writes.actions.open(fullEventType())
        show()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_turn_off)).assertExists()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_TOGGLE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_ARCHIVE_TAG).assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_turned_off)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_turn_on)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_restore)).assertExists()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_ARCHIVE_TAG).performClick()
        settle()
        tag("$SCHEDULING_EVENT_TYPE_TEST_EMAIL_TAG-cancellation").performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_test_email_sent, "ana@example.com"))
            .assertExists()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_DELETE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_DELETE_DIALOG_TAG).assertExists()
        tag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_TAG).assertExists()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_DELETE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_DELETE_CONFIRM_TAG).performClick()
        settle()
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_TAG).assertExists()
        assertEquals(0, deleted)
        tag(SCHEDULING_EVENT_TYPE_ACTIONS_DELETE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_DELETE_CONFIRM_TAG).performClick()
        settle()
        assertEquals(1, deleted)
        val email = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL).single().params
        assertEquals(JsonPrimitive("cancellation"), email["type"])
    }

    @Test
    fun `hosts load, retry, add, edit, remove and save, with rotation only for round robin`() {
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_HOSTS_GET,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(schedulingHost(), schedulingHost("u9", name = "")))),
        )
        rig.admin.value(SchedulingAdminOp.USERS_LIST, listOf(schedulerUser("u2", "Bo"), schedulerUser("u3")))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT, SchedulingItems(listOf(schedulingHost())))
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_PATCH, fullEventType())
        writes.hosts.open(schedulingEventType())
        show()
        tag(SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_LOADING_TAG).assertExists()
        tag(SCHEDULING_EVENT_TYPE_HOSTS_SAVE_TAG).assertIsNotEnabled()
        settle()
        tag(SCHEDULING_EVENT_TYPE_HOSTS_DIALOG_FAILURE_TAG).assertExists()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        settle()
        tag("$SCHEDULING_EVENT_TYPE_ROTATION_TAG-even").assertDoesNotExist()
        tag("$SCHEDULING_EVENT_TYPE_ROUTING_TAG-round_robin").performClick()
        tag("$SCHEDULING_EVENT_TYPE_ROTATION_TAG-soonest").performClick()
        composeRule.onNodeWithText("u3@example.com").assertExists()
        tag("$SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG-u9").performClick()
        tag("$SCHEDULING_EVENT_TYPE_ADD_HOST_TAG-u2").performClick()
        tag("$SCHEDULING_EVENT_TYPE_HOST_ROLE_TAG-u2-optional").performClick()
        tag("$SCHEDULING_EVENT_TYPE_HOST_PRIORITY_TAG-u2").performTextReplacement("2")
        tag("$SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG-u1").performClick()
        composeRule.onNodeWithText("Bo").assertExists()
        tag("$SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG-u2").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_hosts_empty)).assertExists()
        tag(SCHEDULING_EVENT_TYPE_HOSTS_SAVE_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_hosts_need_one)).assertExists()
        writes.hosts.add("u2")
        writes.hosts.load()
        settle()
        tag("$SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG-u9").performClick()
        tag("$SCHEDULING_EVENT_TYPE_ADD_HOST_TAG-u2").performClick()
        tag("$SCHEDULING_EVENT_TYPE_ADD_HOST_TAG-u3").performClick()
        tag("$SCHEDULING_EVENT_TYPE_ADD_HOST_TAG-u2").assertDoesNotExist()
        tag(SCHEDULING_EVENT_TYPE_HOSTS_SAVE_TAG).performClick()
        tag("$SCHEDULING_EVENT_TYPE_HOST_REMOVE_TAG-u1").assertIsNotEnabled()
        settle()
        assertNull(writes.dialog.value)
        val put = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT).single().params
        assertTrue(put.toString().contains("u1"))
        val routing = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_PATCH).single().params
        assertEquals(JsonPrimitive("round_robin"), routing["routing_mode"])
        // A refused directory read leaves the list editable with no add choice.
        rig.admin.clear(SchedulingAdminOp.USERS_LIST)
        rig.admin.fail(SchedulingAdminOp.USERS_LIST, offline)
        writes.hosts.open(schedulingEventType())
        settle()
        tag("$SCHEDULING_EVENT_TYPE_HOST_ROW_TAG-u1").assertExists()
        tag("$SCHEDULING_EVENT_TYPE_ADD_HOST_TAG-u2").assertDoesNotExist()
    }

    @Test
    fun `questions list, add a select with options, edit, and delete after asking`() {
        val first = schedulingQuestion()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST,
            FakeSchedulingAnswer.Value(SchedulingItems(emptyList<Any>())),
            FakeSchedulingAnswer.Value(SchedulingItems(listOf(first))),
            FakeSchedulingAnswer.Failed(offline),
        )
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE, first)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE, SchedulingNoContent(ok = true))
        writes.questions.open("intro")
        show()
        tag(SCHEDULING_EVENT_TYPE_QUESTIONS_DIALOG_LOADING_TAG).assertExists()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_questions_empty)).assertExists()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_ADD_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_EDITOR_TAG).assertExists()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_LABEL_TAG).performTextInput("Budget")
        tag("$SCHEDULING_EVENT_TYPE_QUESTION_TYPE_TAG-select").performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_REQUIRED_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_POSITION_TAG).performTextReplacement("3")
        tag(SCHEDULING_EVENT_TYPE_QUESTION_SAVE_TAG).performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_question_needs_option)).assertExists()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_OPTION_TAG).performTextInput("Low")
        tag(SCHEDULING_EVENT_TYPE_QUESTION_ADD_OPTION_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_OPTION_TAG).performTextInput("High")
        tag(SCHEDULING_EVENT_TYPE_QUESTION_ADD_OPTION_TAG).performClick()
        tag("$SCHEDULING_EVENT_TYPE_QUESTION_OPTION_REMOVE_TAG-1").performClick()
        composeRule.onNodeWithText("Low").assertExists()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_SAVE_TAG).performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_LABEL_TAG).assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_question_added)).assertExists()
        val created = rig.admin.callsTo(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE).single().params
        assertEquals(JsonPrimitive(true), created["required"])
        assertEquals(JsonPrimitive(3), created["position"])

        tag("$SCHEDULING_EVENT_TYPE_QUESTION_EDIT_TAG-q1").performClick()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_question_edit_title)).assertExists()
        tag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        tag("$SCHEDULING_EVENT_TYPE_QUESTION_DELETE_TAG-q1").performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_DELETE_DIALOG_TAG).assertExists()
        tag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        tag("$SCHEDULING_EVENT_TYPE_QUESTION_DELETE_TAG-q1").performClick()
        tag(SCHEDULING_EVENT_TYPE_QUESTION_DELETE_CONFIRM_TAG).performClick()
        tag("$SCHEDULING_EVENT_TYPE_QUESTION_EDIT_TAG-q1").assertIsNotEnabled()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_event_types_question_deleted)).assertExists()
        composeRule.onNodeWithText("Company").assertExists()
        tag(SCHEDULING_WRITE_RETRY_TAG).performClick()
        settle()
        tag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        assertNull(writes.dialog.value)
    }
}
