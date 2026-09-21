package com.distronode.districtai.core.auth

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THE CENTRAL ASSERTION HERE IS THAT A MISMATCHED `state` IS NEVER EXCHANGED.
 *
 * Any app on an Android device can register the `districtai://` scheme, so this app can receive
 * a callback it never initiated: a code injected by a malicious app, or an old callback replayed
 * out of browser history. Exchanging such a code would bind this app's session to an
 * attacker-chosen account — the classic authorization-code injection. The check must therefore
 * happen BEFORE the network call, and the test proves it by asserting the server was never
 * contacted at all.
 *
 * ⚠️ Robolectric is required because NativeLoginFlow builds and parses `android.net.Uri`, which
 * is a framework class with no JVM implementation.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class NativeLoginFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var store: FakeTokenStore
    private lateinit var coordinator: TokenRefreshCoordinator
    private lateinit var flow: NativeLoginFlow

    private val redirectUri = "districtai://auth"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val baseUrl = server.url("/").toString().trimEnd('/')
        val api = NativeAuthApi(baseUrl, OkHttpClient())
        store = FakeTokenStore()
        coordinator = TokenRefreshCoordinator(store, api, nowMillis = { NOW })
        flow = NativeLoginFlow(
            baseUrl = baseUrl,
            redirectUri = redirectUri,
            api = api,
            coordinator = coordinator,
            deviceIdProvider = { "device-abcdefgh" },
            deviceNameProvider = { "Test Device" },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun enqueueTokens() {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """
                {"tokenType":"Bearer","accessToken":"access-1","accessTokenExpiresAt":${NOW + 600_000},
                 "refreshToken":"refresh-1","refreshTokenExpiresAt":${NOW + 5_184_000_000}}
                """.trimIndent(),
            ).build(),
        )
    }

    private fun stateFrom(url: String): String =
        Uri.parse(url).getQueryParameter("state") ?: error("authorize URL carried no state")

    // ── The authorize URL ────────────────────────────────────────────────────

    @Test
    fun `authorize url carries the challenge, state and exact redirect`() {
        val uri = Uri.parse(flow.authorizeUrl())

        assertEquals("/auth/native", uri.path)
        // 43 chars of base64url — the server's isValidCodeChallenge rejects anything else, and
        // padding left on the base64 would produce 44 and fail at authorize time.
        val challenge = uri.getQueryParameter("code_challenge")
        assertNotNull(challenge)
        assertTrue(
            "challenge '$challenge' must match the server's ^[A-Za-z0-9-_]{43}$ shape",
            Regex("^[A-Za-z0-9\\-_]{43}$").matches(challenge!!),
        )
        assertNotNull(uri.getQueryParameter("state"))
        // ⛔ Byte-for-byte: NATIVE_REDIRECT_ALLOWLIST is a literal-equality check.
        assertEquals(redirectUri, uri.getQueryParameter("redirect_uri"))
        // The VERIFIER must never appear in a URL — only its digest.
        assertTrue(
            "the code_verifier must never leave the device",
            !flow.authorizeUrl().contains("code_verifier"),
        )
    }

    @Test
    fun `each login attempt uses a fresh challenge`() {
        val first = Uri.parse(flow.authorizeUrl())
        val second = Uri.parse(flow.authorizeUrl())
        assertTrue(
            "reusing a challenge across attempts would defeat PKCE",
            first.getQueryParameter("code_challenge") != second.getQueryParameter("code_challenge"),
        )
    }

    // ── The callback ─────────────────────────────────────────────────────────

    @Test
    fun `a valid callback exchanges the code and adopts the session`() = runTest {
        val url = flow.authorizeUrl()
        enqueueTokens()

        val outcome = flow.completeLogin(
            Uri.parse("$redirectUri?code=real-code&state=${stateFrom(url)}"),
        )

        assertEquals(LoginOutcome.Success, outcome)
        // The refresh token must be on disk, and the marker clear, before anything can use the
        // access token — the same ordering rule the refresh path obeys.
        assertEquals(listOf("write(refresh-1)", "clearPending"), store.operations)
        assertEquals("refresh-1", store.read()?.refreshToken)
        assertEquals(AccessToken.Available("access-1"), coordinator.accessToken())
    }

    @Test
    fun `a mismatched state is refused WITHOUT contacting the server`() = runTest {
        flow.authorizeUrl()

        val outcome = flow.completeLogin(
            Uri.parse("$redirectUri?code=injected-code&state=attacker-chosen-state"),
        )

        assertEquals(LoginOutcome.StateMismatch, outcome)
        assertEquals(
            "⛔ the code must never be exchanged — doing so binds this app's session to an " +
                "account the attacker chose",
            0,
            server.requestCount,
        )
        assertNull("no session may be adopted", store.read())
    }

    @Test
    fun `a callback with no state at all is refused`() = runTest {
        flow.authorizeUrl()
        val outcome = flow.completeLogin(Uri.parse("$redirectUri?code=injected-code"))
        assertEquals(LoginOutcome.StateMismatch, outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a callback with no attempt in flight is refused`() = runTest {
        // A stale deep link out of browser history, arriving after a restart.
        val outcome = flow.completeLogin(Uri.parse("$redirectUri?code=c&state=s"))
        assertEquals(LoginOutcome.NoAttemptInProgress, outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a second callback cannot reuse the first attempt's verifier`() = runTest {
        val url = flow.authorizeUrl()
        val state = stateFrom(url)
        enqueueTokens()

        assertEquals(
            LoginOutcome.Success,
            flow.completeLogin(Uri.parse("$redirectUri?code=code-1&state=$state")),
        )

        // Replaying the same callback must not start a second exchange: the attempt was
        // consumed, so there is no verifier left to spend.
        val replay = flow.completeLogin(Uri.parse("$redirectUri?code=code-1&state=$state"))
        assertEquals(LoginOutcome.NoAttemptInProgress, replay)
        assertEquals("only the first callback should have hit the server", 1, server.requestCount)
    }

    @Test
    fun `an error parameter is surfaced rather than exchanged`() = runTest {
        val state = stateFrom(flow.authorizeUrl())
        val outcome = flow.completeLogin(Uri.parse("$redirectUri?error=access_denied&state=$state"))
        assertEquals(LoginOutcome.Denied("access_denied"), outcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a rejected exchange reports rejected and stores nothing`() = runTest {
        val state = stateFrom(flow.authorizeUrl())
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())

        val outcome = flow.completeLogin(Uri.parse("$redirectUri?code=expired&state=$state"))

        assertEquals(LoginOutcome.Rejected, outcome)
        assertNull(store.read())
    }

    @Test
    fun `cancel discards the attempt so a later callback cannot complete it`() = runTest {
        val state = stateFrom(flow.authorizeUrl())
        flow.cancel()

        val outcome = flow.completeLogin(Uri.parse("$redirectUri?code=c&state=$state"))

        assertEquals(LoginOutcome.NoAttemptInProgress, outcome)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}

/** ⚠️ Tracks Robolectric's newest supported level, not the app's targetSdk. */
private const val ROBOLECTRIC_SDK = 35
