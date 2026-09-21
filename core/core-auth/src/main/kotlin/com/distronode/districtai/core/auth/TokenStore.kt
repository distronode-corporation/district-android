package com.distronode.districtai.core.auth

/**
 * Durable storage for the native session.
 *
 * ⛔ THE REFRESH TOKEN IS THE ONLY THING WORTH PERSISTING, AND THE ACCESS TOKEN MUST NOT
 * BE. The access token lives 10 minutes; writing it to disk buys nothing and widens the
 * window in which a stolen backup or a rooted-device dump yields a usable credential.
 * Implementations persist [NativeTokens.refreshToken] and its expiry; the access token is
 * held in memory by [TokenRefreshCoordinator] and re-minted after a process restart.
 *
 * ⛔ THE `pendingRefreshToken` MARKER IS NOT AN OPTIMISATION — IT PREVENTS A FALSE
 * SECURITY ALARM. The server's rotation is mandatory and a replayed refresh token revokes
 * the whole family. If the process dies between sending a refresh and persisting its
 * response, the disk still holds a token the server has already rotated. Presenting it on
 * next launch is indistinguishable from theft: the server revokes the family and logs
 * `[auth] Native refresh replay detected`. Recording which token is in flight BEFORE
 * sending it lets the client recognise that state on the next launch and go straight to a
 * clean re-login instead — same user-visible outcome (sign in again), but no bogus replay
 * alarm and no revocation of a family that was never compromised.
 *
 * This is an interface so the storage primitive is swappable. See [KeystoreTokenStore] for
 * the production one and the in-memory fake in the test source set.
 */
interface TokenStore {

    /** The persisted session, or null when there is none. */
    fun read(): PersistedSession?

    /** Replace the persisted session. Implementations must write DURABLY before returning. */
    fun write(session: PersistedSession)

    /**
     * Forget the session, including any pending-refresh marker.
     *
     * ⛔ DELIBERATELY DOES **NOT** CLEAR THE REVOKE OUTBOX — see [pendingRevokeToken]. The
     * outbox names a credential the SERVER is still honouring, and the sign-out that wrote
     * it calls this method microseconds later. A `clear()` that took the outbox with it
     * would erase the only record of the token it exists to chase, which is precisely the
     * stranding the outbox was added to prevent. The two slots look symmetric and are not:
     * the refresh marker describes THIS session (so it dies with it), the revoke outbox
     * describes a server-side row that outlives it.
     */
    fun clear()

    /**
     * The refresh token currently in flight, if a refresh was started and never observed
     * to complete. Null in the normal case.
     */
    fun pendingRefreshToken(): String?

    /**
     * Record that [refreshToken] is about to be sent.
     *
     * ⛔ MUST be durable before the network call is made. If this write is lazy or
     * cached, the marker is exactly as lost as the response it exists to detect.
     */
    fun markRefreshPending(refreshToken: String)

    /** Clear the marker after the rotated token has been durably persisted. */
    fun clearRefreshPending()

    /**
     * A refresh token this device has SIGNED OUT OF LOCALLY but has not yet been able to
     * revoke server-side. Null in the normal case.
     *
     * ⛔ AN OUTBOX, NOT A MARKER, AND THE DISTINCTION IS WHY IT IS A SECOND SLOT RATHER THAN
     * A REUSE OF [pendingRefreshToken]. That one means "a refresh is in flight and its
     * outcome is unknown", and [TokenRefreshCoordinator] reads it to decide whether the
     * stored token is already spent — writing a sign-out into it would make the next launch
     * report `InterruptedRefresh` for a session that was deliberately ended, and would make
     * a genuine interrupted refresh indistinguishable from a sign-out.
     *
     * ⛔ WHY IT EXISTS AT ALL. `POST /api/auth/native/revoke` answers **503** when its
     * database write threw, and the route's own header spells out the contract: the client
     * must KEEP the credential and try again, because the server may still honour that
     * refresh token for the rest of its 60-day life. But the user asked to sign out NOW, so
     * the local wipe cannot wait — a device that still looks signed in is the worse of the
     * two failures. Keeping the token HERE is how both are satisfied: the session is gone
     * from the user's point of view, and the credential is still tracked well enough to be
     * killed on a later launch.
     *
     * ⚠️ SCOPED TO ONE ROW SERVER-SIDE, so a stale entry is harmless. `revokeNativeSession`
     * matches on the presented token's hash alone — not on its family and not on the user —
     * so draining an outbox entry written before a re-login cannot touch the new session.
     */
    fun pendingRevokeToken(): String?

    /**
     * Record that [refreshToken] still needs revoking server-side.
     *
     * ⛔ MUST be durable before [clear] runs, for the same reason the refresh marker must be
     * durable before the network call: a buffered write is lost in exactly the process death
     * it exists to survive.
     */
    fun markRevokePending(refreshToken: String)

    /** Clear the outbox once the server has accepted (or already forgotten) the token. */
    fun clearRevokePending()
}

/**
 * What actually goes to disk.
 *
 * Note the absence of an access token — see the ⛔ on [TokenStore].
 */
data class PersistedSession(
    val refreshToken: String,
    /** Epoch MILLISECONDS. */
    val refreshTokenExpiresAt: Long,
    /** Opaque installation id, sent on token exchange so the server can scope sign-outs. */
    val deviceId: String,
)
