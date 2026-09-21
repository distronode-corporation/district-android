package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `calendar.*` family plus `zoom.status`.
 *
 * ⛔ `connected` AND `configured` ARE DIFFERENT QUESTIONS AND COLLAPSING THEM SHOWS THE WRONG
 * BUTTON. `configured` is "the INSTANCE has credentials for a provider" — an operator of the
 * scheduler set those up and a tenant cannot. `connected` is "this CALLER has a connection". Not
 * configured means "your administrator has not enabled calendar sync"; configured and not
 * connected means "connect yours". Both flags are required on both status types for that reason.
 */
class SchedulingAdminCalendarContractFixtureTest {

    @Test
    fun `calendar status decodes its connections, its providers and its unconfigured list`() {
        val status = SchedulingAdminFixtures.data(
            "district-scheduling-calendar-status.json",
            SchedulingCalendarStatus.serializer(),
        )

        assertTrue(status.connected)
        assertTrue(status.configured)

        // ⚠️ THREE NESTED COLLECTIONS ON ONE BODY, and they answer three different questions: what
        // the instance supports, what this caller has connected, and which of the supported ones
        // the instance has not finished wiring.
        assertEquals(listOf("google", "microsoft", "caldav"), status.providers)
        assertEquals(2, status.connections.size)
        assertEquals(listOf("microsoft"), status.unconfiguredProviders)

        // ⚠️ `provider` IS THE CALLER'S PRIMARY, not the first entry of the list above.
        assertEquals("google", status.provider)
    }

    @Test
    fun `a connection's destination and conflict flags are independent`() {
        val connections = SchedulingAdminFixtures.data(
            "district-scheduling-calendar-status.json",
            SchedulingCalendarStatus.serializer(),
        ).connections

        // ⛔ BOTH FLAGS ARE REQUIRED ON THIS TYPE. A connection can be consulted for conflicts
        // without being where new events are written, and exactly one may be the destination —
        // defaulting either would let a screen offer to write into a read-only account, or quietly
        // stop consulting one the host relies on.
        assertEquals("cal-conn-contract-1", connections[0].id)
        assertEquals("google", connections[0].provider)
        assertTrue(connections[0].isDestination)
        assertTrue(connections[0].checkConflicts)

        assertEquals("caldav", connections[1].provider)
        assertEquals("contract@fastmail.test", connections[1].accountEmail)
        assertFalse(connections[1].isDestination)
        assertFalse(connections[1].checkConflicts)
    }

    @Test
    fun `a status body with no connections list is rejected rather than read as none connected`() {
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingCalendarStatus.serializer(),
                    """{"connected":true,"configured":true}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `the caldav acknowledgement carries a flag and an address, and no credential`() {
        val caldav = SchedulingAdminFixtures.data(
            "district-scheduling-caldav-connect.json",
            SchedulingCaldavConnection.serializer(),
        )

        assertTrue(caldav.connected)
        assertEquals("contract@fastmail.test", caldav.accountEmail)

        // ⛔ THE OP SENDS AN APP PASSWORD AND THE ANSWER MUST NEVER ECHO ONE. If a `password` or
        // `token` key ever appears here the strict decoder rejects it, which is the correct
        // direction: it would mean a credential had started coming back.
        val body = ContractFixtures.read("district-scheduling-caldav-connect.json")
        assertFalse("no credential may reach this client", body.contains("password"))
        assertFalse(body.contains("token"))
    }

    @Test
    fun `a calendar listing decodes a configured row and an unconfigured one`() {
        val calendars = SchedulingAdminFixtures.data(
            "district-scheduling-calendars.json",
            SchedulingCalendarSelections.serializer(),
        ).calendars

        assertEquals(2, calendars.size)

        // ⛔ THE PRESENT-AND-ABSENT PAIR, AND HERE ABSENT IS AN INSTRUCTION RATHER THAN A GAP.
        // `false` and "not stated" are different things to the fork on the write path, so a
        // non-null default here would silently turn every unconfigured calendar into an explicit
        // refusal the next time the selection was PUT back.
        assertEquals(true, calendars[0].primary)
        assertEquals(true, calendars[0].writable)
        assertEquals(true, calendars[0].checkConflicts)
        assertEquals(true, calendars[0].isDestination)

        assertEquals("holidays@group.v.calendar.google.test", calendars[1].id)
        assertEquals("Statutory holidays", calendars[1].name)
        assertNull("an unconfigured calendar states no primary flag", calendars[1].primary)
        assertNull(calendars[1].writable)
        assertNull(calendars[1].checkConflicts)
        assertNull(calendars[1].isDestination)
    }

    @Test
    fun `the calendar listing is NOT read through the shared items wrapper`() {
        // ⛔ `calendar.connections.calendars.get` DECLARES ITS OWN `{calendars}` KEY. Reading it
        // through `SchedulingItems` is the failure worth pinning: it would report an EMPTY LIST,
        // which is a wrong answer rather than an error, and a screen would say "no calendars" for
        // an account with two.
        assertTrue(
            runCatching {
                SchedulingAdminFixtures.data(
                    "district-scheduling-calendars.json",
                    SchedulingItems.serializer(SchedulingCalendarSelection.serializer()),
                )
            }.isFailure,
        )
    }

    @Test
    fun `zoom status reports the instance app and the caller's account separately`() {
        val zoom = SchedulingAdminFixtures.data(
            "district-scheduling-zoom-status.json",
            SchedulingZoomStatus.serializer(),
        )

        // ⚠️ THE EXACT CASE THE TWO FLAGS EXIST FOR: the instance has a Zoom app and THIS caller
        // has not connected. "Connect yours", not "ask your administrator".
        assertTrue(zoom.configured)
        assertFalse(zoom.connected)
    }

    @Test
    fun `every calendar fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-calendar-status.json",
            SchedulingCalendarStatus.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-caldav-connect.json",
            SchedulingCaldavConnection.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-calendars.json",
            SchedulingCalendarSelections.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-zoom-status.json",
            SchedulingZoomStatus.serializer(),
        )
    }
}
