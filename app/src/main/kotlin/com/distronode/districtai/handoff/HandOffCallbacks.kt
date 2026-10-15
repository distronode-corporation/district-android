package com.distronode.districtai.handoff

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a `districtai://handoff` callback meets the hand-off that is waiting for it.
 *
 * ⛔ PROCESS-SCOPED, FOR THE REASON `LoginController` IS. The callback arrives as a VIEW intent on
 * `MainActivity` (routed to the live instance by `singleTask`), while the hand-off waiting for it
 * runs in the scheduling screen's ViewModel. Neither owns the other, and a rotation can replace the
 * activity between leg 1 and its callback, so the meeting point has to outlive both.
 *
 * ⛔ ONE HAND-OFF AT A TIME, AND [begin] REFUSES A SECOND RATHER THAN REPLACING THE FIRST. The
 * browser holds one nonce cookie per host and a second leg 1 overwrites it, so two hand-offs in
 * flight would leave the first one's code bound to a cookie that no longer exists, and its leg 3
 * would end on the server's 410 page. Refusing keeps the first intact; the second press is the
 * one that does nothing, which is also what the screen's own single-flight already does.
 *
 * ⛔ A CALLBACK IS HOSTILE INPUT, AND EVERY WAY IT CAN BE WRONG IS DROPPED WITHOUT ENDING THE WAIT.
 * The activity is exported, so any app can fire `districtai://handoff?state=…&nonce=…`. One whose
 * state is not the pending one, or whose nonce is not 43 base64url characters, is ignored and the
 * real callback can still arrive; letting an injected callback END the attempt would let any app on
 * the device push every hand-off onto the unbound path. Nothing pending (a new process after the
 * old one died with the browser open, or a stale intent) and a second callback for a hand-off that
 * already has one are dropped too: a nonce is only ever accepted once.
 *
 * ⚠️ PROCESS DEATH LOSES THE PENDING HAND-OFF, AND THAT IS THE HONEST OUTCOME. The ViewModel that
 * was waiting died with it, so there is nobody to give a nonce to; the callback is dropped as
 * NOTHING_PENDING and the user taps again, which starts from a fresh state.
 */
class HandOffCallbacks(private val log: HandOffLog) {

    private val lock = Any()

    /** ⚠️ Guarded by [lock]: a callback is delivered on the main thread, a hand-off may finish anywhere. */
    private var pending: PendingHandOff? = null

    /**
     * Start waiting for the callback that carries [state], or answer null when a hand-off is
     * already pending. See the class doc for why a second one is refused.
     */
    fun begin(state: String): PendingHandOff? = synchronized(lock) {
        if (pending != null) return null
        PendingHandOff(state).also { pending = it }
    }

    /**
     * Stop waiting, whatever happened. ⛔ Called from a `finally`, so a cancelled hand-off (the
     * screen closed mid-wait) cannot leave the slot taken and refuse every later tap.
     */
    fun finish(handOff: PendingHandOff) {
        synchronized(lock) {
            if (pending === handOff) pending = null
        }
        handOff.settle()
    }

    /**
     * Offer a callback's two query values, answering what was done with them.
     *
     * ⛔ ONLY THE OUTCOME IS LOGGED, never either value; see [HandOffEvent].
     */
    fun deliver(state: String?, nonce: String?): HandOffEvent {
        val outcome = synchronized(lock) { classify(state, nonce) }
        log.record(outcome)
        return outcome
    }

    private fun classify(state: String?, nonce: String?): HandOffEvent {
        val handOff = pending ?: return HandOffEvent.CALLBACK_NOTHING_PENDING
        if (state != handOff.state) return HandOffEvent.CALLBACK_STATE_MISMATCH
        // ⚠️ The state is checked FIRST, so a stranger's callback is reported as a mismatch whatever
        // it carries, and the nonce's shape is only judged for a callback that could be ours.
        val wellFormed = nonce?.takeIf(HandOffTokens::isWellFormedNonce)
            ?: return HandOffEvent.CALLBACK_MALFORMED_NONCE
        return if (handOff.offer(wellFormed)) {
            HandOffEvent.CALLBACK_ACCEPTED
        } else {
            HandOffEvent.CALLBACK_ALREADY_SETTLED
        }
    }
}

/**
 * One hand-off waiting for its callback.
 *
 * ⚠️ NOT A DATA CLASS, so nothing can print the nonce it may be holding by printing this.
 */
class PendingHandOff(
    /** The `state` sent on leg 1, which the callback must echo. Not secret from the browser. */
    val state: String,
) {
    private val nonce = CompletableDeferred<String>()

    /** False once a nonce was taken or the wait is over: a nonce is accepted at most once. */
    fun offer(value: String): Boolean = nonce.complete(value)

    /**
     * The nonce, or null when none arrived within [timeoutMillis].
     *
     * ⛔ THE WAIT IS SETTLED ON THE WAY OUT EITHER WAY, so a callback that turns up after the
     * timeout is answered ALREADY_SETTLED instead of completing a value nobody will read, and the
     * hand-off that gave up on it goes ahead unbound without a late nonce racing it.
     */
    suspend fun awaitNonce(timeoutMillis: Long): String? = try {
        withTimeoutOrNull(timeoutMillis) { nonce.await() }
    } finally {
        settle()
    }

    fun settle() {
        nonce.cancel()
    }
}
