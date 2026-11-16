package com.distronode.districtai.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.distronode.districtai.ApiEnvironment
import com.distronode.districtai.AppContainer
import com.distronode.districtai.R
import com.distronode.districtai.auth.CustomTabsLauncher
import com.distronode.districtai.l10n.appLanguage
import com.distronode.districtai.ui.ARG_WORKSPACE_ID
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.pathArgument

/**
 * The setup wizard's route. ⚠️ WORKSPACE-SCOPED IN THE PATH, like every other workspace screen, so a
 * switch of workspace can never show one tenant's setup under another's name.
 */
const val SETUP_WIZARD_ROUTE: String = "workspace/{$ARG_WORKSPACE_ID}/setup"

fun setupWizardRoute(workspaceId: String): String = "workspace/$workspaceId/setup"

/**
 * The wizard's destination, entered from the overview's "Finish setting up" card.
 *
 * ⚠️ ITS OWN BUILDER, NOT INLINE IN `DistrictNavHost`, for the reason the rooms lobby has one: lambdas
 * inside a `composable {}` block count toward the enclosing function's complexity.
 *
 * ⚠️ CLOSING READS THE OVERVIEW AGAIN, so a finished wizard retires the card and a rename made in step
 * 1 shows in the workspace list.
 */
internal fun NavGraphBuilder.setupWizardDestination(
    container: AppContainer,
    navController: NavHostController,
    sessionEpoch: Int,
    workspaceName: (String) -> String?,
    onShowMessage: (String) -> Unit,
    onClosed: () -> Unit,
) {
    composable(SETUP_WIZARD_ROUTE) { entry ->
        val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
        // ⛔ THE APPLICATION CONTEXT: the launcher is held across a recomposition a rotation can outlast.
        val context = LocalContext.current.applicationContext
        val deps = remember(container) {
            SetupWizardDeps(
                setup = container.setupWizardRepository,
                config = container.workspaceConfigRepository,
                personaOptions = container.personaOptionsRepository,
                callHandling = container.callHandlingRepository,
                members = container.membersRepository,
                store = PreferencesSetupDraftStore(context),
            )
        }
        SetupWizardRoute(
            deps = deps,
            workspaceId = workspaceId,
            workspaceName = workspaceName(workspaceId).orEmpty(),
            sessionEpoch = sessionEpoch,
            apiBaseUrl = ApiEnvironment.baseUrl,
            launch = { url ->
                CustomTabsLauncher.launchExternally(context, url) !is CustomTabsLauncher.LaunchResult.NoBrowser
            },
            onShowMessage = onShowMessage,
            onClose = {
                navController.popBackStack()
                onClosed()
            },
        )
    }
}

/**
 * The wizard wired to its ViewModel, the Custom Tab and the lifecycle.
 *
 * ⚠️ THE BROWSER TELLS THE APP NOTHING, so coming back to the screen is what triggers the re-read
 * ([SetupWizardViewModel.resumed]), as on the scheduling calendar's hand-off.
 *
 * @param launch opens a URL in a browser and answers whether one opened.
 */
@Composable
internal fun SetupWizardRoute(
    deps: SetupWizardDeps,
    workspaceId: String,
    workspaceName: String,
    sessionEpoch: Int,
    apiBaseUrl: String,
    launch: (String) -> Boolean,
    onShowMessage: (String) -> Unit,
    onClose: () -> Unit,
) {
    val language = appLanguage()
    val viewModel: SetupWizardViewModel = viewModel(
        // ⚠️ Keyed on the workspace: the step on screen and its half-typed form belong to ONE tenant.
        key = "setup-$workspaceId",
        factory = SetupWizardViewModel.factory(deps, workspaceId, workspaceName, language),
    )
    // ⛔ REPLAYS THE READ, NEVER A WRITE.
    OnSessionChanged(sessionEpoch, viewModel::load)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.resumed() }
    val actions = HandOffActions(
        viewModel = viewModel,
        apiBaseUrl = apiBaseUrl,
        launch = launch,
        browserMissing = stringResource(R.string.settings_browser_missing),
        onShowMessage = onShowMessage,
    )
    SetupWizardScreen(wizard = viewModel, actions = actions, onClose = onClose)
}

/** The pages the wizard hands to the web, opened in a Custom Tab; a missing browser is said, not swallowed. */
private class HandOffActions(
    private val viewModel: SetupWizardViewModel,
    private val apiBaseUrl: String,
    private val launch: (String) -> Boolean,
    private val browserMissing: String,
    private val onShowMessage: (String) -> Unit,
) : SetupActions {

    override fun canOpen(page: SetupHandOff): Boolean = page.destination(apiBaseUrl) != null

    override fun openHandOff(page: SetupHandOff) {
        val url = page.destination(apiBaseUrl) ?: return
        if (launch(url)) viewModel.handOffOpened() else onShowMessage(browserMissing)
    }
}
