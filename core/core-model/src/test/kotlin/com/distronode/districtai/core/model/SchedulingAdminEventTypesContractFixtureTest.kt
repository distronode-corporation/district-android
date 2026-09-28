package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `eventTypes.*` family: the row, its hosts, its questions and the slots it offers.
 *
 * ⛔ THE SECOND ROW OF `district-scheduling-event-types.json` IS WHY THIS FAMILY'S DTO HAS THIRTY
 * OPTIONAL FIELDS. It OMITS twelve keys and sends thirteen more as explicit `null`, which are two
 * different wire shapes with the same meaning, and both have to decode. Four fields survive as
 * required; they are what reject a `{}` body.
 */
class SchedulingAdminEventTypesContractFixtureTest {

    private fun eventTypes(): List<SchedulingEventType> = SchedulingAdminFixtures.data(
        "district-scheduling-event-types.json",
        SchedulingItems.serializer(SchedulingEventType.serializer()),
    ).items

    @Test
    fun `a fully populated event type decodes every optional field it was sent`() {
        val row = eventTypes().first()

        assertEquals("et-contract-1", row.id)
        assertEquals("phone-consultation", row.slug)
        assertEquals(30, row.durationMinutes)
        assertEquals("A 30 minute introductory call.", row.description)
        assertEquals(15, row.slotIntervalMinutes)
        assertEquals("phone", row.locationType)
        assertEquals("+14165551234", row.locationValue)
        assertEquals("even", row.rrStrategy)
        assertEquals(120, row.minNoticeMinutes)

        // ⚠️ A NESTED COLLECTION OF PRIMITIVES, which is the one place an `Int` list appears on
        // this surface. Minutes before the booking, ordered as the fork stored them.
        assertEquals(listOf(1440, 60), row.reminders)

        assertEquals(true, row.owned)
        assertEquals("contract@distronode.test", row.ownerEmail)
    }

    @Test
    fun `a sparse event type decodes with its omissions absent and its nulls null`() {
        val row = eventTypes()[1]

        assertEquals("site-visit", row.slug)
        assertEquals(60, row.durationMinutes)

        // ⛔ THE EXPLICIT NULLS. Thirteen keys arrive as `null` on this row, and a default of any
        // kind would turn "the host wrote no confirmation message" into a message.
        assertNull("an explicit null description stays null", row.description)
        assertNull(row.locationValue)
        assertNull(row.msgConfirmation)
        assertNull(row.subjReminder)
        assertNull("reminders is an explicit null, not an empty list", row.reminders)

        // ⛔ AND THE OMISSIONS, WHICH ARE A DIFFERENT WIRE SHAPE WITH THE SAME MEANING. `owned` is
        // the one that matters: a `Boolean = false` default here would tell a screen that somebody
        // else owns this event type when the server said nothing at all.
        assertNull("owned is absent on this row", row.owned)
        assertNull(row.ownerName)
        assertNull(row.slotIntervalMinutes)
        assertNull(row.routingMode)

        assertEquals(false, row.isActive)
        assertEquals(true, row.archived)
        assertFalse(
            "the sparse row really does omit owned",
            ContractFixtures.read("district-scheduling-event-types.json")
                .substringAfter("et-contract-2")
                .contains("\"owned\""),
        )
    }

    @Test
    fun `the single event-type read carries the round-robin fields the list row omitted`() {
        val row = SchedulingAdminFixtures.data(
            "district-scheduling-event-type.json",
            SchedulingEventType.serializer(),
        )

        assertEquals("round_robin", row.routingMode)
        assertEquals("priority", row.rrStrategy)

        // ⚠️ A GENERATED LOCATION SENDS A NULL VALUE AND THAT IS NOT AN EMPTY FIELD. `livekit`
        // mints its room at booking time, so "no location" would be describing a call that will
        // have one.
        assertEquals("livekit", row.locationType)
        assertNull(row.locationValue)

        // ⚠️ ZERO IS A VALUE HERE, not an absence: buffers and notice are explicitly 0 on this row.
        assertEquals(0, row.bufferBeforeMinutes)
        assertEquals(0, row.minNoticeMinutes)
        assertEquals(0, row.maxActiveBookings)

        assertEquals(false, row.owned)
        assertEquals("Other Host", row.ownerName)
    }

