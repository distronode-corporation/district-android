package com.distronode.districtai.ui.scheduling.admin.bookings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingBookingAnswer
import com.distronode.districtai.core.model.SchedulingSlot
import com.distronode.districtai.core.model.SchedulingSlots
import com.distronode.districtai.core.model.SchedulingUser
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_CANCEL_TAG
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_WRITE_REJECTION_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.core.network.testing.FakeSchedulingAnswer
import com.distronode.districtai.ui.scheduling.admin.schedulingBooking
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The bookings list, its row actions and its three dialogs, as a member sees them. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingBookingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val calls = mutableListOf<String>()

    private val offline = ApiResult.NetworkFailure(IOException("x"))

    private fun user(id: String, email: String, name: String) =
        SchedulingUser(
            id = id,
            email = email,
            name = name,
            isAdmin = false,
            isOwner = false,
            role = "member",
            archived = false,
        )

    private val scope = TestScope(StandardTestDispatcher())

    private fun writes(rig: SchedulingAdminTestRig) =
        SchedulingBookingWrites(scope, rig.adminRepository, "ws", { "UTC" }, { LocalDate.of(2026, 10, 2) }) {}

    /** Let the holder's requests answer, then let the screen draw what they changed. */
    private fun settle() {
        scope.advanceUntilIdle()
        composeRule.waitForIdle()
    }

    private fun actions(actionable: Boolean = true) = SchedulingBookingsActions(
        onBack = { calls += "back" },
        onRefresh = { calls += "refresh" },
        onSelectView = { calls += "view:${it.pathValue}" },
        onLoadMore = { calls += "more" },
        onOpen = { calls += "open:${it.id}" },
        isActionable = { actionable },
        onCancel = { calls += "cancel:${it.id}" },
        onReschedule = { calls += "reschedule:${it.id}" },
        onReassign = { calls += "reassign:${it.id}" },
    )

    private fun ready(
        rows: List<SchedulingBooking>,
        transform: (SchedulingBookingsUiState) -> SchedulingBookingsUiState = { it },
    ) =
        transform(
            SchedulingBookingsUiState(
                list = SectionState.Ready(Unit),
                rows = rows,
                timezone = "UTC",
                canManage = true,
                schedulerIsAdmin = true,
                eventTypes = listOf(schedulingEventType()),
            ),
        )

    @Test
    fun `rows carry when, who, what and host, and the row actions a manager may take`() {
        val rows = listOf(
            schedulingBooking(),
            schedulingBooking(id = "b2", startAt = "2026-10-03T13:00:00Z", eventTypeSlug = null, hostName = null),
        )
        composeRule.setContent { DistrictTheme { SchedulingBookingsScreen(ready(rows), actions()) } }
        composeRule.onNodeWithText("Oct 2, 2026, 13:00").assertExists()
        composeRule.onNodeWithText("Bo Chen · Intro call · Ana Lima").assertIsDisplayed()
        val unknownType = str(R.string.scheduling_admin_overview_booking)
        composeRule.onNodeWithText("Bo Chen · $unknownType · ${str(R.string.scheduling_admin_common_none)}")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_ROW_TAG-b1").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_CANCEL_TAG-b1").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_RESCHEDULE_TAG-b1").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_REASSIGN_TAG-b1").performClick()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_RESCHEDULE_TAG-b2").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_VIEW_TAG-PAST").performClick()
        composeRule.onNodeWithTag(SCHEDULING_BOOKINGS_MORE_TAG).performClick()
        assertEquals(listOf("open:b1", "cancel:b1", "reschedule:b1", "reassign:b1", "view:past", "more"), calls)
    }

    @Test
    fun `no write controls for a viewer, a non-admin, or a booking that is over`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingBookingsScreen(
                    ready(listOf(schedulingBooking())) {
                        it.copy(schedulerIsAdmin = false, isEnd = true, loadingMore = true)
                    },
                    actions(actionable = true),
                )
                SchedulingBookingsScreen(
                    ready(listOf(schedulingBooking(id = "v"))) { it.copy(canManage = false) },
                    actions(),
                )
                SchedulingBookingsScreen(ready(listOf(schedulingBooking(id = "o"))), actions(actionable = false))
            }
        }
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_CANCEL_TAG-b1").assertExists()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_REASSIGN_TAG-b1").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_CANCEL_TAG-v").assertDoesNotExist()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKINGS_CANCEL_TAG-o").assertDoesNotExist()
    }

    @Test
    fun `loading, failed, empty, a failed next page and a page on its way`() {
        composeRule.setContent {
            DistrictTheme {
                SchedulingBookingsScreen(SchedulingBookingsUiState(), actions())
                SchedulingBookingsScreen(
                    SchedulingBookingsUiState(list = SectionState.Failed(FailureText(UiText.Literal("Down")))),
                    actions(),
                )
                SchedulingBookingsScreen(ready(emptyList()) { it.copy(view = SchedulingBookingView.PAST) }, actions())
                SchedulingBookingsScreen(
                    ready(listOf(schedulingBooking(id = "x"))) {
                        it.copy(appendFailure = FailureText(UiText.Literal("Next page failed")), loadingMore = true)
                    },
                    actions(),
                )
            }
        }
        composeRule.onNodeWithTag(SCHEDULING_BOOKINGS_LOADING_TAG).assertExists()
        composeRule.onNodeWithText("Down").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_bookings_empty_past)).assertExists()
        composeRule.onNodeWithText("Next page failed").assertExists()
        composeRule.onNodeWithTag(SCHEDULING_BOOKINGS_MORE_TAG).assertIsNotEnabled()
    }

    @Test
    fun `the cancel dialog takes a reason and refuses one too long`() {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.BOOKINGS_CANCEL, schedulingBooking(status = "cancelled"))
        val writes = writes(rig)
        writes.cancel.open(schedulingBooking())
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingBookingDialogs(dialog, writes, "UTC") }
        }
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_CANCEL_REASON_TAG).performTextInput("x".repeat(1001))
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_CANCEL_CONFIRM_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_REJECTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_WRITE_CANCEL_TAG).performClick()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_CANCEL_DIALOG_TAG).assertDoesNotExist()
        writes.cancel.open(schedulingBooking())
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_CANCEL_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_bookings_cancel_done)).assertIsDisplayed()
    }

    @Test
    fun `the reschedule dialog steps days, picks a time and names the new time`() {
        val rig = SchedulingAdminTestRig()
        val slot = SchedulingSlot("2026-10-02T14:00:00Z", "2026-10-02T14:30:00Z")
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_SLOTS, SchedulingSlots(listOf(slot)))
        rig.admin.value(SchedulingAdminOp.BOOKINGS_RESCHEDULE, schedulingBooking(startAt = slot.start))
        val writes = writes(rig)
        writes.reschedule.open(schedulingBooking())
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingBookingDialogs(dialog, writes, "UTC") }
        }
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_PREVIOUS_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_DAY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_CONFIRM_TAG).assertIsNotEnabled()
        settle()
        composeRule.onNodeWithTag("$SCHEDULING_BOOKING_RESCHEDULE_SLOT_TAG-${slot.start}").performClick()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_CONFIRM_TAG).performClick()
        settle()
        composeRule.onNodeWithText("Booking moved to Oct 2, 2026, 14:00").assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_NEXT_TAG).performClick()
    }

    @Test
    fun `the reschedule dialog says when a day is full, or when it could not read it`() {
        val rig = SchedulingAdminTestRig()
        rig.admin.answer(
            SchedulingAdminOp.EVENT_TYPES_SLOTS,
            FakeSchedulingAnswer.Value(SchedulingSlots(emptyList())),
            FakeSchedulingAnswer.Failed(offline),
        )
        val writes = writes(rig)
        writes.reschedule.open(schedulingBooking())
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingBookingDialogs(dialog, writes, "UTC") }
        }
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_bookings_reschedule_no_slots)).assertIsDisplayed()
        writes.reschedule.submit()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_bookings_reschedule_pick_time)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_NEXT_TAG).performClick()
        settle()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_RESCHEDULE_FAILURE_TAG).assertIsDisplayed()
    }

    @Test
    fun `the reassign dialog lists the other hosts by name or email`() {
        val rig = SchedulingAdminTestRig()
        rig.admin.answer(
            SchedulingAdminOp.USERS_LIST,
            FakeSchedulingAnswer.Failed(offline),
            FakeSchedulingAnswer.Value(
                listOf(
                    user("u2", "cy@example.com", ""),
                    user("u3", "di@example.com", "Di"),
                ),
            ),
        )
        rig.admin.value(SchedulingAdminOp.BOOKINGS_REASSIGN, schedulingBooking(hostId = "u2"))
        val writes = writes(rig)
        writes.reassign.open(schedulingBooking())
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme { SchedulingBookingDialogs(dialog, writes, "UTC") }
        }
        settle()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_REASSIGN_FAILURE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.overview_retry)).performClick()
        settle()
        composeRule.onNodeWithText("cy@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Di").performClick()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_REASSIGN_CONFIRM_TAG).performClick()
        settle()
    }

    @Test
    fun `the reassign dialog says when nobody else can take it, and nothing is drawn with no dialog`() {
        val rig = SchedulingAdminTestRig()
        rig.admin.value(SchedulingAdminOp.USERS_LIST, emptyList<SchedulingUser>())
        val writes = writes(rig)
        writes.reassign.open(schedulingBooking())
        composeRule.setContent {
            val dialog by writes.dialog.collectAsState()
            DistrictTheme {
                SchedulingBookingDialogs(dialog, writes, "UTC")
                SchedulingBookingDialogs(null, writes, "UTC")
            }
        }
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_REASSIGN_DIALOG_TAG).assertExists()
        settle()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_bookings_reassign_no_hosts)).assertIsDisplayed()
    }

    @Test
    fun `one booking shows its answers, a blank one as none, and cancel to a manager`() {
        var cancel = 0
        val answers = listOf(
            SchedulingBookingAnswer("q1", "Company", "text", "Acme"),
            SchedulingBookingAnswer("q2", "Notes", "text", ""),
        )
        composeRule.setContent {
            DistrictTheme {
                SchedulingBookingDetailScreen(
                    SchedulingBookingDetailUiState(SectionState.Ready(answers), canManage = true),
                    onBack = {},
                    onRefresh = {},
                    onCancel = { cancel++ },
                )
                SchedulingBookingDetailScreen(
                    SchedulingBookingDetailUiState(SectionState.Ready(emptyList())),
                    {},
                    {},
                    {},
                )
                SchedulingBookingDetailScreen(SchedulingBookingDetailUiState(), {}, {}, {})
                SchedulingBookingDetailScreen(
                    SchedulingBookingDetailUiState(SectionState.Failed(FailureText(UiText.Literal("Answers down")))),
                    {},
                    {},
                    {},
                )
            }
        }
        composeRule.onNodeWithText("Acme").assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_common_none)).assertExists()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_bookings_no_answers)).assertExists()
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_DETAIL_LOADING_TAG).assertExists()
        composeRule.onNodeWithText("Answers down").assertExists()
        // ⚠️ By semantics, not by touch: four screens are stacked here and a touch lands on the top one.
        composeRule.onNodeWithTag(SCHEDULING_BOOKING_DETAIL_CANCEL_TAG).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, cancel)
    }
}
