package com.distronode.districtai.core.auth

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single source of a usable access token.
 *
 * ⛔ THE INVARIANT THIS CLASS EXISTS TO PROTECT: A REFRESH TOKEN IS NEVER SENT TWICE.
 * The server's rotation is mandatory — every refresh mints a successor and stamps
 * `rotatedAt` on the presented token — and a token that arrives already-rotated is treated
 * as theft, because the server cannot tell a duplicated credential from a retrying client.
 * Its response is to revoke the entire `familyId` chain and log
 * `[auth] Native refresh replay detected`. So every plausible way of sending the same
 * token twice has to be closed here:
 *
 *   1. TWO CONCURRENT CALLERS. Two requests 401 at once, both refresh, both present the
 *      same token. Closed by the mutex below (single-flight), with a fast path so the
 *      common case does not serialise.
 *   2. REACTING TO EXPIRY INSTEAD OF ANTICIPATING IT. Waiting for a 401 guarantees a
 *      thundering herd at the 10-minute boundary. Closed by refreshing [EARLY_MARGIN_MS]
 *      early.
 *   3. PROCESS DEATH MID-REFRESH. The response is lost, the disk still holds a token the
 *      server has rotated. Closed by the pending marker — see [TokenStore] — which turns
 *      this into a deliberate re-login instead of a false replay alarm.
 *   4. PERSISTING THE SUCCESSOR TOO LATE. Closed by writing the new token to disk BEFORE
 *      returning its access token to any caller.
 *
 * ⚠️ THE SERVER ACCEPTS THAT CASE 3 LOGS THE USER OUT, and so must the UI. There is no
 * recovery: the client does not have the successor token and never will. `native-session.ts`
 * states this tradeoff explicitly — the alternative is being unable to distinguish theft
 * from packet loss and resolving it in the attacker's favour. Design the re-login prompt
 * for it rather than treating it as an error.
 */
