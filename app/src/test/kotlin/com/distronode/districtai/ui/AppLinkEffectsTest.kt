package com.distronode.districtai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.applinks.AppLinkDeepLinks
import com.distronode.districtai.applinks.DistrictSection
import com.distronode.districtai.core.model.WorkspaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Turning a resolved [DistrictSection] into a route, and the effect that navigates to it.
 *
 * ⚠️ ROBOLECTRIC, UNLIKE `AppLinkResolverTest`, AND THE SPLIT IS THE WHOLE REASON THESE ARE TWO
 * FILES. Every builder in [workspaceRoute] runs `Uri.encode`, which returns null on the bare JVM
 * under `isReturnDefaultValues = true` — so this half needs a real Android runtime and the resolver
 * half deliberately does not. `RoutesTest` makes the same call for the same reason.
 *
 * ⛔ THE PROPERTY WORTH MOST HERE IS THAT NULL MEANS **WAIT**, NOT "NOWHERE". Every workspace-scoped
 * route needs an id that arrives from two reads still in flight on a cold start; if null were read
 * as "drop it", App Links would work only when the app was already open with a workspace already
 * loaded, which is not the case they exist for.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class AppLinkEffectsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── appLinkRoute: can the app act on this link yet ────────────────────────────────────

    @Test
    fun `the overview needs no workspace`() {
        // ⚠️ IT IS THE START DESTINATION, so this is a no-op on a cold start and a real navigation
        // when the user was somewhere else. Either way it is where an unmapped dashboard URL belongs.
        assertEquals(Routes.OVERVIEW, appLinkRoute(DistrictSection.OVERVIEW, null, null))
    }

    @Test
    fun `every workspace-scoped section waits while the workspace is unresolved`() {
        // ⛔ THIS IS THE STATE FOR THE WHOLE OF THE FIRST TWO READS ON A COLD START FROM A LINK.
        DistrictSection.entries
            .filter { it != DistrictSection.OVERVIEW }
            .forEach { assertNull(it.name, appLinkRoute(it, workspaceId = null, role = null)) }
    }

    @Test
    fun `a resolved workspace unblocks every section`() {
        DistrictSection.entries.forEach {
            assertNotNull(it.name, appLinkRoute(it, workspaceId = "ws-1", role = WorkspaceRole.CLIENT))
        }
    }

    @Test
    fun `the role does not decide whether a link is navigable`() {
        // ⚠️ A VIEWER FOLLOWS THE SAME LINK TO THE SAME SCREEN. The role is encoded into the route
        // because destinations gate their own writes on it; it is not an admission check here, and
        // making it one would silently swallow links for the least-privileged member.
        WorkspaceRole.entries.forEach { role ->
            assertNotNull(role.name, appLinkRoute(DistrictSection.HQ, "ws-1", role))
        }
    }

    // ── workspaceRoute: the exhaustive mapping ────────────────────────────────────────────

    @Test
    fun `each workspace-scoped section maps to its own destination`() {
        val role = WorkspaceRole.CLIENT

        assertEquals(Routes.inbox("ws-1", role), workspaceRoute(DistrictSection.INBOX, "ws-1", role))
        assertEquals(Routes.callLog("ws-1"), workspaceRoute(DistrictSection.CALLS, "ws-1", role))
        assertEquals(
            Routes.contacts("ws-1", role),
            workspaceRoute(DistrictSection.CONTACTS, "ws-1", role),
        )
        assertEquals(Routes.hq("ws-1", role), workspaceRoute(DistrictSection.HQ, "ws-1", role))
        assertEquals(
            Routes.analytics("ws-1"),
            workspaceRoute(DistrictSection.ANALYTICS, "ws-1", role),
        )
        assertEquals(Routes.billing("ws-1", role), workspaceRoute(DistrictSection.BILLING, "ws-1", role))
        assertEquals(
            Routes.marketplace("ws-1", role),
            workspaceRoute(DistrictSection.MARKETPLACE, "ws-1", role),
        )
        assertEquals(
            Routes.workflows("ws-1", role),
            workspaceRoute(DistrictSection.WORKFLOWS, "ws-1", role),
        )
        // ⛔ THE ONE ARM THAT DROPS THE ROLE, AND IT IS THE SAFE ONE. `Routes.SCHEDULING` carries
        // no role segment because the status route sends `canManage` with every read — so the
        // cross-tenant hazard this whole mapping has to be careful about (a link naming workspace
        // B navigated with the role held in workspace A) is structurally absent here.
        assertEquals(
            Routes.scheduling("ws-1"),
            workspaceRoute(DistrictSection.SCHEDULING, "ws-1", role),
        )
        assertEquals(
            "a role must not change where a scheduling link lands",
            workspaceRoute(DistrictSection.SCHEDULING, "ws-1", null),
            workspaceRoute(DistrictSection.SCHEDULING, "ws-1", role),
        )
    }

    @Test
    fun `the overview answers here too, ignoring the workspace`() {
        // ⚠️ TOTAL RATHER THAN PARTIAL, AND ASSERTED RATHER THAN ASSUMED. This arm is unreachable
        // through `appLinkRoute`, which short-circuits it before this is called, but a partial
        // function whose "impossible" arm returns a WRONG route is exactly the shape that becomes a
        // real bug the day the caller changes.
        assertEquals(Routes.OVERVIEW, workspaceRoute(DistrictSection.OVERVIEW, "ws-1", WorkspaceRole.AGENCY))
    }

    @Test
    fun `every section produces a distinct route`() {
        // ⛔ THE EXHAUSTIVENESS GUARD, SPELLED AS AN ASSERTION. The `when` in `workspaceRoute` is
        // exhaustive over the enum, so a new section is a COMPILE error there — but a new section
        // wired to an existing route would still compile, and would look like a mapping that works
        // while sending two different URLs to the same screen.
        val routes = DistrictSection.entries.map { workspaceRoute(it, "ws-1", WorkspaceRole.CLIENT) }

        assertEquals(DistrictSection.entries.size, routes.toSet().size)
    }

    @Test
    fun `a null role becomes the literal none`() {
        // ⚠️ WHICH `WorkspaceRole.fromWire` FAILS CLOSED ON — the safe direction. A link followed
        // before the role is known lands read-only rather than landing with write controls enabled.
        assertTrue(workspaceRoute(DistrictSection.INBOX, "ws-1", null).endsWith("/none"))
    }

    @Test
    fun `the workspace id is encoded into the path`() {
        // ⛔ AN UNENCODED `/` ADDRESSES A DIFFERENT DESTINATION. The id comes from a URL the OS
        // handed us, so this is the one argument on this path with an untrusted origin.
        val route = workspaceRoute(DistrictSection.CALLS, "ws/1", WorkspaceRole.CLIENT)

        assertEquals("workspace/ws%2F1/calls", route)
    }

    // ── AppLinkDeepLinkEffect: acting on it, once ────────────────────────────────────────

    @Composable
    private fun Harness(
        deepLinks: AppLinkDeepLinks,
        workspaceId: String?,
        onController: (NavHostController) -> Unit,
    ) {
        val navController = rememberNavController()
        onController(navController)
        NavHost(navController = navController, startDestination = Routes.OVERVIEW) {
            composable(Routes.OVERVIEW) {}
            composable(Routes.BILLING) {}
        }
        AppLinkDeepLinkEffect(
            deepLinks = deepLinks,
            workspaceId = workspaceId,
            role = WorkspaceRole.CLIENT,
            navController = navController,
        )
    }

    @Test
    fun `nothing pending navigates nowhere`() {
        val deepLinks = AppLinkDeepLinks()
        var navController: NavHostController? = null
        composeRule.setContent { Harness(deepLinks, "ws-1") { navController = it } }
        composeRule.waitForIdle()

        assertEquals(Routes.OVERVIEW, navController?.currentDestination?.route)
    }

    @Test
    fun `a pending section navigates and is cleared`() {
        val deepLinks = AppLinkDeepLinks()
        var navController: NavHostController? = null
        composeRule.setContent { Harness(deepLinks, "ws-1") { navController = it } }
        composeRule.waitForIdle()

        deepLinks.offer(DistrictSection.BILLING)
        composeRule.waitForIdle()

        assertEquals(Routes.BILLING, navController?.currentDestination?.route)
        assertEquals(
            "the route carries the resolved workspace and role",
            "ws-1",
            navController?.currentBackStackEntry?.arguments?.getString("workspaceId"),
        )
        assertNull("a link that survived its own resolution would re-fire", deepLinks.pending.value)
    }

    @Test
    fun `a link held while the workspace is unresolved fires when it arrives`() {
        // ⛔ THE COLD-START CASE, END TO END, AND THE ONLY ONE THAT PROVES THE HOLDER EARNS ITS KEEP.
        // The link is offered while `workspaceId` is null — where `appLinkRoute` answers null and the
        // effect returns without clearing — and the navigation happens on the recomposition that
        // brings the workspace in.
        val deepLinks = AppLinkDeepLinks()
        var navController: NavHostController? = null
        var workspaceId by mutableStateOf<String?>(null)
        composeRule.setContent { Harness(deepLinks, workspaceId) { navController = it } }
        composeRule.waitForIdle()

        deepLinks.offer(DistrictSection.BILLING)
        composeRule.waitForIdle()
        assertEquals("still waiting", Routes.OVERVIEW, navController?.currentDestination?.route)
        assertEquals(DistrictSection.BILLING, deepLinks.pending.value)

        workspaceId = "ws-1"
        composeRule.waitForIdle()

        assertEquals(Routes.BILLING, navController?.currentDestination?.route)
        assertNull(deepLinks.pending.value)
    }

    @Test
    fun `the same link twice does not stack two copies of the destination`() {
        // ⛔ `launchSingleTop` IS NOT COSMETIC HERE. A link tapped repeatedly — the ordinary shape for
        // a URL in a group chat — would otherwise push one identical destination per tap, and the
        // user would have to press back once per tap to escape.
        val deepLinks = AppLinkDeepLinks()
        var navController: NavHostController? = null
        composeRule.setContent { Harness(deepLinks, "ws-1") { navController = it } }
        composeRule.waitForIdle()

        deepLinks.offer(DistrictSection.BILLING)
        composeRule.waitForIdle()
        deepLinks.offer(DistrictSection.BILLING)
        composeRule.waitForIdle()

        assertEquals(Routes.BILLING, navController?.currentDestination?.route)
        assertEquals(
            "one back press should land on the overview, not on a second billing screen",
            Routes.OVERVIEW,
            navController?.previousBackStackEntry?.destination?.route,
        )
    }
}
