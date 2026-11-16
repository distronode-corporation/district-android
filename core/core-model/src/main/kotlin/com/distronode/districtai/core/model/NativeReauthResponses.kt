package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * What a fresh re-authentication is for. The service binds each proof to one action and refuses a
 * code or a proof spent on another. The Kotlin mirror of the Swift core's `NativeReauthAction`.
 *
 * ⛔ [wire] IS SENT ON EVERY STEP AND MUST BE THE SAME ONE: the sheet's address, the code exchange
 * and the proof are each bound to it.
 */
enum class NativeReauthAction(val wire: String) {
    /** Deleting the signed-in account. */
    DELETE_ACCOUNT("delete-account"),

    /** Turning two-step verification on or off. */
    TWO_FACTOR_CHANGE("2fa-change"),
}

/**
 * `POST /api/auth/native/reauth/sso` and `/reauth/password`: a proof for one action now stands on
 * the service for [expiresInSeconds]. Fixture: `district-native-reauth.json`.
 *
 * ⚠️ THE PROOF ITSELF IS NOT HERE. It lives on the service, keyed on this installation's bearer, so
 * nothing on the device carries it.
 */
@Serializable
data class NativeReauthResponse(
    val success: Boolean,
    /** The action the proof is for, as [NativeReauthAction.wire]. */
    val action: String,
    val expiresInSeconds: Int,
)
