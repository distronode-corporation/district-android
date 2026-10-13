package com.distronode.districtai.ui.scheduling.admin.overview

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.TOP_BAR_BACK_DESCRIPTION
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingBookingAttendee
import com.distronode.districtai.core.model.SchedulingBookingPage
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import com.distronode.districtai.str
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SCHEDULING_ADMIN_REFRESH_TAG
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminTestRig
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.schedulingBooking
import com.distronode.districtai.ui.scheduling.admin.schedulingEventType
import com.distronode.districtai.ui.scheduling.admin.schedulingMe
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The booking desk: its reads, its one-line readings, and every row's states. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class SchedulingOverviewTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule(order = 0)
    val mainDispatcher = MainDispatcherRule(dispatcher)

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-10-02T12:00:00Z")

    private fun rule(day: Int, start: String, end: String, eventType: String? = null) =
        SchedulingAvailabilityRule(
            id = "r$day$start",
            eventTypeId = eventType,
            dayOfWeek = day,
            startTime = start,
            endTime = end,
        )

    @Test
    fun `the desk reads today's upcoming bookings in the member's zone, for the tenancy when the member may manage`() =
        runTest(dispatcher) {
            val rig = SchedulingAdminTestRig(canManage = true)
            rig.admin.value(SchedulingAdminOp.ME_GET, schedulingMe(timezone = "America/Toronto"))
            rig.admin.value(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingItems(listOf(schedulingEventType())))
            rig.admin.value(
                SchedulingAdminOp.CALENDAR_STATUS,
                SchedulingCalendarStatus(true, true, connections = emptyList()),
            )
            val rules = SchedulingItems(listOf(rule(1, "09:00", "17:00")))
            rig.admin.value(SchedulingAdminOp.AVAILABILITY_RULES_LIST, rules)
            rig.admin.value(SchedulingAdminOp.BOOKINGS_LIST, SchedulingBookingPage(items = listOf(schedulingBooking())))
            val viewModel = SchedulingOverviewViewModel(rig.adminRepository, rig.scheduling, "ws") {
                Instant.parse("2026-10-02T02:00:00Z")
            }
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals("America/Toronto", state.timezone)
            assertEquals("acme-book.example", state.publicHost)
            assertEquals(1, (state.bookings as SectionState.Ready).value.size)
            val params = rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).single().params
            assertEquals(JsonPrimitive("2026-10-01"), params["from"])
            assertEquals(JsonPrimitive("all"), params["scope"])
            assertEquals(JsonPrimitive(10), params["limit"])
            assertEquals(JsonPrimitive("asc"), params["order"])
        }

    @Test
    fun `a failed profile reads as UTC, and each failed read fails on its own`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig(canManage = false)
        rig.admin.value(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingItems(listOf(schedulingEventType())))
        val viewModel = SchedulingOverviewViewModel(rig.adminRepository, rig.scheduling, "ws") { now }
        advanceUntilIdle()
        val state = viewModel.state.value
        assertEquals("UTC", state.timezone)
        assertEquals(SectionState.Ready(listOf(schedulingEventType())), state.eventTypes)
        assertEquals(true, state.calendar is SectionState.Failed)
        assertNull(rig.admin.callsTo(SchedulingAdminOp.BOOKINGS_LIST).single().params["scope"])
        viewModel.load()
        assertEquals(SectionState.Loading, viewModel.state.value.eventTypes)
        advanceUntilIdle()
    }

    @Test
    fun `working hours group equal days, read three in a row as a range, and ignore event type rules`() {
        var summary: String? = "unset"
        var none: String? = "unset"
        composeRule.setContent {
            summary = workingHoursSummary(
                listOf(
                    rule(1, "09:00", "12:00"),
                    rule(1, "13:00", "17:00"),
                    rule(2, "13:00", "17:00"),
                    rule(2, "09:00", "12:00"),
                    rule(3, "09:00", "12:00"),
                    rule(3, "13:00", "17:00"),
                    rule(6, "10:00", "11:00"),
                    rule(0, "10:00", "11:00"),
                    rule(4, "08:00", "09:00", eventType = "et-1"),
                ),
            )
            none = workingHoursSummary(listOf(rule(4, "08:00", "09:00", eventType = "et-1")))
        }
        composeRule.waitForIdle()
        assertEquals("Mon to Wed, 9:00 to 12:00 and 13:00 to 17:00; Sat, Sun, 10:00 to 11:00", summary)
        assertNull(none)
    }

    @Test
    fun `small readings`() {
        assertEquals("9:00", schedulingDisplayTime("09:00"))
        assertEquals("ab:cd", schedulingDisplayTime("ab:cd"))
        assertEquals("0900", schedulingDisplayTime("0900"))
        assertEquals("09:", schedulingDisplayTime("09:"))
        val items = listOf(
            schedulingEventType("a"),
            schedulingEventType("b", isActive = false),
            schedulingEventType("c", isPublic = false),
            schedulingEventType("d", isActive = null, isPublic = null),
        )
        assertEquals(listOf("a", "d"), bookableEventTypes(items).map { it.slug })
        val read = mutableMapOf<String, String?>()
        composeRule.setContent {
            read["types"] = eventTypesSummary(items)
            read["noTypes"] = eventTypesSummary(emptyList())
            read["one"] = eventTypesSummary(listOf(schedulingEventType()))
            read["who"] = bookingWho(schedulingBooking())
            val emailOnly = SchedulingBookingAttendee(name = " ", email = "e@x")
            read["email"] = bookingWho(schedulingBooking(attendee = emailOnly))
            read["someone"] = bookingWho(schedulingBooking(attendee = SchedulingBookingAttendee()))
            read["nobody"] = bookingWho(schedulingBooking(attendee = null))
            read["what"] = bookingEventTypeName(schedulingBooking(), listOf(schedulingEventType()))
            read["slug"] = bookingEventTypeName(schedulingBooking(eventTypeSlug = "zzz"), items)
            read["none"] = bookingEventTypeName(schedulingBooking(eventTypeSlug = ""), items)
            read["null"] = bookingEventTypeName(schedulingBooking(eventTypeSlug = null), items)
            read["today"] = bookingWhen("2026-10-02T15:00:00Z", "UTC", now)
            read["tomorrow"] = bookingWhen("2026-10-03T15:00:00Z", "UTC", now)
            read["later"] = bookingWhen("2026-10-09T15:00:00Z", "UTC", now)
            read["bad"] = bookingWhen("never", "UTC", now)
        }
        composeRule.waitForIdle()
        assertEquals("4 event types, 3 active", read["types"])
        assertNull(read["noTypes"])
        assertEquals("1 event type, 1 active", read["one"])
        assertEquals("Bo Chen", read["who"])
        assertEquals("e@x", read["email"])
        assertEquals(str(R.string.scheduling_admin_overview_someone), read["someone"])
        assertEquals(str(R.string.scheduling_admin_overview_someone), read["nobody"])
        assertEquals("Intro call", read["what"])
        assertEquals("zzz", read["slug"])
        assertEquals(str(R.string.scheduling_admin_overview_booking), read["none"])
        assertEquals(str(R.string.scheduling_admin_overview_booking), read["null"])
        assertEquals("Today 15:00", read["today"])
        assertEquals("Tomorrow 15:00", read["tomorrow"])
        assertEquals("Oct 9, 15:00", read["later"])
        assertNull(read["bad"])
    }

    @Test
    fun `a full desk draws every row and the coming-up list`() {
        var back = 0
        var refresh = 0
        val state = SchedulingOverviewUiState(
            eventTypes = SectionState.Ready(
                listOf(schedulingEventType(), schedulingEventType("old", "Old", isActive = false)),
            ),
            calendar = SectionState.Ready(
                SchedulingCalendarStatus(
                    connected = true,
                    configured = true,
                    connections = listOf(SchedulingCalendarConnection("c1", "google", "ana@example.com", true, true)),
                ),
            ),
            rules = SectionState.Ready(listOf(rule(1, "09:00", "17:00"))),
            bookings = SectionState.Ready(
                listOf(schedulingBooking(), schedulingBooking(id = "b2", startAt = "bad", status = "cancelled")),
            ),
            timezone = "UTC",
            publicHost = "acme-book.example",
        )
        composeRule.setContent {
            DistrictTheme { SchedulingOverviewScreen(state, onBack = { back++ }, onRefresh = { refresh++ }, now = now) }
        }
        composeRule.onNodeWithText("https://acme-book.example/book/intro").assertIsDisplayed()
        composeRule.onNodeWithText("Google Calendar, ana@example.com").assertIsDisplayed()
        composeRule.onNodeWithText("Mon, 9:00 to 17:00").assertIsDisplayed()
        composeRule.onNodeWithText("2 event types, 1 active").assertIsDisplayed()
        composeRule.onNodeWithText("Today 13:00, Bo Chen, Intro call").assertIsDisplayed()
        composeRule.onNodeWithTag("$SCHEDULING_OVERVIEW_ROW_TAG-b2").assertIsDisplayed()
        composeRule.onAllNodesWithText("Bo Chen · Intro call").assertCountEquals(2)
        composeRule.onNodeWithTag(TOP_BAR_BACK_DESCRIPTION).performClick()
        composeRule.onNodeWithTag(SCHEDULING_ADMIN_REFRESH_TAG).performClick()
        assertEquals(1, back)
        assertEquals(1, refresh)
    }

    @Test
    fun `an empty desk says what is missing in each row`() {
        val state = SchedulingOverviewUiState(
            eventTypes = SectionState.Ready(listOf(schedulingEventType(isActive = false))),
            calendar = SectionState.Ready(SchedulingCalendarStatus(false, true, connections = emptyList())),
            rules = SectionState.Ready(emptyList()),
            bookings = SectionState.Ready(emptyList()),
            publicHost = "acme-book.example",
        )
        composeRule.setContent { DistrictTheme { SchedulingOverviewScreen(state, {}, {}, now) } }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_desk_no_event_types)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_desk_no_calendar)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_desk_no_hours)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_desk_no_bookings)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_coming_up_empty)).assertIsDisplayed()
        composeRule.onNodeWithText("1 event type, 0 active").assertIsDisplayed()
    }

    @Test
    fun `no host yet, no event types at all, loading and failed rows`() {
        val failure = FailureText(UiText.Literal("Calendar down"))
        val state = SchedulingOverviewUiState(
            eventTypes = SectionState.Ready(emptyList()),
            calendar = SectionState.Failed(failure),
            rules = SectionState.Loading,
            bookings = SectionState.Failed(FailureText(UiText.Literal("Bookings down"))),
            publicHost = null,
        )
        composeRule.setContent { DistrictTheme { SchedulingOverviewScreen(state, {}, {}, now) } }
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_desk_no_host)).assertIsDisplayed()
        composeRule.onNodeWithText("Calendar down").assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.scheduling_admin_overview_desk_no_event_types)).assertIsDisplayed()
        composeRule.onNodeWithTag(SCHEDULING_OVERVIEW_COMING_UP_TAG).assertIsDisplayed()
    }

    @Test
    fun `the coming-up card waits while its read is on its way`() {
        composeRule.setContent { DistrictTheme { SchedulingOverviewScreen(SchedulingOverviewUiState(), {}, {}) } }
        composeRule.onNodeWithTag(SCHEDULING_OVERVIEW_LOADING_TAG).assertIsDisplayed()
    }

    @Test
    fun `a failed read can come back`() = runTest(dispatcher) {
        val rig = SchedulingAdminTestRig()
        rig.admin.fail(SchedulingAdminOp.ME_GET, ApiResult.NetworkFailure(IOException("down")))
        val viewModel = SchedulingOverviewViewModel(rig.adminRepository, rig.scheduling, "ws")
        advanceUntilIdle()
        assertEquals(true, viewModel.state.value.bookings is SectionState.Failed)
    }
}
