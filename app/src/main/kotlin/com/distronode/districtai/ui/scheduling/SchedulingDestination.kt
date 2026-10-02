package com.distronode.districtai.ui.scheduling

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.distronode.districtai.AppContainer
import com.distronode.districtai.auth.CustomTabsLauncher
import com.distronode.districtai.ui.ARG_WORKSPACE_ID
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.Routes
import com.distronode.districtai.ui.pathArgument

/**
 * The workspace's booking page, as a navigation destination.
 *
 * ⛔ IN THIS PACKAGE RATHER THAN IN `DistrictNavHost.kt`, WHICH IS WHERE EVERY OTHER DESTINATION
 * BUILDER LIVES, AND IT IS A CEILING RATHER THAN A PREFERENCE. That file was already at detekt's
 * `TooManyFunctions` limit for a FILE (11, and the rule fires AT the threshold, not above it), so
 * adding an eleventh top-level function there fails the build — while inlining the destination into
 * `DistrictNavHost` itself fails the OTHER ceiling, `CyclomaticComplexMethod`, because every lambda
 * and every `if` inside a `composable {}` block counts toward the enclosing function and that one
 * is already at 15. Two ceilings, one function, and this is the only placement that clears both.
 * The cut is along a real seam: the screen, its state machine and now its wiring are one package.
 *
 * ⚠️ `internal` RATHER THAN `private`, AND THAT IS THE COST OF THE MOVE. `Routes`,
 * [ARG_WORKSPACE_ID] and [OnSessionChanged] are `internal` to the module, so they are reachable
 * from here; this builder has to be at least as visible to be callable from `DistrictNavHost`.
 *
 * ⛔ THE HAND-OFF USES [CustomTabsLauncher.launch], NOT `launchExternally`, AND THE DIFFERENCE
 * MATTERS BOTH WAYS. `launchExternally` exists to stop an implicit intent for a URL THIS APP CLAIMS
 * from resolving back into the app — an unbounded relaunch loop with no user input in it. The
 * scheduler hand-off lands on `https://<workspace>-book.distronode.com/v1/auth/sso`, and the App
 * Links filter is bound to the two EXACT hosts `distronode.com` and `www.distronode.com` under a
 * `/dashboard/district` path prefix, so that URL is not claimable by this app and the loop is
 * impossible by construction. Using the stricter launcher anyway would REFUSE on a device with
 * several browsers and no default — where `browserPackage` correctly returns null after filtering
 * out the platform resolver activity — and the operator would be unable to open a scheduler that
 * works perfectly well. Same call as sign-in, whose URL this app does not claim either.
 *
 * ⚠️ THE DASHBOARD HAND-OFF IS THE SAME CALL AND ITS ENTRY URL IS ALSO UNCLAIMED —
 * `/dashboard/handoff` sits OUTSIDE the `/dashboard/district` prefix — but it 302s to
 * `/dashboard/district/scheduling`, which IS claimed and which `AppLinkResolver` maps to this very
 * screen. Chrome does not hand a Custom Tab navigation to the app that opened the tab, so the
 * redirect should stay in the sheet; that is a documented behaviour of the browser rather than
 * anything this repo controls, ⛔ **and it has not been verified on a device**. If an operator
 * reports the sheet bouncing back into the app, this is the first thing to check, and the answer
 * would be a `next` outside the claimed prefix rather than a change of launcher.
 *
 * ⛔ AND THE URL IS SPENT IMMEDIATELY AND KEPT NOWHERE. It carries a 60-second single-use JWT; the
 * ViewModel answers it through a callback rather than parking it on a `StateFlow` for exactly that
 * reason, and nothing here logs it.
 *
 * ⛔ THAT CALLBACK CAPTURES THE APPLICATION CONTEXT AND NOTHING ELSE, because the ViewModel holds it
 * across the mint and a rotation can land mid-mint. It used to report "no browser" through
 * `onShowMessage`, the Activity's snackbar on the Activity's `lifecycleScope`, so the pressing
 * Activity was held for the whole request and, once rotated, the message went to a destroyed one
 * and was dropped. The no-browser outcome is now a notice in the ViewModel's state, which the
 * live screen renders (`MainActivitySchedulingHandOffTest`). ⚠️ `applicationContext` is taken here
 * rather than trusted from the caller, so the rule holds whatever `context` is passed in.
 */
internal fun NavGraphBuilder.schedulingDestination(
    container: AppContainer,
    navController: NavHostController,
    sessionEpoch: Int,
    context: Context,
) {
    val appContext = context.applicationContext
    val launchInBrowser: (String) -> Boolean = { url ->
        CustomTabsLauncher.launch(appContext, url) !is CustomTabsLauncher.LaunchResult.NoBrowser
    }

    composable(Routes.SCHEDULING) { entry ->
        val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
        val viewModel: SchedulingViewModel = viewModel(
            // ⚠️ Keyed on the workspace ALONE — there is no role in this route to key on. An
            // unkeyed instance would show the previous tenant's booking URL under the new tenant's
            // name until the reload landed, and a booking link is exactly the kind of value
            // somebody copies to a customer before noticing.
            key = "scheduling-$workspaceId",
            factory = SchedulingViewModel.factory(container.schedulingRepository, workspaceId),
        )
        val state by viewModel.state.collectAsStateWithLifecycle()

        // ⚠️ An idempotent GET, so replaying it on a session change is unconditionally safe. ⛔ It
        // must stay pointed at the READ: replaying `enable` would provision a tenancy because a
        // token was refreshed, and each call reaches two third parties.
        OnSessionChanged(sessionEpoch) { viewModel.load() }

        SchedulingScreen(
            state = state,
            onBack = { navController.popBackStack() },
            onRetry = viewModel::load,
            onEnable = viewModel::enable,
            onOpenDashboard = { viewModel.openDashboard(launchInBrowser) },
            onOpenScheduler = { viewModel.openScheduler(launchInBrowser) },
            onDismissNotice = viewModel::dismissNotice,
        )
    }
}
