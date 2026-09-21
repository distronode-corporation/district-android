package com.distronode.districtai.ui

/**
 * Why there is no session — which decides the wording, and the wording matters.
 *
 * ⚠️ These are three different sentences, not three ways of saying one thing. "Please sign in
 * again" is wrong on a first run, and a security-flavoured warning is wrong for the routine case
 * AND unactionable, since the only remedy in every case is to sign in.
 *
 * ⛔ LIVES IN `ui`, NOT `ui.overview`, AND THE MOVE WAS THE POINT. [FailureText] is the mapping
 * EVERY screen shares, and it imported this from a single screen's package — so the call log and
 * the contacts list both depended on the overview's namespace to describe their own session
 * state. That is the shape a screen-specific concept has just before it silently becomes a
 * shared one; putting it beside the mapping that produces it makes the actual ownership visible.
 */
enum class SignedOutCause {
    /** First run, or a local sign-out. Show the ordinary sign-in screen, not an error. */
    NEVER_SIGNED_IN,

    /**
     * The process died mid-refresh, so the stored token is presumed spent.
     *
     * ⚠️ NOT A SECURITY EVENT, even though it ends the session. Re-sending that token would
     * revoke the whole token family and log a replay warning describing an attack that did not
     * happen, so the client deliberately signs out instead — a tradeoff the server documents and
     * accepts. Word it as routine.
     */
    ROUTINE,

    /**
     * The server refused the credential, or the refresh window elapsed. Unknown, expired, revoked
     * and replayed are indistinguishable here by design.
     */
    SESSION_INVALID,
}
