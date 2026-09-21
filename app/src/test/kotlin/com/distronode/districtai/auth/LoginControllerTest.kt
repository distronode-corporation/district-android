package com.distronode.districtai.auth

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.auth.LoginOutcome
import com.distronode.districtai.core.auth.PkceLoginFlow
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ⛔ THIS IS A TEST ABOUT ORDERING, WHICH IS WHY IT CAN EXIST AT ALL. Every rule below is about
 * WHEN something is called relative to the activity lifecycle, and none of them involve HTTP — so
 * [PkceLoginFlow] is faked and no OkHttp stack, `TokenRefreshCoordinator` or Android Keystore is
 * constructed. That seam is the only reason these paths are reachable off a device.
 *
 * ⚠️ The two rules worth reading first are `a resume with no intervening pause reports nothing`
 * and `a callback followed by a resume does not report a failure`. Both are cases where the
 * obvious implementation tells the user sign-in failed when it did not, and neither is observable
 * on this project's emulator: Waydroid shows the app and the browser side by side, so the activity
 * never pauses and the whole mechanism sits idle. See [LoginAttemptTracker].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class LoginControllerTest {

    private class FakeLoginFlow(var outcome: LoginOutcome = LoginOutcome.Success) : PkceLoginFlow {
        var cancels = 0
        val completed = mutableListOf<Uri>()

        override fun authorizeUrl(): String = "https://www.distronode.com/auth/native?state=s"

        override suspend fun completeLogin(callback: Uri): LoginOutcome {
            completed += callback
            return outcome
        }

        override fun cancel() {
            cancels += 1
        }
    }

    private fun callback(): Uri = Uri.parse("districtai://auth?code=c&state=s")

    // ── The browser hand-off ─────────────────────────────────────────────────

    @Test
    fun `a login that found no browser says so and arms nothing`() = runTest {
        // ⚠️ Arming the tracker here would make the next resume report "sign-in did not complete"
        // on top of the more accurate "no browser", replacing a precise diagnosis with a vague one.
        val flow = FakeLoginFlow()
        val controller = LoginController(flow, SessionSignal(), this)

        controller.onBrowserLaunched(opened = false)
        assertEquals(LoginStatus.NoBrowser, controller.status.value)

        controller.onHostPaused()
        controller.onHostResumed()
        assertEquals(
            "a failed launch must not later be reported as an incomplete login",
            LoginStatus.NoBrowser,
            controller.status.value,
        )
    }

    @Test
    fun `an opened browser reports waiting`() = runTest {
        val controller = LoginController(FakeLoginFlow(), SessionSignal(), this)

        controller.onBrowserLaunched(opened = true)

        assertEquals(LoginStatus.WaitingForBrowser, controller.status.value)
    }

    // ── The browser leg that ends with no callback ────────────────────────────

    @Test
    fun `a return from the browser with no callback is reported and the attempt dropped`() = runTest {
        // ⛔ Without this the screen sits on "waiting for the browser" forever and the user cannot
        // tell a slow login from a broken one.
        val flow = FakeLoginFlow()
        val controller = LoginController(flow, SessionSignal(), this)

        controller.onBrowserLaunched(opened = true)
        controller.onHostPaused()
        controller.onHostResumed()

        assertEquals(LoginStatus.DidNotComplete, controller.status.value)
        assertEquals("the pending verifier must be dropped so the next tap starts clean", 1, flow.cancels)
    }

    @Test
    fun `a resume with no intervening pause reports nothing`() = runTest {
        // ⛔ THE LOAD-BEARING GUARD. onResume can run again without the browser ever having covered
        // this activity, and reporting then would tell the user sign-in failed the instant they
        // tapped the button.
        val flow = FakeLoginFlow()
        val controller = LoginController(flow, SessionSignal(), this)

        controller.onBrowserLaunched(opened = true)
        controller.onHostResumed()

        assertEquals(LoginStatus.WaitingForBrowser, controller.status.value)
        assertEquals(0, flow.cancels)
    }

    @Test
    fun `a callback followed by a resume does not report a failure`() = runTest {
        // ⛔ The resume that follows a callback is the browser handing control back on SUCCESS. The
        // tracker is told before the exchange starts precisely so this ordering cannot misfire.
        val controller = LoginController(FakeLoginFlow(), SessionSignal(), this)

        controller.onBrowserLaunched(opened = true)
        controller.onHostPaused()
        controller.onCallback(callback())
        advanceUntilIdle()
        controller.onHostResumed()

        assertNull("a successful login must end with nothing to say", controller.status.value)
    }

    // ── The exchange ─────────────────────────────────────────────────────────

    @Test
    fun `a successful exchange clears the status and advances the session epoch`() = runTest {
        // ⛔ The epoch is what every other screen watches. Without it a login completes and no
        // visible screen ever re-reads — the re-login dead end this class was built to remove.
        val signal = SessionSignal()
        val controller = LoginController(FakeLoginFlow(LoginOutcome.Success), signal, this)
        val before = signal.epoch.value

        controller.onCallback(callback())
        advanceUntilIdle()

        assertNull(controller.status.value)
        assertEquals(before + 1, signal.epoch.value)
    }

    @Test
    fun `a state mismatch is refused and changes no session`() = runTest {
        // ⛔ The authorization-code-injection case. Any app on the device can claim the scheme, so
        // this must never look like a session change to anything downstream.
        val signal = SessionSignal()
        val controller = LoginController(FakeLoginFlow(LoginOutcome.StateMismatch), signal, this)
        val before = signal.epoch.value

        controller.onCallback(callback())
        advanceUntilIdle()

        assertEquals(LoginStatus.Refused, controller.status.value)
        assertEquals("a refused callback is not a session change", before, signal.epoch.value)
    }

    @Test
    fun `every failure outcome maps to a distinct status and none advances the epoch`() = runTest {
        val expected = mapOf(
            LoginOutcome.NoAttemptInProgress to LoginStatus.LinkExpired,
            LoginOutcome.Rejected to LoginStatus.Expired,
            LoginOutcome.RateLimited to LoginStatus.RateLimited,
            LoginOutcome.Unreachable to LoginStatus.Unreachable,
        )

        expected.forEach { (outcome, status) ->
            val signal = SessionSignal()
            val controller = LoginController(FakeLoginFlow(outcome), signal, this)

            controller.onCallback(callback())
            advanceUntilIdle()

            assertEquals("$outcome", status, controller.status.value)
            assertEquals("$outcome must not advance the epoch", 0, signal.epoch.value)
        }
    }

    @Test
    fun `a server-named refusal is carried through verbatim`() = runTest {
        // ⚠️ Server-authored, so it is shown as-is rather than replaced by a guess.
        val controller = LoginController(FakeLoginFlow(LoginOutcome.Denied("sso_refused")), SessionSignal(), this)

        controller.onCallback(callback())
        advanceUntilIdle()

        assertEquals(LoginStatus.Denied("sso_refused"), controller.status.value)
    }

    @Test
    fun `the callback uri is passed to the flow unmodified`() = runTest {
        // The flow re-reads state, error and code off it, so anything lost here is a silent auth bug.
        val flow = FakeLoginFlow()
        val controller = LoginController(flow, SessionSignal(), this)
        val uri = callback()

        controller.onCallback(uri)
        advanceUntilIdle()

        assertEquals(1, flow.completed.size)
        assertSame(uri, flow.completed.single())
    }

    @Test
    fun `clearing the status leaves nothing to show`() = runTest {
        val controller = LoginController(FakeLoginFlow(), SessionSignal(), this)
        controller.onBrowserLaunched(opened = true)

        controller.clearStatus()

        assertNull(controller.status.value)
    }
}
