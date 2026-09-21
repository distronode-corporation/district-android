package com.distronode.districtai.core.auth

import android.net.Uri

/**
 * Drives the two-leg native login.
 *
 * ⛔ LEG ONE HAPPENS IN THE SYSTEM BROWSER, NOT A WEBVIEW, AND THAT IS THE POINT. Opening
 * `/auth/native` in a Chrome Custom Tab reuses the server's EXISTING login surface — Google
 * SSO, Microsoft SSO and the argon2id password route — instead of reimplementing three flows
 * and their rate limits natively. A WebView would additionally be rejected by Google's OAuth
 * for embedded user-agents, and would hand this app the user's Google password.
 *
 * ⛔ LEG TWO IS A DIRECT POST THAT NO OTHER PROCESS OBSERVED. The browser can only hand back a
 * URL, and URLs are observable (logs, history, another app claiming the scheme). So it carries
 * only a single-use code, which is worthless without the verifier this class kept in memory.
 *
 * ⚠️ THE VERIFIER IS DELIBERATELY NOT PERSISTED. A login attempt interrupted by process death
 * is simply restarted — one extra tap — rather than writing a live exchange secret to disk. Do
 * not "fix" this by persisting [pending].
 */
class NativeLoginFlow(
    private val baseUrl: String,
    private val redirectUri: String,
    private val api: NativeAuthApi,
    private val coordinator: TokenRefreshCoordinator,
    private val deviceIdProvider: () -> String,
    private val deviceNameProvider: () -> String? = { null },
) : PkceLoginFlow {

    /**
     * The in-flight attempt. In memory only, and single-slot: starting a new login abandons any
     * previous one, which is correct — the user tapped sign-in again.
     */
    @Volatile
    private var pending: PkceChallenge? = null

    /**
     * Begin a login. Returns the URL to open in a Custom Tab.
     *
     * ⚠️ The returned URL must be opened in a SYSTEM browser. Opening it in an in-app WebView
     * defeats the whole design (see the class doc).
     */
    override fun authorizeUrl(): String {
        val challenge = Pkce.newChallenge()
        pending = challenge
        return Uri.parse("$baseUrl/auth/native")
            .buildUpon()
            // The server validates shape: 43 chars of base64url.
            .appendQueryParameter("code_challenge", challenge.challenge)
            .appendQueryParameter("state", challenge.state)
            // ⛔ Must match NATIVE_REDIRECT_ALLOWLIST byte-for-byte. It is a literal-equality
            // check with an explicit note never to relax it, because an unchecked redirect_uri
            // would hand an authenticated user's code to a host an attacker controls.
            .appendQueryParameter("redirect_uri", redirectUri)
            .build()
            .toString()
    }

    /**
     * Handle the browser's callback and complete the exchange.
     *
     * ⛔ STATE IS CHECKED BEFORE THE CODE IS SPENT. Any app on the device can register the
     * `districtai://` scheme, so a callback can arrive that this app never initiated — an
     * injected code, or an old callback replayed out of browser history. Exchanging it would
     * bind THIS app's session to an attacker-chosen account. A state mismatch is therefore
     * refused outright, and the pending attempt is discarded so a retry starts clean.
     */
    override suspend fun completeLogin(callback: Uri): LoginOutcome {
        val attempt = pending ?: return LoginOutcome.NoAttemptInProgress

        // Cleared up front: this attempt is now spent whatever happens, and leaving it set
        // would let a second callback reuse the same verifier.
        pending = null

        val returnedState = callback.getQueryParameter("state")
        if (returnedState == null || returnedState != attempt.state) {
            return LoginOutcome.StateMismatch
        }

        // The authorize page surfaces its own failures as an `error` parameter rather than a
        // code — for example the user dismissing the browser, or a login that never completed.
        callback.getQueryParameter("error")?.let { return LoginOutcome.Denied(it) }

        val code = callback.getQueryParameter("code")
            ?: return LoginOutcome.Denied("missing_code")

        val request = CodeExchangeRequest(
            code = code,
            codeVerifier = attempt.verifier,
            redirectUri = redirectUri,
            deviceId = deviceIdProvider(),
            deviceName = deviceNameProvider(),
        )

        return when (val result = api.exchangeCode(request)) {
            is CodeExchangeResult.Success -> {
                // Persists the refresh token before the access token becomes usable — the same
                // ordering rule the refresh path obeys. See TokenRefreshCoordinator.
                coordinator.adopt(result.tokens, request.deviceId)
                LoginOutcome.Success
            }
            CodeExchangeResult.Rejected -> LoginOutcome.Rejected
            CodeExchangeResult.RateLimited -> LoginOutcome.RateLimited
            CodeExchangeResult.TransportFailure -> LoginOutcome.Unreachable
        }
    }

    /** Abandon an in-flight attempt, e.g. the user backed out of the browser. */
    override fun cancel() {
        pending = null
    }
}

sealed interface LoginOutcome {
    data object Success : LoginOutcome

    /**
     * A callback arrived with no attempt in flight. Benign on its own — a stale deep link from
     * history — but deliberately distinguished from [StateMismatch], which is not benign.
     */
    data object NoAttemptInProgress : LoginOutcome

    /**
     * ⛔ The callback's `state` did not match the one this app generated. Treat as hostile: a
     * code was injected, or a callback was replayed. Never exchange it.
     */
    data object StateMismatch : LoginOutcome

    /** The authorize page returned an error, or no code. Surfaced with the server's reason. */
    data class Denied(val reason: String) : LoginOutcome

    /** Expired (codes live 120s), replayed, or PKCE mismatch. Start over. */
    data object Rejected : LoginOutcome

    data object RateLimited : LoginOutcome

    data object Unreachable : LoginOutcome
}
