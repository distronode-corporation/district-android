package com.distronode.districtai

import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.ui.MainLooperDrain
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.SIGN_IN_ACTION_DESCRIPTION
import com.distronode.districtai.ui.SIGN_IN_ROOT_DESCRIPTION
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sign-in gate and the PKCE callback, driven through the real activity.
 *
 * ⛔ THE CALLBACK IS HOSTILE INPUT. `districtai://auth` is an exported VIEW filter, so any app on the
 * device can fire one at this activity with whatever query it likes. The flow's `state` check is
 * what turns an injected authorization code into a refusal, and these tests fire the injections a
 * hostile app would: a wrong state, a state with no code, a provider error, a foreign host on our
 * scheme, and our callback's query on a foreign scheme. None of them may reach the token exchange,
 * which is also why none of them needs a network.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK], application = ShellTestApplication::class)
class MainActivitySignInTest {

    private val compose = createEmptyComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(MainLooperDrain()).around(compose)

    private val harness = ShellHarness(compose)

    @After
    fun tearDown() = harness.close()

    /** Launch signed out, tap Sign in with a browser present, and return the authorize URL it opened. */
    private fun startLogin(): Uri {
        harness.installBrowser()
        harness.signedOut()
        harness.launch()
        harness.awaitDescription(SIGN_IN_ACTION_DESCRIPTION)
        compose.onNodeWithContentDescription(SIGN_IN_ACTION_DESCRIPTION).performClick()
        compose.waitForIdle()
        val opened = checkNotNull(harness.nextStarted()) { "sign-in opened nothing" }
        return checkNotNull(opened.data)
    }

    @Test
    fun `a device with no session lands on the sign-in screen`() {
        harness.signedOut()

        harness.launch()

        harness.awaitDescription(SIGN_IN_ROOT_DESCRIPTION)
        assertNull("nothing has been said about a login yet", harness.loginStatus)
    }

    @Test
    fun `tapping Sign in opens the authorize page in the browser and waits for it`() {
        val authorize = startLogin()

        assertEquals("/auth/native", authorize.path)
        // ⛔ THE STATE IS WHAT A CALLBACK HAS TO ECHO. An authorize URL without one would make the
        // callback check below meaningless.
        assertTrue(authorize.getQueryParameter("state").orEmpty().isNotEmpty())
        assertEquals(LoginStatus.WaitingForBrowser, harness.loginStatus)
        harness.awaitText(harness.string(R.string.login_status_waiting))
    }

    @Test
    fun `with no browser at all, Sign in says so instead of waiting forever`() {
        // Every start throws, as it does on a device with no browser.
        shadowOf(harness.app).checkActivities(true)
        harness.signedOut()
        harness.launch()
        harness.awaitDescription(SIGN_IN_ACTION_DESCRIPTION)

        compose.onNodeWithContentDescription(SIGN_IN_ACTION_DESCRIPTION).performClick()
        compose.waitForIdle()

        assertEquals(LoginStatus.NoBrowser, harness.loginStatus)
        harness.awaitText(harness.string(R.string.login_status_no_browser))
        // ⚠️ And no attempt is armed, so coming back to the app does not add a vaguer message.
        harness.pauseAndResume()
        assertEquals(LoginStatus.NoBrowser, harness.loginStatus)
    }

    @Test
    fun `coming back from the browser with no callback reports that sign-in did not complete`() {
        startLogin()

        harness.pauseAndResume()

        assertEquals(LoginStatus.DidNotComplete, harness.loginStatus)
        harness.awaitText(harness.string(R.string.login_status_did_not_complete))
    }

    @Test
    fun `a callback whose state does not match this attempt is refused and never exchanged`() {
        val authorize = startLogin()
        val state = checkNotNull(authorize.getQueryParameter("state"))

        harness.deliver(harness.viewIntent("districtai://auth?code=injected&state=${state}x"))

        assertEquals(LoginStatus.Refused, harness.loginStatus)
        harness.awaitText(harness.string(R.string.login_status_refused))
        assertNull("the callback is consumed, so a recreation cannot replay it", harness.activity.intent.data)
    }

    @Test
    fun `a callback with no state at all is refused`() {
        startLogin()

        harness.deliver(harness.viewIntent("districtai://auth?code=injected"))

        assertEquals(LoginStatus.Refused, harness.loginStatus)
    }

    @Test
    fun `a matching state with no code is denied rather than exchanged`() {
        val state = checkNotNull(startLogin().getQueryParameter("state"))

        harness.deliver(harness.viewIntent("districtai://auth?state=$state"))

        assertEquals(LoginStatus.Denied("missing_code"), harness.loginStatus)
        harness.awaitText(harness.string(R.string.login_status_denied, "missing_code"))
    }

    @Test
    fun `a provider error on the callback is shown with the server's reason`() {
        val state = checkNotNull(startLogin().getQueryParameter("state"))

        harness.deliver(harness.viewIntent("districtai://auth?state=$state&error=access_denied"))

        assertEquals(LoginStatus.Denied("access_denied"), harness.loginStatus)
    }

    @Test
    fun `our scheme on a foreign host is still held to the state check`() {
        // ⚠️ THE ACTIVITY CHECKS THE SCHEME ONLY; the host is not what protects this. The state is,
        // and a hostile app firing `districtai://evil` cannot know it.
        startLogin()

        harness.deliver(harness.viewIntent("districtai://evil?code=injected&state=guessed"))

        assertEquals(LoginStatus.Refused, harness.loginStatus)
    }

    @Test
    fun `the callback's query on a foreign scheme is not treated as a callback`() {
        val state = checkNotNull(startLogin().getQueryParameter("state"))
        val foreign = "https://evil.example/auth?code=injected&state=$state"

        harness.deliver(harness.viewIntent(foreign))

        // Still waiting on the real browser leg, and the intent is left for whoever owns it.
        assertEquals(LoginStatus.WaitingForBrowser, harness.loginStatus)
        assertEquals(foreign, harness.activity.intent.data.toString())
    }

    @Test
    fun `a stale callback on a cold start reports an expired link, once`() {
        // ⛔ THE ROTATION BUG. The launch intent is re-delivered to onCreate on every recreation, and
        // without the clear a second pass would re-run the exchange on a spent attempt.
        harness.signedOut()

        harness.launch(harness.viewIntent("districtai://auth?code=stale&state=stale"))

        harness.await("the expired-link status") { harness.loginStatus == LoginStatus.LinkExpired }
        assertNull(harness.activity.intent.data)

        harness.container.loginController.clearStatus()
        harness.recreate()

        assertNull("a recreation must not replay the consumed callback", harness.loginStatus)
    }
}
