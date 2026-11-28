// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.ui.account

import com.distronode.districtai.core.auth.PkceChallenge
import com.distronode.districtai.core.data.NativeReauthRepository
import com.distronode.districtai.core.model.NativeReauthAction
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeAccountSecurityApi
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SSO leg of a fresh re-authentication: the page's address, the callback it waits for, and the
 * guards on that callback.
 */
class SsoReauthTest {

    private val api = FakeAccountSecurityApi()
    private val callbacks = ReauthCallbacks()
    private val opened = mutableListOf<String>()

    private val reauthenticator = SsoReauthenticator(
        baseUrl = "https://app.example.test/",
        redirectUri = "districtai://auth",
        deviceId = { "device-1234" },
        repository = NativeReauthRepository(api),
        callbacks = callbacks,
    )

    private fun query(url: String): Map<String, String> =
        URI(url).rawQuery.split("&").associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            name to URLDecoder.decode(value, "UTF-8")
        }

    @Test
    fun `the page's address carries the handshake, the provider, the device hash and the action`() {
        val challenge = PkceChallenge(verifier = "v", challenge = "c-hash", state = "s-1")

        val url = SsoReauthenticator.sheetUrl(
            baseUrl = "https://app.example.test/",
            challenge = challenge,
            provider = ReauthProvider.MICROSOFT,
            deviceHash = "ab".repeat(32),
            action = NativeReauthAction.TWO_FACTOR_CHANGE,
            redirectUri = "districtai://auth",
        )

        assertTrue(url.startsWith("https://app.example.test/auth/native/reauth?"))
        assertEquals(
            mapOf(
                "code_challenge" to "c-hash",
                "state" to "s-1",
                "redirect_uri" to "districtai://auth",
                "provider" to "azure-ad",
                "device" to "ab".repeat(32),
                "action" to "2fa-change",
            ),
            query(url),
        )
        assertFalse("the verifier never leaves the device", url.contains("=v&") || url.endsWith("=v"))
    }

    @Test
    fun `the device is sent as the lower-case hex SHA-256 of its id, never the id`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SsoReauthenticator.sha256Hex("abc"),
        )
    }

    @Test
    fun `a callback with this attempt's state is exchanged with its verifier`() = runTest {
        val outcome = async {
            reauthenticator.reauthenticate(ReauthProvider.GOOGLE, NativeReauthAction.TWO_FACTOR_CHANGE) {
                opened += it
                true
            }
        }
        runCurrent()
        val state = query(opened.single())["state"]

        assertTrue("a pending attempt takes the callback", callbacks.deliver(state, "code-1"))

        assertEquals(SsoReauthOutcome.Proved(300), outcome.await())
        val exchange = api.ssoExchanges.single()
        assertEquals("2fa-change", exchange[0])
        assertEquals("code-1", exchange[1])
        assertEquals(43, exchange[2].length)
        assertEquals("districtai://auth", exchange[3])
        assertEquals(SsoReauthenticator.sha256Hex("device-1234"), query(opened.single())["device"])
        assertFalse("spent: the next callback is a sign-in's", callbacks.deliver(state, "code-2"))
    }

    @Test
    fun `a callback with another state is never exchanged`() = runTest {
        val outcome = async {
            reauthenticator.reauthenticate(ReauthProvider.GOOGLE, NativeReauthAction.DELETE_ACCOUNT) { true }
        }
        runCurrent()
        callbacks.deliver("forged", "code-1")

        assertEquals(SsoReauthOutcome.StateMismatch, outcome.await())
        assertTrue(api.ssoExchanges.isEmpty())
    }

    @Test
    fun `a callback without a code is not exchanged`() = runTest {
        val outcome = async {
            reauthenticator.reauthenticate(ReauthProvider.APPLE, NativeReauthAction.TWO_FACTOR_CHANGE) {
                opened += it
                true
            }
        }
        runCurrent()
        callbacks.deliver(query(opened.single())["state"], "")

        assertEquals(SsoReauthOutcome.NoCode, outcome.await())

        val missing = async {
            reauthenticator.reauthenticate(ReauthProvider.APPLE, NativeReauthAction.TWO_FACTOR_CHANGE) {
                opened += it
                true
            }
        }
        runCurrent()
        callbacks.deliver(query(opened.last())["state"], null)

        assertEquals(SsoReauthOutcome.NoCode, missing.await())
    }

    @Test
    fun `a refused code is reported with the service's failure`() = runTest {
        val refusal = ApiResult.Forbidden("We could not confirm it is you.", "reauth_failed")
        api.ssoResult = refusal
        val outcome = async {
            reauthenticator.reauthenticate(ReauthProvider.GOOGLE, NativeReauthAction.TWO_FACTOR_CHANGE) {
                opened += it
                true
            }
        }
        runCurrent()
        callbacks.deliver(query(opened.single())["state"], "code-1")

        assertEquals(SsoReauthOutcome.Refused(refusal), outcome.await())
    }

    @Test
    fun `no browser means the page could not open, and nothing is left pending`() = runTest {
        val outcome = reauthenticator.reauthenticate(
            ReauthProvider.GOOGLE,
            NativeReauthAction.TWO_FACTOR_CHANGE
        ) { false }

        assertEquals(SsoReauthOutcome.CouldNotOpen, outcome)
        assertFalse(callbacks.deliver("s", "c"))
    }

    @Test
    fun `coming back to the app without a callback is a cancellation`() = runTest {
        val outcome = async {
            reauthenticator.reauthenticate(ReauthProvider.GOOGLE, NativeReauthAction.TWO_FACTOR_CHANGE) { true }
        }
        runCurrent()
        // A resume before the browser ever covered the app is not one.
        callbacks.onHostResumed()
        runCurrent()
        assertFalse(outcome.isCompleted)

        callbacks.onHostPaused()
        callbacks.onHostResumed()

        assertEquals(SsoReauthOutcome.Cancelled, outcome.await())
    }

    @Test
    fun `an attempt cancelled while the browser is open leaves nothing pending`() = runTest {
        val outcome = async {
            reauthenticator.reauthenticate(ReauthProvider.GOOGLE, NativeReauthAction.TWO_FACTOR_CHANGE) { true }
        }
        runCurrent()

        outcome.cancel()
        runCurrent()

        assertFalse("the next callback is a sign-in's", callbacks.deliver("s", "c"))
    }

    @Test
    fun `with nothing pending the host's lifecycle is ignored and a callback is a sign-in's`() {
        callbacks.onHostPaused()
        callbacks.onHostResumed()

        assertFalse(callbacks.deliver("s", "c"))
    }

    @Test
    fun `a second attempt abandons the first as cancelled`() = runTest {
        val first = callbacks.begin()
        val second = callbacks.begin()

        assertEquals(null, first.callback.await())
        callbacks.end(first)
        assertTrue("ending a stale attempt leaves the new one pending", callbacks.deliver("s", "c"))
        assertEquals("s" to "c", second.callback.await())
    }
}
