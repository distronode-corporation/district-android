// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The widget's write rules (the iOS app's `WidgetSnapshotTests`): counts belong to a workspace id,
 * a late answer never lands under another workspace or after a sign-out, and a redraw is asked for
 * only when something changed.
 */
class HomeWidgetPublisherTest {

    private val harness = PublisherHarness()
    private val publisher = harness.publisher
    private val store = harness.store

    @Test
    fun `a workspace, then its counts, are written and each asks for a redraw`() {
        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordUnread("ws-a", 3)
        publisher.recordCalls("ws-a", 12)

        assertEquals(
            HomeWidgetSnapshot("ws-a", "Alpha", unread = 3, callsLast7Days = 12, callsReadAtMillis = 1_000L),
            store.snapshot,
        )
        assertEquals(3, harness.redraws)
    }

    @Test
    fun `a switch drops the other workspace's counts, even to a workspace with the same name`() {
        publisher.workspaceRead().show("ws-a", "Front desk")
        publisher.recordUnread("ws-a", 3)
        publisher.recordCalls("ws-a", 12)

        publisher.workspaceRead().show("ws-b", "Front desk")

        assertEquals(HomeWidgetSnapshot("ws-b", "Front desk"), store.snapshot)
    }

    @Test
    fun `a rename keeps the counts, which are still that workspace's`() {
        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordUnread("ws-a", 3)

        publisher.workspaceRead().show("ws-a", "Alpha Inc")

        assertEquals(HomeWidgetSnapshot("ws-a", "Alpha Inc", unread = 3), store.snapshot)
    }

    @Test
    fun `a count for another workspace, or with nothing on the widget, is dropped`() {
        publisher.recordUnread("ws-a", 3)
        publisher.recordCalls("ws-a", 12)
        assertNull("nothing shown, nothing written", store.snapshot)

        publisher.workspaceRead().show("ws-b", "Bravo")
        publisher.recordUnread("ws-a", 3)
        publisher.recordCalls("ws-a", 12)

        assertEquals(HomeWidgetSnapshot("ws-b", "Bravo"), store.snapshot)
        assertEquals(1, harness.redraws)
    }

    @Test
    fun `a count read from a saved copy is not recorded, and the live one stays`() {
        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordCalls("ws-a", 12)
        publisher.recordUnread("ws-a", 3)
        val live = store.snapshot

        // Offline: the Overview and the Inbox answer from their saved copies.
        harness.offline = true
        harness.now = 90_000_000L
        publisher.recordCalls("ws-a", 7)
        publisher.recordUnread("ws-a", 9)

        assertEquals("the last live counts, with their own read time", live, store.snapshot)

        harness.offline = false
        publisher.recordCalls("ws-a", 8)
        assertEquals(8, store.snapshot?.callsLast7Days)
    }

    @Test
    fun `counts are clamped at zero`() {
        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordUnread("ws-a", -2)
        publisher.recordCalls("ws-a", -1)

        assertEquals(0, store.snapshot?.unread)
        assertEquals(0, store.snapshot?.callsLast7Days)
    }

    @Test
    fun `the same snapshot again is not written and asks for no redraw`() {
        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordUnread("ws-a", 3)
        val writes = store.writes

        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordUnread("ws-a", 3)

        assertEquals(writes, store.writes)
        assertEquals(2, harness.redraws)
    }

    @Test
    fun `the same call count read later restarts its day`() {
        publisher.workspaceRead().show("ws-a", "Alpha")
        publisher.recordCalls("ws-a", 12)
        harness.now = 5_000L

        publisher.recordCalls("ws-a", 12)

        assertEquals(5_000L, store.snapshot?.callsReadAtMillis)
        assertEquals(3, harness.redraws)
    }

    @Test
    fun `sign-out clears the snapshot and redraws, and a second clear does neither`() {
        publisher.workspaceRead().show("ws-a", "Alpha")

        publisher.clear()
        assertNull(store.snapshot)
        assertEquals(2, harness.redraws)

        val writes = store.writes
        publisher.clear()
        assertEquals(writes, store.writes)
        assertEquals(2, harness.redraws)
    }

    @Test
    fun `a workspace list that answers after a sign-out writes nothing`() {
        // ⛔ Taken before the request, as the Overview does; the sign-out lands while it is in flight.
        val inFlight = publisher.workspaceRead()
        publisher.clear()

        inFlight.show("ws-a", "Alpha")

        assertNull(store.snapshot)
        assertEquals(0, harness.redraws)

        // A read taken after the sign-out (the next account's) is written as usual.
        publisher.workspaceRead().show("ws-b", "Bravo")
        assertEquals(HomeWidgetSnapshot("ws-b", "Bravo"), store.snapshot)
    }

    @Test
    fun `without a clock, a call count is stamped with the system time`() {
        val fake = FakeHomeWidgetStore()
        val real = HomeWidgetPublisher(store = fake, redraw = {})
        real.workspaceRead().show("ws-a", "Alpha")

        val before = System.currentTimeMillis()
        real.recordCalls("ws-a", 1)
        val after = System.currentTimeMillis()

        val stamped = fake.snapshot?.callsReadAtMillis ?: error("no read time")
        assertEquals(true, stamped in before..after)
    }
}
