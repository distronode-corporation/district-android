package com.distronode.districtai.core.auth

import android.net.Uri

/**
 * The three operations a login host needs from the PKCE flow.
 *
 * ⛔ EXISTS SO LIFECYCLE ORDERING CAN BE TESTED WITHOUT A NETWORK STACK OR A KEYSTORE. The rules
 * the app-layer login host enforces are all about ORDER — arm the attempt only once a browser
 * actually opened, tell the tracker about a callback BEFORE the exchange starts so the resulting
 * `onResume` cannot report failure for an attempt that succeeded, and never run the exchange in a
 * scope the activity can cancel. None of those rules involve HTTP. Depending on [NativeLoginFlow]
 * directly would drag `NativeAuthApi`, an `OkHttpClient`, `TokenRefreshCoordinator` and
 * `KeystoreTokenStore` into every one of those tests, and `KeystoreTokenStore` needs a real
 * Android Keystore — so the ordering rules would only be exercisable on a device.
 *
 * ⚠️ DELIBERATELY NOT A WIDER SEAM. Three methods, matching [NativeLoginFlow] exactly, is the
 * whole surface the host uses. It is not an extension point and there is no expectation of a
 * second production implementation; widening it to "abstract the auth layer" would invite one.
 */
interface PkceLoginFlow {

    /** Begin an attempt and return the URL to open in a SYSTEM browser. */
    fun authorizeUrl(): String

    /** Handle the browser's callback and complete the token exchange. */
    suspend fun completeLogin(callback: Uri): LoginOutcome

    /** Abandon an in-flight attempt. */
    fun cancel()
}
