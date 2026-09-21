package com.distronode.districtai.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.distronode.districtai.applinks.AppLinkDeepLinks
import com.distronode.districtai.applinks.DistrictSection
import com.distronode.districtai.core.model.WorkspaceRole

/**
 * The in-app route for a claimed dashboard section, or null while it is not yet navigable.
 *
 * ⛔ NULL MEANS **WAIT**, NOT "NOWHERE". Every workspace-scoped route needs an id that arrives from
 * two reads still in flight on a cold start; returning null is what makes the caller hold the link
 * rather than drop it. Dropping would make App Links work only when the app was already open and a
 * workspace already loaded, i.e. not in the case they exist for.
 *
 * ⛔ THIS LIVES IN THE `ui` PACKAGE AND NOT BESIDE THE RESOLVER, BECAUSE IT TOUCHES THE FRAMEWORK.
 * Every builder in [workspaceRoute] runs `Uri.encode`, which in a plain JVM unit test is stubbed to
 * return null (`isReturnDefaultValues = true`). The resolver stays pure and plain-JVM-testable
 * precisely because it never builds a route; this half is exercised under Robolectric, where `Uri`
 * is real. Keeping them in one file would drag the pure half onto the slow runner for no gain.
 *
 * ⛔ SPLIT IN TWO, AND detekt IS WHY — the same ceiling `DistrictNavHost`'s extracted destination
 * builders document. As one function this was a ten-arm `when` in which eight arms carried a `?.let`,
 * which detekt scores at cyclomatic complexity **19** against a threshold of 15, and
 * `maxIssues: 0` makes that a build failure rather than a warning. The cut is along a real seam: the
 * outer function decides only whether the app knows enough to navigate, and [workspaceRoute] holds
 * the mapping.
 *
 * @param workspaceId the ACTIVE workspace, once the overview has resolved one.
 * @param role the active workspace's role, encoded into every tenant route. ⚠️ Null becomes the
 *   literal "none", which `WorkspaceRole.fromWire` fails closed on — the safe direction.
 */
internal fun appLinkRoute(
    section: DistrictSection,
    workspaceId: String?,
    role: WorkspaceRole?,
): String? = when {
    // ── Navigable immediately: it needs no workspace ──────────────────────────────────
    // ⚠️ The overview IS the start destination, so this is a no-op on a cold start and a real
    // navigation when the user was somewhere else. Either way it is where an unmapped dashboard
    // URL belongs: see the nine listed in AppLinkResolver.
    section == DistrictSection.OVERVIEW -> Routes.OVERVIEW
    // ── Everything else is workspace-scoped ───────────────────────────────────────────
    workspaceId == null -> null
    else -> workspaceRoute(section, workspaceId, role)
}

/**
 * The route for a section, given a workspace that has resolved.
 *
 * ⛔ THE `when` IS EXHAUSTIVE OVER [DistrictSection] SO A NEW SECTION IS A COMPILE ERROR, AND THAT
 * PROPERTY MOVED HERE WHEN [appLinkRoute] WAS SPLIT. It is the whole point of the enum:
 * `AppLinkResolver` maps an unrecognised URL segment to [DistrictSection.OVERVIEW], so a section
 * added there without a route here would silently land on the overview and look like a mapping that
 * works. The outer function's `when` is over booleans and could not carry it.
 *
 * ⚠️ TOTAL RATHER THAN PARTIAL: the overview answers here too, ignoring the workspace. It is
 * unreachable through [appLinkRoute], which short-circuits it before this is called, but a partial
 * function whose "impossible" arm returns a wrong route is exactly the shape that becomes a real
 * bug the day the caller changes. `AppLinkEffectsTest` calls this directly so the arm is asserted
 * rather than assumed.
 */
internal fun workspaceRoute(
    section: DistrictSection,
    workspaceId: String,
    role: WorkspaceRole?,
): String = when (section) {
    DistrictSection.INBOX -> Routes.inbox(workspaceId, role)
    DistrictSection.CALLS -> Routes.callLog(workspaceId)
    DistrictSection.CONTACTS -> Routes.contacts(workspaceId, role)
    DistrictSection.HQ -> Routes.hq(workspaceId, role)
    DistrictSection.ANALYTICS -> Routes.analytics(workspaceId)
    DistrictSection.BILLING -> Routes.billing(workspaceId, role)
    DistrictSection.MARKETPLACE -> Routes.marketplace(workspaceId, role)
    DistrictSection.WORKFLOWS -> Routes.workflows(workspaceId, role)
    // ⚠️ THE ONE ARM THAT DROPS `role` ON THE FLOOR, and it is the safest arm here rather than the
    // sloppiest. `Routes.SCHEDULING` carries no role segment at all, because the status route sends
    // `canManage` with every read — so the cross-tenant hazard this whole function has to be
    // careful about (a link naming workspace B navigated with the role held in workspace A) is
    // structurally absent for this destination.
    DistrictSection.SCHEDULING -> Routes.scheduling(workspaceId)
    // ⛔ TWO PRODUCTS ONE SEGMENT APART, AND THE ROLE IS CARRIED FOR A DIFFERENT REASON THAN ON
    // EVERY ARM ABOVE: it gates the READ, not a control, because every desk and support route
    // excludes `viewer`. That makes the cross-tenant hazard this function is careful about fail
    // in the SAFE direction — a link naming workspace B, navigated with the role held in A, lands
    // a viewer-in-B on a screen whose every request the server refuses.
    DistrictSection.DESK -> Routes.desk(workspaceId, role)
    DistrictSection.SUPPORT -> Routes.support(workspaceId, role)
    DistrictSection.OVERVIEW -> Routes.OVERVIEW
}

/**
 * Navigate to the section a verified App Link named, once the app knows enough to.
 *
 * ⛔ OUTSIDE `DistrictNavHost` FOR THE REASON `PushDeepLinkEffect` IS: lambdas inside that function
 * count toward its cyclomatic complexity, which is already at detekt's ceiling.
 *
 * ⛔ `launchSingleTop`, WHICH IS NOT COSMETIC HERE. A link tapped repeatedly — the ordinary shape
 * for a URL in a group chat — would otherwise stack one identical destination per tap, and the user
 * would have to press back once per tap to escape. It also keeps a `/dashboard/district` link from
 * pushing a second overview on top of the start destination.
 *
 * ⚠️ THE LINK IS CLEARED ONLY WHEN IT RESOLVES. While [appLinkRoute] returns null the effect leaves
 * it pending and re-runs when `workspaceId` changes, which is what makes a cold start work.
 */
@Composable
internal fun AppLinkDeepLinkEffect(
    deepLinks: AppLinkDeepLinks,
    workspaceId: String?,
    role: WorkspaceRole?,
    navController: NavHostController,
) {
    val pending by deepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending, workspaceId, role) {
        val section = pending ?: return@LaunchedEffect
        val route = appLinkRoute(section, workspaceId, role) ?: return@LaunchedEffect
        deepLinks.clear()
        navController.navigate(route) { launchSingleTop = true }
    }
}
