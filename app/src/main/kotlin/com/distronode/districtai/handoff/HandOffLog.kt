package com.distronode.districtai.handoff

import android.util.Log

/**
 * What the dashboard hand-off says about itself: which path it took, and what it did with a
 * callback.
 *
 * ⛔ AN ENUM AND NOTHING ELSE, SO NO LOG LINE CAN CARRY THE NONCE. The nonce is half of a
 * credential (with the cookie, it is what lets a code sign a browser in), and the state is what
 * makes a callback acceptable. A logger that took a string would be one interpolation away from
 * printing either; one that takes a closed set of constants cannot. Nothing here goes to Sentry
 * either: a breadcrumb travels with every later crash report, and the path taken is a local
 * diagnostic, not a reportable event.
 */
enum class HandOffEvent {
    /** The browser called back with this hand-off's state and a well-formed nonce; the code is bound. */
    BOUND,

    /**
     * Leg 1 was opened in the browser and no acceptable callback arrived in time, so the hand-off
     * was given up and nothing was minted.
     */
    NO_CALLBACK,

    /** A second hand-off was asked for while one was running, and was not started. */
    ALREADY_RUNNING,

    /** A callback was accepted for the pending hand-off. */
    CALLBACK_ACCEPTED,

    /** A callback arrived with no hand-off pending: a new process, a stale one, or an injection. */
    CALLBACK_NOTHING_PENDING,

    /** A callback carried a state that is not the pending one. */
    CALLBACK_STATE_MISMATCH,

    /** A callback carried the pending state and a nonce of the wrong shape. */
    CALLBACK_MALFORMED_NONCE,

    /** A callback arrived after the pending hand-off had already taken one, or stopped waiting. */
    CALLBACK_ALREADY_SETTLED,
}

/** ⚠️ An interface so tests can read what was recorded, and assert what was not. */
fun interface HandOffLog {
    fun record(event: HandOffEvent)
}

/**
 * Logcat, at INFO, with the event's name as the whole message.
 *
 * ⚠️ INFO RATHER THAN DEBUG because the one thing this is for is telling, from a user's
 * `adb logcat`, whether their hand-offs are bound or their browser never called back.
 */
internal object LogcatHandOffLog : HandOffLog {
    const val TAG = "DistrictHandOff"

    override fun record(event: HandOffEvent) {
        Log.i(TAG, event.name)
    }
}
