// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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

/**
 * `DELETE /api/auth/delete-account`: the account is gone. Fixture: `district-account-delete.json`.
 *
 * ⚠️ [externalErasurePending] MEANS SUPPORT DATA HELD BY A VENDOR IS STILL BEING ERASED; the account
 * itself is deleted either way (the service answers 202 then, 200 otherwise).
 */
@Serializable
data class AccountDeleteResponse(
    val success: Boolean,
    val accountDeleted: Boolean,
    val externalErasurePending: Boolean,
    val message: String? = null,
    /**
     * The support-data erasure's counts, kept whole and not read: nothing on a device acts on them,
     * and the service may add a count.
     */
    val supportErasure: JsonObject? = null,
)
