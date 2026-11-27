// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A fresh re-authentication in the browser comes back on the sign-in redirect, so the activity has
 * to hand it to the pending re-authentication rather than to the sign-in.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivityReauthCallbackTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a callback while a re-authentication is pending is its, never a sign-in's`() {
        harness.signedOut()
        harness.launch()
        val pending = harness.container.reauthCallbacks.begin()

        harness.deliver(harness.viewIntent("${ApiEnvironment.PKCE_REDIRECT_URI}?code=c-1&state=s-1"))

        assertEquals("s-1" to "c-1", runBlocking { pending.callback.await() })
        assertNull("consumed", harness.activity.intent.data)
        assertNull("the sign-in heard nothing", harness.loginStatus)
    }

    @Test
    fun `with none pending the callback is a sign-in's, as before`() {
        harness.signedOut()
        harness.launch()

        harness.deliver(harness.viewIntent("${ApiEnvironment.PKCE_REDIRECT_URI}?code=c-1&state=s-1"))

        assertTrue("the sign-in answered it", harness.loginStatus != null)
    }

    @Test
    fun `coming back from the browser with no callback cancels the re-authentication`() {
        harness.signedOut()
        harness.launch()
        val pending = harness.container.reauthCallbacks.begin()

        harness.pauseAndResume()

        assertNull(runBlocking { pending.callback.await() })
    }
}
