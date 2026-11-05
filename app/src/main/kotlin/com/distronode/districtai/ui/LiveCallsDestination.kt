package com.distronode.districtai.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.distronode.districtai.AppContainer
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.ui.live.LiveCallsActions
import com.distronode.districtai.ui.live.LiveCallsFeed
import com.distronode.districtai.ui.live.LiveCallsScreen
import com.distronode.districtai.ui.live.LiveCallsView
import com.distronode.districtai.ui.live.LiveCallsViewModel

/**
 * Live calls: listen to, whisper into and take over a live AI call.
 *
 * ⛔ ITS OWN BUILDER, for the complexity reason [roomsLobbyDestination] gives, and because it owns
 * a live media session as the rooms do.
 *
 * ⛔ THE ROLE IS A PATH SEGMENT AND FAILS CLOSED: an unrecognised value is a viewer's screen
 * (listen only). Whisper and take over refuse `viewer` server-side.
 *
 * ⛔ THE ROOM FOLLOWS THE DESTINATION'S LIFECYCLE: stopping it (the app backgrounded, another screen
 * pushed on top) leaves the call, so no supervisor and no microphone is left in a customer's call
 * behind a screen nobody sees. See [LiveCallsViewModel].
 */
internal fun NavGraphBuilder.liveCallsDestination(
    container: AppContainer,
    navController: NavHostController,
    sessionEpoch: Int,
    onSignIn: () -> Unit,
) {
    composable(Routes.LIVE_CALLS) { entry ->
        val workspaceId = entry.pathArgument(ARG_WORKSPACE_ID)
        val role = WorkspaceRole.fromWire(entry.pathArgument(ARG_ROLE))
        val viewModel: LiveCallsViewModel = viewModel(
            key = "live-calls-$workspaceId",
            factory = LiveCallsViewModel.factory(
                repository = container.superviseRepository,
                workspaceId = workspaceId,
                canWrite = role.allowsMutation(),
                engineFactory = container.callEngineFactory,
                feed = { scope, onEvent -> LiveCallsFeed(workspaceId, container.liveDependencies, scope, onEvent) },
            ),
        )
        val list by viewModel.list.collectAsStateWithLifecycle()
        val supervision by viewModel.supervision.collectAsStateWithLifecycle()
        val whispers by viewModel.whispers.collectAsStateWithLifecycle()
        val prompt by viewModel.prompt.collectAsStateWithLifecycle()

        OnSessionChanged(sessionEpoch, viewModel::refresh)
        LifecycleStartEffect(viewModel) {
            viewModel.onScreenStarted(true)
            onStopOrDispose { viewModel.onScreenStarted(false) }
        }
        val microphone = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
            viewModel.takeOver::onMicrophonePermissionResult,
        )
        // ⚠️ A counter, so a second attempt after a denial asks again. See LiveCallsPrompt.
        LaunchedEffect(prompt.microphoneRequest) {
            if (prompt.microphoneRequest > 0) microphone.launch(Manifest.permission.RECORD_AUDIO)
        }

        LiveCallsScreen(
            view = LiveCallsView(list, supervision, whispers, prompt, canWrite = role.allowsMutation()),
            actions = LiveCallsActions(
                onBack = @DontMemoize { navController.popBackStack() },
                onRefresh = viewModel::refresh,
                onSignIn = onSignIn,
                onListen = viewModel::listen,
                onLeave = viewModel::leave,
                onDraft = viewModel::setWhisperDraft,
                onSend = viewModel::sendWhisper,
                onAskTakeOver = viewModel.takeOver::ask,
                onCancelTakeOver = viewModel.takeOver::cancel,
                onConfirmTakeOver = viewModel.takeOver::confirm,
            ),
        )
    }
}
