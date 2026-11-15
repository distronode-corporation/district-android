package com.distronode.districtai.ui.account

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DontMemoize
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.distronode.districtai.AppContainer
import com.distronode.districtai.auth.CustomTabsLauncher
import com.distronode.districtai.core.model.NativeReauthAction

/**
 * The two-step verification destination: its model, built from the container, and its screen.
 *
 * ⚠️ HERE RATHER THAN INLINE IN `DistrictNavHost`, so the route there is three lines.
 *
 * @param context ⛔ THE APPLICATION CONTEXT, which the model's SSO leg holds across a suspending
 *   request; see the ⛔ on `DistrictNavHost`'s own. `CustomTabsLauncher` sets `FLAG_ACTIVITY_NEW_TASK`,
 *   which is what makes launching from it legal.
 */
@Composable
fun TwoStepDestination(container: AppContainer, context: Context, onBack: () -> Unit) {
    val viewModel: TwoStepViewModel = viewModel(
        // ⚠️ UNKEYED, like Devices: two-step verification belongs to the ACCOUNT, not a workspace.
        factory = TwoStepViewModel.factory(
            settings = container.twoStepSettingsRepository,
            reauth = container.nativeReauthRepository,
            sso = @DontMemoize { provider ->
                val action = NativeReauthAction.TWO_FACTOR_CHANGE
                container.ssoReauthenticator.reauthenticate(provider, action) @DontMemoize { url ->
                    CustomTabsLauncher.launch(context, url) !is CustomTabsLauncher.LaunchResult.NoBrowser
                }
            },
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    TwoStepScreen(state = state, actions = viewModel, onBack = onBack)
}