class TokenRefreshCoordinator(
    private val store: TokenStore,
    private val refreshApi: RefreshApi,
    /** Injected so tests can drive time without sleeping. */
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * Where [TokenStore] is touched.
     *
     * ⛔ THE STORE IS NOT CHEAP AND IT IS ON THE PATH OF EVERY SINGLE REQUEST. Production is
     * [KeystoreTokenStore]: a `read()` does `KeyStore.getInstance("AndroidKeystore").load(null)`
     * and then four AES-GCM decrypts, and `write()` persists with `commit()` rather than
     * `apply()` — a synchronous fsync, which is required here (see [TokenStore]: a buffered
     * marker is exactly as lost as the response it exists to detect) and is therefore
     * unavoidably slow. All of that used to run on whatever dispatcher the caller happened to
     * be on, which for a `viewModelScope` request is `Dispatchers.Main.immediate`: disk and
     * crypto on the frame clock, per request, with a StrictMode disk-read violation to match.
     *
     * ⚠️ The FAST PATH IN [accessToken] DELIBERATELY STAYS OFF THIS DISPATCHER. It reads two
     * volatile fields and nothing else, so dispatching for it would add a context switch to the
     * overwhelmingly common case in order to protect main from two field reads.
     *
     * ⚠️ `Dispatchers.IO` rather than `Default`: this is blocking disk I/O plus a Keystore that
     * may block on hardware, and IO is the pool sized for exactly that.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Serialises refreshes only. Reads of an already-valid token take the fast path and
     * never touch it, so the steady state costs nothing.
     */
    private val refreshMutex = Mutex()

    /**
     * The access token, in memory ONLY, deliberately never persisted (see [TokenStore]).
     * Null after a process restart, which simply means the next call refreshes.
     */
    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var accessTokenExpiresAt: Long = 0L

    /**
     * True only while a refresh request is actually outstanding in THIS process.
     *
     * ⛔ WITHOUT THIS, THE INTERRUPTED-REFRESH CHECK MISFIRES ON ITS OWN CONCURRENCY. The
     * marker means "a refresh was sent and its outcome is unknown", which is only
     * actionable when a PREVIOUS process left it behind. While a refresh is in flight here,
     * the marker is legitimately set and the old session is legitimately still on disk —
     * the exact state the check looks for. Queued callers therefore read it as an
     * interrupted refresh and bailed out while a perfectly good refresh completed beside
     * them. Caught by `concurrent callers trigger exactly one refresh`.
     *
     * ⛔ AND IT MUST BE CLEARED ON EVERY EXIT PATH, INCLUDING FAILURE. "This process set
     * the marker at some point" is the wrong meaning: after a transport failure the marker
     * stays set on purpose, and if this flag stayed true a retry in the same process would
     * skip the interrupted check and re-send the possibly-spent token — reintroducing
     * exactly the replay the marker exists to prevent.
     */
    @Volatile
    private var refreshInFlight: Boolean = false

    /**
     * A valid access token, refreshing if necessary.
     *
     * Callers should treat [AccessToken.ReauthRequired] as terminal: clear their state and
     * route to login. Retrying will not help, and on [ReauthReason.InterruptedRefresh] in
     * particular a retry is exactly what would trigger the family revocation this avoided.
     */
    suspend fun accessToken(): AccessToken {
        // ── Fast path ────────────────────────────────────────────────────────
        // Deliberately outside the mutex: the overwhelmingly common case is "the token is
        // still good", and taking a lock for it would serialise every request in the app
        // behind one another for no benefit.
        //
        // ⚠️ ALSO OUTSIDE THE DISPATCHER SWITCH, for the same reason — two volatile reads do
        // not justify a hop. See the note on [io].
        cachedIfFresh()?.let { return AccessToken.Available(it) }

        // Everything past here reads or writes the store, so it moves to [io] as one unit
        // rather than switching per call — a refresh touches it four times.
        return withContext(io) { acquireToken() }
    }

    private suspend fun acquireToken(): AccessToken {
        val persisted = store.read() ?: return AccessToken.ReauthRequired(ReauthReason.NoSession)

        // ── The interrupted-refresh check ────────────────────────────────────
        // Checked BEFORE any network call. If the marker names the token still on disk, a
        // previous refresh was sent and its response never landed, so this token is
        // already spent server-side. Sending it would revoke the family and produce a
        // security warning describing an attack that did not happen.
        //
        // ⛔ `!refreshInFlight` IS LOAD-BEARING — see the field's doc. A refresh in flight
        // right now presents the identical on-disk state, and without this guard every
        // caller queued behind it is told to re-authenticate.
        val pending = store.pendingRefreshToken()
        if (!refreshInFlight && pending != null && pending == persisted.refreshToken) {
            store.clear()
            return AccessToken.ReauthRequired(ReauthReason.InterruptedRefresh)
        }

        if (nowMillis() >= persisted.refreshTokenExpiresAt) {
            store.clear()
            return AccessToken.ReauthRequired(ReauthReason.RefreshTokenExpired)
        }

        return refreshMutex.withLock {
            // ── Double-checked ───────────────────────────────────────────────
            // Another caller may have completed a refresh while this one waited. Without
            // this re-check, every queued caller would fire its own refresh with a token
            // the first caller already spent — which is precisely failure mode 1.
            cachedIfFresh()?.let { return@withLock AccessToken.Available(it) }

            val current = store.read()
                ?: return@withLock AccessToken.ReauthRequired(ReauthReason.NoSession)

            // ── The interrupted-refresh check, AUTHORITATIVELY ───────────────
            // ⛔ THE COPY ABOVE IS A FAST PATH AND CANNOT BE THE ONLY ONE. It is skipped
            // whenever `refreshInFlight` is true, which is exactly the state every caller
            // queued behind a running refresh observes — so those callers reached here with
            // the check never evaluated. If the refresh they were waiting on then ended in
            // TransportFailure (marker left set, store deliberately unchanged), the next
            // caller through this lock re-sent the very token that may already have been
            // spent, and the server answers a second presentation by revoking the whole
            // family: every device signed out, reported as a theft that never happened.
            //
            // ⚠️ NO `refreshInFlight` GUARD HERE, and that is what makes it authoritative:
            // holding this mutex IS the proof that no refresh is running, since the only
            // call to performRefresh happens under it.
            val pending = store.pendingRefreshToken()
            if (pending != null && pending == current.refreshToken) {
                store.clear()
                return@withLock AccessToken.ReauthRequired(ReauthReason.InterruptedRefresh)
            }

            performRefresh(current)
        }
    }

    /**
     * Adopt the tokens from a completed login (or an initial code exchange).
     *
     * Persists the refresh token before the access token becomes visible, for the same
     * ordering reason as [performRefresh].
     *
     * ⚠️ SUSPEND BECAUSE IT WRITES TO THE KEYSTORE WITH `commit()`. It is called from
     * [NativeLoginFlow]'s completion, which runs on whatever dispatcher handled the OAuth
     * callback — the main thread, in the deep-link case.
     */
    suspend fun adopt(tokens: NativeTokens, deviceId: String) {
        withContext(io) {
            store.write(
                PersistedSession(
                    refreshToken = tokens.refreshToken,
                    refreshTokenExpiresAt = tokens.refreshTokenExpiresAt,
                    deviceId = deviceId,
                ),
            )
            store.clearRefreshPending()
        }
        accessToken = tokens.accessToken
        accessTokenExpiresAt = tokens.accessTokenExpiresAt
    }

    /**
     * Discard the cached access token if it is still the one the caller used.
     *
     * ⛔ WITHOUT THIS, A SERVER-SIDE REVOCATION STRANDS THE APP FOR UP TO THE FULL ACCESS
     * TOKEN TTL. An access token is a stateless JWS, but the server checks revocation on
     * every authenticated request (`requireAuth` → `isSessionRevoked`), so a password change,
     * a password reset or an account deletion makes a signature-valid, unexpired token start
     * answering 401 "Session expired. Please sign in again." The fast path in [accessToken]
     * only consults the clock, so it would keep handing back that same dead token for the
     * remainder of its 10 minutes and every request would 401 with no action the user could
     * take to recover.
     *
     * ⛔ THE TOKEN ARGUMENT IS NOT DECORATION — it makes this safe under concurrency. Two
     * requests can 401 together; the first invalidates and refreshes, the second then calls
     * this with the OLD token and must not wipe the good one that just replaced it. Comparing
     * before clearing turns the second call into a no-op. An unconditional `accessToken =
     * null` would throw away a valid token and force an extra refresh — and extra refreshes
     * are exactly what the 429-driven sign-out this class guards against is made of.
     *
     * @return true if the cached token was actually discarded.
     */
    fun invalidateAccessToken(token: String): Boolean {
        if (accessToken != token) return false
        accessToken = null
        accessTokenExpiresAt = 0L
        return true
    }

    /**
     * Local sign-out. The caller is responsible for `POST /api/auth/native/revoke`.
     *
     * ⛔ THE IN-MEMORY TOKEN IS CLEARED FIRST, BEFORE THE SUSPENDING DISK WIPE. Doing the disk
     * work first would leave a window — however short — in which the coordinator still hands a
     * live access token to a request racing the sign-out. Order matters more here than the
     * dispatcher does.
     */
    suspend fun forget() {
        accessToken = null
        accessTokenExpiresAt = 0L
        withContext(io) { store.clear() }
    }

    private fun cachedIfFresh(): String? {
        val token = accessToken ?: return null
        return if (needsRefresh(accessTokenExpiresAt)) null else token
    }

    private fun needsRefresh(expiresAt: Long): Boolean = nowMillis() + EARLY_MARGIN_MS >= expiresAt

    /**
     * ⛔ THE WHOLE ROTATION IS [NonCancellable], AND THAT IS NOT DEFENSIVE TIDYING. This runs in
     * the calling coroutine's job, and the callers are `viewModelScope` and Paging loads — both
     * routinely cancelled by ordinary navigation. Cancelled anywhere in the middle, the rotation
     * is not undone, it is merely UNOBSERVED:
     *
     *   - after `markRefreshPending` but before the request: the marker names a token that was
     *     never sent, so the next attempt reads it as an interrupted refresh and signs the user
     *     out for navigating away;
     *   - after the response but before `store.write`: the server HAS rotated, the successor
     *     exists only in the discarded result, and the session is unrecoverable.
     *
     * A refresh is a state transition shared with the server; once begun it has to be carried to
     * a persisted conclusion. Bounded by OkHttp's 30s `callTimeout`, so this cannot hang a
     * cancelled scope indefinitely.
     */
    private suspend fun performRefresh(current: PersistedSession): AccessToken =
        withContext(NonCancellable) { performRefreshUninterruptible(current) }

    private suspend fun performRefreshUninterruptible(current: PersistedSession): AccessToken {
        // ⛔ MARKER BEFORE NETWORK, AND IT MUST BE DURABLE. If this write is buffered, it
        // is lost in exactly the crash it exists to detect.
        refreshInFlight = true
        store.markRefreshPending(current.refreshToken)

        val outcome = try {
            refreshApi.refresh(current.refreshToken)
        } finally {
            // Cleared here, before any branch below, so every exit path — including a
            // thrown exception — leaves this false. See the field's doc.
            refreshInFlight = false
        }

        val rotated = when (outcome) {
            is RefreshResult.Success -> outcome.tokens
            RefreshResult.Rejected -> {
                // The server collapses unknown / expired / revoked / replayed into one
                // opaque `invalid_grant`, on purpose, so there is nothing to distinguish
                // here. Any of them means this credential is dead.
                store.clear()
                return AccessToken.ReauthRequired(ReauthReason.RefreshRejected)
            }
            RefreshResult.RateLimited -> {
                // ⛔ The token was NOT consumed — the server rate-limits before rotating — so
                // the marker MUST be cleared. Leaving it set would make the next attempt
                // report InterruptedRefresh and sign the user out over a transient 429.
                store.clearRefreshPending()
                return AccessToken.RetryLater
            }
            RefreshResult.TransportFailure -> {
                // ⚠️ The marker is deliberately LEFT SET. The request may have reached the
                // server and rotated the token even though the response did not arrive —
                // that is indistinguishable from here. Clearing the marker would let a
                // later attempt present the possibly-spent token and revoke the family.
                // Leaving it set costs a re-login in the ambiguous case and prevents a
                // false replay alarm; the next attempt takes the InterruptedRefresh path.
                return AccessToken.ReauthRequired(ReauthReason.RefreshUnreachable)
            }
            RefreshResult.NotSent -> {
                // ⛔ THE REQUEST NEVER LEFT THE DEVICE, so the token is provably unspent and
                // this is handled exactly like the 429 above: clear the marker, keep the
                // session, let the caller retry. Leaving it set is what signed people out for
                // opening the app offline. See RefreshResult.NotSent.
                store.clearRefreshPending()
                return AccessToken.RetryLater
            }
        }

        // ⛔ PERSIST THE SUCCESSOR BEFORE ANY CALLER CAN USE ITS ACCESS TOKEN. Returning
        // first and writing after leaves a window where the app is making authenticated
        // requests with a refresh token that only exists in memory; a crash there loses
        // the successor while the server has already rotated the predecessor.
        store.write(
            PersistedSession(
                refreshToken = rotated.refreshToken,
                refreshTokenExpiresAt = rotated.refreshTokenExpiresAt,
                deviceId = current.deviceId,
            ),
        )
        store.clearRefreshPending()

        accessToken = rotated.accessToken
        accessTokenExpiresAt = rotated.accessTokenExpiresAt
        return AccessToken.Available(rotated.accessToken)
    }

    companion object {
        /**
         * Refresh this far before the access token actually expires.
         *
         * 60s against a 10-minute TTL. Sized to cover a slow request plus the server's own
         * 5s clock-skew tolerance, without refreshing so eagerly that the effective token
         * lifetime shrinks meaningfully. Raising it materially would increase refresh
         * traffic sixfold at 10-minute TTLs for no benefit.
         */
        const val EARLY_MARGIN_MS: Long = 60_000
    }
}

