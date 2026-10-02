package com.distronode.districtai.auth

import android.net.Uri
import com.distronode.districtai.core.auth.LoginOutcome
import com.distronode.districtai.core.auth.NativeLoginFlow
import com.distronode.districtai.core.auth.PkceLoginFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns a login attempt from the browser hand-off to the token exchange.
 *
 * ⛔ THIS IS PROCESS-SCOPED, NOT ACTIVITY- OR VIEWMODEL-SCOPED, AND THAT IS THE ENTIRE FIX.
 * All three pieces of login state used to be plain `MainActivity` fields — the status message,
 * the [LoginAttemptTracker], and the `lifecycleScope` the token exchange ran in. The activity is
 * `singleTask` with no `android:configChanges`, so a rotation, a dark-mode switch or a font-size
 * change destroys and recreates it, and each of those fields failed differently:
 *
 *   1. THE TRACKER WAS DEFEATED BY ROTATION. Its whole job is detecting a browser leg that ended
 *      without a callback, so the screen cannot sit on "Waiting for sign-in to complete in the
 *      browser…" forever. Rotating while the Custom Tab is in front handed the recreated activity
 *      a FRESH tracker with `awaitingCallback = false` — so the one mechanism built to prevent a
 *      silently stuck screen was disabled by the most ordinary interaction on the platform.
 *   2. THE STATUS WAS LOST, so the recreated screen said nothing about a login in progress.
 *   3. THE EXCHANGE WAS CANCELLED. `lifecycleScope` is cancelled at `onDestroy`, so a recreation
 *      between the server rotating the authorization code and `adopt` persisting the result left
 *      the code SPENT server-side and the tokens nowhere — an unrecoverable dead end for that
 *      attempt.
 *
 * ⚠️ WHY NOT A ViewModel, WHICH IS THE USUAL ANSWER. A ViewModel survives a configuration change
 * but not a genuine activity destruction, and `viewModelScope` is cancelled in `onCleared` — so it
 * fixes (1) and (2) and only narrows (3). The exchange is the one operation here that must not be
 * interrupted by anything short of process death, because interrupting it destroys a single-use
 * credential. A process-scoped holder covers all three with one object and no `SavedStateHandle`
 * plumbing.
 *
 * ⚠️ AND WHY NOT `SavedStateHandle`, WHICH WOULD ALSO SURVIVE PROCESS DEATH. Restoring
 * "awaiting a callback" across process death would be a LIE: [NativeLoginFlow] keeps the PKCE
 * verifier in memory only, deliberately, so a login interrupted by process death cannot complete
 * and must be restarted. Persisting the status would show a waiting message for an attempt that
 * is already unrecoverable. Losing it with the process is the honest behaviour.
 *
 * @param loginFlow the PKCE flow itself. ⚠️ AN INTERFACE, NOT [NativeLoginFlow] DIRECTLY, for the
 *   same reason [LoginAttemptTracker] is a separate class: the rules this controller enforces are
 *   about lifecycle ORDERING, and depending on the concrete flow means they cannot be exercised
 *   without an OkHttp stack and a Keystore. Three methods is the entire surface used.
 * @param scope must outlive the activity. See (3) above.
 */