    @Test
    fun `an event type with no name is rejected rather than decoded`() {
        // ⛔ THE FOUR REQUIRED FIELDS ARE THE ONLY THING THAT REJECTS A STRUCTURALLY WRONG 200.
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingEventType.serializer(),
                    """{"id":"x","slug":"y","duration_minutes":30}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `the host roster decodes with an avatar present on one row and absent on the other`() {
        val hosts = SchedulingAdminFixtures.data(
            "district-scheduling-hosts.json",
            SchedulingItems.serializer(SchedulingHost.serializer()),
        ).items

        assertEquals(2, hosts.size)
        assertEquals("sched-user-contract", hosts[0].userId)

        // ⛔ THE PRESENT-AND-ABSENT PAIR ON ONE COLLECTION.
        assertEquals(
            "https://book.example.com/media/avatars/contract.png",
            hosts[0].avatarUrl,
        )
        assertNull("the rotation host has no picture", hosts[1].avatarUrl)

        // ⚠️ THE FORK'S ROUTING VOCABULARY, not a District role and not the op's `minRole`.
        assertEquals("required", hosts[0].role)
        assertEquals("rotation", hosts[1].role)
        assertEquals(0, hosts[0].priority)
        assertEquals(2, hosts[1].priority)

        // ⚠️ AN ARCHIVED HOST IS STILL ON THE ROSTER. The list is the assignment, not the set of
        // people who can actually be booked.
        assertFalse(hosts[0].archived)
        assertTrue(hosts[1].archived)
    }

    @Test
    fun `booking questions decode with options on a select and null on a text field`() {
        val questions = SchedulingAdminFixtures.data(
            "district-scheduling-questions.json",
            SchedulingItems.serializer(SchedulingQuestion.serializer()),
        ).items
        val single = SchedulingAdminFixtures.data(
            "district-scheduling-question.json",
            SchedulingQuestion.serializer(),
        )

        // ⚠️ THE NESTED COLLECTION. Choices are ordered and the order is the fork's.
        assertEquals(listOf("District", "Ledger", "Something else"), questions[0].options)
        assertEquals("select", questions[0].type)
        assertTrue(questions[0].required)

        // ⛔ EXPLICITLY NULL, NOT EMPTY. An empty list would render a select with no choices;
        // null is "this is not a select".
        assertNull(questions[1].options)
        assertEquals("text", questions[1].type)
        assertFalse(questions[1].required)
        assertEquals(1, questions[1].position)

        // ⚠️ THE SINGLE READ IS THE SAME ROW, which is what makes the patch response and the list
        // row one type rather than two that drift.
        assertEquals(questions[1], single)
    }

    @Test
    fun `slots decode with their hosts map, their taken window and a null host list`() {
        val slots = SchedulingAdminFixtures.data(
            "district-scheduling-slots.json",
            SchedulingSlots.serializer(),
        )

        assertEquals(2, slots.slots.size)

        // ⚠️ A NESTED COLLECTION AND THE ONE MAP ON THIS SURFACE, keyed by scheduler user id.
        assertEquals(
            listOf("sched-user-contract"),
            slots.slots[0].hostIds,
        )
        assertEquals("Contract Member", slots.hosts?.get("sched-user-contract")?.name)

        // ⛔ NULL ON A FIXED-HOST SLOT, not empty: there is nothing to choose between.
        assertNull(slots.slots[1].hostIds)

        // ⛔ `taken` IS NOT THE COMPLEMENT OF `slots`. It is sent only when the event type shows
        // taken windows, and a client that inferred it from absence would grey out every hour
        // outside the availability rules as well.
        assertEquals(1, slots.taken?.size)
        assertEquals("2026-09-14T15:00:00Z", slots.taken?.first()?.start)
    }

    @Test
    fun `the test-email acknowledgement says it was handed to a transport, not delivered`() {
        val result = SchedulingAdminFixtures.data(
            "district-scheduling-test-email.json",
            SchedulingTestEmailResult.serializer(),
        )

        assertTrue(result.sent)
        assertEquals("contract@distronode.test", result.to)
    }

    @Test
    fun `every event-type fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-event-types.json",
            SchedulingItems.serializer(SchedulingEventType.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-event-type.json",
            SchedulingEventType.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-hosts.json",
            SchedulingItems.serializer(SchedulingHost.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-questions.json",
            SchedulingItems.serializer(SchedulingQuestion.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-question.json",
            SchedulingQuestion.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-slots.json",
            SchedulingSlots.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-test-email.json",
            SchedulingTestEmailResult.serializer(),
        )
    }
}
