package com.distronode.districtai.core.auth

/**
 * The credential pair returned by `POST /api/auth/native/{token,refresh}`.
 *
 * ⚠️ BOTH EXPIRY FIELDS ARE IN MILLISECONDS, matching the server, which returns
 * `accessTokenExpiresAt` / `refreshTokenExpiresAt` in ms even though the JWT's own
 * `exp`/`iat` claims inside the access token are in SECONDS. The server does this
 * deliberately and returns the value rather than letting clients recompute it, precisely
 * because deriving it twice is how the two units get mixed up. Do not "normalise" these
 * to seconds.
 */
data class NativeTokens(
    /**
     * Compact JWS, 10-minute TTL. Held in memory only — see [TokenStore].
     */
    val accessToken: String,
    /** Epoch MILLISECONDS. */
    val accessTokenExpiresAt: Long,
    /**
     * Opaque, 32 bytes of entropy, base64url. Sliding 60-day TTL, re-set on every
     * rotation.
     *
     * ⛔ SINGLE USE. The server stamps `rotatedAt` on this value the moment it is
     * exchanged, and presenting it again is treated as a replay: the ENTIRE token family
     * is revoked and the user is signed out on every device in that chain. This is the
     * one invariant the whole of [TokenRefreshCoordinator] exists to protect.
     */
    val refreshToken: String,
    /** Epoch MILLISECONDS. */
    val refreshTokenExpiresAt: Long,
)
