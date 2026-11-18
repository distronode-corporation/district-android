package com.distronode.districtai.push

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

/**
 * The proof that an Answer intent came from this app's own ringing notification.
 *
 * ⛔ `MainActivity` IS EXPORTED, SO ITS EXTRAS ARE NOT EVIDENCE OF ANYTHING. The launcher, the login
 * callback and App Links all need it reachable from outside, which means any installed app can start
 * it with any extras it likes. A call id and a boolean are therefore not a request to answer: a call
 * id is no secret (any non-blank value used to do), and the flag is one `putExtra` away. What an
 * outside app cannot produce is a value it has never seen, so the Answer action carries one, minted
 * here when the notification is built and handed out only inside an immutable `PendingIntent`.
 *
 * ⛔ ONE TOKEN, FOR ONE CALL, REDEEMED ONCE. Minting for a new ring replaces the previous call's
 * token, so a stale Answer cannot reach the call that rang next; redeeming clears it, so a replay of
 * an intent that already answered is refused. A wrong call id or a wrong token leaves the live token
 * in place: a forged attempt must not be able to disarm the real button.
 *
 * ⚠️ PROCESS-SCOPED, LIKE `IncomingCallController`, AND THAT IS ENOUGH. A process that died after
 * posting the ring has lost this token, and it has also lost the ringing state an answer needs, so
 * the intent degrades to "show the call", which is the safe direction.
 */
internal class AnswerTokens(private val random: SecureRandom = SecureRandom()) {

    private class Held(val callId: String, val token: String)

    private val held = AtomicReference<Held?>(null)

    /** A fresh token for [callId], replacing whatever was held for an earlier ring. */
    fun mint(callId: String): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        held.set(Held(callId, token))
        return token
    }

    /**
     * Whether [token] is the one minted for [callId]; true at most once per mint.
     *
     * ⚠️ The comparison is constant-time, so the check does not leak how much of a guess was right.
     */
    fun redeem(callId: String?, token: String?): Boolean {
        val current = held.get()
        val matches = current != null &&
            callId == current.callId &&
            token != null &&
            MessageDigest.isEqual(token.toByteArray(), current.token.toByteArray())
        return matches && held.compareAndSet(current, null)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
