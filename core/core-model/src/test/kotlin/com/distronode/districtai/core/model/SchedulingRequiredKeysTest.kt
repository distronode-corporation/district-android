package com.distronode.districtai.core.model

import kotlinx.serialization.builtins.serializer
import org.junit.Test

/**
 * The required half of every scheduling-admin row, pinned key by key.
 *
 * WHY THIS IS THE CONTRACT WORTH PINNING. These rows pass through our RPC from a scheduler fork, and
 * their split between required and optional keys is the fixtures' rather than a preference (see
 * [SchedulingEventType]). A key quietly widened to a default would let a structurally wrong 200
 * decode into a row with no name, and no decode assertion would notice, because every committed
 * fixture sends the key anyway. [WireMirror.assertRequiredKeys] builds each row from its required
 * properties only, pins the production encoding of that row exactly, and then removes each key in
 * turn and demands a refusal.
 *
 * The rows are built in Kotlin rather than read from a fixture on purpose: the fixtures always
 * carry the optional keys too, so only a hand-built row shows that nothing optional is written
 * when it is absent.
 */
class SchedulingRequiredKeysTest {

    @Test
    fun `an event type requires exactly its id, slug, name and duration`() {
        WireMirror.assertRequiredKeys(
            SchedulingEventType.serializer(),
            SchedulingEventType(id = "et-1", slug = "intro", name = "Intro call", durationMinutes = 30),
            """{"id":"et-1","slug":"intro","name":"Intro call","duration_minutes":30}""",
        )
    }