class LoginController(
    private val loginFlow: PkceLoginFlow,
    private val sessionSignal: SessionSignal,
    private val scope: CoroutineScope,
    /** Injected so the lifecycle-ordering rule stays testable in isolation. */
    private val attempt: LoginAttemptTracker = LoginAttemptTracker(),
    /**
     * Ran once, after a login that produced tokens.
     *
     * ⛔ THIS IS THE ONLY POINT IN THE APP WHERE "A SESSION JUST STARTED" IS KNOWN, WHICH IS WHY THE
     * PUSH REGISTRATION HANGS OFF IT RATHER THAN OFF THE SESSION EPOCH. [SessionSignal] is bumped by
     * a sign-OUT as well, so a registrar driven from it would fire a register with no bearer on the
     * way out — a 401 spent against a 20/min per-account ceiling, and a row that stays pointed at
     * the account that just left. Here the branch is unambiguous.
     *
     * ⛔ AND IT MUST NOT BLOCK OR THROW. It runs inside the exchange's coroutine, immediately before
     * the epoch bump every screen reloads on; anything slow here delays the whole signed-in UI, and
     * anything that threw would abort a login that has already succeeded. `PushRegistrar.onSignedIn`
     * is fire-and-forget for exactly that reason.
     *
     * ⚠️ Defaulted so the login tests that predate push construct this class unchanged.
     */
    private val onSignedIn: () -> Unit = {},
) {

    private val _status = MutableStateFlow<LoginStatus?>(null)

    /** What the sign-in screen should be telling the user, or null for nothing at all. */
    val status: StateFlow<LoginStatus?> = _status.asStateFlow()

    /** The URL to open in a system browser. Also arms the attempt tracker. */
    fun authorizeUrl(): String = loginFlow.authorizeUrl()

    /**
     * Report how the browser hand-off went.
     *
     * ⚠️ The tracker is armed ONLY when a browser actually opened. Arming it on the no-browser
     * path would make the next `onResumed` report "sign-in did not complete" on top of the more
     * accurate "no browser available", replacing a precise diagnosis with a vague one.
     */
    fun onBrowserLaunched(opened: Boolean) {
        if (!opened) {
            _status.value = LoginStatus.NoBrowser
            return
        }
        attempt.onLoginStarted()
        _status.value = LoginStatus.WaitingForBrowser
    }

    /** Forward the host's `onPause`. See [LoginAttemptTracker]: the pause is load-bearing. */
    fun onHostPaused() {
        attempt.onPaused()
    }

    /**
     * Forward the host's `onResume`.
     *
     * Back in the foreground with a login still outstanding means the browser leg ended without a
     * callback. The pending attempt is dropped too, so the next tap starts clean rather than
     * holding a verifier whose code was never issued.
     */
    fun onHostResumed() {
        if (!attempt.onResumed()) return
        loginFlow.cancel()
        _status.value = LoginStatus.DidNotComplete
    }

    /**
     * Handle a login callback URI and complete the exchange.
     *
     * ⚠️ The tracker is told BEFORE the exchange starts, and therefore before the resulting
     * `onResume`, or that resume would report a failure for an attempt that actually succeeded.
     */
    fun onCallback(uri: Uri) {
        attempt.onCallbackReceived()
        _status.value = LoginStatus.Completing

        // ⛔ `scope`, NEVER the activity's lifecycleScope. See (3) in the class doc — a
        // cancellation here spends the authorization code and drops the tokens it bought.
        scope.launch {
            val outcome = loginFlow.completeLogin(uri)
            _status.value = outcome.toStatus()
            if (outcome == LoginOutcome.Success) {
                // ⛔ BEFORE THE EPOCH BUMP, AND IT MUST STAY FIRE-AND-FORGET. The bump is what makes
                // every screen re-read, so ordering a slow call ahead of it would delay the signed-in
                // UI behind a token fetch; ordering it AFTER would be equally fine today and would
                // become wrong the moment anything here needed to run before the graph existed.
                // Registering push is the one such thing: it wants the bearer the coordinator has
                // just adopted, and nothing about the UI.
                onSignedIn()
                // ⛔ The coordinator has adopted the tokens, so every screen must re-read. This
                // is the signal that replaces the old activity-held `onSignedIn` closure, which
                // reloaded a ViewModel nobody was looking at.
                sessionSignal.onSessionChanged()
            }
        }
    }

    /** Drop any status, e.g. once the user is signed in and the message no longer applies. */
    fun clearStatus() {
        _status.value = null
    }

    private fun LoginOutcome.toStatus(): LoginStatus? = when (this) {
        // Nothing to say: the app is about to render the signed-in UI.
        LoginOutcome.Success -> null
        // Deliberately blunt: this is the authorization-code-injection case.
        LoginOutcome.StateMismatch -> LoginStatus.Refused
        LoginOutcome.NoAttemptInProgress -> LoginStatus.Interrupted
        LoginOutcome.Rejected -> LoginStatus.Expired
        LoginOutcome.RateLimited -> LoginStatus.RateLimited
        LoginOutcome.Unreachable -> LoginStatus.Unreachable
        is LoginOutcome.Denied -> LoginStatus.Denied(reason)
    }
}

/**
 * What the sign-in screen is telling the user.
 *
 * ⛔ A TYPE, NOT THE `String?` THIS REPLACED. The activity held pre-rendered English, which made
 * every login message the only copy in the app that `stringResource` could not reach — in an app
 * whose manifest declares `supportsRtl="true"`. Keeping it symbolic means the wording lives in
 * `strings.xml` and this class stays testable without a `Context`.
 */
sealed interface LoginStatus {

    /** No browser on the device at all, so sign-in cannot start. */
    data object NoBrowser : LoginStatus

    /** The browser leg is outstanding. */
    data object WaitingForBrowser : LoginStatus

    /** The browser leg ended with no callback — a 404 authorize page, a dismissed tab, a refusal. */
    data object DidNotComplete : LoginStatus

    /** A callback arrived and the token exchange is running. */
    data object Completing : LoginStatus

    /** The response did not match this request. ⚠️ The code-injection case; say so plainly. */
    data object Refused : LoginStatus

    /**
     * A callback arrived with no attempt in progress.
     *
     * ⚠️ WORDED AS "INTERRUPTED", NOT "LINK EXPIRED", BECAUSE A REAL CAUSE IS PROCESS DEATH. The
     * PKCE verifier lives in memory only, so a process the OS killed while the browser was open
     * (an SSO with an authenticator-app hop on a low-RAM phone) comes back with nothing to complete,
     * and "expired" blamed a link the user did nothing wrong with. A stale callback re-opened from
     * history lands here too, and "try again" is the right advice for both.
     */
    data object Interrupted : LoginStatus

    /** The authorization code was expired, replayed, or failed PKCE. */
    data object Expired : LoginStatus

    data object RateLimited : LoginStatus

    data object Unreachable : LoginStatus

    /** The server named a reason. ⚠️ Server-authored, so it is shown as-is. */
    data class Denied(val reason: String) : LoginStatus
}
