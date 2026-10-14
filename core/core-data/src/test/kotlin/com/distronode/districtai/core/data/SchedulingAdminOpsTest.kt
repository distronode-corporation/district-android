package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingBranding
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarSelections
import com.distronode.districtai.core.model.SchedulingItems
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.core.network.SchedulingAdminOp
import com.distronode.districtai.core.network.testing.FakeSchedulingAdminApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every typed op: the op name it sends and the params object, key for key.
 *
 * ⛔ THE EXPECTED JSON IS WRITTEN OUT, NOT DERIVED. The keys are the server catalog's spelling,
 * including its inconsistencies (`account` against `account_email`, `user_id` against `userId`,
 * `groupId`), and district-core-swift's wrappers are the reference. A test that rebuilt the
 * expectation from the same helpers would pass through any typo.
 */
class SchedulingAdminOpsTest {

    private val api = FakeSchedulingAdminApi()
    private val repository = SchedulingAdminRepository(api) {}

    private fun assertSent(op: SchedulingAdminOp, json: String) {
        val call = api.calls.last()
        assertEquals(op, call.op)
        assertEquals("ws", call.workspaceId)
        assertEquals(Json.parseToJsonElement(json), call.params)
    }

    @Test
    fun `bookings send their filters and drop the blank ones`() = runTest {
        api.value(SchedulingAdminOp.BOOKINGS_ANSWERS, SchedulingItems(listOf<Any>()))
        val full = SchedulingBookingFilter(
            status = "confirmed", whenFilter = "upcoming", from = "2026-10-01", to = "2026-10-31",
            eventTypeSlug = "intro", host = "h", team = "t", limit = 25, offset = 50, allHosts = true, order = "asc",
        )
        repository.bookings("ws", full)
        assertSent(
            SchedulingAdminOp.BOOKINGS_LIST,
            """{"status":"confirmed","when":"upcoming","from":"2026-10-01","to":"2026-10-31","event_type":"intro",
               "host":"h","team":"t","limit":25,"offset":50,"scope":"all","order":"asc"}""",
        )
        repository.bookings("ws", SchedulingBookingFilter(status = " "))
        assertSent(SchedulingAdminOp.BOOKINGS_LIST, "{}")

        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.bookingAnswers("ws", "b1"))
        assertSent(SchedulingAdminOp.BOOKINGS_ANSWERS, """{"id":"b1"}""")
        repository.cancelBooking("ws", "b1", "sick")
        assertSent(SchedulingAdminOp.BOOKINGS_CANCEL, """{"id":"b1","reason":"sick"}""")
        repository.cancelBooking("ws", "b1", "  ")
        assertSent(SchedulingAdminOp.BOOKINGS_CANCEL, """{"id":"b1"}""")
        repository.rescheduleBooking("ws", "b1", "2026-10-02T13:00:00Z")
        assertSent(SchedulingAdminOp.BOOKINGS_RESCHEDULE, """{"id":"b1","start_at":"2026-10-02T13:00:00Z"}""")
        repository.reassignBooking("ws", "b1", "u2")
        assertSent(SchedulingAdminOp.BOOKINGS_REASSIGN, """{"id":"b1","host_id":"u2"}""")
    }

    @Test
    fun `availability rules and overrides`() = runTest {
        api.value(SchedulingAdminOp.AVAILABILITY_RULES_LIST, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST, SchedulingItems(listOf<Any>()))
        repository.availabilityRules("ws")
        assertSent(SchedulingAdminOp.AVAILABILITY_RULES_LIST, "{}")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.availabilityRules("ws", "et1"))
        assertSent(SchedulingAdminOp.AVAILABILITY_RULES_LIST, """{"event_type_id":"et1"}""")
        repository.createAvailabilityRule("ws", null, 1, "09:00", "17:00")
        assertSent(
            SchedulingAdminOp.AVAILABILITY_RULES_CREATE,
            """{"day_of_week":1,"start_time":"09:00","end_time":"17:00"}""",
        )
        repository.patchAvailabilityRule("ws", "r1", 2, "10:00", "11:00")
        assertSent(
            SchedulingAdminOp.AVAILABILITY_RULES_PATCH,
            """{"id":"r1","day_of_week":2,"start_time":"10:00","end_time":"11:00"}""",
        )
        repository.patchAvailabilityRule("ws", "r1")
        assertSent(SchedulingAdminOp.AVAILABILITY_RULES_PATCH, """{"id":"r1"}""")
        repository.deleteAvailabilityRule("ws", "r1")
        assertSent(SchedulingAdminOp.AVAILABILITY_RULES_DELETE, """{"id":"r1"}""")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.availabilityOverrides("ws"))
        assertSent(SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST, "{}")
        repository.createAvailabilityOverride(
            "ws",
            SchedulingOverrideDraft("2026-10-02", "custom_hours", "2026-10-04", "09:00", "12:00"),
        )
        assertSent(
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
            """{"date":"2026-10-02","reason":"custom_hours","end_date":"2026-10-04","start_time":"09:00",
               "end_time":"12:00"}""",
        )
        repository.createAvailabilityOverride("ws", SchedulingOverrideDraft("2026-10-05", "day_off"))
        assertSent(SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE, """{"date":"2026-10-05","reason":"day_off"}""")
        repository.deleteAvailabilityOverride("ws", "o1")
        assertSent(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE, """{"id":"o1"}""")
        repository.deleteAvailabilityOverrideGroup("ws", "g1")
        assertSent(SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE_GROUP, """{"groupId":"g1"}""")
    }

    @Test
    fun `event types`() = runTest {
        api.value(SchedulingAdminOp.EVENT_TYPES_LIST, SchedulingItems(listOf<Any>()))
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.eventTypes("ws"))
        assertSent(SchedulingAdminOp.EVENT_TYPES_LIST, "{}")
        repository.eventType("ws", "intro")
        assertSent(SchedulingAdminOp.EVENT_TYPES_GET, """{"slug":"intro"}""")
        repository.createEventType(
            "ws",
            SchedulingEventTypeDraft(
                slug = "intro", name = "Intro", durationMinutes = 30, description = "d", slotIntervalMinutes = 15,
                locationType = "phone", locationValue = "+1", routingMode = "single", bufferBeforeMinutes = 5,
                bufferAfterMinutes = 10, minNoticeMinutes = 60, maxFutureDays = 30, maxActiveBookings = 2,
                showTakenSlots = false,
            ),
        )
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_CREATE,
            """{"slug":"intro","name":"Intro","duration_minutes":30,"description":"d","slot_interval_minutes":15,
               "location_type":"phone","location_value":"+1","routing_mode":"single","buffer_before_minutes":5,
               "buffer_after_minutes":10,"min_notice_minutes":60,"max_future_days":30,"max_active_bookings":2,
               "show_taken_slots":false}""",
        )
        repository.createEventType("ws", SchedulingEventTypeDraft("a", "A", 15))
        assertSent(SchedulingAdminOp.EVENT_TYPES_CREATE, """{"slug":"a","name":"A","duration_minutes":15}""")
        repository.patchEventType(
            "ws",
            "intro",
            SchedulingEventTypeChanges(
                name = "n", description = "d", durationMinutes = 1, slotIntervalMinutes = 2, locationType = "lt",
                locationValue = "lv", routingMode = "rm", rrStrategy = "rr", bufferBeforeMinutes = 3,
                bufferAfterMinutes = 4, minNoticeMinutes = 5, maxFutureDays = 6, maxActiveBookings = 7,
                isActive = true, isPublic = false, showTakenSlots = true, archived = false, msgConfirmation = "mc",
                msgCancellation = "mx", msgReschedule = "mr", msgReminder = "mm", msgGreeting = "mg",
                subjConfirmation = "sc", subjCancellation = "sx", subjReschedule = "sr", subjReminder = "sm",
                reminders = listOf(60, 1440),
            ),
        )
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_PATCH,
            """{"slug":"intro","name":"n","description":"d","duration_minutes":1,"slot_interval_minutes":2,
               "location_type":"lt","location_value":"lv","routing_mode":"rm","rr_strategy":"rr",
               "buffer_before_minutes":3,"buffer_after_minutes":4,"min_notice_minutes":5,"max_future_days":6,
               "max_active_bookings":7,"is_active":true,"is_public":false,"show_taken_slots":true,"archived":false,
               "msg_confirmation":"mc","msg_cancellation":"mx","msg_reschedule":"mr","msg_reminder":"mm",
               "msg_greeting":"mg","subj_confirmation":"sc","subj_cancellation":"sx","subj_reschedule":"sr",
               "subj_reminder":"sm","reminders":[60,1440]}""",
        )
        repository.patchEventType("ws", "intro", SchedulingEventTypeChanges())
        assertSent(SchedulingAdminOp.EVENT_TYPES_PATCH, """{"slug":"intro"}""")
        repository.deleteEventType("ws", "intro")
        assertSent(SchedulingAdminOp.EVENT_TYPES_DELETE, """{"slug":"intro"}""")
        repository.sendEventTypeTestEmail("ws", "intro", "confirmation")
        assertSent(SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL, """{"slug":"intro","type":"confirmation"}""")
        repository.eventTypeSlots("ws", "intro", "2026-10-02", "2026-10-03", "America/Toronto")
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_SLOTS,
            """{"slug":"intro","from":"2026-10-02","to":"2026-10-03","tz":"America/Toronto"}""",
        )
        repository.eventTypeSlots("ws", "intro")
        assertSent(SchedulingAdminOp.EVENT_TYPES_SLOTS, """{"slug":"intro"}""")
    }

    @Test
    fun `event type hosts and questions`() = runTest {
        api.value(SchedulingAdminOp.EVENT_TYPES_HOSTS_GET, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST, SchedulingItems(listOf<Any>()))
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.eventTypeHosts("ws", "intro"))
        assertSent(SchedulingAdminOp.EVENT_TYPES_HOSTS_GET, """{"slug":"intro"}""")
        val put = repository.putEventTypeHosts("ws", "intro", listOf(SchedulingHostAssignment("u1", "required", 2)))
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), put)
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT,
            """{"slug":"intro","hosts":[{"user_id":"u1","role":"required","priority":2}]}""",
        )
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.eventTypeQuestions("ws", "intro"))
        assertSent(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST, """{"slug":"intro"}""")
        repository.createEventTypeQuestion(
            "ws",
            "intro",
            SchedulingQuestionDraft("Size", "select", true, listOf("S", "M"), 3),
        )
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE,
            """{"slug":"intro","label":"Size","type":"select","required":true,"options":["S","M"],"position":3}""",
        )
        repository.createEventTypeQuestion("ws", "intro", SchedulingQuestionDraft("Why", "text", false))
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE,
            """{"slug":"intro","label":"Why","type":"text","required":false}""",
        )
        repository.patchEventTypeQuestion(
            "ws",
            "intro",
            "q1",
            SchedulingQuestionChanges("L", "checkbox", listOf("a"), false, 1),
        )
        assertSent(
            SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH,
            """{"slug":"intro","id":"q1","label":"L","type":"checkbox","options":["a"],"required":false,
               "position":1}""",
        )
        repository.patchEventTypeQuestion("ws", "intro", "q1", SchedulingQuestionChanges())
        assertSent(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH, """{"slug":"intro","id":"q1"}""")
        repository.deleteEventTypeQuestion("ws", "intro", "q1")
        assertSent(SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE, """{"slug":"intro","id":"q1"}""")
    }

    @Test
    fun `calendars, including the account and account_email split`() = runTest {
        api.value(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            SchedulingCalendarSelections(listOf(SchedulingCalendarSelection("c1", "Work"))),
        )
        repository.calendarStatus("ws")
        assertSent(SchedulingAdminOp.CALENDAR_STATUS, "{}")
        repository.connectCaldav("ws", "me@x", "app-pass", "icloud", "https://caldav.x")
        assertSent(
            SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
            """{"username":"me@x","app_password":"app-pass","preset":"icloud","server_url":"https://caldav.x"}""",
        )
        repository.connectCaldav("ws", "me@x", "app-pass")
        assertSent(SchedulingAdminOp.CALENDAR_CALDAV_CONNECT, """{"username":"me@x","app_password":"app-pass"}""")
        val listed = repository.connectionCalendars("ws", "k1", "google", "me@x")
        assertEquals(listOf(SchedulingCalendarSelection("c1", "Work")), listed.valueOrNull())
        assertSent(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            """{"id":"k1","provider":"google","account":"me@x"}""",
        )
        repository.setConnectionCalendars(
            "ws",
            "k1",
            "google",
            listOf(
                SchedulingCalendarSelection("c1", "Work", true, true, true, false),
                SchedulingCalendarSelection("c2", "Home"),
            ),
            "me@x",
        )
        assertSent(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT,
            """{"id":"k1","provider":"google","account_email":"me@x","calendars":[
               {"id":"c1","name":"Work","primary":true,"writable":true,"check_conflicts":true,"is_destination":false},
               {"id":"c2","name":"Home"}]}""",
        )
        repository.setDestinationConnection("ws", "k1", "google", "me@x")
        assertSent(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION,
            """{"id":"k1","provider":"google","account_email":"me@x"}""",
        )
        repository.deleteCalendarConnection("ws", "k1", "google")
        assertSent(SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE, """{"id":"k1","provider":"google"}""")
        repository.zoomStatus("ws")
        assertSent(SchedulingAdminOp.ZOOM_STATUS, "{}")
        // The account is optional on the read and both writes.
        repository.connectionCalendars("ws", "k1", "caldav")
        assertSent(SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET, """{"id":"k1","provider":"caldav"}""")
        repository.setConnectionCalendars("ws", "k1", "caldav", emptyList())
        assertSent(
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT,
            """{"id":"k1","provider":"caldav","calendars":[]}""",
        )
        repository.setDestinationConnection("ws", "k1", "caldav")
        assertSent(SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION, """{"id":"k1","provider":"caldav"}""")
    }

    @Test
    fun `users, teams and members, including the user_id and userId split`() = runTest {
        api.value(SchedulingAdminOp.USERS_UPCOMING_BOOKINGS, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.TEAMS_LIST, SchedulingItems(listOf<Any>()))
        repository.schedulerUsers("ws")
        assertSent(SchedulingAdminOp.USERS_LIST, "{}")
        repository.schedulerUsers("ws", includeArchived = true)
        assertSent(SchedulingAdminOp.USERS_LIST, """{"include_archived":true}""")
        repository.archiveSchedulerUser("ws", "u1")
        assertSent(SchedulingAdminOp.USERS_ARCHIVE, """{"id":"u1"}""")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.upcomingBookings("ws", "u1"))
        assertSent(SchedulingAdminOp.USERS_UPCOMING_BOOKINGS, """{"id":"u1"}""")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.teams("ws"))
        assertSent(SchedulingAdminOp.TEAMS_LIST, "{}")
        repository.team("ws", "t1")
        assertSent(SchedulingAdminOp.TEAMS_GET, """{"id":"t1"}""")
        repository.createTeam("ws", "Sales", "sales")
        assertSent(SchedulingAdminOp.TEAMS_CREATE, """{"name":"Sales","slug":"sales"}""")
        repository.createTeam("ws", "Sales")
        assertSent(SchedulingAdminOp.TEAMS_CREATE, """{"name":"Sales"}""")
        repository.patchTeam("ws", "t1", "S", "s")
        assertSent(SchedulingAdminOp.TEAMS_PATCH, """{"id":"t1","name":"S","slug":"s"}""")
        repository.patchTeam("ws", "t1")
        assertSent(SchedulingAdminOp.TEAMS_PATCH, """{"id":"t1"}""")
        repository.deleteTeam("ws", "t1")
        assertSent(SchedulingAdminOp.TEAMS_DELETE, """{"id":"t1"}""")
        repository.addTeamMember("ws", "t1", "u1", 3)
        assertSent(SchedulingAdminOp.TEAMS_MEMBERS_ADD, """{"id":"t1","user_id":"u1","routing_priority":3}""")
        repository.addTeamMember("ws", "t1", "u1")
        assertSent(SchedulingAdminOp.TEAMS_MEMBERS_ADD, """{"id":"t1","user_id":"u1"}""")
        repository.setTeamMemberPriority("ws", "t1", "u1", 0)
        assertSent(SchedulingAdminOp.TEAMS_MEMBERS_PATCH, """{"id":"t1","userId":"u1","routing_priority":0}""")
        repository.removeTeamMember("ws", "t1", "u1")
        assertSent(SchedulingAdminOp.TEAMS_MEMBERS_REMOVE, """{"id":"t1","userId":"u1"}""")
    }

    @Test
    fun `developer keys, apps and webhooks`() = runTest {
        api.value(SchedulingAdminOp.API_KEYS_LIST, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.OAUTH_CONNECTIONS_LIST, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.WEBHOOKS_LIST, SchedulingItems(listOf<Any>()))
        api.value(SchedulingAdminOp.WEBHOOKS_DELIVERIES, SchedulingItems(listOf<Any>()))
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.apiKeys("ws"))
        assertSent(SchedulingAdminOp.API_KEYS_LIST, "{}")
        repository.createApiKey("ws", "CI")
        assertSent(SchedulingAdminOp.API_KEYS_CREATE, """{"name":"CI"}""")
        repository.deleteApiKey("ws", "k1")
        assertSent(SchedulingAdminOp.API_KEYS_DELETE, """{"id":"k1"}""")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.oauthConnections("ws"))
        assertSent(SchedulingAdminOp.OAUTH_CONNECTIONS_LIST, "{}")
        repository.deleteOAuthConnection("ws", "o1")
        assertSent(SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE, """{"id":"o1"}""")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.webhooks("ws"))
        assertSent(SchedulingAdminOp.WEBHOOKS_LIST, "{}")
        repository.createWebhook("ws", "https://h", listOf(SchedulingWebhookEvent.BOOKING_CREATED), listOf("id"))
        assertSent(
            SchedulingAdminOp.WEBHOOKS_CREATE,
            """{"url":"https://h","events":["booking.created"],"fields":["id"]}""",
        )
        repository.createWebhook("ws", "https://h", emptyList())
        assertSent(SchedulingAdminOp.WEBHOOKS_CREATE, """{"url":"https://h","events":[]}""")
        repository.updateWebhook("ws", "w1", listOf("booking.cancelled"), listOf("id"))
        assertSent(SchedulingAdminOp.WEBHOOKS_PATCH, """{"id":"w1","events":["booking.cancelled"],"fields":["id"]}""")
        repository.updateWebhook("ws", "w1")
        assertSent(SchedulingAdminOp.WEBHOOKS_PATCH, """{"id":"w1"}""")
        repository.deleteWebhook("ws", "w1")
        assertSent(SchedulingAdminOp.WEBHOOKS_DELETE, """{"id":"w1"}""")
        assertEquals(SchedulingAdminResult.Ok(emptyList<Any>()), repository.webhookDeliveries("ws", "w1"))
        assertSent(SchedulingAdminOp.WEBHOOKS_DELIVERIES, """{"id":"w1"}""")
    }

    @Test
    fun `profile, branding and the booking assistant`() = runTest {
        repository.me("ws")
        assertSent(SchedulingAdminOp.ME_GET, "{}")
        repository.updateMe(
            "ws",
            SchedulingMeUpdate(
                name = "N", timezone = "UTC", timeFormat = "24h", weekStart = 1, dateFormat = "dmy",
                notifyConfirmation = true, notifyCancellation = false, notifyReschedule = true,
                notifyReminder = false, notifyHostBooking = true, notifyHostCancel = false, notifyHostReschedule = true,
            ),
        )
        assertSent(
            SchedulingAdminOp.ME_PATCH,
            """{"name":"N","timezone":"UTC","time_format":"24h","week_start":1,"date_format":"dmy",
               "notify_confirmation":true,"notify_cancellation":false,"notify_reschedule":true,
               "notify_reminder":false,"notify_host_booking":true,"notify_host_cancel":false,
               "notify_host_reschedule":true}""",
        )
        repository.updateMe("ws", SchedulingMeUpdate())
        assertSent(SchedulingAdminOp.ME_PATCH, "{}")
        repository.deleteAvatar("ws")
        assertSent(SchedulingAdminOp.ME_AVATAR_DELETE, "{}")
        repository.branding("ws")
        assertSent(SchedulingAdminOp.SETTINGS_BRANDING_GET, "{}")
        val branding = SchedulingBranding(
            businessName = "Acme", logoUrl = "", logoHeight = 40, logoOpacity = 100, bannerUrl = "",
            bannerOpacity = 60, privacyUrl = "https://p", termsUrl = "https://t", fallbackLocale = "en",
        )
        repository.updateBranding("ws", SchedulingBrandingUpdate.from(branding))
        assertSent(
            SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
            """{"business_name":"Acme","logo_height":40,"logo_opacity":100,"banner_opacity":60,
               "privacy_url":"https://p","terms_url":"https://t","fallback_locale":"en"}""",
        )
        repository.deleteBrandingLogo("ws")
        assertSent(SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE, "{}")
        repository.deleteBrandingBanner("ws")
        assertSent(SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE, "{}")
        repository.llmSettings("ws")
        assertSent(SchedulingAdminOp.SETTINGS_LLM_GET, "{}")
        repository.updateLlmSettings("ws", enabled = true, extraInstructions = "Be brief")
        assertSent(SchedulingAdminOp.SETTINGS_LLM_PATCH, """{"enabled":true,"extra_instructions":"Be brief"}""")
        repository.updateLlmSettings("ws")
        assertSent(SchedulingAdminOp.SETTINGS_LLM_PATCH, "{}")
    }
}
