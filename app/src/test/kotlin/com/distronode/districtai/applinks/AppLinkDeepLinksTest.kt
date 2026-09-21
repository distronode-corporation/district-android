package com.distronode.districtai.applinks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pending-link holder.
 *
 * ⛔ ITS OWN FILE RATHER THAN A BLOCK INSIDE `AppLinkResolverTest`, mirroring `PushDeepLinksTest`
 * beside `PushIntentActionTest`. The two production files answer different questions — one decides
 * WHAT a URL means, this one decides WHEN the answer can be acted on — and the holder is the half
 * that has to survive a rotation mid-load.
 *
 * ⛔ THE HOLDER EXISTS BECAUSE THE INTENT ARRIVES BEFORE ANYTHING CAN NAVIGATE. `MainActivity` gets
 * the `ACTION_VIEW` in `onCreate`/`onNewIntent`, at which point the graph may not be composed and —
 * the binding constraint — the active workspace is unknown, because it comes from `workspace/list`
 * plus `overview`, both still in flight on a cold start.
 */
class AppLinkDeepLinksTest {

    @Test
    fun `nothing outstanding is the normal state`() {
        assertNull(AppLinkDeepLinks().pending.value)
    }

    @Test
    fun `an offered section is held until it is cleared`() {
        val links = AppLinkDeepLinks()

        links.offer(DistrictSection.BILLING)
        assertEquals(DistrictSection.BILLING, links.pending.value)

        links.clear()
        assertNull(links.pending.value)
    }

    @Test
    fun `the last section offered wins`() {
        // ⚠️ TWO LINKS TAPPED IN QUICK SUCCESSION ARE ONE INTENT EACH, and the second is the one the
        // user is looking at. There is no queue because there is no sensible way to honour the first
        // afterwards — it would navigate away from the screen they just asked for.
        val links = AppLinkDeepLinks()

        links.offer(DistrictSection.INBOX)
        links.offer(DistrictSection.CONTACTS)

        assertEquals(DistrictSection.CONTACTS, links.pending.value)
    }

    @Test
    fun `clearing when nothing is pending is a no-op rather than a fault`() {
        // ⛔ `clear` IS CALLED ON EVERY RESOLUTION, INCLUDING ONES THAT NAVIGATE NOWHERE — a pending
        // link that survived its own resolution would re-fire the moment the workspace finished
        // loading for the user's own reasons and yank them somewhere they did not ask to go. So the
        // idempotent case is the ordinary one, not an edge.
        val links = AppLinkDeepLinks()

        links.clear()
        assertNull(links.pending.value)

        links.offer(DistrictSection.HQ)
        links.clear()
        links.clear()
        assertNull(links.pending.value)
    }

    @Test
    fun `a section can be offered again after being cleared`() {
        // The same link tapped twice is two independent deliveries, not a deduplicated one: the
        // holder carries no memory of what it already served.
        val links = AppLinkDeepLinks()

        links.offer(DistrictSection.BILLING)
        links.clear()
        links.offer(DistrictSection.BILLING)

        assertEquals(DistrictSection.BILLING, links.pending.value)
    }
}
