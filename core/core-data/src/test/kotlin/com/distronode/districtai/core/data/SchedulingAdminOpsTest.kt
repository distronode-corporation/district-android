package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingOverrideCreated
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every one of the 75 typed ops: the op NAME it sends and the `params` object it builds.
 *
 * ⛔ THIS IS THE ONLY GATE ON THE ONE THING THE COMPILER CANNOT SEE. The op crosses the wire as a
 * string and `params` as an untyped object, so a wrapper that named the wrong op, misspelled a key
 * or dropped a path key compiles perfectly and fails as a **400** at runtime. Reading the enum back
 * would prove nothing; each expectation below is typed out against `admin-ops.ts`.
 *
 * ⛔ AND THE PATH KEYS ARE ASSERTED PRESENT. `slug`, `id` and `groupId` are BOTH the address and a
 * required member of the op's schema; the route strips them AFTER validating, so a wrapper that
 * removed one gets a 400 naming the very field it was being tidy about.
 *
 * ⚠️ THE PAYLOADS ARE MINIMAL RATHER THAN REALISTIC, deliberately. Realism is the contract tests'
 * job in core-model, which read the committed fixtures; what these prove is that each wrapper named
 * the RESPONSE TYPE that matches its op, which a minimal body of the right shape establishes and a
 * fixture would only restate.
 */
/**
 * ⚠️ `LargeClass` IS SUPPRESSED DELIBERATELY, AND SPLITTING THIS WOULD MAKE IT WORSE. The class
 * covers the scheduling-admin catalogue op by op, so its size tracks the SERVER's allowlist rather
 * than any decision taken here; cutting it at detekt's six-hundred-line mark would put sibling ops
 * in different files and leave the next person guessing which half holds the one they want. The
 * ceiling stays on for every other class in the module.
 */
@Suppress("LargeClass")
class SchedulingAdminOpsTest {

    private val api = FakeSchedulingAdminApi()
    private val repository = SchedulingAdminRepository(api)

    // ── Event types ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the event-type reads and the delete name their ops and carry the slug`() = runTest {
        val list = call(
            EVENT_TYPES,
            SchedulingAdminOp.EVENT_TYPES_LIST,
            obj(),
        ) { repository.listEventTypes(WORKSPACE) }
        assertEquals(1, list.size)
        assertEquals("phone-consultation", list.first().slug)

        val single = call(
            EVENT_TYPE,
            SchedulingAdminOp.EVENT_TYPES_GET,
            obj("slug" to s("phone-consultation")),
        ) { repository.eventType(WORKSPACE, "phone-consultation") }
        assertEquals(30, single.durationMinutes)

