package com.distronode.districtai.push

import com.distronode.districtai.core.data.PushTokenRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the FCM token comes from.
 *
 * ⛔ AN INTERFACE BECAUSE `FirebaseMessaging.getInstance()` IS UNTESTABLE HERE AND UNAVAILABLE ON
 * HALF THE DEVICES THIS APP RUNS ON. It throws when no default `FirebaseApp` exists, and on a
 * handset without Play Services — every Waydroid image, and a real share of the installed base —
 * token acquisition simply fails. A seam means [PushRegistrar]'s ordering rules can be exercised
 * without any of that, which matters because those rules are the part that can be wrong.
 *
 * ⚠️ `null` IS AN ORDINARY ANSWER AND NOT AN ERROR: no Play Services, Firebase not initialised (see
 * [DistrictFirebase.firebaseAppIdFor], which refuses an unrecognised package rather than guessing),
 * or the SDK still fetching one. Push is simply absent in those cases, which every path in this app
 * already tolerates.
 */
fun interface PushTokenSource {
    suspend fun currentToken(): String?
}

/**
 * When this installation's push token is registered, and when it is withdrawn.
 *
 * ⛔ THE REPOSITORY OWNS THE TWO CALLS; THIS OWNS THE **ORDERING**, WHICH IS THE PART WITH RULES.
 * There are exactly four moments and each has a constraint that is invisible from the repository:
 *
 *   1. **After a successful sign-in.** The server's upsert is keyed on the INSTALLATION, so a phone
 *      previously signed in as somebody else keeps delivering THEIR notifications until this
 *      account claims the row.
 *   2. **Once per process start, but only with a stored session.** This is the retry for (1) and
 *      (3) below: both are single attempts, so a rotation that landed while the API was unreachable
 *      left the server holding a dead token until the user signed out and back in. The upsert is
 *      idempotent and one request per launch is far under the 20/min per-account ceiling. ⛔ NOT
 *      on a device with no stored session: before a login there is no bearer, so the register would
 *      spend a 401 against that ceiling and record nothing.
 *   3. **On every token rotation.** FCM rotates a registration token on its own schedule (app data
 *      cleared, a restore to a new device, a periodic refresh), and `onNewToken` is the only
 *      notification of it. A missed rotation is silent: the old token keeps being accepted by our
 *      server and rejected by FCM, so push stops with nothing anywhere reporting a problem.
 *   4. **Before the sign-out revoke, never after.** `unregisterPushToken` authenticates with the
 *      access token; once `revoke` has ended the session and `forget()` has wiped the store there
 *      is no credential left to make the call with.
 *
 * ⚠️ A REGISTER THE SERVER DID NOT AFFIRM IS REPORTED THROUGH [reportFailure], because "push quietly
 * stopped" is otherwise invisible to everyone. The report is a fixed string naming the trigger and
 * never the token. A null token is NOT reported: it is the ordinary answer on a device without Play
 * Services (see [PushTokenSource]), and reporting it would send one event per launch per such device.
 *
 * ⛔ NOTHING HERE MAY BLOCK OR FAIL THE THING IT IS ATTACHED TO. A login that failed because a
 * courtesy channel could not be registered, or a sign-out that hung on an unreachable server while
 * the user watched, are both worse outcomes than no push. So (1) to (3) are fire-and-forget into an
 * injected scope, and (4), which the sign-out sequence genuinely has to WAIT for because it must
 * precede the revoke, is bounded by [unregisterTimeoutMillis] rather than by OkHttp's 30-second
 * call timeout. A sign-out is a button the user pressed; thirty seconds of nothing is a broken app.
 *
 * ⚠️ IT KEEPS NO RECORD OF HAVING REGISTERED, DELIBERATELY. The server's write is an idempotent
 * upsert keyed on the installation, so re-registering costs one request and is always correct; a
 * local "already done" flag would be a cache of server state that is wrong exactly after the case
 * it exists to optimise (a rotation), and it would survive into a session belonging to a different
 * account.
 *
 * @param scope ⛔ NOT a ViewModel's and not an Activity's. Registration is triggered by a login
 *   completing and by a system service callback, neither of which is scoped to a screen; the
 *   application graph's own scope is what survives both.
 * @param reportFailure receives one of the fixed `REGISTER_NOT_AFFIRMED_*` messages. Production
 *   passes `DistrictSentry::reportNonFatal`; a seam so the tests can read what would be sent.
 */
