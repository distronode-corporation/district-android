// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

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
            sso = @DontMemoize { provider ->
                val action = NativeReauthAction.DELETE_ACCOUNT
                container.ssoReauthenticator.reauthenticate(provider, action) @DontMemoize { url ->
                    CustomTabsLauncher.launch(context, url) !is CustomTabsLauncher.LaunchResult.NoBrowser
                }
            },
            deleted = @DontMemoize { container.finishDeletedAccount(it) },
        ),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    DeleteAccountScreen(state = state, actions = viewModel, onBack = onBack)
}
