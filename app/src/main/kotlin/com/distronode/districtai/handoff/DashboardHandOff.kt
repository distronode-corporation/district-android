package com.distronode.districtai.handoff

import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.network.ApiResult

/**
 * The dashboard hand-off, bound to the browser that will redeem it (security item S33).
 *
 * ⛔ WHAT IT FIXES. `GET /dashboard/handoff?code=` signs in WHICHEVER browser opens it, so a code
 * that reaches anybody else's browser within its sixty seconds is a session for them, and a code
 * planted in a victim's browser signs the victim into the planter's account (login CSRF). The fix
 * binds the code to a nonce cookie only the browser this app opened holds:
 *
 *   1. the app opens `/dashboard/handoff/start?state=S` in a Custom Tab; the server sets the
 *      host-only cookie `__Host-dn-handoff-nonce=N` and redirects to `districtai://handoff?state=S&nonce=N`;
 *   2. the callback reaches [HandOffCallbacks], which checks S, and the app mints the code with N
 *      (`POST /api/district/scheduling/handoff {…, nonce}`), at once, because the code lives 60 s;
 *   3. the app opens the code's URL in a Custom Tab of the SAME browser, whose cookie jar now
 *      holds N, and the server redeems the code only against that cookie.
 *
 * ⛔ THERE IS NO UNBOUND FALLBACK, AND THAT IS THE POINT (security item S33). Every code this app
 * mints is bound, so the server can turn `HANDOFF_REQUIRE_NONCE` on; a single unbound mint from an
 * installed build would keep it off. When the browser does not call back within
 * [CALLBACK_TIMEOUT_MILLIS], the hand-off is given up: nothing is minted and the screen offers a
 * retry ([Outcome.Unanswered]). District Core for Rust (4.0.2) and for Swift (7.0.1) do the same.
 * The nonce is non-null all the way to the request type, so an unbound mint cannot be written.
 *
 * ⚠️ NO PROBE OF LEG 1 EITHER. One used to ask the server whether it served leg 1 before putting a
 * Custom Tab on screen, so an older server could be met with the unbound flow. With no unbound flow
 * there is nothing to choose between: every region serves leg 1, and a browser that cannot reach it
 * shows its own error page and never calls back, which ends in the same retryable notice.
 *
 * ⛔ BOTH BROWSER LEGS GO THROUGH THE SAME LAUNCHER, AND THAT IS HOW THEY SHARE ONE COOKIE JAR. A
 * Custom Tab uses its browser's jar, and the launcher pins the device's default browser by package
 * whenever one can be named, so leg 3 lands in the browser that took the cookie in leg 1. ⚠️ With
 * several browsers and no default the intent goes out implicit and the user chooses twice; picking
 * two different browsers ends leg 3 on the server's 410 page, and the retry starts again from leg 1.
 *
 * ⛔ THE NONCE IS NEVER LOGGED AND NEVER LEAVES THIS FUNCTION EXCEPT IN THE MINT'S BODY. What is
 * recorded is which path was taken ([HandOffEvent.BOUND] or [HandOffEvent.NO_CALLBACK]), a closed
 * set of constants; see [HandOffLog].
 *
 * ⚠️ THE USER WHO CLOSES THE LEG-1 TAB BEFORE IT CALLS BACK is not detected: the wait runs out and
 * the screen offers a retry. Watching for the activity resuming without a callback (the way
 * `LoginAttemptTracker` does for sign-in) would cut that wait short, at the cost of a
 * lifecycle-ordering rule this path does not otherwise need.
 */
class DashboardHandOff(
    private val repository: SchedulingRepository,
    private val callbacks: HandOffCallbacks,
    private val log: HandOffLog,
    /** ⚠️ Injected so a test can follow the state from leg 1 to its callback. */
    private val newState: () -> String = HandOffTokens::newState,
) {

    /** How one press ended. ⚠️ No arm carries the nonce or the code's URL. */
    sealed interface Outcome {
        /** A URL went to [open]'s launcher; [opened] is whether a browser took it. */
        data class Launched(val opened: Boolean) : Outcome

        /** The mint was refused; the screen words [failure]. */
        data class Refused(val failure: ApiResult.Failure) : Outcome

        /** Another hand-off is still running, and this press started nothing. */
        data object AlreadyRunning : Outcome

        /**
         * The browser opened leg 1 and never called back in time, so nothing was minted.
         *
         * ⚠️ RETRYABLE: a fresh press starts a fresh leg 1, which is the one thing that can fix it
         * (the person was slow on the browser's "Open District AI?" prompt, or declined it).
         */
        data object Unanswered : Outcome
    }

    /**
     * Run one hand-off for [workspaceId], opening each browser leg through [launch].
     *
     * @param launch opens a URL in a browser and answers whether one took it. ⛔ Called with the
     *   leg-1 URL and then with the code's URL, never with anything else, and it must open both in
     *   the same browser (see the class doc).
     */
    suspend fun open(workspaceId: String, launch: (String) -> Boolean): Outcome {
        val pending = callbacks.begin(newState())
        if (pending == null) {
            log.record(HandOffEvent.ALREADY_RUNNING)
            return Outcome.AlreadyRunning
        }
        // ⛔ THE SLOT IS HELD UNTIL LEG 3 HAS BEEN HANDED TO THE BROWSER, not just until the
        // callback: a second leg 1 any earlier would replace the cookie this code is bound to.
        return try {
            when (val binding = bind(pending, launch)) {
                Binding.NoBrowser -> Outcome.Launched(opened = false)
                Binding.Unanswered -> Outcome.Unanswered
                is Binding.Bound -> mintAndOpen(workspaceId, binding.nonce, launch)
            }
        } finally {
            callbacks.finish(pending)
        }
    }

    private suspend fun mintAndOpen(
        workspaceId: String,
        nonce: String,
        launch: (String) -> Boolean,
    ): Outcome = when (val minted = repository.dashboardHandOff(workspaceId, nonce)) {
        is ApiResult.Success -> Outcome.Launched(opened = launch(minted.value))
        is ApiResult.Failure -> Outcome.Refused(minted)
    }

    /** Leg 1 and its callback: a nonce, or the reason there is none. */
    private suspend fun bind(pending: PendingHandOff, launch: (String) -> Boolean): Binding {
        if (!launch(repository.handOffStartUrl(pending.state))) return Binding.NoBrowser
        val nonce = pending.awaitNonce(CALLBACK_TIMEOUT_MILLIS)
        if (nonce == null) {
            log.record(HandOffEvent.NO_CALLBACK)
            return Binding.Unanswered
        }
        log.record(HandOffEvent.BOUND)
        return Binding.Bound(nonce)
    }

    private sealed interface Binding {
        /** ⚠️ Not a data class: its `toString` must not print the nonce. */
        class Bound(val nonce: String) : Binding

        /** Leg 1 opened, and the browser never called back in time. */
        data object Unanswered : Binding

        /** Leg 1 found no browser, so there is nothing to hand leg 3 to either. */
        data object NoBrowser : Binding
    }

    internal companion object {
        /**
         * How long the app waits for `districtai://handoff` after opening leg 1.
         *
         * ⚠️ THIRTY SECONDS, as District Core for Rust and for Swift wait. A working browser calls
         * back within a second or two, but the browser may first ask "Open District AI?", and the
         * wait has to cover a person reading that. Running out costs a retry, never an unbound
         * code. The server's nonce cookie lives five minutes, well past it.
         */
        const val CALLBACK_TIMEOUT_MILLIS = 30_000L
    }
}
