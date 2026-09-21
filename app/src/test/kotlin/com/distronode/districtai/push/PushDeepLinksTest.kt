package com.distronode.districtai.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A message notification's deep link, and the three answers to "can it be acted on yet".
 *
 * ⛔ THE HOLDER EXISTS BECAUSE THE INTENT ARRIVES **BEFORE** ANYTHING CAN NAVIGATE. `MainActivity`
 * receives it in `onCreate`, at which point the active workspace is not known — it comes from
 * `GET /api/district/workspace/list` plus `GET /api/district/overview`, which are in flight. So the
 * decision is deferred rather than guessed, and this is where the deferral is checked.
 */
class PushDeepLinksTest {

    @Test
    fun `a pending link is held until it is cleared`() {
        val links = PushDeepLinks()

        assertNull("nothing outstanding is the normal state", links.pending.value)
        links.offer(InboxDeepLink("ws-1", "msg-1"))
        assertEquals(InboxDeepLink("ws-1", "msg-1"), links.pending.value)

        links.clear()
        assertNull(links.pending.value)
    }

    @Test
    fun `the last link offered wins`() {
        // ⚠️ TWO NOTIFICATIONS TAPPED IN QUICK SUCCESSION ARE ONE INTENT EACH, and the second is the
        // one the user is looking at. There is no queue because there is no sensible way to honour
        // the first afterwards — it would navigate away from the screen they just asked for.
        val links = PushDeepLinks()

        links.offer(InboxDeepLink("ws-1", "msg-1"))
        links.offer(InboxDeepLink("ws-2", "msg-2"))

        assertEquals(InboxDeepLink("ws-2", "msg-2"), links.pending.value)
    }

    @Test
    fun `nothing pending means nothing to decide`() {
        assertEquals(
            DeepLinkDecision.Wait,
            inboxDeepLinkDecision(pending = null, workspaceId = "ws-1"),
        )
    }

    @Test
    fun `an unresolved workspace means wait, not drop`() {
        // ⛔ ON A COLD START FROM A NOTIFICATION THIS IS THE STATE FOR THE WHOLE OF THE FIRST TWO
        // READS. Dropping here would make the deep link work only when the app was already open —
        // i.e. never in the case it exists for.
        assertEquals(
            DeepLinkDecision.Wait,
            inboxDeepLinkDecision(
                pending = InboxDeepLink("ws-1", "msg-1"),
                workspaceId = null,
            ),
        )
    }

    @Test
    fun `a link for the active workspace is acted on, carrying the message id to the resolver`() {
        // ⚠️ THE DECISION NO LONGER BUILDS A ROUTE. Reaching the thread needs a network exchange, so
        // this says WHETHER to act and hands the whole link on; resolveMessageDeepLinkRoute says where.
        assertEquals(
            DeepLinkDecision.Go(InboxDeepLink("ws-1", "msg-1")),
            inboxDeepLinkDecision(
                pending = InboxDeepLink("ws-1", "msg-1"),
                workspaceId = "ws-1",
            ),
        )
    }

    @Test
    fun `a link for a different workspace is dropped rather than switching tenants`() {
        // ⛔ A DELIBERATE LIMITATION RATHER THAN A BUG. The active workspace is explicit client state
        // the user chose; a notification silently re-pointing the whole app at another tenant —
        // mid-task, from a lock screen — is a worse outcome than landing on the overview. The
        // notification did its job: it said something arrived.
        assertEquals(
            DeepLinkDecision.Drop,
            inboxDeepLinkDecision(
                pending = InboxDeepLink("ws-2", "msg-2"),
                workspaceId = "ws-1",
            ),
        )
    }
}
