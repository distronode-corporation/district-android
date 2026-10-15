package com.distronode.districtai.handoff

import java.security.SecureRandom
import java.util.Base64

/**
 * The two values the nonce-bound dashboard hand-off passes through the browser: the `state` this
 * app makes up, and the `nonce` the server hands back.
 *
 * ⛔ THE STATE IS WHAT TIES A CALLBACK TO A TAP, AND IT IS FRESH PER HAND-OFF. `districtai://handoff`
 * is an exported VIEW filter, so any app on the device can fire one at the activity with a nonce of
 * its choosing. A callback is accepted only when it echoes the state of the hand-off pending right
 * now (see [HandOffCallbacks]), and a state nobody else has seen is what makes that a check rather
 * than a formality. A reused or predictable state would let an injected nonce through, and the code
 * minted for it would then be bound to a cookie the attacker's browser holds.
 *
 * ⚠️ 32 BYTES OF [SecureRandom] AS BASE64URL WITHOUT PADDING: 43 characters, all from the route's
 * `[A-Za-z0-9._~-]` alphabet (base64url uses `-` and `_` and nothing else outside the
 * alphanumerics), inside its 16 to 256 bounds, and unreserved in a URL so it travels unencoded.
 * The same construction as the PKCE `state` in core-auth.
 */
internal object HandOffTokens {

    private val random = SecureRandom()

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    /** A fresh `state` for one hand-off, or for one probe of leg 1. */
    fun newState(): String {
        val bytes = ByteArray(STATE_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    /**
     * Whether [nonce] has the one shape the server mints: exactly 43 base64url characters.
     *
     * ⛔ CHECKED BEFORE IT IS SENT, NOT LEFT TO THE SERVER'S 400. A callback is hostile input, and a
     * value that is not a nonce (empty, padded, percent-decoded into something else, or a few
     * kilobytes of junk) is dropped at the door rather than carried into a request body. The server
     * checks the same shape and answers `invalid_nonce`, which this makes unreachable from a
     * well-formed callback.
     */
    fun isWellFormedNonce(nonce: String): Boolean = NONCE_SHAPE.matches(nonce)

    /** 32 bytes is 256 bits, and encodes to 43 base64url characters with no padding. */
    private const val STATE_BYTES = 32

    /** ⛔ Exactly 43, from the base64url alphabet only: `+`, `/` and `=` are not in it. */
    private val NONCE_SHAPE = Regex("^[A-Za-z0-9_-]{43}$")
}