class PushRegistrar(
    private val repository: PushTokenRepository,
    private val tokens: PushTokenSource,
    private val scope: CoroutineScope,
    private val reportFailure: (String) -> Unit,
    /**
     * ⚠️ INJECTABLE SO THE SIGN-OUT ORDERING IS TESTABLE WITHOUT WAITING FIVE SECONDS. The
     * production value is a bound on how long a sign-out may pause for a courtesy call, not a
     * network timeout — OkHttp's own is 30s, which is far too long to hold a button press.
     */
    private val unregisterTimeoutMillis: Long = UNREGISTER_TIMEOUT_MILLIS,
) {

    /**
     * A login just completed: claim this installation for the account that signed in.
     *
     * ⚠️ FIRE AND FORGET. The caller is `LoginController`'s success branch, which is also what bumps
     * the session epoch every screen reloads on; making that wait on a token fetch would delay the
     * whole signed-in UI behind Play Services.
     */
    fun onSignedIn() {
        scope.launch { registerCurrentToken(REGISTER_NOT_AFFIRMED_SIGN_IN) }
    }

    /**
     * The application graph was just built: re-register if a session was restored from disk.
     *
     * ⚠️ [hasSession] IS A FUNCTION, CALLED INSIDE THE LAUNCH, because the answer is a Keystore read
     * plus a decrypt and the caller is the graph's constructor, which must not do disk I/O. See
     * moment (2) in the class doc for why this exists and why it is gated.
     */
    fun onProcessStart(hasSession: suspend () -> Boolean) {
        scope.launch {
            if (hasSession()) registerCurrentToken(REGISTER_NOT_AFFIRMED_PROCESS_START)
        }
    }

    /**
     * FCM issued a new token for this installation.
     *
     * ⚠️ THE TOKEN IS HANDED IN RATHER THAN FETCHED. `onNewToken` already has the new value, and
     * asking the SDK again would be a second source of truth that can disagree with the callback
     * that woke us — which is the exact moment a disagreement is most likely.
     *
     * ⛔ FIRE AND FORGET INTO [scope], NOT INTO THE SERVICE'S LIFETIME. `FirebaseMessagingService`
     * is torn down as soon as its callback returns, so a coroutine scoped to it would be cancelled
     * mid-request; the application scope is what outlives the delivery.
     */
    fun onNewToken(token: String) {
        scope.launch { register(token, REGISTER_NOT_AFFIRMED_ROTATION) }
    }

    /**
     * Withdraw this installation before the session is revoked.
     *
     * ⛔ SUSPENDING AND AWAITED, UNLIKE THE OTHER TWO, BECAUSE THE ORDER IS THE POINT — it needs the
     * live bearer that the next step of `signOut` destroys. ⚠️ Bounded by
     * [unregisterTimeoutMillis]: on expiry the sign-out proceeds regardless, leaving a registered
     * row that the server retires on its own once FCM reports the token gone. A device left looking
     * signed in because a courtesy call hung is the worse failure, and it is the one the user can
     * see.
     *
     * @return true only when the server affirmed it. False covers both "we could not reach it" and
     *   "we ran out of time", which are the same thing to the caller: it does not know, and it
     *   proceeds anyway.
     */
    suspend fun unregister(): Boolean =
        withTimeoutOrNull(unregisterTimeoutMillis) { repository.unregister() } ?: false

    /**
     * ⚠️ A NULL TOKEN IS A NO-OP, NOT A FAILURE TO REPORT. It is what a device with no Play Services
     * answers, and what a build whose package Firebase has never seen answers — see
     * [DistrictFirebase.firebaseAppIdFor], which refuses rather than guessing. The repository would
     * reject a blank anyway; short-circuiting here means no request is even attempted.
     */
    private suspend fun registerCurrentToken(failureMessage: String) {
        val token = tokens.currentToken() ?: return
        register(token, failureMessage)
    }

    /** ⛔ [failureMessage] is one of the constants below and never includes [token]. */
    private suspend fun register(token: String, failureMessage: String) {
        if (!repository.register(token)) reportFailure(failureMessage)
    }

    internal companion object {
        /** Five seconds. Named because detekt counts a bare literal as a magic number. */
        private const val UNREGISTER_TIMEOUT_MILLIS = 5_000L

        const val REGISTER_NOT_AFFIRMED_SIGN_IN: String =
            "Push token registration was not affirmed after sign-in"
        const val REGISTER_NOT_AFFIRMED_PROCESS_START: String =
            "Push token registration was not affirmed at process start"
        const val REGISTER_NOT_AFFIRMED_ROTATION: String =
            "Push token registration was not affirmed after an FCM token rotation"
    }
}
