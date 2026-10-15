package com.distronode.districtai.handoff

import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.withTimeoutOrNull

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
 * ⛔ THE FALLBACK IS REQUIRED, AND IT IS WHAT MAKES ANY RELEASE ORDER SAFE. Today's production has
 * no `/dashboard/handoff/start`, and a shipped Play build cannot be reverted, so whenever leg 1 does
 * not deliver a nonce this does today's UNBOUND flow (leg 2 without `nonce`, then leg 3). The server
 * accepts both until its `HANDOFF_REQUIRE_NONCE` flag is flipped, and answers 400 `nonce_required`
 * to the unbound flow after that.
 *
 * ⛔ THE DESIGN CHOICE: AN APP-SIDE PROBE OF LEG 1 BEFORE THE CUSTOM TAB, NOT THE TIMEOUT ALONE.
 * The fallback has to be detected in the app, because a Custom Tab showing a 404 or 400 page never
 * calls back. Using the ten-second timeout as the only trigger would put that error page on screen
 * on every tap against an older server, leave the user looking at it for ten seconds while the app
 * waits, and then stack the dashboard's tab on top of it, with the error page still behind it for
 * the back button. So leg 1 is first asked of the server directly ([SchedulingRepository.handOffStartServed]:
 * one small GET, no bearer, redirects not followed), and only a redirect onto `districtai://handoff`
 * opens the Custom Tab. Against an older server the fallback is immediate and nothing is shown.
 *
 * ⚠️ WHAT THE PROBE COSTS, AND WHY IT IS STILL RIGHT. One extra round trip per tap, and one extra
 * leg 1 at the server. Its cookie lands in OkHttp's jar, which is no jar at all (the shared client
 * has none), and its nonce was never bound to anything; the browser's own leg 1 mints a fresh one,
 * which the route allows by design. It proves the ROUTE exists, not that this browser will follow a
 * redirect to a custom scheme, so the timeout stays as the second trigger: a browser that never
 * calls back still falls back after [CALLBACK_TIMEOUT_MILLIS]. Once every region serves leg 1 and the
 * flag is on, the probe always passes and could go, together with the fallback it gates.
 *
 * ⛔ BOTH BROWSER LEGS GO THROUGH THE SAME LAUNCHER, AND THAT IS HOW THEY SHARE ONE COOKIE JAR. A
 * Custom Tab uses its browser's jar, and the launcher pins the device's default browser by package
 * whenever one can be named, so leg 3 lands in the browser that took the cookie in leg 1. ⚠️ With
 * several browsers and no default the intent goes out implicit and the user chooses twice; picking
 * two different browsers ends leg 3 on the server's 410 page, and the retry starts again from leg 1.
 *
 * ⛔ THE NONCE IS NEVER LOGGED AND NEVER LEAVES THIS FUNCTION EXCEPT IN THE MINT'S BODY. What is
 * recorded is which path was taken ([HandOffEvent.BOUND] or one of the two fallbacks), a closed set
 * of constants; see [HandOffLog].
 *
 * ⚠️ THE USER WHO CLOSES THE LEG-1 TAB BEFORE IT CALLS BACK is not detected: the wait runs out and
 * the unbound flow opens the dashboard anyway, which is the page they asked for. Watching for the
 * activity resuming without a callback (the way `LoginAttemptTracker` does for sign-in) would cut
 * that wait short, at the cost of a lifecycle-ordering rule this path does not otherwise need.
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
                is Binding.Bound -> mintAndOpen(workspaceId, binding.nonce, launch)
                Binding.Unbound -> mintAndOpen(workspaceId, null, launch)
            }
        } finally {
            callbacks.finish(pending)
        }
    }

    private suspend fun mintAndOpen(
        workspaceId: String,
        nonce: String?,
        launch: (String) -> Boolean,
    ): Outcome = when (val minted = repository.dashboardHandOff(workspaceId, nonce)) {
        is ApiResult.Success -> Outcome.Launched(opened = launch(minted.value))
        is ApiResult.Failure -> Outcome.Refused(minted)
    }

    /**
     * Legs 1 and the callback: a nonce, or the reason there is none.
     *
     * ⚠️ THE PROBE GETS ITS OWN STATE, never [pending]'s, so its `Location` can never be mistaken
     * for the browser leg's callback, and the browser leg's state is seen by the browser only.
     */
    private suspend fun bind(pending: PendingHandOff, launch: (String) -> Boolean): Binding {
        val served = withTimeoutOrNull(START_PROBE_TIMEOUT_MILLIS) {
            repository.handOffStartServed(newState())
        }
        if (served != true) {
            log.record(HandOffEvent.FALLBACK_START_UNAVAILABLE)
            return Binding.Unbound
        }
        if (!launch(repository.handOffStartUrl(pending.state))) return Binding.NoBrowser
        val nonce = pending.awaitNonce(CALLBACK_TIMEOUT_MILLIS)
        if (nonce == null) {
            log.record(HandOffEvent.FALLBACK_NO_CALLBACK)
            return Binding.Unbound
        }
        log.record(HandOffEvent.BOUND)
        return Binding.Bound(nonce)
    }

    private sealed interface Binding {
        /** ⚠️ Not a data class: its `toString` must not print the nonce. */
        class Bound(val nonce: String) : Binding

        data object Unbound : Binding

        /** Leg 1 found no browser, so there is nothing to hand leg 3 to either. */
        data object NoBrowser : Binding
    }

    internal companion object {
        /**
         * How long the leg-1 probe may take before the hand-off goes unbound.
         *
         * ⚠️ SHORTER THAN THE CALLBACK'S WAIT, because the user is looking at a busy button rather
         * than at a browser, and the probe is one small request: on a connection where it takes
         * longer than this, the browser's own leg 1 would not have called back in time either.
         */
        const val START_PROBE_TIMEOUT_MILLIS = 5_000L

        /**
         * How long the app waits for `districtai://handoff` after opening leg 1.
         *
         * ⚠️ TEN SECONDS, the figure the server half was designed around. A working browser calls
         * back within a second or two (the route answers its redirect at once); this only runs out
         * when the browser never follows the redirect into the app.
         */
        const val CALLBACK_TIMEOUT_MILLIS = 10_000L
    }
}
