package com.distronode.districtai.ui

import com.distronode.districtai.auth.LoginStatus
import com.distronode.districtai.core.designsystem.Tone

/**
 * Which [Tone] a login status wears.
 *
 * ⛔ THESE MESSAGES ARE NOT ALL THE SAME KIND OF MESSAGE, AND THEY USED TO LOOK IT. Every status
 * rendered as the same small grey line, so "waiting for sign-in to complete in the browser" and
 * "that sign-in was refused" were visually identical. The second is the
 * authorization-code-injection case — a callback whose `state` did not match the one this app
 * generated, meaning something tried to bind this app's session to an account the user did not
 * choose. It must not read as a progress note.
 *
 * ⚠️ IN `ui/`, NOT IN THE DESIGN SYSTEM. [LoginStatus] is an app-layer type, and the design system
 * deliberately depends on no other project module — a primitive that knows about login statuses has
 * stopped being a primitive.
 *
 * ⚠️ Exhaustive with no `else`: adding a status without deciding how alarming it looks should not
 * compile. That is the same reason `SignInScreen`'s wording mapper is exhaustive.
 */
fun toneForLoginStatus(status: LoginStatus): Tone = when (status) {
    // In flight. Not a verdict, so the accent rather than a semantic colour.
    LoginStatus.WaitingForBrowser, LoginStatus.Completing -> Tone.District

    // ⛔ Hostile. A state mismatch is a refused callback, not a failed one.
    LoginStatus.Refused -> Tone.Danger

    // Cannot proceed on this device at all.
    LoginStatus.NoBrowser -> Tone.Danger

    // Recoverable by trying again — worth flagging, not worth alarming.
    LoginStatus.DidNotComplete,
    LoginStatus.LinkExpired,
    LoginStatus.Expired,
    LoginStatus.RateLimited,
    LoginStatus.Unreachable,
    -> Tone.Warning

    // ⚠️ Server-authored reason. It could be anything from "user cancelled" to a policy refusal, so
    // the frame stays neutral rather than asserting a severity this client cannot judge.
    is LoginStatus.Denied -> Tone.Neutral
}
