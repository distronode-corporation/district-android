package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `bookings.*` family: the page, the answers, the notes and the transcript.
 *
 * ⛔ THE CANCELLED ROW IN `district-scheduling-bookings.json` IS WHY THIS FAMILY'S DTO HAS FOUR
 * REQUIRED FIELDS AND NOT MORE. It carries an id, a start, an end and a status and NOTHING else —
 * no event type, no host, no attendees — so anything else made required would refuse the row a
 * cancellation screen exists to show.
 */
class SchedulingAdminBookingsContractFixtureTest {

    private fun page(): SchedulingBookingPage = SchedulingAdminFixtures.data(
        "district-scheduling-bookings.json",
        SchedulingBookingPage.serializer(),
    )

    @Test
    fun `a booking page decodes its rows, its tallies and its window`() {
        val decoded = page()

        assertEquals(2, decoded.items.size)
        assertEquals(2, decoded.total)
        assertEquals(50, decoded.limit)
        assertEquals(0, decoded.offset)

        // ⛔ THE TALLIES ARE NOT DERIVED FROM `items` AND MUST NOT BE RECOMPUTED FROM IT. The page
        // is one window of a filtered query; these count the whole tenancy either side of now,
        // which is what the tab labels show.
        assertEquals(1, decoded.counts?.upcoming)
        assertEquals(1, decoded.counts?.past)
    }

    @Test
    fun `a confirmed booking carries its attendees and a cancelled one carries almost nothing`() {
        val rows = page().items

        // ⚠️ THE NESTED COLLECTION.
        assertEquals(1, rows[0].attendees?.size)
        assertEquals("Dana Booker", rows[0].attendees?.first()?.name)
        assertEquals("dana@contract.test", rows[0].attendees?.first()?.email)
        assertEquals("phone-consultation", rows[0].eventTypeSlug)
        assertEquals("Contract Member", rows[0].hostName)

        // ⛔ THE SPARSE ROW. Eight keys are absent and every one of them is optional for exactly
        // this reason; the `attendees` absence is the one that would otherwise read as "nobody is
        // coming" rather than "the server did not say".
        assertEquals("bk-contract-2", rows[1].id)
        assertEquals("cancelled", rows[1].status)
        assertNull("a cancelled row names no event type", rows[1].eventTypeSlug)
        assertNull(rows[1].hostId)
        assertNull("absent attendees are unknown, never empty", rows[1].attendees)
        assertNull(rows[1].createdAt)

        // ⚠️ AND NO REASON EITHER, which is why `cancellation_reason`'s presence is evidence and
        // its absence is not. Branch on `status`.
        assertNull(rows[1].cancellationReason)
    }

    @Test
    fun `the single booking read carries the cancellation reason the list row lacked`() {
        val booking = SchedulingAdminFixtures.data(
            "district-scheduling-booking.json",
            SchedulingBooking.serializer(),
        )

        assertEquals("cancelled", booking.status)
        assertEquals("Customer asked to postpone", booking.cancellationReason)
        assertEquals("2026-09-11T11:00:00Z", booking.updatedAt)
        assertEquals(1, booking.attendees?.size)
    }

    @Test
    fun `a booking with no start time is rejected rather than decoded`() {
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingBooking.serializer(),
                    """{"id":"bk","status":"confirmed","end_at":"2026-09-14T13:30:00Z"}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `booking answers keep an empty string as a real answer`() {
        val answers = SchedulingAdminFixtures.data(
            "district-scheduling-booking-answers.json",
            SchedulingItems.serializer(SchedulingBookingAnswer.serializer()),
        ).items

        assertEquals(2, answers.size)
        assertEquals("q-contract-1", answers[0].questionId)
        assertEquals("District", answers[0].value)
        assertEquals("select", answers[0].type)

        // ⛔ `""` IS "LEFT BLANK" AND IS NOT NULL. Every field on this type is required precisely
        // so a blank answer cannot be collapsed into "no answer" — the two are different things to
        // whoever reads the booking.
        assertEquals("", answers[1].value)
        assertEquals("text", answers[1].type)
    }

    @Test
    fun `notes distinguish written from pending, and both from absent`() {
        val notes = SchedulingAdminFixtures.data(
            "district-scheduling-booking-notes.json",
            SchedulingBookingNotes.serializer(),
        )
        val regenerated = SchedulingAdminFixtures.data(
            "district-scheduling-booking-notes-regenerated.json",
            SchedulingBookingNotesRegenerated.serializer(),
        )

        assertTrue(notes.exists)
        assertEquals("Dana wants a quote for twelve seats.", notes.content)
        assertEquals("ready", notes.status)
        assertEquals("2026-09-14T13:35:00Z", notes.updatedAt)

        // ⛔ THE REGENERATE ANSWER IS A NEAR-TWIN WITH NO `updated_at`, WHICH IS THE CATALOG'S
        // SHAPE. A shared type would model a key the route strips and the strict decoder would
        // fail on it — which is the correct direction and worth knowing before merging the two.
        assertTrue(regenerated.exists)
        assertEquals("pending", regenerated.status)
        assertEquals(
            "Dana wants a quote for twelve seats, and a security review.",
            regenerated.content,
        )
        assertTrue(
            "the regenerate fixture really does omit updated_at",
            !ContractFixtures.read("district-scheduling-booking-notes-regenerated.json")
                .contains("updated_at"),
        )
    }

    @Test
    fun `a notes body with no exists flag is rejected rather than read as absent notes`() {
        // ⛔ `exists` IS NOT `content != null`. A row can exist with status `pending` and no
        // content yet, which a screen words as "being written" rather than "none" — and reading
        // absence as "no notes" offers a Generate button that starts a second run of the one
        // already in flight.
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingBookingNotes.serializer(),
                    """{"content":"something","status":"ready"}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `a transcript decodes its text and tolerates having none`() {
        val transcript = SchedulingAdminFixtures.data(
            "district-scheduling-booking-transcript.json",
            SchedulingBookingTranscript.serializer(),
        )

        assertTrue(transcript.exists)
        assertEquals(
            "Host: Thanks for joining. Attendee: Happy to be here.",
            transcript.text,
        )

        // ⚠️ THE ABSENT HALF OF THE PAIR, which no fixture covers because the route only ships a
        // transcript when there is one. `exists: false` with no text is the shape the fork sends
        // for a call that was never recorded.
        val empty = ContractFixtures.json.decodeFromString(
            SchedulingBookingTranscript.serializer(),
            """{"exists":false}""",
        )
        assertEquals(false, empty.exists)
        assertNull(empty.text)
    }

    @Test
    fun `every booking fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-bookings.json",
            SchedulingBookingPage.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-booking.json",
            SchedulingBooking.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-booking-answers.json",
            SchedulingItems.serializer(SchedulingBookingAnswer.serializer()),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-booking-notes.json",
            SchedulingBookingNotes.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-booking-notes-regenerated.json",
            SchedulingBookingNotesRegenerated.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-booking-transcript.json",
            SchedulingBookingTranscript.serializer(),
        )
    }
}
