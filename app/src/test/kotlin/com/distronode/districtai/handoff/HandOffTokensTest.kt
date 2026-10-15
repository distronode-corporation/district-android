package com.distronode.districtai.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-off's `state` generator and its nonce check.
 *
 * ⛔ THE STATE MUST BE ONE THE ROUTE ACCEPTS AND NOBODY CAN GUESS. The route answers a 400 page (and
 * never calls back) for anything outside 16 to 256 characters of `[A-Za-z0-9._~-]`, which would turn
 * every hand-off into a ten-second wait; and a state that repeats is a state another app can echo.
 */
class HandOffTokensTest {

    @Test
    fun `a state is 43 characters of the route's alphabet, unpadded`() {
        repeat(SAMPLES) {
            val state = HandOffTokens.newState()

            assertEquals(state, 43, state.length)
            assertTrue(state, ROUTE_STATE_SHAPE.matches(state))
            assertFalse("base64url padding is not in the alphabet", state.contains('='))
        }
    }

    @Test
    fun `every state is fresh`() {
        val states = List(SAMPLES) { HandOffTokens.newState() }

        assertEquals(SAMPLES, states.toSet().size)
    }

    @Test
    fun `a nonce is exactly 43 base64url characters`() {
        assertTrue(HandOffTokens.isWellFormedNonce(NONCE))
        assertTrue(HandOffTokens.isWellFormedNonce("-".repeat(43)))
        assertTrue(HandOffTokens.isWellFormedNonce("_".repeat(43)))
    }

    @Test
    fun `anything else is not a nonce`() {
        val malformed = listOf(
            "",
            NONCE.dropLast(1),
            NONCE + "A",
            // Standard base64's two extra characters and its padding.
            NONCE.dropLast(1) + "+",
            NONCE.dropLast(1) + "/",
            NONCE.dropLast(1) + "=",
            // What a percent-decoded or space-carrying value looks like.
            NONCE.dropLast(1) + " ",
            NONCE.dropLast(1) + "%",
            // ⚠️ A trailing newline: `$` in some regex flavours matches before one.
            NONCE + "\n",
            "x".repeat(4096),
        )
        for (value in malformed) {
            assertFalse("accepted: <$value>", HandOffTokens.isWellFormedNonce(value))
        }
    }

    private companion object {
        const val SAMPLES = 64
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"

        /** The route's own rule for `state`, restated so the generator is checked against it. */
        val ROUTE_STATE_SHAPE = Regex("^[A-Za-z0-9._~-]{16,256}$")
    }
}
