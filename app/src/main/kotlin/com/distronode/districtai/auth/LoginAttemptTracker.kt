package com.distronode.districtai.auth

/**
 * Decides when a browser login leg ended WITHOUT a callback.
 *
 * ⛔ THE PROBLEM THIS SOLVES. The browser leg can fail in ways this process cannot observe: the
 * authorize page 404s (which is production's current state, since the server half is undeployed),
 * the user dismisses the tab, or an SSO provider refuses. In none of those cases does a callback
 * arrive — so without this, the sign-in screen sits on "Waiting for sign-in to complete in the
 * browser…" forever, and the user cannot tell a slow login from a broken one.
 *
 * ⛔ AND WHY IT IS A SEPARATE CLASS RATHER THAN TWO FLAGS IN THE ACTIVITY. The rule depends on
 * lifecycle ORDERING, which is the easiest thing in Android to get subtly wrong and the hardest to
 * assert from an activity: verifying it there means driving a real lifecycle through an activity
 * that builds the Keystore and the network stack. Extracted, it is a handful of plain functions with
 * exhaustive tests, and the activity just forwards callbacks.
 *
 * ⚠️ THE `paused` REQUIREMENT IS LOAD-BEARING, NOT BELT-AND-BRACES. `onResume` can run again without
 * the browser ever having covered this activity, and reporting a failure then would tell the user
 * sign-in failed the instant they tapped the button. Requiring an intervening pause is what makes
 * "we are back" mean it.
 *
 * ⚠️ MEASURED ON A WINDOWED EMULATOR, THIS CORRECTLY DOES NOTHING. Waydroid shows the app and the
 * browser side by side, so the activity never pauses and no failure is reported — the guard behaving
 * exactly as designed. That also means this path cannot be verified on that emulator, which is why
 * the tests below exist instead.
 */
class LoginAttemptTracker {

    private var awaitingCallback = false
    private var pausedSinceStart = false

    /** The browser was opened. */
    fun onLoginStarted() {
        awaitingCallback = true
        pausedSinceStart = false
    }

    /** This activity went to the background — normally because the browser covered it. */
    fun onPaused() {
        if (awaitingCallback) pausedSinceStart = true
    }

    /**
     * Back in the foreground.
     *
     * @return true when the login leg ended without a callback, i.e. the caller should tell the user
     *   it did not complete and abandon the pending attempt.
     */
    fun onResumed(): Boolean {
        if (!awaitingCallback || !pausedSinceStart) return false
        reset()
        return true
    }

    /**
     * A callback arrived and is being handled.
     *
     * ⚠️ Must be called BEFORE the resulting `onResume`, or that resume would also report a failure
     * for an attempt that actually succeeded. The activity does this from its intent handler, which
     * runs first.
     */
    fun onCallbackReceived() {
        reset()
    }

    /** The user abandoned the attempt, or it was reported. */
    fun reset() {
        awaitingCallback = false
        pausedSinceStart = false
    }

    /** Whether a browser leg is outstanding. Exposed for the UI's waiting state. */
    val isAwaitingCallback: Boolean get() = awaitingCallback
}