/** Result of asking for an access token. */
sealed interface AccessToken {
    data class Available(val token: String) : AccessToken

    /** Terminal. Route to login; do not retry. */
    data class ReauthRequired(val reason: ReauthReason) : AccessToken

    /**
     * Transient. The session is intact and the stored token is still valid — the refresh was
     * merely rate-limited. Fail the in-flight request, keep the user signed in, try again
     * later.
     *
     * ⛔ Do NOT treat this as a sign-out. That is the entire reason it is a separate case
     * rather than another [ReauthReason].
     */
    data object RetryLater : AccessToken
}

enum class ReauthReason {
    /** Never signed in, or signed out locally. */
    NoSession,

    /**
     * A refresh was in flight when the process died. The stored token is presumed spent.
     * ⚠️ Not a security event — see [TokenStore].
     */
    InterruptedRefresh,

    /** The 60-day sliding window elapsed without the app being opened. */
    RefreshTokenExpired,

    /**
     * The server refused the token: unknown, expired, revoked, or replayed. Indistinguishable
     * by design.
     */
    RefreshRejected,

    /**
     * The refresh could not be delivered or its response was lost. The next attempt will
     * report [InterruptedRefresh] because the marker is intentionally still set.
     */
    RefreshUnreachable,
}

/** The network seam. */
interface RefreshApi {
    suspend fun refresh(refreshToken: String): RefreshResult
}

