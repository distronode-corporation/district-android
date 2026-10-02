package com.distronode.districtai.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⛔ THESE TESTS EXIST BECAUSE THE EMULATOR CANNOT COVER THIS. Waydroid runs windowed, so the app and
 * the browser are visible at once and the activity never pauses — the tracker correctly reports
 * nothing, which means the interesting path is unreachable there. Verified here instead.
 */
class LoginAttemptTrackerTest {

    @Test
    fun `reports a failure when the user returns without a callback`() {
        // The real case: the authorize page 404s (production's current state), or the user dismisses
        // the tab. No callback ever arrives, so the screen must stop promising progress.
        val tracker = LoginAttemptTracker()

        tracker.onLoginStarted()
        tracker.onPaused()

        assertTrue(tracker.onResumed())
        tracker.onPaused()
        assertFalse("the attempt must be abandoned once reported", tracker.onResumed())
    }

    @Test
    fun `does not report before the browser has ever covered the activity`() {
        // ⛔ THE GUARD THAT MATTERS. onResume can run again without an intervening pause, and
        // reporting then would say sign-in failed the instant the button was tapped.
        val tracker = LoginAttemptTracker()

        tracker.onLoginStarted()

        assertFalse(tracker.onResumed())
        tracker.onPaused()
        assertTrue("the attempt is still live", tracker.onResumed())
    }

    @Test
    fun `does not report when a callback was received`() {
        // A successful login also resumes the activity. Reporting a failure there would overwrite
        // "Signed in" with an error for an attempt that worked.
        val tracker = LoginAttemptTracker()

        tracker.onLoginStarted()
        tracker.onPaused()
        tracker.onCallbackReceived()

        assertFalse(tracker.onResumed())
    }

    @Test
    fun `does not report twice for one attempt`() {
        // Otherwise every later resume — switching apps, unlocking the screen — would re-announce a
        // failure the user already dismissed.
        val tracker = LoginAttemptTracker()
        tracker.onLoginStarted()
        tracker.onPaused()

        assertTrue(tracker.onResumed())
        assertFalse(tracker.onResumed())
    }

    @Test
    fun `does not report when no login was ever started`() {
        // Ordinary app backgrounding must be silent.
        val tracker = LoginAttemptTracker()

        tracker.onPaused()

        assertFalse(tracker.onResumed())
    }

    @Test
    fun `a second attempt starts clean`() {
        // Tapping sign-in again after a failure must not inherit the previous attempt's pause.
        val tracker = LoginAttemptTracker()
        tracker.onLoginStarted()
        tracker.onPaused()
        tracker.onResumed()

        tracker.onLoginStarted()

        assertFalse("the new attempt has not been backgrounded yet", tracker.onResumed())
        tracker.onPaused()
        assertTrue("the new attempt is live", tracker.onResumed())
    }

    @Test
    fun `a pause with no live attempt is not remembered`() {
        // Backgrounding the app BEFORE signing in must not make the first resume after a later tap
        // look like a returned browser.
        val tracker = LoginAttemptTracker()

        tracker.onPaused()
        tracker.onLoginStarted()

        assertFalse(tracker.onResumed())
    }

    @Test
    fun `an explicit reset abandons the attempt`() {
        val tracker = LoginAttemptTracker()
        tracker.onLoginStarted()
        tracker.onPaused()

        tracker.reset()

        assertFalse(tracker.onResumed())
        // ⚠️ And a later browser-shaped pause does not revive it.
        tracker.onPaused()
        assertFalse(tracker.onResumed())
    }
}
