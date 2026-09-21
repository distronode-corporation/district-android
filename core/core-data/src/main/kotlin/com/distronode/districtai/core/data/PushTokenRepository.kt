package com.distronode.districtai.core.data

import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.PLATFORM_ANDROID
import com.distronode.districtai.core.network.PushApi
import com.distronode.districtai.core.network.PushTokenRegisterRequest

/**
 * This installation's push registration: on after sign-in, off before sign-out.
 *
 * ⛔ EVERY FUNCTION HERE RETURNS AND NONE THROWS, AND THAT IS THE CONTRACT RATHER THAN DEFENSIVE
 * HABIT. Push is a courtesy channel layered on top of paths that already work without it — the
 * inbox has a poll, and an unanswered ring falls through to PSTN — so a registration failure must
 * never be able to fail the thing it was attached to. The two call sites make that concrete: one
 * runs during sign-IN, where throwing would turn a successful login into a failed one, and the
 * other runs during sign-OUT, where throwing would abandon the token revoke that follows it. See
 * `PushRegistrar`, which is what enforces the timeout those call sites also need.
 *
 * ⛔ IT HOLDS NO TOKEN AND CACHES NOTHING, DELIBERATELY. An FCM registration token is owned by the
 * SDK and can be rotated by the SDK at any time — that is what `onNewToken` exists for — so a copy
 * kept here would be a second source of truth that is wrong exactly when it matters, i.e. straight
 * after a rotation. The caller reads the current token and passes it in; re-registering an
 * unchanged token is an idempotent upsert server-side and costs one request.
 *
 * ⛔ AND IT DOES NOT DECIDE **WHEN** TO REGISTER. Sequencing (register after a login, unregister
 * before the revoke, never on a timer against a 20/min ceiling) lives in `PushRegistrar` because
 * it needs a scope and a token source; this layer is the two calls and the envelope check.
 *
 * ⚠️ NO ANDROID DEPENDENCY, WHICH IS WHY IT IS IN THIS MODULE AND NOT IN `:app`. Everything
 * Firebase-shaped stays behind a seam in the application module; what crosses into here is a
 * `String`.
 */
class PushTokenRepository(private val api: PushApi) {

    /**
     * Tell the server this installation can be pushed to at [token].
     *
     * ⛔ A BLANK TOKEN IS REFUSED WITHOUT A REQUEST, AND IT IS A REAL CASE RATHER THAN A GUARD FOR
     * TIDINESS. `FirebaseMessaging.getToken()` fails on a device with no Play Services, on an
     * emulator without them, and while Firebase is still fetching one — and the natural shapes of
     * "no token yet" are null and the empty string. Sending one would spend a request against a
     * 20/min per-account ceiling to be told 400, and — worse — a client that treated that 400 as
     * "registration failed" would retry it.
     *
     * ⚠️ IDEMPOTENT SERVER-SIDE: the row is upserted on the installation id the bearer names, so
     * registering an unchanged token is free and registering a new one replaces the old. That is
     * what lets the registrar simply register rather than track whether it already has.
     *
     * @return true only when the server AFFIRMED it. Everything else — offline, 401, 429, a 200
     *   whose envelope did not say `success` — is false, and false means "push may not work",
     *   never "push is off".
     */
    suspend fun register(token: String): Boolean {
        if (token.isBlank()) return false
        // ⚠️ THE PLATFORM IS NAMED AT THE CALL SITE RATHER THAN DEFAULTED ON THE TYPE, because a
        // Kotlin default would be dropped from the JSON body entirely — see
        // [PushTokenRegisterRequest] for the encoder setting that makes that true.
        return affirmed(
            api.registerPushToken(
                PushTokenRegisterRequest(token = token, platform = PLATFORM_ANDROID),
            ),
        )
    }

    /**
     * Stop pushing to this installation.
     *
     * ⛔ THE RETURN VALUE IS NOT DECORATION AND IT IS NOT SYMMETRICAL WITH [register]'S. The server
     * answers `{success:true}` even when there was nothing to delete — deliberately, so the route
     * is not an existence oracle over the device-id space — but a delete that THREW answers 500,
     * and that means the row may still be live and this handset may still receive another person's
     * notifications after they sign in on it. So false here is "we do not know", which is a state
     * worth being able to report; it is not "there was nothing to remove".
     *
     * ⛔ NEEDS A LIVE BEARER. Once `signOut` has revoked the refresh token and wiped the store there
     * is no credential left to authenticate this with, which is why it runs FIRST in that sequence
     * and not as tidy-up afterwards.
     */
    suspend fun unregister(): Boolean = affirmed(api.unregisterPushToken())

    /**
     * ⚠️ THE ENVELOPE CHECK IS NOT CEREMONY HERE EITHER. `PushRegistrationResponse.success`
     * defaults to false, so a `{}` body — or any 200 from something that is not this API — decodes
     * cleanly into a well-formed "no". Reading the flag is what makes that a no rather than a yes.
     */
    private fun affirmed(
        result: ApiResult<com.distronode.districtai.core.model.PushRegistrationResponse>,
    ): Boolean = result is ApiResult.Success && result.value.success
}
