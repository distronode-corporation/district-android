package com.distronode.districtai.core.auth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ WHAT THIS TEST IS ACTUALLY FOR: proving the store FAILS CLOSED.
 *
 * Robolectric does not implement `AndroidKeyStore`, so every crypto operation here fails.
 * That is not a limitation to work around — it is the single most valuable environment to
 * assert against, because it reproduces the real conditions this class must survive: a key
 * invalidated by a device-security change, a Keystore that will not load, or a value
 * restored from a backup whose key no longer exists.
 *
 * In all of those the only safe outcome is "there is no session", which routes the user to a
 * clean login. The unacceptable outcomes are a crash on launch (an app that cannot start) or
 * a half-decoded session (an app making requests with a broken credential). Both are what
 * this asserts against.
 *
 * ⚠️ ROUND-TRIP ENCRYPTION IS NOT VERIFIED HERE and cannot be — it needs a real Keystore.
 * That is covered on a device, by signing in and persisting a real token.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class KeystoreTokenStoreTest {

    private fun store() = KeystoreTokenStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `reads null rather than throwing when the keystore is unavailable`() {
        assertNull(
            "An unavailable Keystore must read as 'no session'. Throwing here crashes the " +
                "app on launch, which is strictly worse than a re-login.",
            store().read(),
        )
    }

    @Test
    fun `a failed write leaves nothing readable rather than storing plaintext`() {
        val subject = store()
        // Encryption cannot succeed in this environment, so this write must not persist
        // anything usable — least of all the token in the clear.
        subject.write(
            PersistedSession(
                refreshToken = "super-secret-refresh-token",
                refreshTokenExpiresAt = Long.MAX_VALUE,
                deviceId = "device-1",
            ),
        )
        assertNull(
            "⛔ A write that cannot encrypt must store nothing. Falling back to plaintext " +
                "would put a 60-day credential on disk in the clear.",
            subject.read(),
        )
    }

    @Test
    fun `pending marker reads null rather than throwing`() {
        val subject = store()
        subject.markRefreshPending("some-token")
        assertNull(subject.pendingRefreshToken())
    }

    @Test
    fun `clear is safe on an empty store`() {
        // clear() runs on every terminal auth path, including ones reached before anything
        // was ever written. It must not throw there.
        store().clear()
        store().clearRefreshPending()
        assertNull(store().read())
    }

    // ── The revoke outbox ────────────────────────────────────────────────────

    @Test
    fun `revoke outbox reads null rather than throwing`() {
        // Same fail-closed rule as the refresh marker: an unavailable Keystore means "there is
        // nothing readable here", never a crash on launch. A sign-out whose outbox cannot be
        // read simply does not get retried — strictly better than an app that will not start.
        val subject = store()
        subject.markRevokePending("some-token")
        assertNull(subject.pendingRevokeToken())
    }

    @Test
    fun `a revoke outbox that cannot be encrypted stores nothing rather than plaintext`() {
        // ⛔ The outbox holds a REFRESH TOKEN — the app's longest-lived credential, and one the
        // server is by definition still honouring, since chasing it is the whole point. Falling
        // back to plaintext here would be worse than losing the retry.
        val subject = store()
        subject.markRevokePending("super-secret-refresh-token")

        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("district_native_session", android.content.Context.MODE_PRIVATE)
            .all
            .values
            .map { it.toString() }
        assertFalse(
            "a token that could not be encrypted must not reach disk in the clear",
            raw.any { it.contains("super-secret-refresh-token") },
        )
    }

    @Test
    fun `clearing the revoke outbox is safe when there is nothing in it`() {
        // Runs on every successful drain, including the overwhelmingly common one where the
        // outbox was empty to begin with.
        store().clearRevokePending()
        assertNull(store().pendingRevokeToken())
    }

    @Test
    fun `clear does not throw with a revoke outbox present`() {
        // ⛔ THE ORDER SIGN-OUT ACTUALLY USES: the outbox is written and `clear()` runs
        // microseconds later, so `clear()` has to cope with the slot existing. Under Robolectric
        // the encrypt fails so nothing is stored and the carry-across is a no-op — what this
        // pins is that the path does not throw, which is the part that would crash a sign-out.
        //
        // ⚠️ THE CARRY-ACROSS ITSELF CANNOT BE ASSERTED HERE and this test does not pretend to.
        // It needs a real AndroidKeyStore, which Robolectric does not implement; `FakeTokenStore`
        // models the same rule and `AppContainerTest` drives the ordering through it.
        val subject = store()
        subject.markRevokePending("some-token")
        subject.clear()
        assertNull(subject.read())
    }
}

/**
 * ⚠️ Tracks Robolectric's newest supported level, NOT the app's targetSdk. Robolectric 4.16.1
 * ships no API 36/37 runtime jars.
 */
private const val ROBOLECTRIC_SDK = 35
