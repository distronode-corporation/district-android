package com.distronode.districtai.core.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These tests are about ONE invariant: a refresh token is never sent twice. The server
 * revokes the entire token family on a replay, so every failure mode below is a real
 * sign-out-all-devices event, not a cosmetic bug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenRefreshCoordinatorTest {

    private val deviceId = "device-under-test"

    private fun session(refreshToken: String, expiresInMs: Long = DAY_MS * 60) =
        PersistedSession(refreshToken, NOW + expiresInMs, deviceId)

    private fun tokens(suffix: String, accessTtlMs: Long = TEN_MINUTES_MS) = NativeTokens(
        accessToken = "access-$suffix",
        accessTokenExpiresAt = NOW + accessTtlMs,
        refreshToken = "refresh-$suffix",
        refreshTokenExpiresAt = NOW + DAY_MS * 60,
    )

    // ── 1. Single flight ─────────────────────────────────────────────────────

    @Test
    fun `concurrent callers trigger exactly one refresh`() = runTest {
        val store = FakeTokenStore(session("r0"))
        val gate = CompletableDeferred<Unit>()
        val api = CountingRefreshApi(gate) { RefreshResult.Success(tokens("r1")) }

        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })

        // Ten callers all discover an absent/stale access token at once — the shape of an
        // app resuming and firing every screen's request simultaneously.
        val results = List(10) { async { coordinator.accessToken() } }
        advanceUntilIdle()
        gate.complete(Unit)
        val resolved = results.awaitAll()

        assertEquals(
            "A refresh token is single-use; a second concurrent refresh would present an " +
                "already-rotated token and revoke the whole family.",
            1,
            api.callCount,
        )
        assertEquals(
            "every caller should receive the SAME newly-minted access token",
            setOf("access-r1"),
            resolved.map { (it as AccessToken.Available).token }.toSet(),
        )
        assertEquals(
            "only the original token should ever have been sent",
            listOf("r0"),
            api.presentedTokens,
        )
    }

    // ── 2. Persist before use ────────────────────────────────────────────────

    @Test
    fun `rotated refresh token is persisted before its access token is returned`() = runTest {
        val store = FakeTokenStore(session("r0"))
        val api = CountingRefreshApi(null) { RefreshResult.Success(tokens("r1")) }
        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })

        val result = coordinator.accessToken()
        assertTrue(result is AccessToken.Available)

        // The ORDER is the assertion. `markPending` must precede the network call, the
        // successor must be written before the caller can act on it, and only then is the
        // marker cleared.
        assertEquals(
            listOf("markPending(r0)", "write(refresh-r1)", "clearPending"),
            store.operations,
        )
    }

    // ── 3. Process death mid-refresh ─────────────────────────────────────────

    @Test
    fun `process death mid-refresh re-authenticates instead of replaying the token`() = runTest {
        val store = FakeTokenStore(session("r0"))
        val api = CountingRefreshApi(null) { RefreshResult.TransportFailure }

        // First run: the refresh is sent, the response never arrives.
        val first = TokenRefreshCoordinator(store, api, nowMillis = { NOW }).accessToken()
        assertEquals(
            AccessToken.ReauthRequired(ReauthReason.RefreshUnreachable),
            first,
        )
        assertEquals("the marker must survive to be seen after a restart", "r0", store.pendingRefreshToken())

        // Now the process dies and restarts: durable state survives, memory does not.
        val restarted = store.snapshotForRestart()
        val afterRestartApi = CountingRefreshApi(null) { RefreshResult.Success(tokens("r1")) }
        val result = TokenRefreshCoordinator(restarted, afterRestartApi, nowMillis = { NOW }).accessToken()

        assertEquals(
            "the stored token is presumed spent, so the only safe action is a clean re-login",
            AccessToken.ReauthRequired(ReauthReason.InterruptedRefresh),
            result,
        )
        assertEquals(
            "⛔ the possibly-rotated token must NOT be sent again — that is the replay the " +
                "server revokes an entire family for",
            0,
            afterRestartApi.callCount,
        )
        assertTrue("the dead session should be cleared", restarted.read() == null)
    }

    @Test
    fun `retrying in the same process after a transport failure does not resend the token`() = runTest {
        val store = FakeTokenStore(session("r0"))
        var response: RefreshResult = RefreshResult.TransportFailure
        val api = CountingRefreshApi(null) { response }
        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })

        // The response is lost. The marker stays set on purpose.
        assertEquals(
            AccessToken.ReauthRequired(ReauthReason.RefreshUnreachable),
            coordinator.accessToken(),
        )
        assertEquals(1, api.callCount)

        // The same coordinator instance is asked again — an app retrying without restarting.
        // Even if the server would now answer successfully, the stored token may already
        // have been rotated by the lost request, so it MUST NOT be presented again.
        response = RefreshResult.Success(tokens("r1"))
        val second = coordinator.accessToken()

        assertEquals(
            AccessToken.ReauthRequired(ReauthReason.InterruptedRefresh),
            second,
        )
        assertEquals(
            "⛔ resending here is the replay that revokes the family — the in-flight flag " +
                "must be cleared on the failure path, not merely on success",
            1,
            api.callCount,
        )
        assertEquals(listOf("r0"), api.presentedTokens)
    }

    // ── 4. Proactive refresh ─────────────────────────────────────────────────

    @Test
    fun `refreshes before expiry rather than waiting for a 401`() = runTest {
        val store = FakeTokenStore(session("r0"))
        val api = CountingRefreshApi(null) { RefreshResult.Success(tokens("r1")) }
        var clock = NOW
        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { clock })

        coordinator.adopt(tokens("r1", accessTtlMs = TEN_MINUTES_MS), deviceId)

        // Comfortably inside the token's life: no refresh.
        clock = NOW + TEN_MINUTES_MS - TokenRefreshCoordinator.EARLY_MARGIN_MS - 1_000
        assertEquals(AccessToken.Available("access-r1"), coordinator.accessToken())
        assertEquals("should not refresh a token that is still good", 0, api.callCount)

        // Inside the early margin but NOT yet expired: refresh anyway. Waiting for a real
        // 401 would mean every in-flight request fails once at the boundary.
        clock = NOW + TEN_MINUTES_MS - TokenRefreshCoordinator.EARLY_MARGIN_MS + 1
        coordinator.accessToken()
        assertEquals("should refresh pre-emptively inside the margin", 1, api.callCount)
    }

    // ── 5. Terminal failures ─────────────────────────────────────────────────

    @Test
    fun `a rejected refresh clears the session`() = runTest {
        val store = FakeTokenStore(session("r0"))
        val api = CountingRefreshApi(null) { RefreshResult.Rejected }

        val result = TokenRefreshCoordinator(store, api, nowMillis = { NOW }).accessToken()

        assertEquals(AccessToken.ReauthRequired(ReauthReason.RefreshRejected), result)
        assertTrue("a dead credential must not be retained", store.read() == null)
    }

    @Test
    fun `an expired refresh token never reaches the network`() = runTest {
        // Past the 60-day sliding window.
        val store = FakeTokenStore(session("r0", expiresInMs = -1))
        val api = CountingRefreshApi(null) { RefreshResult.Success(tokens("r1")) }

        val result = TokenRefreshCoordinator(store, api, nowMillis = { NOW }).accessToken()

        assertEquals(AccessToken.ReauthRequired(ReauthReason.RefreshTokenExpired), result)
        assertEquals("no point spending a round trip on a token we know is dead", 0, api.callCount)
    }

    @Test
    fun `no stored session reports NoSession without touching the network`() = runTest {
        val store = FakeTokenStore(initial = null)
        val api = CountingRefreshApi(null) { RefreshResult.Success(tokens("r1")) }

        val result = TokenRefreshCoordinator(store, api, nowMillis = { NOW }).accessToken()

        assertEquals(AccessToken.ReauthRequired(ReauthReason.NoSession), result)
        assertEquals(0, api.callCount)
    }

    // ── 6. No token is ever presented twice, across a whole sequence ─────────

    @Test
    fun `a long sequence of refreshes never presents the same token twice`() = runTest {
        val store = FakeTokenStore(session("r0"))
        var generation = 0
        val api = CountingRefreshApi(null) {
            generation += 1
            RefreshResult.Success(tokens("r$generation"))
        }
        var clock = NOW
        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { clock })

        repeat(25) {
            coordinator.accessToken()
            // Jump past the early-refresh margin so each iteration genuinely refreshes.
            clock += TEN_MINUTES_MS
        }

        assertEquals("each iteration should have refreshed once", 25, api.callCount)
        assertEquals(
            "⛔ a duplicate here means the client would have triggered a family revocation",
            api.presentedTokens.size,
            api.presentedTokens.toSet().size,
        )
        assertEquals(
            "the marker log should likewise contain no duplicates",
            store.markedTokens.size,
            store.markedTokens.toSet().size,
        )
    }

    // ── 6. Rejecting a token the clock still considers fresh ─────────────────

    @Test
    fun `an invalidated access token is re-minted on the next call`() = runTest {
        // ⛔ THE GAP THIS CLOSES. An access token is a stateless JWS, but the server checks
        // revocation on EVERY authenticated request, so a password change or an account
        // deletion makes a signature-valid, unexpired token start answering 401. The fast path
        // only consults the clock, so without invalidation it would keep handing back that dead
        // token for the rest of its 10 minutes and every request would 401 with nothing the
        // user could do about it.
        val store = FakeTokenStore(session("r0"))
        var generation = 0
        val api = CountingRefreshApi(null) {
            generation += 1
            RefreshResult.Success(tokens("r$generation"))
        }
        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })

        val first = coordinator.accessToken()
        assertEquals(AccessToken.Available("access-r1"), first)
        // Proves the fast path is real: a second call with the clock unmoved must NOT refresh.
        assertEquals(AccessToken.Available("access-r1"), coordinator.accessToken())
        assertEquals(1, api.callCount)

        assertTrue("the cached token should have been discarded", coordinator.invalidateAccessToken("access-r1"))

        assertEquals(AccessToken.Available("access-r2"), coordinator.accessToken())
        assertEquals(2, api.callCount)
    }

    @Test
    fun `invalidating a token that is no longer cached is a no-op`() = runTest {
        // ⛔ THE CONCURRENCY GUARD. Two requests can 401 together: the first invalidates and
        // refreshes, the second then arrives with the OLD token. Comparing before clearing
        // makes that second call harmless. An unconditional clear would throw away the good
        // token that just replaced it and force an extra refresh — and extra refreshes are what
        // the 429-driven sign-out this class guards against are made of.
        val store = FakeTokenStore(session("r0"))
        var generation = 0
        val api = CountingRefreshApi(null) {
            generation += 1
            RefreshResult.Success(tokens("r$generation"))
        }
        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })

        coordinator.accessToken()
        coordinator.invalidateAccessToken("access-r1")
        val replacement = coordinator.accessToken()
        assertEquals(AccessToken.Available("access-r2"), replacement)

        // The straggler, still holding the token that was already replaced.
        assertFalse(
            "a stale token must not evict the successor",
            coordinator.invalidateAccessToken("access-r1"),
        )

        assertEquals(AccessToken.Available("access-r2"), coordinator.accessToken())
        assertEquals("no extra refresh should have been triggered", 2, api.callCount)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val TEN_MINUTES_MS = 10 * 60 * 1000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }

    // ── 5. Failures that must not cost the session ───────────────────────────

    @Test
    fun `a caller queued behind a failed refresh does not re-present the same token`() = runTest {
        // ⛔ THE REPLAY THIS WHOLE FILE EXISTS TO PREVENT, VIA THE ONE PATH IT DID NOT COVER.
        // The interrupted-refresh check was skipped whenever `refreshInFlight` was true, which
        // is precisely what a queued caller observes. When the refresh it was waiting on ended
        // in TransportFailure — marker set, store deliberately unchanged — the queued caller
        // re-sent the very token that may already have been spent, and the server answers a
        // second presentation by revoking the entire family.
        val store = FakeTokenStore(session("r0"))
        val gate = CompletableDeferred<Unit>()
        val api = CountingRefreshApi(gate) { RefreshResult.TransportFailure }

        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })

        val first = async { coordinator.accessToken() }
        val second = async { coordinator.accessToken() }
        advanceUntilIdle()

        gate.complete(Unit)
        val results = listOf(first, second).awaitAll()
        advanceUntilIdle()

        assertEquals("r0 must be presented exactly once", 1, api.callCount)
        assertEquals(listOf("r0"), api.presentedTokens)
        // The queued caller is told to re-authenticate rather than being handed a token, and
        // the ambiguous session is cleared exactly once.
        assertTrue(
            results.any {
                it is AccessToken.ReauthRequired && it.reason == ReauthReason.InterruptedRefresh
            },
        )
        assertNull(store.read())
    }

    @Test
    fun `the queued caller is refused by the check under the lock, deterministically`() = runTest {
        // ⛔ THE SAME REPLAY AS THE TEST ABOVE, WITH THE SCHEDULING PINNED. On the real IO
        // dispatcher the second caller can arrive after the first has already finished, and is
        // then refused by the unlocked check instead, so which of the two checks ran is down to
        // thread timing. With `io` on the test scheduler the second caller provably evaluates the
        // unlocked check while the refresh is in flight (and so skips it), queues on the mutex,
        // and is refused by the authoritative check once the failed refresh releases the lock.
        val store = FakeTokenStore(session("r0"))
        val gate = CompletableDeferred<Unit>()
        val api = CountingRefreshApi(gate) { RefreshResult.TransportFailure }
        val coordinator = TokenRefreshCoordinator(
            store,
            api,
            nowMillis = { NOW },
            io = StandardTestDispatcher(testScheduler),
        )

        val first = async { coordinator.accessToken() }
        val second = async { coordinator.accessToken() }
        advanceUntilIdle()
        assertEquals("the second caller is queued, not sending", 1, api.callCount)

        gate.complete(Unit)

        assertEquals(AccessToken.ReauthRequired(ReauthReason.RefreshUnreachable), first.await())
        assertEquals(AccessToken.ReauthRequired(ReauthReason.InterruptedRefresh), second.await())
        assertEquals("r0 must be presented exactly once", listOf("r0"), api.presentedTokens)
        assertNull("the ambiguous session is cleared", store.read())
    }

    @Test
    fun `a request that never left the device keeps the session`() = runTest {
        // ⛔ OPENING THE APP OFFLINE USED TO BURN THE SESSION. A connect-phase failure was
        // mapped to TransportFailure, whose contract is "may have rotated", so the marker was
        // left set and the next attempt cleared everything. The token is provably unspent here.
        val store = FakeTokenStore(session("r0"))
        val api = CountingRefreshApi(null) { RefreshResult.NotSent }

        val coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })
        val result = coordinator.accessToken()
        advanceUntilIdle()

        assertTrue("offline is retryable, not terminal", result is AccessToken.RetryLater)
        assertNotNull("the session survives being offline", store.read())
        assertEquals("r0", store.read()?.refreshToken)
        assertNull("the marker must be cleared, the token was never sent", store.pendingRefreshToken())

        // And the retry actually works rather than tripping the interrupted check.
        val online = CountingRefreshApi(null) { RefreshResult.Success(tokens("r1")) }
        val resumed = TokenRefreshCoordinator(store, online, nowMillis = { NOW })
        val second = resumed.accessToken()
        advanceUntilIdle()

        assertTrue(second is AccessToken.Available)
        assertEquals("refresh-r1", store.read()?.refreshToken)
    }

    @Test
    fun `cancelling the caller does not discard a completed rotation`() = runTest {
        // ⛔ THE ROTATION IS SHARED STATE WITH THE SERVER, SO IT CANNOT BE ABANDONED HALFWAY.
        // performRefresh runs in the caller's job, and the callers are viewModelScope and Paging
        // loads — cancelled by ordinary navigation. Cancelled after the server rotated but before
        // the successor was persisted, the session was unrecoverable: the server had moved on and
        // the only copy of the new token went with the discarded result.
        val store = FakeTokenStore(session("r0"))
        val gate = CompletableDeferred<Unit>()
        val api = CountingRefreshApi(gate) { RefreshResult.Success(tokens("r1")) }

        // ⚠️ `io` IS PINNED TO THE TEST SCHEDULER HERE, unlike the other cases in this file.
        // The coordinator defaults it to the real Dispatchers.IO, which the other tests get away
        // with because they await a result and the resumption pumps the scheduler. This one
        // deliberately CANCELS its caller, so there is nothing left awaiting: the NonCancellable
        // continuation would finish on a real IO thread with no virtual-time task to resume into,
        // and the test would hang rather than fail. Pinning it makes the whole sequence
        // deterministic.
        val coordinator = TokenRefreshCoordinator(
            store,
            api,
            nowMillis = { NOW },
            io = StandardTestDispatcher(testScheduler),
        )

        val job = launch { coordinator.accessToken() }
        advanceUntilIdle()
        assertEquals("the refresh is in flight", 1, api.callCount)

        // The user navigates away mid-refresh.
        job.cancel()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals("the successor must be persisted", "refresh-r1", store.read()?.refreshToken)
        assertNull("and the marker cleared", store.pendingRefreshToken())
    }
}

/**
 * Records every token presented, so a test can assert on the SEQUENCE rather than just a
 * count. `gate`, when supplied, holds the first call open so concurrency can be forced
 * deterministically instead of hoped for.
 */
private class CountingRefreshApi(
    private val gate: CompletableDeferred<Unit>?,
    private val respond: () -> RefreshResult,
) : RefreshApi {

    var callCount: Int = 0
        private set

    val presentedTokens: MutableList<String> = mutableListOf()

    override suspend fun refresh(refreshToken: String): RefreshResult {
        callCount += 1
        presentedTokens += refreshToken
        gate?.await()
        return respond()
    }
}
