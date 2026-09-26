package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingWebhookEvent
import com.distronode.districtai.core.network.SchedulingAdminOp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduling-admin wrappers called with their optional arguments left out.
 *
 * [SchedulingAdminOpsTest] pins every op with the arguments a screen would usually pass. This class
 * pins the other half of the same contract: an argument a caller leaves out is ABSENT from `params`,
 * never sent as null. The ops are patches and optional filters, so an absent key means "leave it"
 * or "no filter" to the fork, while a null would be validated, and usually refused, as a value.
 */
class SchedulingAdminOmittedArgsTest {

    private val api = FakeSchedulingAdminApi()
    private val repository = SchedulingAdminRepository(api)

    @Test
    fun `a webhook created without a field list and patched without events sends neither`() = runTest {
        sends(
            """{"id":"wh-1","url":"u","events":[],"secret":"whsec"}""",
            SchedulingAdminOp.WEBHOOKS_CREATE,
            obj("url" to s("https://hooks.test/b"), "events" to JsonArray(listOf(s("booking.created")))),
        ) {
            repository.createWebhook(
                WORKSPACE,
                "https://hooks.test/b",
                listOf(SchedulingWebhookEvent.BOOKING_CREATED),
            )
        }

        sends(
            NO_CONTENT,
            SchedulingAdminOp.WEBHOOKS_PATCH,
            obj("id" to s("wh-1"), "fields" to JsonArray(listOf(s("attendee_name")))),
        ) { repository.updateWebhook(WORKSPACE, "wh-1", fields = listOf("attendee_name")) }
    }

    @Test
    fun `an availability rule or override patched in part sends only the part that changed`() = runTest {
        sends(
            """{"id":"r-1","day_of_week":2,"start_time":"09:00","end_time":"17:00"}""",
            SchedulingAdminOp.AVAILABILITY_RULES_PATCH,
            obj("id" to s("r-1"), "day_of_week" to JsonPrimitive(2)),
        ) { repository.patchAvailabilityRule(WORKSPACE, "r-1", dayOfWeek = 2) }

        sends(
            """{"id":"o-1","date":"2026-09-24","is_available":true,"reason":"custom_hours"}""",
            SchedulingAdminOp.AVAILABILITY_OVERRIDES_PATCH,
            obj("id" to s("o-1"), "start_time" to s("10:00"), "end_time" to s("14:00")),
        ) {
            repository.patchAvailabilityOverride(WORKSPACE, "o-1", startTime = "10:00", endTime = "14:00")
        }
    }

    @Test
    fun `a team renamed without a slug, and a member added without a priority, send neither`() = runTest {
        sends(TEAM, SchedulingAdminOp.TEAMS_PATCH, obj("id" to s("t-1"), "name" to s("Sales"))) {
            repository.patchTeam(WORKSPACE, "t-1", name = "Sales")
        }

        // ⚠️ No priority means the fork's own default for a new member, not zero.
        sends(TEAM, SchedulingAdminOp.TEAMS_MEMBERS_ADD, obj("id" to s("t-1"), "user_id" to s("u-1"))) {
            repository.addTeamMember(WORKSPACE, "t-1", "u-1")
        }
    }

    @Test
    fun `slots asked for with no window or zone, and a cancel with no reason, send only the address`() =
        runTest {
            sends("""{"slots":[]}""", SchedulingAdminOp.EVENT_TYPES_SLOTS, obj("slug" to s("site-visit"))) {
                repository.eventTypeSlots(WORKSPACE, "site-visit")
            }

            sends(BOOKING, SchedulingAdminOp.BOOKINGS_CANCEL, obj("id" to s("bk-1"))) {
                repository.cancelBooking(WORKSPACE, "bk-1")
            }
        }

    @Test
    fun `calendar connections named without a preset or an account send neither key`() = runTest {
        sends(
            """{"connected":true,"account_email":"a@b.test"}""",
            SchedulingAdminOp.CALENDAR_CALDAV_CONNECT,
            obj(
                "username" to s("a@b.test"),
                "app_password" to s("secret"),
                "server_url" to s("https://dav.test"),
            ),
        ) { repository.connectCaldav(WORKSPACE, "a@b.test", "secret", serverUrl = "https://dav.test") }

        sends(
            """{"calendars":[{"id":"c","name":"n"}]}""",
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_GET,
            obj("id" to s("conn-1"), "provider" to s("google")),
        ) { repository.connectionCalendars(WORKSPACE, "conn-1", "google") }

        sends(
            NO_CONTENT,
            SchedulingAdminOp.CALENDAR_CONNECTIONS_CALENDARS_PUT,
            obj(
                "id" to s("conn-1"),
                "provider" to s("google"),
                "calendars" to JsonArray(listOf(obj("id" to s("c"), "name" to s("n")))),
            ),
        ) {
            repository.setConnectionCalendars(
                WORKSPACE,
                "conn-1",
                "google",
                listOf(SchedulingCalendarSelection(id = "c", name = "n")),
            )
        }
    }

    private suspend fun <T> sends(
        payload: String,
        op: SchedulingAdminOp,
        expectedParams: JsonObject,
        block: suspend () -> SchedulingAdminOutcome<T>,
    ) {
        api.payloadJson = payload
        val outcome = block()
        assertEquals("wrong op", op, api.lastOp)
        assertEquals("wrong params for ${op.wire}", expectedParams, api.lastParams)
        assertEquals(WORKSPACE, api.lastWorkspaceId)
        assertTrue("expected a decoded answer, got $outcome", outcome is SchedulingAdminOutcome.Success)
    }

    private fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

    private fun s(value: String): JsonElement = JsonPrimitive(value)

    private companion object {
        const val WORKSPACE = "ws-1"
        const val NO_CONTENT = """{"ok":true}"""
        const val TEAM = """{"id":"t-1","name":"Sales","slug":"sales"}"""
        const val BOOKING = """{"id":"bk-1","start_at":"a","end_at":"b","status":"cancelled"}"""
    }
}
