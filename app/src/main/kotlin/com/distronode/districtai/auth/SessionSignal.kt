package com.distronode.districtai.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * "The session identity changed" — broadcast to every screen that might be showing stale state.
 *
 * ⛔ THE BUG THIS EXISTS TO FIX, WHICH A USER COULD NOT RECOVER FROM WITHOUT KILLING THE APP.
 * A mid-session sign-out leaves whichever screen is visible showing "Your session has ended" and
 * a Sign in button. That button previously had NO completion path: the activity held a single
 * `onSignedIn` closure, captured from its own throwaway ViewModel, so a successful login reloaded
 * an instance nobody was looking at. The visible screen — the destination's ViewModel, or a
 * Pager that had already failed on `Unauthorized` — was never told, and no screen offered
 * pull-to-refresh. There was no way back in.
 *
 * ⛔ A COUNTER, NOT A BOOLEAN, AND NOT AN EVENT. A boolean cannot distinguish "signed in" from
 * "signed in again", so two successive logins would look identical and the second would not
 * re-trigger anything. A plain SharedFlow event would be lost by a screen that was not composed
 * at the moment it fired — which is the common case, since the browser leg means the app was
 * backgrounded. A monotonically increasing epoch held in a [StateFlow] is durable state: a
 * screen compares what it observed at entry with what is current and acts on any advance,
 * whenever it happens to look.
 *
 * ⚠️ IT ADVANCES ON SIGN-OUT TOO, deliberately. "Re-read everything, the identity behind the
 * data changed" is the same instruction in both directions; a reload after a local sign-out is
 * what produces the `NEVER_SIGNED_IN` state that returns the user to the sign-in screen.
 *
 * ⚠️ PROCESS-SCOPED, held by `AppContainer`. It must outlive both the activity and any
 * ViewModelStore, because the login it reports on completes while neither is guaranteed to
 * exist.
 */
class SessionSignal {

    private val _epoch = MutableStateFlow(0)

    /** Advances once per session change. Compare against the value observed at entry. */
    val epoch: StateFlow<Int> = _epoch.asStateFlow()

    /**
     * A login completed, or a sign-out happened.
     *
     * ⚠️ `update` rather than `value += 1`: this is called from the login exchange, which does
     * not run on a scope this class controls, and read-modify-write on a StateFlow is not atomic.
     */
    fun onSessionChanged() {
        _epoch.update { it + 1 }
    }
}
