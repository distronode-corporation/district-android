package com.distronode.districtai.core.network

import com.distronode.districtai.core.auth.NativeTokens
import com.distronode.districtai.core.auth.PersistedSession
import com.distronode.districtai.core.auth.RefreshApi
import com.distronode.districtai.core.auth.RefreshResult
import com.distronode.districtai.core.auth.TokenRefreshCoordinator
import com.distronode.districtai.core.auth.TokenStore

/**
 * A real [TokenRefreshCoordinator] over in-memory fakes.
 *
 * ⛔ THE COORDINATOR IS DELIBERATELY NOT MOCKED. The 401-retry path in [DistrictApiClient]
 * only works if `invalidateAccessToken` actually clears the cache AND the next
 * `accessToken()` actually refreshes — an interaction between the two classes. A stubbed
 * coordinator would let the client's retry loop pass while that interaction was broken,
 * which is the only way this can fail in the field.
 */
internal class FakeTokenStore(
    private var session: PersistedSession? = null,
) : TokenStore {
    private var pending: String? = null

    /**
     * ⚠️ PRESENT ONLY TO SATISFY THE INTERFACE. Nothing in this module signs out — the revoke
     * outbox is written by `AppContainer.signOut` and drained at app start, neither of which is
     * on any path [DistrictApiClient] takes. Modelled faithfully anyway (not cleared by [clear],
     * matching `KeystoreTokenStore`) so it cannot become the odd one out later.
     */
    private var pendingRevoke: String? = null

    override fun read(): PersistedSession? = session

    override fun write(session: PersistedSession) {
        this.session = session
    }

    override fun clear() {
        session = null
        pending = null
    }

    override fun pendingRefreshToken(): String? = pending

    override fun markRefreshPending(refreshToken: String) {
        pending = refreshToken
    }

    override fun clearRefreshPending() {
        pending = null
    }

    override fun pendingRevokeToken(): String? = pendingRevoke

    override fun markRevokePending(refreshToken: String) {
        pendingRevoke = refreshToken
    }

    override fun clearRevokePending() {
        pendingRevoke = null
    }
}

/** Hands out a predictable series of access tokens so a retry can be told from a reuse. */
internal class FakeRefreshApi(
    private var outcome: () -> RefreshResult = { RefreshResult.TransportFailure },
) : RefreshApi {
    var callCount: Int = 0
        private set

    override suspend fun refresh(refreshToken: String): RefreshResult {
        callCount++
        return outcome()
    }

    fun rotating(accessTokenPrefix: String = "access", ttlMillis: Long = TEN_MINUTES) {
        var generation = 0
        outcome = {
            generation++
            RefreshResult.Success(
                NativeTokens(
                    accessToken = "$accessTokenPrefix-$generation",
                    accessTokenExpiresAt = System.currentTimeMillis() + ttlMillis,
                    refreshToken = "refresh-$generation",
                    refreshTokenExpiresAt = System.currentTimeMillis() + SIXTY_DAYS,
                ),
            )
        }
    }

    fun always(result: RefreshResult) {
        outcome = { result }
    }

    private companion object {
        // Comfortably beyond the coordinator's 60s early-refresh margin, so a token minted
        // here is considered fresh and the fast path is exercised.
        const val TEN_MINUTES = 600_000L
        const val SIXTY_DAYS = 60L * 24 * 60 * 60 * 1000
    }
}

/** A coordinator holding a valid stored session, ready to refresh on demand. */
internal fun signedInCoordinator(
    refreshApi: FakeRefreshApi,
): TokenRefreshCoordinator = TokenRefreshCoordinator(
    store = FakeTokenStore(
        PersistedSession(
            refreshToken = "refresh-0",
            refreshTokenExpiresAt = System.currentTimeMillis() + 60L * 24 * 60 * 60 * 1000,
            deviceId = "device-under-test",
        ),
    ),
    refreshApi = refreshApi,
)

/** A coordinator with nothing stored — every call must report NoSession. */
internal fun signedOutCoordinator(): TokenRefreshCoordinator = TokenRefreshCoordinator(
    store = FakeTokenStore(null),
    refreshApi = FakeRefreshApi(),
)
