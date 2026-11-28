// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-ring proof that an Answer intent came from this app's own notification.
 *
 * ⛔ EVERY REFUSAL BELOW IS AN INTENT ANOTHER APP COULD SEND. The activity is exported, so the only
 * thing an outside sender cannot supply is a value it never saw; each case pins one way of trying.
 */
class AnswerTokensTest {

    private val tokens = AnswerTokens()

    @Test
    fun `the minted token answers its own call, once`() {
        val token = tokens.mint("CA1")

        assertTrue(tokens.redeem("CA1", token))
        assertFalse("a replay of an answered intent is refused", tokens.redeem("CA1", token))
    }

    @Test
    fun `nothing redeems before a ring was posted`() {
        assertFalse(tokens.redeem("CA1", "anything"))
    }

    @Test
    fun `a missing or guessed token is refused, and leaves the real button armed`() {
        val token = tokens.mint("CA1")

        assertFalse(tokens.redeem("CA1", null))
        assertFalse(tokens.redeem("CA1", ""))
        assertFalse(tokens.redeem("CA1", token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'))
        // ⛔ A FORGED ATTEMPT MUST NOT DISARM THE REAL ONE.
        assertTrue(tokens.redeem("CA1", token))
    }

    @Test
    fun `the token is bound to the call it was minted for`() {
        val token = tokens.mint("CA1")

        assertFalse(tokens.redeem("CA2", token))
        assertFalse(tokens.redeem(null, token))
        assertTrue(tokens.redeem("CA1", token))
    }

    @Test
    fun `a new ring retires the previous call's token`() {
        val first = tokens.mint("CA1")
        val second = tokens.mint("CA2")

        assertNotEquals(first, second)
        assertFalse("a stale Answer cannot reach the call that rang next", tokens.redeem("CA1", first))
        assertFalse(tokens.redeem("CA2", first))
        assertTrue(tokens.redeem("CA2", second))
    }

    @Test
    fun `tokens are 256 bits, url-safe and unpadded`() {
        val token = tokens.mint("CA1")

        // 32 bytes in unpadded base64 is 43 characters.
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }
}
