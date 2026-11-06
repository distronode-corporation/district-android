package com.distronode.districtai.ui.live

import android.os.PowerManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
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
 * Turns the display off while the phone is held to the ear, for as long as [active].
 *
 * ⛔ A TAKEOVER IS HELD AT THE EAR, AND THE SCREEN UNDER THE CHEEK CARRIES HAND BACK. The display
 * stays on during a session ([KeepScreenOn]), so without this a cheek could hand a customer's call
 * back to the AI. `PROXIMITY_SCREEN_OFF_WAKE_LOCK` blanks the screen and its touches while the
 * proximity sensor is covered, as a phone app does.
 *
 * ⛔ HELD ONLY WHILE A TAKEOVER IS ON SCREEN. Released when [active] goes false (hand back, the
 * call ending), when the screen stops (the app backgrounded, which also leaves the call), and when
 * the screen goes away. ⚠️ Released with `RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`, so a screen still
 * at the ear stays off until the phone comes away. A device with no proximity sensor gets no lock.
 * The timeout is a backstop only: a lock leaked by a bug ends on its own.
 */
@Composable
fun ProximityScreenOff(active: Boolean) {
    val context = LocalContext.current
    LifecycleStartEffect(context, active) {
        val power = context.getSystemService(PowerManager::class.java)
        val lock = power
            ?.takeIf { active && it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, PROXIMITY_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire(PROXIMITY_LOCK_BACKSTOP_MS)
            }
        onStopOrDispose {
            if (lock?.isHeld == true) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }
}

/** The wake lock's tag: `app:purpose`, as PowerManager asks. */
internal const val PROXIMITY_LOCK_TAG = "districtai:live-takeover-proximity"

/** Longer than any takeover; see [ProximityScreenOff]. */
internal const val PROXIMITY_LOCK_BACKSTOP_MS = 4L * 60 * 60 * 1000

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
