package com.distronode.districtai.ui

import com.distronode.districtai.applinks.DistrictSection
import com.distronode.districtai.core.model.WorkspaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The desk and support route builders, and their app-link mappings.
 *
 * ⛔ A SEPARATE CLASS FROM `RoutesTest`, for the reason the contract-fixture tests split: that file
 * is already at detekt's LargeClass ceiling and the healthy answer is another class rather than a
 * raised threshold.
 *
 * ⚠️ Robolectric, because `Uri.encode` is `android.net.Uri` and returns null off-device.
 */
@RunWith(RobolectricTestRunner::class)
class DeskSupportRoutesTest {

    private fun segments(route: String) = route.split("/").size

    @Test
    fun `every template and its builder agree on segment count`() {
        // ⛔ A BUILDER PRODUCING A DIFFERENT SEGMENT COUNT THAN ITS TEMPLATE NEVER MATCHES, and
        // Navigation's failure for that is a silent no-op rather than an exception — the tap simply
        // does nothing.
        assertEquals(
            segments(Routes.DESK),
            segments(Routes.desk("ws1", WorkspaceRole.CLIENT)),
        )
        assertEquals(
            segments(Routes.DESK_TICKET),
            segments(Routes.deskTicket("ws1", WorkspaceRole.CLIENT, "tkt1")),
        )
        assertEquals(
            segments(Routes.DESK_SETTINGS),
            segments(Routes.deskSettings("ws1", WorkspaceRole.CLIENT)),
        )
        assertEquals(
            segments(Routes.SUPPORT),
            segments(Routes.support("ws1", WorkspaceRole.CLIENT)),
        )
        assertEquals(
            segments(Routes.SUPPORT_REQUEST),
            segments(Routes.supportRequest("ws1", WorkspaceRole.CLIENT, "DA-42")),
        )
    }

    @Test
    fun `the desk and support paths are DIFFERENT, because they are different products`() {
        // ⛔ `desk` IS THE TENANT'S CUSTOMERS WRITING TO THEM; `support` IS THE TENANT WRITING TO
        // US. One shared path, or one shared destination, would collapse the distinction the
        // server keeps with two separate route families and two separate role guards.
        assertTrue(Routes.desk("ws1", WorkspaceRole.CLIENT).contains("/desk/"))
        assertTrue(Routes.support("ws1", WorkspaceRole.CLIENT).contains("/support/"))
        assertFalse(Routes.desk("ws1", WorkspaceRole.CLIENT).contains("/support/"))
        assertFalse(Routes.support("ws1", WorkspaceRole.CLIENT).contains("/desk/"))
    }

    @Test
    fun `the desk sections nest under the queue, so back lands on the queue`() {
        val queue = Routes.desk("ws1", WorkspaceRole.CLIENT)

        assertTrue(Routes.deskTicket("ws1", WorkspaceRole.CLIENT, "tkt1").startsWith("$queue/"))
        assertTrue(Routes.deskSettings("ws1", WorkspaceRole.CLIENT).startsWith("$queue/"))
    }

    @Test
    fun `a null role becomes the literal none, which fromWire rejects`() {
        // ⛔ FAILS CLOSED. Every desk and support route excludes `viewer` on the READ as well as the
        // writes, so a corrupted role must land on the refusal rather than on the queue.
        assertTrue(Routes.desk("ws1", null).endsWith("/none"))
        assertTrue(Routes.support("ws1", null).endsWith("/none"))
        assertNull(WorkspaceRole.fromWire("none"))
    }

    @Test
    fun `every role round-trips through both paths`() {
        WorkspaceRole.entries.forEach { role ->
            val desk = Routes.desk("ws1", role).substringAfterLast('/')
            val support = Routes.support("ws1", role).substringAfterLast('/')
            assertEquals(role, WorkspaceRole.fromWire(desk))
            assertEquals(role, WorkspaceRole.fromWire(support))
        }
    }

