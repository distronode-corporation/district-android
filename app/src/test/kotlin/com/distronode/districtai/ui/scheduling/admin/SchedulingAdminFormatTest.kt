package com.distronode.districtai.ui.scheduling.admin

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.model.SchedulingMe
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.str
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The readings of times, links and wire values every section shares. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class SchedulingAdminFormatTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `instants, zones, today and booking links`() {
        assertEquals(Instant.parse("2026-10-02T13:00:00Z"), schedulingInstantOrNull("2026-10-02T13:00:00.000Z"))
        assertNull(schedulingInstantOrNull("yesterday"))
        assertNull(schedulingInstantOrNull(null))
        assertEquals(ZoneId.of("America/Toronto"), schedulingZone("America/Toronto"))
        assertEquals(ZoneId.of("UTC"), schedulingZone("Not/AZone"))
        // 02:00 UTC on Oct 2 is still Oct 1 in Toronto.
        assertEquals("2026-10-01", schedulingToday("America/Toronto", Instant.parse("2026-10-02T02:00:00Z")))
        assertEquals("https://acme.example/book/intro%20call", schedulingBookingUrl("acme.example", "intro call"))
        assertEquals("https://acme.example/book/a-b_c.d~e", schedulingBookingUrl("acme.example", "a-b_c.d~e"))
    }

    @Test
    fun `days convert between the scheduler's Sunday-first numbering and Monday-first display`() {
        assertEquals(6, schedulingDisplayIndex(0))
        assertEquals(0, schedulingDisplayIndex(1))
        assertEquals(5, schedulingDisplayIndex(6))
        (0 until DAYS_IN_WEEK).forEach { assertEquals(it, schedulingDisplayIndex(schedulingDayOfWeek(it))) }
    }

    @Test
    fun `a blank profile zone reads as UTC`() {
        val me = SchedulingMe(
            id = "u", email = "e", name = "n", timezone = "", timeFormat = "24h", weekStart = 1, dateFormat = "x",
            isAdmin = false, isOwner = false, role = "member", notifyConfirmation = true, notifyCancellation = true,
            notifyReschedule = true, notifyReminder = true, notifyHostBooking = true, notifyHostCancel = true,
            notifyHostReschedule = true,
        )
        assertEquals("UTC", me.displayTimezone)
        assertEquals("Europe/Paris", me.copy(timezone = "Europe/Paris").displayTimezone)
    }

    @Test
    fun `labels for minutes, times, providers, statuses and weekdays`() {
        val read = mutableMapOf<String, Any>()
        composeRule.setContent {
            read["none"] = schedulingMinutes(null)
            read["thirty"] = schedulingMinutes(30)
            read["when"] = schedulingDateTime("2026-10-02T13:00:00Z", "America/Toronto")
            read["badWhen"] = schedulingDateTime("nope", "UTC")
            read["time"] = schedulingTime("2026-10-02T13:00:00Z", "Europe/Paris")
            read["badTime"] = schedulingTime(null, "UTC")
            read["providers"] = listOf("google", "microsoft", "apple", "caldav", "zoom", "fastmail").map {
                schedulingProviderLabel(it)
            }
            read["statuses"] = listOf("confirmed", "cancelled", "rescheduled", "pending").map {
                schedulingBookingStatus(it)
            }
            read["days"] = (0 until DAYS_IN_WEEK).map { schedulingWeekdayShort(it) + "/" + schedulingWeekday(it) }
        }
        composeRule.waitForIdle()
        assertEquals(str(R.string.scheduling_admin_common_not_set), read["none"])
        assertEquals("30 min", read["thirty"])
        assertEquals("Oct 2, 2026, 9:00 a.m.", read["when"])
        assertEquals(str(R.string.scheduling_admin_common_unknown_time), read["badWhen"])
        assertEquals("15:00", read["time"])
        assertEquals(str(R.string.scheduling_admin_common_unknown_time), read["badTime"])
        assertEquals(
            listOf("Google Calendar", "Microsoft 365", "Apple Calendar", "CalDAV", "Zoom", "fastmail"),
            read["providers"],
        )
        assertEquals(
            listOf(
                SchedulingStatusLabel("Confirmed", SchedulingStatusKind.SUCCESS),
                SchedulingStatusLabel("Cancelled", SchedulingStatusKind.ERROR),
                SchedulingStatusLabel("Rescheduled", SchedulingStatusKind.INFO),
                SchedulingStatusLabel("pending", SchedulingStatusKind.INFO),
            ),
            read["statuses"],
        )
        assertEquals("Mon/Monday", (read["days"] as List<*>).first())
        assertEquals("Sun/Sunday", (read["days"] as List<*>).last())
    }

    @Test
    fun `sections round-trip through their path segments`() {
        SchedulingSection.entries.forEach { assertEquals(it, SchedulingSection.forPathSegment(it.pathSegment)) }
        assertNull(SchedulingSection.forPathSegment("nope"))
        assertNull(SchedulingSection.forPathSegment(null))
        assertEquals("event-types", SchedulingSection.EVENT_TYPES.pathSegment)
        // Reported, never thrown: an unknown op is the server's catalog moving ahead of this build.
        reportUnknownSchedulingOp(SchedulingAdminOp.ME_GET)
    }

    @Test
    fun `routes encode their path parts`() {
        assertEquals(
            "workspace/ws%201/scheduling/section/bookings",
            SchedulingAdminRoutes.section("ws 1", SchedulingSection.BOOKINGS),
        )
        assertEquals("workspace/ws/scheduling/booking/b%2F1", SchedulingAdminRoutes.booking("ws", "b/1"))
        assertEquals("workspace/ws/scheduling/event-type/intro", SchedulingAdminRoutes.eventType("ws", "intro"))
    }
}