sealed interface RefreshResult {
    data class Success(val tokens: NativeTokens) : RefreshResult

    /**
     * A definite 401 `invalid_grant`. The credential is dead — unknown, expired, revoked or
     * replayed, indistinguishable by design.
     */
    data object Rejected : RefreshResult

    /**
     * HTTP 429.
     *
     * ⛔ NOT A DEAD CREDENTIAL, AND CONFLATING IT WITH ONE LOGS THE USER OUT FOR NOTHING. The
     * refresh route runs its rate-limit check BEFORE `rotateNativeSession`, so on a 429 the
     * token was provably never consumed and is still perfectly valid. The server's own comment
     * sizes the limit to allow "several devices behind one NAT", which is exactly when this
     * fires in the field — an office where two staff phones share an egress IP.
     *
     * The correct handling is therefore: keep the session, clear the pending marker (the token
     * is known-unspent), and let the caller retry later.
     */
    data object RateLimited : RefreshResult

    /**
     * No usable answer — timeout, 5xx, an unreadable 200. ⚠️ AMBIGUOUS: the server may have
     * processed the rotation anyway, which is why the pending marker is left set.
     */
    data object TransportFailure : RefreshResult

    /**
     * The request PROVABLY never left the device — DNS failure, connection refused, no route,
     * a TLS handshake that never completed.
     *
     * ⛔ DISTINCT FROM [TransportFailure] BECAUSE CONFLATING THEM SIGNED PEOPLE OUT FOR BEING
     * OFFLINE. Both were mapped to [TransportFailure], which leaves the pending marker set
     * because the token *might* have been spent. For a connect-phase failure it provably was
     * not: no byte reached the server. So opening the app in airplane mode — any cold start
     * more than ten minutes after last use, since the access token is memory-only — marked the
     * refresh token pending, failed to send it, and the next attempt saw marker == stored token
     * and cleared the session. One offline app-open cost a re-login.
     *
     * Handled exactly like [RateLimited]: the token is known-unspent, so clear the marker, keep
     * the session, and let the caller retry.
     *
     * ⚠️ A [java.net.SocketTimeoutException] is deliberately NOT in this category. A connect
     * timeout and a read timeout are the same exception type, and a read timeout means the
     * request was already sent — so it stays ambiguous.
     */
    data object NotSent : RefreshResult
}