    @Test
    fun `a workspace id containing a slash cannot escape its segment`() {
        // ⚠️ An unencoded `/` would address a different destination entirely. Both ids are
        // server-generated, but a value must not be able to change where it points.
        val desk = Routes.desk("ws/../admin", WorkspaceRole.CLIENT)
        assertFalse(desk.contains("ws/.."))
        assertEquals(segments(Routes.DESK), segments(desk))

        val support = Routes.support("ws/../admin", WorkspaceRole.CLIENT)
        assertFalse(support.contains("ws/.."))
        assertEquals(segments(Routes.SUPPORT), segments(support))
    }

    @Test
    fun `a ticket id containing a slash cannot address another destination`() {
        val route = Routes.deskTicket("ws1", WorkspaceRole.CLIENT, "tkt/../settings")

        assertFalse(route.contains("tkt/.."))
        assertEquals(segments(Routes.DESK_TICKET), segments(route))
    }

    @Test
    fun `a support key containing a slash cannot escape, and a Jira key survives intact`() {
        val hostile = Routes.supportRequest("ws1", WorkspaceRole.CLIENT, "DA/../42")
        assertFalse(hostile.contains("DA/.."))
        assertEquals(segments(Routes.SUPPORT_REQUEST), segments(hostile))

        // ⚠️ A `-` is path-safe, so the ordinary `DA-42` must survive unmangled: a client that
        // percent-encoded it would address a key the server cannot resolve.
        assertTrue(
            Routes.supportRequest("ws1", WorkspaceRole.CLIENT, "DA-42").endsWith("/DA-42"),
        )
    }

    @Test
    fun `a support request is addressable by our own row id as well as by the issue key`() {
        // ⛔ A REQUEST WE HOLD BUT HAVE NOT FILED HAS NO ISSUE KEY. A builder that only accepted
        // `DA-nn` would make exactly the requests a customer is most anxious about unreachable.
        val byRowId = Routes.supportRequest("ws1", WorkspaceRole.CLIENT, "row_2")

        assertTrue(byRowId.endsWith("/row_2"))
        assertEquals(segments(Routes.SUPPORT_REQUEST), segments(byRowId))
    }

    // ── The app-link mappings ────────────────────────────────────────────────

    @Test
    fun `both sections resolve to their own workspace route`() {
        assertEquals(
            Routes.desk("ws1", WorkspaceRole.CLIENT),
            workspaceRoute(DistrictSection.DESK, "ws1", WorkspaceRole.CLIENT),
        )
        assertEquals(
            Routes.support("ws1", WorkspaceRole.CLIENT),
            workspaceRoute(DistrictSection.SUPPORT, "ws1", WorkspaceRole.CLIENT),
        )
    }

    @Test
    fun `neither section is navigable before a workspace resolves`() {
        // ⚠️ Both are workspace-scoped, so a link tapped on a cold start waits rather than landing
        // somewhere wrong. `appLinkRoute` returning null is what keeps the link pending.
        assertNull(appLinkRoute(DistrictSection.DESK, workspaceId = null, role = null))
        assertNull(appLinkRoute(DistrictSection.SUPPORT, workspaceId = null, role = null))
    }

    @Test
    fun `a viewer's link still resolves, and the screen is what refuses`() {
        // ⚠️ THE ROUTE IS BUILT AND THE DESTINATION REFUSES, rather than the link being dropped. A
        // dropped link would leave the user on whatever screen they were on with no explanation;
        // the refusal screen says why the desk is not available on their seat.
        val route = appLinkRoute(DistrictSection.DESK, "ws1", WorkspaceRole.VIEWER)

        assertEquals(Routes.desk("ws1", WorkspaceRole.VIEWER), route)
        assertEquals(WorkspaceRole.VIEWER, WorkspaceRole.fromWire(route!!.substringAfterLast('/')))
    }
}