    @Test
    fun `event type hosts, questions, slots and the test email require their identifying keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingHost.serializer(),
            SchedulingHost(
                userId = "u-1",
                name = "Ada",
                email = "ada@example.com",
                role = "host",
                priority = 2,
                archived = false,
            ),
            """{"user_id":"u-1","name":"Ada","email":"ada@example.com","role":"host","priority":2,"archived":false}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingQuestion.serializer(),
            SchedulingQuestion(
                id = "q-1",
                eventTypeId = "et-1",
                label = "Company",
                type = "text",
                required = true,
                position = 0,
            ),
            """{"id":"q-1","event_type_id":"et-1","label":"Company","type":"text","required":true,"position":0}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingSlot.serializer(),
            SchedulingSlot(start = "2026-09-14T13:00:00Z", end = "2026-09-14T13:30:00Z"),
            """{"start":"2026-09-14T13:00:00Z","end":"2026-09-14T13:30:00Z"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingSlotHost.serializer(),
            SchedulingSlotHost(name = "Ada", avatarUrl = "https://example.com/a.png"),
            """{"name":"Ada","avatar_url":"https://example.com/a.png"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingTestEmailResult.serializer(),
            SchedulingTestEmailResult(sent = true, to = "ada@example.com"),
            """{"sent":true,"to":"ada@example.com"}""",
        )
    }

    @Test
    fun `availability rules and override groups require every scheduling key`() {
        WireMirror.assertRequiredKeys(
            SchedulingAvailabilityRule.serializer(),
            SchedulingAvailabilityRule(id = "r-1", dayOfWeek = 1, startTime = "09:00", endTime = "17:00"),
            """{"id":"r-1","day_of_week":1,"start_time":"09:00","end_time":"17:00"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingOverrideGroup.serializer(),
            SchedulingOverrideGroup(
                groupId = "g-1",
                reason = "Holiday",
                start = "2026-12-24",
                end = "2026-12-26",
                days = 3,
            ),
            """{"group_id":"g-1","reason":"Holiday","start":"2026-12-24","end":"2026-12-26","days":3}""",
        )
    }

    @Test
    fun `bookings, their answers and their counts require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingBooking.serializer(),
            SchedulingBooking(
                id = "b-1",
                startAt = "2026-09-14T13:00:00Z",
                endAt = "2026-09-14T13:30:00Z",
                status = "confirmed",
            ),
            """{"id":"b-1","start_at":"2026-09-14T13:00:00Z","end_at":"2026-09-14T13:30:00Z","status":"confirmed"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingBookingAnswer.serializer(),
            SchedulingBookingAnswer(questionId = "q-1", label = "Company", type = "text", value = "Acme"),
            """{"question_id":"q-1","label":"Company","type":"text","value":"Acme"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingBookingCounts.serializer(),
            SchedulingBookingCounts(upcoming = 4, past = 9),
            """{"upcoming":4,"past":9}""",
        )
    }

    @Test
    fun `calendar connections, selections and the zoom status require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingCalendarConnection.serializer(),
            SchedulingCalendarConnection(
                id = "c-1",
                provider = "google",
                accountEmail = "ada@example.com",
                isDestination = true,
                checkConflicts = false,
            ),
            """{"id":"c-1","provider":"google","account_email":"ada@example.com",""" +
                """"is_destination":true,"check_conflicts":false}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingCaldavConnection.serializer(),
            SchedulingCaldavConnection(connected = true, accountEmail = "ada@fastmail.test"),
            """{"connected":true,"account_email":"ada@fastmail.test"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingCalendarSelections.serializer(),
            SchedulingCalendarSelections(calendars = listOf(SchedulingCalendarSelection(id = "cal-1", name = "Work"))),
            """{"calendars":[{"id":"cal-1","name":"Work"}]}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingZoomStatus.serializer(),
            SchedulingZoomStatus(configured = true, connected = false),
            """{"configured":true,"connected":false}""",
        )
    }

    @Test
    fun `api keys, oauth connections, webhooks and deliveries require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingApiKey.serializer(),
            SchedulingApiKey(id = "k-1", name = "CI"),
            """{"id":"k-1","name":"CI"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingOAuthConnection.serializer(),
            SchedulingOAuthConnection(id = "o-1", clientName = "Zapier"),
            """{"id":"o-1","client_name":"Zapier"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingWebhook.serializer(),
            SchedulingWebhook(id = "w-1", url = "https://hooks.example.com", events = listOf("booking.created")),
            """{"id":"w-1","url":"https://hooks.example.com","events":["booking.created"]}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingWebhookCreated.serializer(),
            SchedulingWebhookCreated(id = "w-2", url = "https://hooks.example.com", events = emptyList()),
            """{"id":"w-2","url":"https://hooks.example.com","events":[]}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingWebhookDelivery.serializer(),
            SchedulingWebhookDelivery(
                id = "d-1",
                webhookId = "w-1",
                event = "booking.created",
                status = "delivered",
                attemptCount = 1,
            ),
            """{"id":"d-1","webhook_id":"w-1","event":"booking.created","status":"delivered","attempt_count":1}""",
        )
    }

    @Test
    fun `branding, locale options and the notes model require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingBranding.serializer(),
            SchedulingBranding(
                businessName = "Acme",
                logoUrl = "https://example.com/logo.png",
                logoHeight = 48,
                logoOpacity = 100,
                bannerUrl = "",
                bannerOpacity = 80,
                privacyUrl = "",
                termsUrl = "https://example.com/terms",
                fallbackLocale = "en",
            ),
            """{"business_name":"Acme","logo_url":"https://example.com/logo.png","logo_height":48,""" +
                """"logo_opacity":100,"banner_url":"","banner_opacity":80,"privacy_url":"",""" +
                """"terms_url":"https://example.com/terms","fallback_locale":"en"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingLocaleOption.serializer(),
            SchedulingLocaleOption(code = "fr", name = "French"),
            """{"code":"fr","name":"French"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingLlmSettings.serializer(),
            SchedulingLlmSettings(enabled = true, extraInstructions = ""),
            """{"enabled":true,"extra_instructions":""}""",
        )
    }

    @Test
    fun `the signed-in scheduling member requires every preference key`() {
        WireMirror.assertRequiredKeys(
            SchedulingMe.serializer(),
            SchedulingMe(
                id = "u-1",
                email = "ada@example.com",
                name = "Ada",
                timezone = "America/Toronto",
                timeFormat = "24h",
                weekStart = 1,
                dateFormat = "YYYY-MM-DD",
                isAdmin = true,
                isOwner = false,
                role = "admin",
                notifyConfirmation = true,
                notifyCancellation = true,
                notifyReschedule = false,
                notifyReminder = true,
                notifyHostBooking = false,
                notifyHostCancel = true,
                notifyHostReschedule = false,
            ),
            """{"id":"u-1","email":"ada@example.com","name":"Ada","timezone":"America/Toronto",""" +
                """"time_format":"24h","week_start":1,"date_format":"YYYY-MM-DD","is_admin":true,""" +
                """"is_owner":false,"role":"admin","notify_confirmation":true,"notify_cancellation":true,""" +
                """"notify_reschedule":false,"notify_reminder":true,"notify_host_booking":false,""" +
                """"notify_host_cancel":true,"notify_host_reschedule":false}""",
        )
    }

    @Test
    fun `teams, their members, users and upcoming bookings require their keys`() {
        WireMirror.assertRequiredKeys(
            SchedulingTeam.serializer(),
            SchedulingTeam(id = "t-1", name = "Sales", slug = "sales"),
            """{"id":"t-1","name":"Sales","slug":"sales"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingTeamMember.serializer(),
            SchedulingTeamMember(
                id = "u-1",
                name = "Ada",
                email = "ada@example.com",
                routingPriority = 1,
                archived = false,
            ),
            """{"id":"u-1","name":"Ada","email":"ada@example.com","routing_priority":1,"archived":false}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingUserTeam.serializer(),
            SchedulingUserTeam(id = "t-1", name = "Sales"),
            """{"id":"t-1","name":"Sales"}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingUser.serializer(),
            SchedulingUser(
                id = "u-1",
                email = "ada@example.com",
                name = "Ada",
                isAdmin = false,
                isOwner = false,
                role = "member",
                archived = true,
            ),
            """{"id":"u-1","email":"ada@example.com","name":"Ada","is_admin":false,"is_owner":false,""" +
                """"role":"member","archived":true}""",
        )
        WireMirror.assertRequiredKeys(
            SchedulingUpcomingBooking.serializer(),
            SchedulingUpcomingBooking(
                id = "b-1",
                startAt = "2026-09-14T13:00:00Z",
                endAt = "2026-09-14T13:30:00Z",
                eventTypeName = "Intro call",
                eventTypeSlug = "intro",
                attendeeName = "Grace",
                attendeeEmail = "grace@example.com",
            ),
            """{"id":"b-1","start_at":"2026-09-14T13:00:00Z","end_at":"2026-09-14T13:30:00Z",""" +
                """"event_type_name":"Intro call","event_type_slug":"intro","attendee_name":"Grace",""" +
                """"attendee_email":"grace@example.com"}""",
        )
    }

    @Test
    fun `the success envelope requires its flag and its data`() {
        WireMirror.assertRequiredKeys(
            SchedulingAdminSuccess.serializer(Int.serializer()),
            SchedulingAdminSuccess(ok = true, data = 7),
            """{"ok":true,"data":7}""",
        )
    }
}
