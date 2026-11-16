package com.distronode.districtai.ui.account

import com.distronode.districtai.core.auth.Pkce
import com.distronode.districtai.core.auth.PkceChallenge
import com.distronode.districtai.core.data.NativeReauthRepository
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.network.ApiResult
import java.net.URLEncoder
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred

/**
 * The providers a fresh re-authentication can go through, by their wire names.
 *
 * ⛔ THE SERVICE'S LINKABLE PROVIDERS, NOT A GUESS ABOUT THIS ACCOUNT. The app does not know which
 * providers an account has linked, so it offers all three and the service decides: a provider the
 * account does not use is refused on the page itself. ⚠️ [displayName] is a proper noun, the same in
 * both languages.
 */
enum class ReauthProvider(val wire: String, val displayName: String) {
    GOOGLE("google", "Google"),
    MICROSOFT("azure-ad", "Microsoft"),
    APPLE("apple", "Apple"),
}

/** How one fresh sign-in at a provider ended. */
sealed interface SsoReauthOutcome {
    /** The service holds a proof for this installation, for this many seconds. */
    data class Proved(val expiresInSeconds: Int) : SsoReauthOutcome

    /** The person closed the browser. ⚠️ Benign: nothing is shown for it. */
    data object Cancelled : SsoReauthOutcome

    /** ⛔ The callback's `state` was not the one this attempt made. Never exchanged. */
    data object StateMismatch : SsoReauthOutcome

    /** The page came back without a code. */
    data object NoCode : SsoReauthOutcome

    /** No browser could open the page. */
    data object CouldNotOpen : SsoReauthOutcome

    /** The service refused the code, or could not be reached. */
    data class Refused(val failure: ApiResult.Failure) : SsoReauthOutcome
}

/**
 * The one re-authentication in flight, and the callback and lifecycle events that end it.
 *
 * ⛔ THE CALLBACK ARRIVES ON THE SIGN-IN REDIRECT, `districtai://auth`, THE SERVICE'S ONLY NATIVE
 * REDIRECT. So `MainActivity` offers every such callback here first: while an attempt is pending it
 * is this attempt's (its `state` decides whether it is honoured), and otherwise it is a sign-in's.
 * A signed-in app has no sign-in pending, so the two cannot both be waiting.
 *
 * ⚠️ A RETURN TO THE APP WITHOUT A CALLBACK IS A CANCELLATION, the same rule the sign-in uses
 * (`LoginAttemptTracker`): the activity pauses when the browser opens, and `onNewIntent` delivers a
 * callback before the `onResume` that follows it, so a resume after a pause with nothing delivered
 * means the browser was closed.
 */
class ReauthCallbacks {

    private val lock = Any()
    private var pending: Pending? = null

    /** One attempt's PKCE values and the answer it is waiting for. */
    class Pending internal constructor(val challenge: PkceChallenge) {
        internal val callback = CompletableDeferred<Pair<String?, String?>?>()
        internal var paused = false
    }

    /** Starts an attempt. ⚠️ Single slot: a second attempt abandons the first as cancelled. */
    fun begin(challenge: PkceChallenge = Pkce.newChallenge()): Pending = synchronized(lock) {
        pending?.callback?.complete(null)
        Pending(challenge).also { pending = it }
    }

    /** Ends [attempt] if it is still the pending one. */
    fun end(attempt: Pending) {
        synchronized(lock) { if (pending === attempt) pending = null }
        attempt.callback.complete(null)
    }

    /**
     * Offers a callback's `state` and `code`. Answers whether a re-authentication took it, which it
     * does whenever one is pending; otherwise the callback is a sign-in's.
     */
    fun deliver(state: String?, code: String?): Boolean {
        val attempt = synchronized(lock) { pending } ?: return false
        attempt.callback.complete(state to code)
        return true
    }

    /** The host activity paused: the browser is in front. */
    fun onHostPaused() {
        synchronized(lock) { pending }?.paused = true
    }

    /** The host activity is back. After a pause with no callback, the browser was closed. */
    fun onHostResumed() {
        val attempt = synchronized(lock) { pending } ?: return
        if (attempt.paused) attempt.callback.complete(null)
    }
}

/**
 * The SSO leg of a fresh re-authentication: the page in a browser, then its code exchanged.
 *
 * ⛔ THE SAME TWO LEGS AS SIGN-IN, WITH THE SAME GUARDS. Leg one opens `/auth/native/reauth`, which
 * relays to the provider with a forced fresh sign-in and hands back a one-time code; leg two spends
 * that code with this attempt's PKCE verifier. `state` is checked before the code is spent, and the
 * verifier lives in memory only.
 *
 * ⚠️ THE SERVICE BINDS THE CODE FOUR WAYS (this account, this installation by the SHA-256 of its id,
 * this action, and the PKCE verifier), so the app adds no check of its own beyond `state`.
 */
class SsoReauthenticator(
    private val baseUrl: String,
    private val redirectUri: String,
    private val deviceId: () -> String,
    private val repository: NativeReauthRepository,
    private val callbacks: ReauthCallbacks,
) {

    /**
     * Runs one attempt. [open] shows the page in a browser and answers whether one opened.
     */
    suspend fun reauthenticate(
        provider: ReauthProvider,
        action: NativeReauthAction,
        open: (String) -> Boolean,
    ): SsoReauthOutcome {
        val attempt = callbacks.begin()
        val url = sheetUrl(baseUrl, attempt.challenge, provider, sha256Hex(deviceId()), action, redirectUri)
        if (!open(url)) {
            callbacks.end(attempt)
            return SsoReauthOutcome.CouldNotOpen
        }
        // ⛔ SPENT WHATEVER HAPPENS, A CANCELLATION INCLUDED: a second callback must not reuse the
        // verifier, and a pending attempt left behind would take the next sign-in's callback.
        val answer = try {
            attempt.callback.await()
        } finally {
            callbacks.end(attempt)
        }
        val (state, code) = answer ?: return SsoReauthOutcome.Cancelled
        return when {
            state != attempt.challenge.state -> SsoReauthOutcome.StateMismatch
            code.isNullOrEmpty() -> SsoReauthOutcome.NoCode
            else -> when (
                val result = repository.reauthenticate(action, code, attempt.challenge.verifier, redirectUri)
            ) {
                is ApiResult.Success -> SsoReauthOutcome.Proved(result.value)
                is ApiResult.Failure -> SsoReauthOutcome.Refused(result)
            }
        }
    }

    companion object {
        /** ⚠️ A PAGE, NOT AN API ROUTE, like the sign-in's `/auth/native`. */
        const val SHEET_PATH: String = "/auth/native/reauth"

        /**
         * The page's address.
         *
         * ⚠️ `snake_case` QUERY NAMES, the page's own (`code_challenge`, `redirect_uri`); the
         * exchange's body is camelCase. `device` is the installation id's SHA-256 in hex, never the
         * id itself.
         */
        fun sheetUrl(
            baseUrl: String,
            challenge: PkceChallenge,
            provider: ReauthProvider,
            deviceHash: String,
            action: NativeReauthAction,
            redirectUri: String,
        ): String {
            val query = listOf(
                "code_challenge" to challenge.challenge,
                "state" to challenge.state,
                "redirect_uri" to redirectUri,
                "provider" to provider.wire,
                "device" to deviceHash,
                "action" to action.wire,
            ).joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }
            return baseUrl.trimEnd('/') + SHEET_PATH + "?" + query
        }

        /** Lower-case hex SHA-256, as the page checks it (`^[0-9a-f]{64}$`). */
        fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
