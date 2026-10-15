package com.distronode.districtai.handoff

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a `districtai://handoff` callback meets the hand-off waiting for it.
 *
 * ⛔ EVERY CALLBACK HERE IS ONE ANOTHER APP COULD FIRE. The activity is exported, so these tests send
 * what a hostile app would: a state it guessed, no state, a nonce of the wrong shape, a callback
 * with nothing pending, and the real callback twice. Only one of them may ever hand a nonce over,
 * none of them may end the wait for the real one, and none of the values may reach the log.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandOffCallbacksTest {

    private val recorded = mutableListOf<HandOffEvent>()
    private val callbacks = HandOffCallbacks { recorded += it }

    @Test
    fun `the pending state's callback with a well-formed nonce hands the nonce over`() = runTest {
        val pending = checkNotNull(callbacks.begin(STATE))

        assertEquals(HandOffEvent.CALLBACK_ACCEPTED, callbacks.deliver(STATE, NONCE))
        assertEquals(NONCE, pending.awaitNonce(TIMEOUT))
        assertEquals(listOf(HandOffEvent.CALLBACK_ACCEPTED), recorded)
    }

    @Test
    fun `a callback with nothing pending is dropped`() {
        // ⚠️ A new process after the old one died with the browser open, a stale intent, or an
        // injection: there is nobody to give the nonce to.
        assertEquals(HandOffEvent.CALLBACK_NOTHING_PENDING, callbacks.deliver(STATE, NONCE))
    }

    @Test
    fun `a stranger's state, or none, is dropped and the wait goes on`() = runTest {
        val pending = checkNotNull(callbacks.begin(STATE))

        assertEquals(HandOffEvent.CALLBACK_STATE_MISMATCH, callbacks.deliver("guessed", NONCE))
        assertEquals(HandOffEvent.CALLBACK_STATE_MISMATCH, callbacks.deliver(null, NONCE))
        // ⛔ AND THE REAL ONE STILL GETS THROUGH. An injected callback that ENDED the wait would let
        // any app on the device push every hand-off onto the unbound path.
        assertEquals(HandOffEvent.CALLBACK_ACCEPTED, callbacks.deliver(STATE, NONCE))
        assertEquals(NONCE, pending.awaitNonce(TIMEOUT))
    }

    @Test
    fun `a malformed or missing nonce on the right state is dropped and the wait goes on`() = runTest {
        val pending = checkNotNull(callbacks.begin(STATE))

        assertEquals(HandOffEvent.CALLBACK_MALFORMED_NONCE, callbacks.deliver(STATE, "short"))
        assertEquals(HandOffEvent.CALLBACK_MALFORMED_NONCE, callbacks.deliver(STATE, null))
        assertEquals(HandOffEvent.CALLBACK_ACCEPTED, callbacks.deliver(STATE, NONCE))
        assertEquals(NONCE, pending.awaitNonce(TIMEOUT))
    }

    @Test
    fun `a second callback for a hand-off that already has one is dropped, and cannot swap it`() =
        runTest {
            val pending = checkNotNull(callbacks.begin(STATE))
            callbacks.deliver(STATE, NONCE)

            assertEquals(HandOffEvent.CALLBACK_ALREADY_SETTLED, callbacks.deliver(STATE, OTHER_NONCE))
            assertEquals("the first nonce stands", NONCE, pending.awaitNonce(TIMEOUT))
        }

    @Test
    fun `no callback within the wait answers null, and a late one is dropped`() = runTest {
        val pending = checkNotNull(callbacks.begin(STATE))
        var nonce: String? = "unset"
        val waiting = launch { nonce = pending.awaitNonce(TIMEOUT) }

        advanceTimeBy(TIMEOUT + 1)
        runCurrent()
        waiting.join()

        assertNull(nonce)
        // ⛔ STILL PENDING (the hand-off goes on to mint unbound), BUT NO LONGER ACCEPTING: a nonce
        // racing the unbound mint could only confuse it.
        assertEquals(HandOffEvent.CALLBACK_ALREADY_SETTLED, callbacks.deliver(STATE, NONCE))
    }

    @Test
    fun `a callback that arrives during the wait ends it at once`() = runTest {
        val pending = checkNotNull(callbacks.begin(STATE))
        var nonce: String? = null
        val waiting = launch { nonce = pending.awaitNonce(TIMEOUT) }
        runCurrent()

        advanceTimeBy(TIMEOUT / 2)
        callbacks.deliver(STATE, NONCE)
        runCurrent()
        waiting.join()

        assertEquals(NONCE, nonce)
        assertEquals(TIMEOUT / 2, testScheduler.currentTime)
    }

    @Test
    fun `one hand-off at a time, and finishing frees the slot`() {
        // ⛔ A SECOND LEG 1 REPLACES THE BROWSER'S NONCE COOKIE, so a second hand-off in flight
        // would strand the first one's code. The second is refused rather than the first replaced.
        val first = checkNotNull(callbacks.begin(STATE))

        assertNull(callbacks.begin("another-state-0123456789"))

        callbacks.finish(first)
        assertNotNull(callbacks.begin("another-state-0123456789"))
    }

    @Test
    fun `finishing a hand-off that is no longer the pending one leaves the pending one alone`() {
        val first = checkNotNull(callbacks.begin(STATE))
        callbacks.finish(first)
        val second = checkNotNull(callbacks.begin("another-state-0123456789"))

        // ⚠️ A stale finish (a second call from a `finally`) must not free a slot it does not hold.
        callbacks.finish(first)

        assertNull(callbacks.begin("third-state-0123456789ab"))
        assertEquals("another-state-0123456789", second.state)
    }

    @Test
    fun `after finishing, the old state's callback has nothing to meet`() {
        val pending = checkNotNull(callbacks.begin(STATE))
        callbacks.finish(pending)

        assertEquals(HandOffEvent.CALLBACK_NOTHING_PENDING, callbacks.deliver(STATE, NONCE))
    }

    @Test
    fun `the log records outcomes and never a state or a nonce`() {
        checkNotNull(callbacks.begin(STATE))
        callbacks.deliver("guessed", NONCE)
        callbacks.deliver(STATE, "short")
        callbacks.deliver(STATE, NONCE)
        callbacks.deliver(STATE, OTHER_NONCE)

        assertEquals(
            listOf(
                HandOffEvent.CALLBACK_STATE_MISMATCH,
                HandOffEvent.CALLBACK_MALFORMED_NONCE,
                HandOffEvent.CALLBACK_ACCEPTED,
                HandOffEvent.CALLBACK_ALREADY_SETTLED,
            ),
            recorded,
        )
        val written = recorded.joinToString { it.name }
        for (secret in listOf(STATE, NONCE, OTHER_NONCE)) {
            assertEquals(written, false, written.contains(secret))
        }
    }

    private companion object {
        const val STATE = "pending-state-0123456789"
        const val NONCE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJ0123456"
        const val OTHER_NONCE = "ZYXWVUTSRQPONMLKJIHGFEDCBAzyxwvutsrqpon_-98"
        const val TIMEOUT = 10_000L
    }
}
