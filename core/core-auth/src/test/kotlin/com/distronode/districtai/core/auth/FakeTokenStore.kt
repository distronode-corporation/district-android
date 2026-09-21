package com.distronode.districtai.core.auth

/**
 * In-memory [TokenStore] that also RECORDS THE ORDER OF OPERATIONS.
 *
 * The order matters more than the values here: the persist-before-use rule is an ordering
 * property, and a test that only inspected final state would pass against an
 * implementation that returned the access token first and wrote afterwards.
 */
class FakeTokenStore(initial: PersistedSession? = null) : TokenStore {

    private var session: PersistedSession? = initial
    private var pending: String? = null

    /**
     * ⚠️ NOT cleared by [clear], mirroring [KeystoreTokenStore]. The outbox names a
     * credential the SERVER still honours, and sign-out writes it immediately before the
     * wipe — a fake that dropped it here would make the production behaviour untestable and
     * would agree with the bug rather than with the code.
     */
    private var pendingRevoke: String? = null

    /** Append-only log of mutations, for ordering assertions. */
    val operations: MutableList<String> = mutableListOf()

    /** Every refresh token ever handed to [markRefreshPending], to detect a double-send. */
    val markedTokens: MutableList<String> = mutableListOf()

    override fun read(): PersistedSession? = session

    override fun write(session: PersistedSession) {
        this.session = session
        operations += "write(${session.refreshToken})"
    }

    override fun clear() {
        session = null
        pending = null
        operations += "clear"
    }

    override fun pendingRefreshToken(): String? = pending

    override fun markRefreshPending(refreshToken: String) {
        pending = refreshToken
        markedTokens += refreshToken
        operations += "markPending($refreshToken)"
    }

    override fun clearRefreshPending() {
        pending = null
        operations += "clearPending"
    }

    override fun pendingRevokeToken(): String? = pendingRevoke

    override fun markRevokePending(refreshToken: String) {
        pendingRevoke = refreshToken
        operations += "markRevoke($refreshToken)"
    }

    override fun clearRevokePending() {
        pendingRevoke = null
        operations += "clearRevoke"
    }

    /** Simulates a process restart: durable state survives, in-memory state does not. */
    fun snapshotForRestart(): FakeTokenStore {
        val restarted = FakeTokenStore(session)
        restarted.pending = pending
        restarted.pendingRevoke = pendingRevoke
        return restarted
    }
}
