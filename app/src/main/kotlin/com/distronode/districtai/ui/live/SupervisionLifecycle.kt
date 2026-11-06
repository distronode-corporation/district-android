package com.distronode.districtai.ui.live

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleStartEffect

/**
 * Ties the Live calls session to the screen: the socket runs while the screen is started, and a
 * real stop leaves the call.
 *
 * ⛔ A ROTATION IS NOT A REAL STOP. MainActivity declares no `configChanges`, so a rotation
 * recreates it and dispatches ON_STOP first; leaving there dropped a listening supervisor, and
 * handed a taken-over call back to the AI mid-sentence. The ViewModel (and the engine, in its
 * engine scope) survive the recreation, so a stop while the activity is changing configurations
 * keeps the session. Backgrounding and leaving the screen still leave (see [LiveCallsViewModel]).
 *
 * ⚠️ THE ACTIVITY IS READ WHEN THE STOP ARRIVES, not when the effect started: it is the one being
 * recreated that says so.
 */
@Composable
fun SupervisionLifecycle(viewModel: LiveCallsViewModel) {
    val activity = LocalActivity.current
    LifecycleStartEffect(viewModel) {
        viewModel.onScreenStarted(true)
        onStopOrDispose {
            viewModel.onScreenStarted(false, recreating = activity?.isChangingConfigurations == true)
        }
    }
}

/**
 * Keeps the display on while [active].
 *
 * ⛔ A SUPERVISE SESSION MUST NOT END ON A SCREEN TIMEOUT. A display that sleeps stops the
 * activity, and a stopped Live calls screen leaves the call: a takeover is held at the ear, nobody
 * touches the screen, and after the timeout the call went back to the AI with no word to the
 * supervisor. Scoped to the view, so it ends with the session or the screen, whichever is first.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}