        val deleted = call(
            NO_CONTENT,
            SchedulingAdminOp.EVENT_TYPES_DELETE,
            obj("slug" to s("phone-consultation")),
        ) { repository.deleteEventType(WORKSPACE, "phone-consultation") }
        assertTrue(deleted.ok)
    }

    @Test
    fun `creating an event type sends the three required keys and drops every unset one`() =
        runTest {
            exercise(
                EVENT_TYPE,
                SchedulingAdminOp.EVENT_TYPES_CREATE,
                obj(
                    "slug" to s("site-visit"),
                    "name" to s("Site visit"),
                    "duration_minutes" to n(60),
                    "location_type" to s("in_person"),
                ),
            ) {
                repository.createEventType(
                    WORKSPACE,
                    SchedulingEventTypeDraft(
                        slug = "site-visit",
                        name = "Site visit",
                        durationMinutes = 60,
                        locationType = "in_person",
                    ),
                )
            }
        }

    @Test
    fun `patching an event type keeps the slug as an address and sends only what changed`() =
        runTest {
            exercise(
                EVENT_TYPE,
                SchedulingAdminOp.EVENT_TYPES_PATCH,
                obj(
                    "slug" to s("site-visit"),
                    "is_active" to b(false),
                    "reminders" to JsonArray(listOf(n(1440))),
                ),
            ) {
                repository.patchEventType(
                    WORKSPACE,
                    "site-visit",
                    SchedulingEventTypeChanges(isActive = false, reminders = listOf(1440)),
                )
            }
        }

    @Test
    fun `hosts read and write name their ops, and the PUT sends the whole roster`() = runTest {
        val hosts = call(
            HOSTS,
            SchedulingAdminOp.EVENT_TYPES_HOSTS_GET,
            obj("slug" to s("site-visit")),
        ) { repository.eventTypeHosts(WORKSPACE, "site-visit") }
        assertEquals("required", hosts.first().role)

        exercise(
            HOSTS,
            SchedulingAdminOp.EVENT_TYPES_HOSTS_PUT,
            obj(
                "slug" to s("site-visit"),
                "hosts" to JsonArray(
                    listOf(
                        obj("user_id" to s("u-1"), "role" to s("rotation"), "priority" to n(2)),
                    ),
                ),
            ),
        ) {
            repository.putEventTypeHosts(
                WORKSPACE,
                "site-visit",
                listOf(SchedulingHostAssignment("u-1", "rotation", 2)),
            )
        }
    }

    @Test
    fun `the question ops carry both the slug and the question id where the catalog wants them`() =
        runTest {
            exercise(
                QUESTIONS,
                SchedulingAdminOp.EVENT_TYPES_QUESTIONS_LIST,
                obj("slug" to s("site-visit")),
            ) { repository.eventTypeQuestions(WORKSPACE, "site-visit") }

            exercise(
                QUESTION,
                SchedulingAdminOp.EVENT_TYPES_QUESTIONS_CREATE,
                obj(
                    "slug" to s("site-visit"),
                    "label" to s("Which floor?"),
                    "type" to s("text"),
                    "required" to b(true),
                ),
            ) {
                repository.createEventTypeQuestion(
                    WORKSPACE,
                    "site-visit",
                    SchedulingQuestionDraft(label = "Which floor?", type = "text", required = true),
                )
            }

            exercise(
                QUESTION,
                SchedulingAdminOp.EVENT_TYPES_QUESTIONS_PATCH,
                obj(
                    "slug" to s("site-visit"),
                    "id" to s("q-1"),
                    "options" to JsonArray(listOf(s("a"), s("b"))),
                ),
            ) {
                repository.patchEventTypeQuestion(
                    WORKSPACE,
                    "site-visit",
                    "q-1",
                    SchedulingQuestionChanges(options = listOf("a", "b")),
                )
            }

            exercise(
                NO_CONTENT,
                SchedulingAdminOp.EVENT_TYPES_QUESTIONS_DELETE,
                obj("slug" to s("site-visit"), "id" to s("q-1")),
            ) { repository.deleteEventTypeQuestion(WORKSPACE, "site-visit", "q-1") }
        }

    @Test
    fun `the test email and the slots read carry their own params`() = runTest {
        val email = call(
            """{"sent":true,"to":"contract@distronode.test"}""",
            SchedulingAdminOp.EVENT_TYPES_TEST_EMAIL,
            obj("slug" to s("site-visit"), "type" to s("confirmation")),
        ) { repository.sendEventTypeTestEmail(WORKSPACE, "site-visit", "confirmation") }
        assertTrue(email.sent)

        // ⚠️ `tz`, NOT `timezone`. The catalog abbreviates this one param and nothing else on the
        // surface; the Kotlin argument is spelled out for readability and renamed on the way out.
        exercise(
            """{"slots":[]}""",
            SchedulingAdminOp.EVENT_TYPES_SLOTS,
            obj(
                "slug" to s("site-visit"),
                "from" to s("2026-09-14"),
                "tz" to s("America/Toronto"),
            ),
        ) {
            repository.eventTypeSlots(
                WORKSPACE,
                "site-visit",
                from = "2026-09-14",
                timezone = "America/Toronto",
            )
        }
    }

    // ── Availability ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the rule ops send their filter, their body and their id`() = runTest {
        exercise(
            RULES,
            SchedulingAdminOp.AVAILABILITY_RULES_LIST,
            obj("event_type_id" to s("et-1")),
        ) { repository.availabilityRules(WORKSPACE, "et-1") }

        // ⚠️ THE UNFILTERED READ OMITS THE KEY, which is "every rule" rather than "the
        // tenancy-wide ones". An explicit null would be a value the server validates.
        exercise(RULES, SchedulingAdminOp.AVAILABILITY_RULES_LIST, obj()) {
            repository.availabilityRules(WORKSPACE)
        }

        exercise(
            RULE,
            SchedulingAdminOp.AVAILABILITY_RULES_CREATE,
            obj(
                "day_of_week" to n(1),
                "start_time" to s("09:00"),
                "end_time" to s("17:00"),
            ),
        ) { repository.createAvailabilityRule(WORKSPACE, null, 1, "09:00", "17:00") }

        exercise(
            RULE,
            SchedulingAdminOp.AVAILABILITY_RULES_PATCH,
            obj("id" to s("r-1"), "start_time" to s("10:00")),
        ) { repository.patchAvailabilityRule(WORKSPACE, "r-1", startTime = "10:00") }

        exercise(
            NO_CONTENT,
            SchedulingAdminOp.AVAILABILITY_RULES_DELETE,
            obj("id" to s("r-1")),
        ) { repository.deleteAvailabilityRule(WORKSPACE, "r-1") }
    }

    @Test
    fun `the override ops cover both create shapes and the camelCase group delete`() = runTest {
        exercise(OVERRIDES, SchedulingAdminOp.AVAILABILITY_OVERRIDES_LIST, obj()) {
            repository.availabilityOverrides(WORKSPACE)
        }

        val single = call(
            """{"id":"o-1","date":"2026-09-25","is_available":false,"reason":"day_off"}""",
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
            obj("date" to s("2026-09-25"), "reason" to s("day_off")),
        ) {
            repository.createAvailabilityOverride(
                WORKSPACE,
                SchedulingOverrideDraft(date = "2026-09-25", reason = "day_off"),
            )
        }
        assertTrue(single is SchedulingOverrideCreated.Single)

        val range = call(
            """{"group_id":"g-1","reason":"out_of_office","start":"2026-12-24",
               |"end":"2026-12-31","days":8}
            """.trimMargin(),
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_CREATE,
            obj(
                "date" to s("2026-12-24"),
                "reason" to s("out_of_office"),
                "end_date" to s("2026-12-31"),
            ),
        ) {
            repository.createAvailabilityOverride(
                WORKSPACE,
                SchedulingOverrideDraft(
                    date = "2026-12-24",
                    reason = "out_of_office",
                    endDate = "2026-12-31",
                ),
            )
        }
        assertTrue(range is SchedulingOverrideCreated.Range)

        exercise(
            OVERRIDE,
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_PATCH,
            obj("id" to s("o-1"), "reason" to s("custom_hours")),
        ) { repository.patchAvailabilityOverride(WORKSPACE, "o-1", reason = "custom_hours") }

        exercise(
            NO_CONTENT,
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE,
            obj("id" to s("o-1")),
        ) { repository.deleteAvailabilityOverride(WORKSPACE, "o-1") }

        // ⛔ `groupId` IS CAMELCASE HERE AND NOWHERE ELSE IN THIS NAMESPACE. A "consistent"
        // `group_id` is a 400 naming a missing required field.
        exercise(
            NO_CONTENT,
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_DELETE_GROUP,
            obj("groupId" to s("g-1")),
        ) { repository.deleteAvailabilityOverrideGroup(WORKSPACE, "g-1") }
    }

    // ── Bookings ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the booking query spells scope as a presence flag and event_type as a slug`() = runTest {
        exercise(
            """{"items":[]}""",
            SchedulingAdminOp.BOOKINGS_LIST,
            obj(
                "status" to s("confirmed"),
                "when" to s("upcoming"),
                "event_type" to s("site-visit"),
                "limit" to n(50),
                "offset" to n(0),
                "scope" to s("all"),
            ),
        ) {
            repository.bookings(
                WORKSPACE,
                SchedulingBookingQuery(
                    status = "confirmed",
                    whenFilter = "upcoming",
                    eventTypeSlug = "site-visit",
                    limit = 50,
                    offset = 0,
                    allHosts = true,
                ),
            )
        }

        // ⛔ `scope` IS OMITTED WHEN FALSE. The server reads PRESENCE, so `scope=false` would be a
        // filter it does not know.
        exercise("""{"items":[]}""", SchedulingAdminOp.BOOKINGS_LIST, obj()) {
            repository.bookings(WORKSPACE)
        }
    }

    @Test
    fun `the per-booking ops all address the booking as id`() = runTest {
        exercise(
            ANSWERS,
            SchedulingAdminOp.BOOKINGS_ANSWERS,
            obj("id" to s("bk-1")),
        ) { repository.bookingAnswers(WORKSPACE, "bk-1") }

        exercise(
            BOOKING,
            SchedulingAdminOp.BOOKINGS_CANCEL,
            obj("id" to s("bk-1"), "reason" to s("postponed")),
        ) { repository.cancelBooking(WORKSPACE, "bk-1", "postponed") }

        exercise(
            BOOKING,
            SchedulingAdminOp.BOOKINGS_RESCHEDULE,
            obj("id" to s("bk-1"), "start_at" to s("2026-09-20T13:00:00Z")),
        ) { repository.rescheduleBooking(WORKSPACE, "bk-1", "2026-09-20T13:00:00Z") }

        exercise(
            BOOKING,
            SchedulingAdminOp.BOOKINGS_REASSIGN,
            obj("id" to s("bk-1"), "host_id" to s("u-2")),
        ) { repository.reassignBooking(WORKSPACE, "bk-1", "u-2") }

        exercise(
            """{"exists":true,"content":"c","status":"ready"}""",
            SchedulingAdminOp.BOOKINGS_NOTES,
            obj("id" to s("bk-1")),
        ) { repository.bookingNotes(WORKSPACE, "bk-1") }

        exercise(
            """{"exists":true,"status":"pending"}""",
            SchedulingAdminOp.BOOKINGS_NOTES_REGENERATE,
            obj("id" to s("bk-1")),
        ) { repository.regenerateBookingNotes(WORKSPACE, "bk-1") }

        exercise(
            """{"exists":false}""",
            SchedulingAdminOp.BOOKINGS_TRANSCRIPT,
            obj("id" to s("bk-1")),
        ) { repository.bookingTranscript(WORKSPACE, "bk-1") }
    }

    // ── Calendars ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the calendar ops send account on the reads and account_email on the writes`() = runTest {
        exercise(
            """{"connected":true,"configured":true,"connections":[]}""",
            SchedulingAdminOp.CALENDAR_STATUS,
            obj(),
        ) { repository.calendarStatus(WORKSPACE) }

        exercise(
            """{"connected":true,"account_email":"a@b.test"}""",
            SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
            obj(
                "username" to s("a@b.test"),
                "app_password" to s("secret"),
                "preset" to s("fastmail"),
            ),
        ) { repository.connectCaldav(WORKSPACE, "a@b.test", "secret", preset = "fastmail") }

        // ⛔ `account` ON THE READ.
        exercise(
            """{"calendars":[{"id":"c","name":"n"}]}""",
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            obj("id" to s("conn-1"), "provider" to s("google"), "account" to s("a@b.test")),
        ) { repository.connectionCalendars(WORKSPACE, "conn-1", "google", "a@b.test") }

        // ⛔ AND `account_email` ON THE WRITE. Copied from `admin-ops.ts` rather than normalised.
        exercise(
            NO_CONTENT,
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT,
            obj(
                "id" to s("conn-1"),
                "provider" to s("google"),
                "account_email" to s("a@b.test"),
                "calendars" to JsonArray(
                    listOf(obj("id" to s("c"), "name" to s("n"), "check_conflicts" to b(true))),
                ),
            ),
        ) {
            repository.setConnectionCalendars(
                WORKSPACE,
                "conn-1",
                "google",
                listOf(SchedulingCalendarSelection(id = "c", name = "n", checkConflicts = true)),
                accountEmail = "a@b.test",
            )
        }

        exercise(
            NO_CONTENT,
            SchedulingAdminOp.CALENDAR_CONNECTIONS_DESTINATION,
            obj("id" to s("conn-1"), "provider" to s("google")),
        ) { repository.setDestinationConnection(WORKSPACE, "conn-1", "google") }

        exercise(
            NO_CONTENT,
            SchedulingAdminOp.CALENDAR_CONNECTIONS_DELETE,
            obj("id" to s("conn-1"), "provider" to s("google")),
        ) { repository.deleteCalendarConnection(WORKSPACE, "conn-1", "google") }

        exercise(
            """{"configured":true,"connected":false}""",
            SchedulingAdminOp.ZOOM_STATUS,
            obj(),
        ) { repository.zoomStatus(WORKSPACE) }
    }

    // ── Users and teams ─────────────────────────────────────────────────────────────────────

    @Test
    fun `the user ops read a bare array and omit include_archived when false`() = runTest {
        val users = call(USERS, SchedulingAdminOp.USERS_LIST, obj()) {
            repository.schedulerUsers(WORKSPACE)
        }
        assertEquals(1, users.size)

        exercise(USERS, SchedulingAdminOp.USERS_LIST, obj("include_archived" to b(true))) {
            repository.schedulerUsers(WORKSPACE, includeArchived = true)
        }

        exercise(
            """{"ok":true,"archived_at":"2026-09-05T12:00:00Z"}""",
            SchedulingAdminOp.USERS_ARCHIVE,
            obj("id" to s("u-1")),
        ) { repository.archiveSchedulerUser(WORKSPACE, "u-1") }

        exercise(
            UPCOMING,
            SchedulingAdminOp.USERS_UPCOMING_BOOKINGS,
            obj("id" to s("u-1")),
        ) { repository.upcomingBookings(WORKSPACE, "u-1") }
    }

    @Test
    fun `the team ops send user_id on the add and userId on the patch and remove`() = runTest {
        exercise(TEAMS, SchedulingAdminOp.TEAMS_LIST, obj()) { repository.teams(WORKSPACE) }

        exercise(TEAM, SchedulingAdminOp.TEAMS_GET, obj("id" to s("t-1"))) {
            repository.team(WORKSPACE, "t-1")
        }

        exercise(TEAM, SchedulingAdminOp.TEAMS_CREATE, obj("name" to s("Sales"))) {
            repository.createTeam(WORKSPACE, "Sales")
        }

        exercise(
            TEAM,
            SchedulingAdminOp.TEAMS_PATCH,
            obj("id" to s("t-1"), "slug" to s("sales")),
        ) { repository.patchTeam(WORKSPACE, "t-1", slug = "sales") }

        exercise(NO_CONTENT, SchedulingAdminOp.TEAMS_DELETE, obj("id" to s("t-1"))) {
            repository.deleteTeam(WORKSPACE, "t-1")
        }

        // ⛔ `user_id` IN THE BODY OF THE ADD.
        exercise(
            TEAM,
            SchedulingAdminOp.TEAMS_MEMBERS_ADD,
            obj("id" to s("t-1"), "user_id" to s("u-1"), "routing_priority" to n(3)),
        ) { repository.addTeamMember(WORKSPACE, "t-1", "u-1", 3) }

        // ⛔ AND `userId` AS A PATH KEY ON THE OTHER TWO. The catalog spells path keys camelCase.
        exercise(
            TEAM,
            SchedulingAdminOp.TEAMS_MEMBERS_PATCH,
            obj("id" to s("t-1"), "userId" to s("u-1"), "routing_priority" to n(0)),
        ) { repository.setTeamMemberPriority(WORKSPACE, "t-1", "u-1", 0) }

        exercise(
            NO_CONTENT,
            SchedulingAdminOp.TEAMS_MEMBERS_REMOVE,
            obj("id" to s("t-1"), "userId" to s("u-1")),
        ) { repository.removeTeamMember(WORKSPACE, "t-1", "u-1") }
    }

    // ── Recordings ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the recording ops unwrap their own list keys rather than the shared items wrapper`() =
        runTest {
            val list = call(RECORDINGS, SchedulingAdminOp.RECORDINGS_LIST, obj()) {
                repository.recordings(WORKSPACE)
            }
            assertEquals("ready", list.first().status)

            exercise(NO_CONTENT, SchedulingAdminOp.RECORDINGS_DELETE, obj("id" to s("rec-1"))) {
                repository.deleteRecording(WORKSPACE, "rec-1")
            }

            val purged = call(
                """{"deleted":4,"failed":1}""",
                SchedulingAdminOp.RECORDINGS_DELETE_ALL,
                obj(),
            ) { repository.deleteAllRecordings(WORKSPACE) }
            assertEquals(4, purged.deleted)

            val consents = call(
                CONSENTS,
                SchedulingAdminOp.RECORDINGS_CONSENT,
                obj("id" to s("rec-1")),
            ) { repository.recordingConsents(WORKSPACE, "rec-1") }
            assertEquals("granted", consents.first().decision)
        }

    // ── Self and settings ───────────────────────────────────────────────────────────────────

    @Test
    fun `the self ops send only what changed and the avatar delete sends nothing`() = runTest {
        exercise(ME, SchedulingAdminOp.ME_GET, obj()) { repository.me(WORKSPACE) }

        exercise(
            ME,
            SchedulingAdminOp.ME_PATCH,
            obj("timezone" to s("America/Toronto"), "notify_reminder" to b(false)),
        ) {
            repository.updateMe(
                WORKSPACE,
                SchedulingMeUpdate(timezone = "America/Toronto", notifyReminder = false),
            )
        }

        exercise(NO_CONTENT, SchedulingAdminOp.ME_AVATAR_DELETE, obj()) {
            repository.deleteAvatar(WORKSPACE)
        }
    }

    @Test
    fun `branding is sent whole, and its two image deletes take no params`() = runTest {
        val current = call(BRANDING, SchedulingAdminOp.SETTINGS_BRANDING_GET, obj()) {
            repository.branding(WORKSPACE)
        }

        // ⛔ ALL SEVEN KEYS, ALWAYS. `settings.branding.patch` declares them required, so a caller
        // that sent three would blank the other four — which is why the update is seeded from what
        // the server currently holds.
        exercise(
            BRANDING,
            SchedulingAdminOp.SETTINGS_BRANDING_PATCH,
            obj(
                "business_name" to s("Contract Agency"),
                "logo_height" to n(48),
                "logo_opacity" to n(100),
                "banner_opacity" to n(80),
                "privacy_url" to s(""),
                "terms_url" to s("https://www.contract.test/terms"),
                "fallback_locale" to s("en"),
            ),
        ) {
            repository.updateBranding(WORKSPACE, SchedulingBrandingUpdate.from(current))
        }

        exercise(NO_CONTENT, SchedulingAdminOp.SETTINGS_BRANDING_LOGO_DELETE, obj()) {
            repository.deleteBrandingLogo(WORKSPACE)
        }
        exercise(NO_CONTENT, SchedulingAdminOp.SETTINGS_BRANDING_BANNER_DELETE, obj()) {
            repository.deleteBrandingBanner(WORKSPACE)
        }
    }

    @Test
    fun `storage, notetaker and llm settings each name their own op and key`() = runTest {
        exercise(STORAGE, SchedulingAdminOp.SETTINGS_STORAGE_GET, obj()) {
            repository.storageSettings(WORKSPACE)
        }
        exercise(
            STORAGE,
            SchedulingAdminOp.SETTINGS_STORAGE_PATCH,
            obj("recordings_enabled" to b(true)),
        ) { repository.setRecordingsEnabled(WORKSPACE, true) }

        exercise("""{"enabled":true}""", SchedulingAdminOp.SETTINGS_NOTETAKER_GET, obj()) {
            repository.notetakerSettings(WORKSPACE)
        }
        exercise(
            """{"enabled":false}""",
            SchedulingAdminOp.SETTINGS_NOTETAKER_PATCH,
            obj("enabled" to b(false)),
        ) { repository.setNotetakerEnabled(WORKSPACE, false) }

        exercise(LLM, SchedulingAdminOp.SETTINGS_LLM_GET, obj()) { repository.llmSettings(WORKSPACE) }

        // ⛔ AN OMITTED `extra_instructions` LEAVES THE STORED PROMPT ALONE AND `""` CLEARS IT.
        // The two are different requests; a screen that sent `""` for "unchanged" would silently
        // drop whatever somebody wrote.
        exercise(LLM, SchedulingAdminOp.SETTINGS_LLM_PATCH, obj("enabled" to b(true))) {
            repository.updateLlmSettings(WORKSPACE, enabled = true)
        }
        exercise(
            LLM,
            SchedulingAdminOp.SETTINGS_LLM_PATCH,
            obj("extra_instructions" to s("")),
        ) { repository.updateLlmSettings(WORKSPACE, extraInstructions = "") }
    }

    // ── Developer ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the api key and oauth ops name their ops and carry their ids`() = runTest {
        exercise(API_KEYS, SchedulingAdminOp.API_KEYS_LIST, obj()) { repository.apiKeys(WORKSPACE) }

        val created = call(
            """{"id":"k-1","name":"Zapier","key":"cno_secret"}""",
            SchedulingAdminOp.API_KEYS_CREATE,
            obj("name" to s("Zapier")),
        ) { repository.createApiKey(WORKSPACE, "Zapier") }
        assertEquals("cno_secret", created.key)

        exercise(NO_CONTENT, SchedulingAdminOp.API_KEYS_DELETE, obj("id" to s("k-1"))) {
            repository.deleteApiKey(WORKSPACE, "k-1")
        }

        exercise(OAUTH, SchedulingAdminOp.OAUTH_CONNECTIONS_LIST, obj()) {
            repository.oauthConnections(WORKSPACE)
        }
        exercise(NO_CONTENT, SchedulingAdminOp.OAUTH_CONNECTIONS_DELETE, obj("id" to s("o-1"))) {
            repository.deleteOAuthConnection(WORKSPACE, "o-1")
        }
    }

    @Test
    fun `the webhook create takes the enum and the patch takes strings`() = runTest {
        exercise(WEBHOOKS, SchedulingAdminOp.WEBHOOKS_LIST, obj()) { repository.webhooks(WORKSPACE) }

        // ⛔ THE ENUM IS THE CHOICE AND ITS WIRE SPELLING IS DOTTED. A screen chooses from what the
        // fork accepts today.
        exercise(
            WEBHOOK_CREATED,
            SchedulingAdminOp.WEBHOOKS_CREATE,
            obj(
                "url" to s("https://hooks.contract.test/bookings"),
                "events" to JsonArray(listOf(s("booking.created"), s("recording.completed"))),
                "fields" to JsonArray(listOf(s("attendee_name"))),
            ),
        ) {
            repository.createWebhook(
                WORKSPACE,
                "https://hooks.contract.test/bookings",
                listOf(
                    SchedulingWebhookEvent.BOOKING_CREATED,
                    SchedulingWebhookEvent.RECORDING_COMPLETED,
                ),
                fields = listOf("attendee_name"),
            )
        }

        // ⛔ AND THE PATCH TAKES STRINGS, because it can be built from a webhook that was READ —
        // possibly carrying an event this build does not model. Forcing the enum would make "add
        // one field to an existing webhook" impossible for exactly the subscriptions a newer
        // server created.
        exercise(
            NO_CONTENT,
            SchedulingAdminOp.WEBHOOKS_PATCH,
            obj(
                "id" to s("wh-1"),
                "events" to JsonArray(listOf(s("booking.created"), s("some.future.event"))),
            ),
        ) {
            repository.updateWebhook(
                WORKSPACE,
                "wh-1",
                events = listOf("booking.created", "some.future.event"),
            )
        }

        exercise(NO_CONTENT, SchedulingAdminOp.WEBHOOKS_DELETE, obj("id" to s("wh-1"))) {
            repository.deleteWebhook(WORKSPACE, "wh-1")
        }

        exercise(DELIVERIES, SchedulingAdminOp.WEBHOOKS_DELIVERIES, obj("id" to s("wh-1"))) {
            repository.webhookDeliveries(WORKSPACE, "wh-1")
        }
    }

    @Test
    fun `a refusal passes through a list wrapper without inventing an empty list`() = runTest {
        // ⛔ THE CONFLATION THIS GUARDS. "We could not look" rendered as "there is nothing" already
        // routed a paying customer to a checkout page on the web; on this surface it tells an
        // operator their calendar is empty. The list wrappers unwrap `items` only on the success
        // arm, so a failure survives with its code intact.
        api.refusal = "unavailable"

        val outcome = repository.listEventTypes(WORKSPACE)

        assertEquals(
            SchedulingAdminOutcome.Failure(SchedulingAdminFailureCode.UNAVAILABLE, "unavailable"),
            outcome,
        )
    }

    /**
     * Run one op for its ASSERTIONS and discard the decoded value.
     *
     * ⛔ NOT A CONVENIENCE WRAPPER, AND OMITTING IT DOES NOT COMPILE. [call] returns `T`, so in
     * statement position Kotlin infers `T` FROM THE EXPECTED TYPE rather than from the lambda —
     * and the last statement inside `runTest { … }` has an expected type of `Unit`. Every such
     * site then failed with "Return type mismatch: expected 'SchedulingAdminOutcome<Unit>', actual
     * 'SchedulingAdminOutcome<SchedulingNoContent>'", 17 of them, and the tell is that the SAME
     * line compiles fine one statement earlier. Returning `Unit` here fixes `T` from [block]
     * before the expected type can reach it.
     *
     * ⚠️ USED AT EVERY STATEMENT-POSITION SITE, not only the 17 that failed. Leaving the others on
     * [call] would make a green build depend on which assertion happens to be last in its test, so
     * reordering two lines would break the build for a reason that reads as unrelated.
     */
    private suspend fun <T> exercise(
        payload: String,
        op: SchedulingAdminOp,
        expectedParams: JsonObject,
        block: suspend () -> SchedulingAdminOutcome<T>,
    ) {
        call(payload, op, expectedParams, block)
    }

    private suspend fun <T> call(
        payload: String,
        op: SchedulingAdminOp,
        expectedParams: JsonObject,
        block: suspend () -> SchedulingAdminOutcome<T>,
    ): T {
        api.payloadJson = payload
        val outcome = block()
        assertEquals("wrong op", op, api.lastOp)
        assertEquals("wrong params for ${op.wire}", expectedParams, api.lastParams)
        assertEquals(WORKSPACE, api.lastWorkspaceId)
        EXERCISED += api.lastOp!!
        return (outcome as SchedulingAdminOutcome.Success).value
    }

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

    private fun s(value: String): JsonElement = JsonPrimitive(value)

    private fun n(value: Int): JsonElement = JsonPrimitive(value)

    private fun b(value: Boolean): JsonElement = JsonPrimitive(value)

    /**
     * ⚠️ NOT `private`, WHICH IT WOULD OTHERWISE BE. JUnit 4 invokes `@AfterClass` reflectively as
     * a static method, and `@JvmStatic` inside a PRIVATE companion generates a private one that
     * the runner cannot call — the census below would then never run and would report nothing,
     * which is the failure mode this whole class exists to avoid.
     */
    companion object {
        const val WORKSPACE = "ws-1"

        const val NO_CONTENT = """{"ok":true}"""
        const val EVENT_TYPE =
            """{"id":"et-1","slug":"phone-consultation","name":"Phone","duration_minutes":30}"""
        const val EVENT_TYPES = """{"items":[$EVENT_TYPE]}"""
        const val HOSTS =
            """{"items":[{"user_id":"u-1","name":"n","email":"e","role":"required",""" +
                """"priority":0,"archived":false}]}"""
        const val QUESTION =
            """{"id":"q-1","event_type_id":"et-1","label":"l","type":"text",""" +
                """"required":false,"position":0}"""
        const val QUESTIONS = """{"items":[$QUESTION]}"""
        const val RULE =
            """{"id":"r-1","day_of_week":1,"start_time":"09:00","end_time":"17:00"}"""
        const val RULES = """{"items":[$RULE]}"""
        const val OVERRIDE =
            """{"id":"o-1","date":"2026-09-24","is_available":true,"reason":"custom_hours"}"""
        const val OVERRIDES = """{"items":[$OVERRIDE]}"""
        const val BOOKING =
            """{"id":"bk-1","start_at":"a","end_at":"b","status":"cancelled"}"""
        const val ANSWERS =
            """{"items":[{"question_id":"q-1","label":"l","type":"text","value":""}]}"""
        const val USERS =
            """[{"id":"u-1","email":"e","name":"n","is_admin":false,"is_owner":false,""" +
                """"role":"member","archived":false}]"""
        const val UPCOMING =
            """{"items":[{"id":"bk-3","start_at":"a","end_at":"b","event_type_name":"n",""" +
                """"event_type_slug":"s","attendee_name":"an","attendee_email":"ae"}]}"""
        const val TEAM = """{"id":"t-1","name":"Sales","slug":"sales"}"""
        const val TEAMS = """{"items":[$TEAM]}"""
        const val RECORDINGS = """{"recordings":[{"id":"rec-1","status":"ready"}]}"""
        const val CONSENTS = """{"consents":[{"identity":"i","decision":"granted"}]}"""
        const val ME =
            """{"id":"u-1","email":"e","name":"n","timezone":"America/Toronto",""" +
                """"time_format":"24h","week_start":1,"date_format":"ymd","is_admin":true,""" +
                """"is_owner":false,"role":"admin","notify_confirmation":true,""" +
                """"notify_cancellation":true,"notify_reschedule":true,"notify_reminder":true,""" +
                """"notify_host_booking":true,"notify_host_cancel":true,""" +
                """"notify_host_reschedule":true}"""
        const val BRANDING =
            """{"business_name":"Contract Agency","logo_url":"","logo_height":48,""" +
                """"logo_opacity":100,"banner_url":"","banner_opacity":80,"privacy_url":"",""" +
                """"terms_url":"https://www.contract.test/terms","fallback_locale":"en"}"""
        const val STORAGE = """{"recordings_enabled":true}"""
        const val LLM = """{"enabled":true,"extra_instructions":""}"""
        const val API_KEYS = """{"items":[{"id":"k-1","name":"Zapier"}]}"""
        const val OAUTH = """{"items":[{"id":"o-1","client_name":"Raycast"}]}"""
        const val WEBHOOKS = """{"items":[{"id":"wh-1","url":"u","events":[]}]}"""
        const val WEBHOOK_CREATED = """{"id":"wh-1","url":"u","events":[],"secret":"whsec"}"""
        const val DELIVERIES =
            """{"items":[{"id":"d-1","webhook_id":"wh-1","event":"booking.created",""" +
                """"status":"delivered","attempt_count":1}]}"""

        /**
         * Which ops the assertions above actually drove.
         *
         * ⛔ COLLECTED AT RUNTIME AND CHECKED AFTER THE WHOLE CLASS, which is the only form of
         * this census that means anything. A hardcoded expected set would be a list derived from
         * the enum, i.e. the code asserting that it equals itself — the exact shape
         * `SchedulingAdminOpTest`'s header warns about. This one fails when an op is added to the
         * catalog and no typed wrapper is written for it.
         *
         * ⚠️ IT THEREFORE FAILS ON A SINGLE-METHOD RUN, by construction. That is the cost of the
         * check being real; run the class.
         */
        val EXERCISED: MutableSet<SchedulingAdminOp> = mutableSetOf()

        @JvmStatic
        @AfterClass
        fun everyCataloguedOpWasExercised() {
            assertEquals(75, SchedulingAdminOp.entries.size)
            assertEquals(
                "every catalogued op needs a typed wrapper and an expectation in this class",
                SchedulingAdminOp.entries.toSet(),
                EXERCISED.toSet(),
            )
        }
    }
}
