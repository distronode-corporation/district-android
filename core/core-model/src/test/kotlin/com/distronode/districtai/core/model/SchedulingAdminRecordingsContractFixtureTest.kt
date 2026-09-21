package com.distronode.districtai.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `recordings.*` family: the list, the bulk-delete tally and the consent roll.
 *
 * ⛔ NEITHER LIST HERE USES THE CATALOG'S SHARED `{items}` WRAPPER. `recordings.list` declares
 * `{recordings}` and `recordings.consent` declares `{consents}`, both by hand server-side; reading
 * either through [SchedulingItems] decodes nothing and reports an EMPTY list, which is a wrong
 * answer rather than an error. Both are pinned below.
 */
class SchedulingAdminRecordingsContractFixtureTest {

    private fun recordings(): List<SchedulingRecording> = SchedulingAdminFixtures.data(
        "district-scheduling-recordings.json",
        SchedulingRecordingList.serializer(),
    ).recordings

    @Test
    fun `a ready recording decodes every field and a failed one decodes two`() {
        val rows = recordings()

        assertEquals(2, rows.size)
        assertEquals("rec-contract-1", rows[0].id)
        assertEquals("bk-contract-1", rows[0].bookingId)
        assertEquals("booking-bk-contract-1", rows[0].room)
        assertEquals("ready", rows[0].status)
        assertEquals(1793, rows[0].durationS)
        assertEquals(true, rows[0].hasFile)
        assertEquals("Dana Booker", rows[0].bookerName)

        // ⛔ THE SPARSE ROW IS WHY ONLY TWO FIELDS ARE REQUIRED. A failed recording has an id and a
        // status and nothing else — no room, no booking, no duration — so anything else made
        // required would refuse the row an operator most needs to see.
        assertEquals("rec-contract-2", rows[1].id)
        assertEquals("failed", rows[1].status)
        assertNull(rows[1].bookingId)
        assertNull(rows[1].room)
        assertNull(rows[1].durationS)
        assertNull(rows[1].createdAt)

        // ⛔ AND `has_file` IS ABSENT RATHER THAN FALSE. It is NOT derivable from `status`: a
        // recording can read ready with its object already removed by retention, and a play button
        // drawn from the status alone is a button that fails.
        assertNull("a failed recording states nothing about its file", rows[1].hasFile)
    }

    @Test
    fun `the recordings list is not readable through the shared items wrapper`() {
        assertTrue(
            runCatching {
                SchedulingAdminFixtures.data(
                    "district-scheduling-recordings.json",
                    SchedulingItems.serializer(SchedulingRecording.serializer()),
                )
            }.isFailure,
        )
    }

    @Test
    fun `a recording with no status is rejected rather than decoded`() {
        assertTrue(
            runCatching {
                ContractFixtures.json.decodeFromString(
                    SchedulingRecording.serializer(),
                    """{"id":"rec","room":"r"}""",
                )
            }.isFailure,
        )
    }

    @Test
    fun `the bulk delete reports what it could not remove without calling itself a failure`() {
        val deleted = SchedulingAdminFixtures.data(
            "district-scheduling-recordings-deleted.json",
            SchedulingRecordingsDeleted.serializer(),
        )

        // ⛔ A NON-ZERO `failed` IS NOT A FAILURE OF THE OP. The op succeeded; some objects could
        // not be removed from storage and their rows survive so the next run retries. Reporting
        // the whole operation as failed would tell an operator nothing was deleted when four were.
        assertEquals(4, deleted.deleted)
        assertEquals(1, deleted.failed)
    }

    @Test
    fun `consent is three-valued and pending is not denied`() {
        val consents = SchedulingAdminFixtures.data(
            "district-scheduling-recordings-consent.json",
            SchedulingRecordingConsents.serializer(),
        ).consents

        assertEquals(2, consents.size)

        assertEquals("host-sched-user-contract", consents[0].identity)
        assertEquals("Contract Member", consents[0].name)
        assertEquals("granted", consents[0].decision)
        assertEquals("2026-09-14T13:00:05Z", consents[0].decidedAt)

        // ⛔ THE PRESENT-AND-ABSENT PAIR, AND THE ABSENCE IS A LEGAL POSITION. This guest was asked
        // and has not answered; rendering that as a refusal misreports it, and rendering it as
        // consent is worse. `name` and `decided_at` are both absent because neither exists yet.
        assertEquals("guest-dana", consents[1].identity)
        assertEquals("pending", consents[1].decision)
        assertNull("a pending participant has given no name", consents[1].name)
        assertNull("a pending decision has no moment", consents[1].decidedAt)
    }

    @Test
    fun `the consent roll is not readable through the shared items wrapper either`() {
        assertTrue(
            runCatching {
                SchedulingAdminFixtures.data(
                    "district-scheduling-recordings-consent.json",
                    SchedulingItems.serializer(SchedulingRecordingConsent.serializer()),
                )
            }.isFailure,
        )
    }

    @Test
    fun `every recordings fixture survives a round trip in both encodings`() {
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-recordings.json",
            SchedulingRecordingList.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-recordings-deleted.json",
            SchedulingRecordingsDeleted.serializer(),
        )
        SchedulingAdminFixtures.roundTrips(
            "district-scheduling-recordings-consent.json",
            SchedulingRecordingConsents.serializer(),
        )
    }
}
