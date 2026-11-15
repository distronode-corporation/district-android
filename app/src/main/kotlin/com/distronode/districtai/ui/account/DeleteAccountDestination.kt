package com.distronode.districtai.ui.account

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.AppContainer
import com.distronode.districtai.auth.CustomTabsLauncher
import com.distronode.districtai.core.model.NativeReauthAction

/**
 * The account deletion destination: its model, built from the container, and its screen.
 *
 * @param context ⛔ THE APPLICATION CONTEXT; see [TwoStepDestination].
 */
@Composable
fun DeleteAccountDestination(container: AppContainer, context: Context, onBack: () -> Unit) {
    val viewModel: DeleteAccountViewModel = viewModel(
        // ⚠️ UNKEYED: the account is not a workspace's.
        factory = DeleteAccountViewModel.factory(
            repository = container.nativeReauthRepository,
            sso = { provider ->
                container.ssoReauthenticator.reauthenticate(provider, NativeReauthAction.DELETE_ACCOUNT) { url ->
                    CustomTabsLauncher.launch(context, url) !is CustomTabsLauncher.LaunchResult.NoBrowser
                }
            },
            deleted = container::finishDeletedAccount,
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeleteAccountScreen(state = state, actions = viewModel, onBack = onBack)
}
