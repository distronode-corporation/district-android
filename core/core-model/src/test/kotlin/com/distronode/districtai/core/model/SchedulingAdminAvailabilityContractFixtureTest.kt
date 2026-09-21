package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `availability.*` family: weekly rules, dated overrides, and the one op with two shapes.
 *
 * ⛔ `availability.overrides.create` ANSWERS EITHER A ROW OR A GROUP SUMMARY, decided by whether
 * the request carried an `end_date`. Two committed fixtures pin the two shapes, and the union that
 * reads them is discriminated on a KEY rather than by trying one and falling back — see
 * [SchedulingOverrideCreated]. A caller that assumed either shape gets a decode failure on half
 * the traffic.
 */
class SchedulingAdminAvailabilityContractFixtureTest {

    @Test
    fun `weekly rules decode with a per-event-type id on one row and an explicit null on the other`() {
        val rules = SchedulingAdminFixtures.data(
            "district-scheduling-rules.json",
            SchedulingItems.serializer(SchedulingAvailabilityRule.serializer()),
        ).items

        assertEquals(2, rules.size)

        // ⛔ THE PRESENT-AND-ABSENT PAIR, and here the absence is a MEANING rather than a gap:
        // `event_type_id: null` is a TENANCY-WIDE rule that governs every event type. A screen
        // that filtered nulls out would hide the rule that governs everything.
        assertEquals("et-contract-1", rules[0].eventTypeId)
        assertNull("a tenancy-wide rule names no event type", rules[1].eventTypeId)

        // ⚠️ LOCAL WALL CLOCK, NOT INSTANTS. These carry no zone; the host's own timezone places
        // them, which is why `me.timezone` is a viewer-level WRITE.
        assertEquals("09:00", rules[0].startTime)
        assertEquals("17:00", rules[0].endTime)
        assertEquals(1, rules[0].dayOfWeek)
        assertEquals(3, rules[1].dayOfWeek)
    }

    @Test
    fun `the single rule read is the same row the list carries`() {
        val single = SchedulingAdminFixtures.data(
            "district-scheduling-rule.json",
            SchedulingAvailabilityRule.serializer(),
        )
        val fromList = SchedulingAdminFixtures.data(
            "district-scheduling-rules.json",
            SchedulingItems.serializer(SchedulingAvailabilityRule.serializer()),
        ).items[1]

        // ⚠️ ONE TYPE FOR THE CREATE, THE PATCH AND THE LIST ROW. Proving it here is what stops the
        // three drifting a field at a time behind three fixtures.
        assertEquals(fromList, single)
        assertNull(single.eventTypeId)
    }

    @Test
    fun `overrides decode with custom hours on one row and a day off on the other`() {
        val overrides = SchedulingAdminFixtures.data(
            "district-scheduling-overrides.json",
            SchedulingItems.serializer(SchedulingAvailabilityOverride.serializer()),
        ).items

        assertEquals(2, overrides.size)

        // ⛔ `is_available` FLIPS THE MEANING OF THE TIMES RATHER THAN LABELLING THE ROW. True with
        // times is "these hours instead of the usual ones".
        assertTrue(overrides[0].isAvailable)
        assertEquals("12:00", overrides[0].startTime)
        assertEquals("16:00", overrides[0].endTime)
        assertEquals("custom_hours", overrides[0].reason)

        // ⛔ AND FALSE IS A DAY OFF, WITH BOTH TIMES EXPLICITLY NULL. A client that read the times
        // without the flag would offer bookings on a day somebody blocked out.
        assertFalse(overrides[1].isAvailable)
        assertNull(overrides[1].startTime)
        assertNull(overrides[1].endTime)

        // ⚠️ THE THIRD SHAPE: `group_id` is PRESENT on the first row and ABSENT on the second,
        // which is what makes it useless as the union's discriminator.
        assertEquals("grp-contract-1", overrides[0].groupId)
        assertNull("a standalone override belongs to no group", overrides[1].groupId)
    }

    @Test
    fun `a single-day create answers the row it made`() {
        val created = SchedulingAdminFixtures.data(
            "district-scheduling-override-created.json",
            SchedulingOverrideCreated.serializer(),
        )

        val single = created as SchedulingOverrideCreated.Single
        assertEquals("ovr-contract-2", single.row.id)
        assertEquals("2026-09-25", single.row.date)
        assertFalse(single.row.isAvailable)
        assertEquals("day_off", single.row.reason)
        assertNull(single.row.startTime)
        assertNull("a single-day create belongs to no group", single.row.groupId)
    }

    @Test
    fun `a ranged create answers a group summary instead`() {
        val created = SchedulingAdminFixtures.data(
            "district-scheduling-override-range.json",
            SchedulingOverrideCreated.serializer(),
        )

        val range = created as SchedulingOverrideCreated.Range
        assertEquals("grp-contract-2", range.group.groupId)
        assertEquals("out_of_office", range.group.reason)
        assertEquals("2026-12-24", range.group.start)
        assertEquals("2026-12-31", range.group.end)

        // ⚠️ INCLUSIVE OF BOTH ENDS: the 24th to the 31st is 8, not 7. Nothing may recompute it
        // from the dates.
        assertEquals(8, range.group.days)
    }

    @Test
    fun `the union picks its arm on a key that only one shape can carry`() {
        // ⛔ THE DISCRIMINATOR IS `days`, NOT `group_id`, AND THIS IS THE CASE THAT PROVES WHY. A
        // single-day row that came from an earlier RANGE carries `group_id` — the overrides
        // fixture has one — so discriminating on it would read that row as a summary and lose the
        // date, the availability flag and the times with it.
        val grouped = """
            {"id":"ovr-contract-1","date":"2026-09-24","is_available":true,
             "reason":"custom_hours","start_time":"12:00","end_time":"16:00",
             "group_id":"grp-contract-1"}
        """.trimIndent()

        val decoded = ContractFixtures.json.decodeFromString(
            SchedulingOverrideCreated.serializer(),
            grouped,
        )

        assertTrue(
            "a row carrying group_id is still a row",
            decoded is SchedulingOverrideCreated.Single,
        )
        assertEquals(
            "grp-contract-1",
            (decoded as SchedulingOverrideCreated.Single).row.groupId,
        )
    }

    @Test
    fun `a body that is neither shape is rejected rather than taking the first arm`() {
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingOverrideCreated.serializer(),
                    """{"id":"x"}""",
                )
            }.isFailure,
        )
        assertTrue(
            "a non-object is refused with a message rather than a cast failure",
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingOverrideCreated.serializer(),
                    """"not-an-object"""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `every availability fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-rules.json",
            SchedulingItems.serializer(SchedulingAvailabilityRule.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-rule.json",
            SchedulingAvailabilityRule.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-overrides.json",
            SchedulingItems.serializer(SchedulingAvailabilityOverride.serializer()),
        )

        // ⛔ THE UNION ROUND-TRIPS THROUGH ITS OWN SERIALIZER, which is the only thing that proves
        // the encode side picks the same arm the decode side did. A serializer that wrote a
        // wrapper object would decode back into the other arm and this is where it would surface.
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-override-created.json",
            SchedulingOverrideCreated.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-override-range.json",
            SchedulingOverrideCreated.serializer(),
        )
    }
}
