// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `GET /api/auth/mfa`: whether the account has a second factor, and how many recovery codes are
 * left. Fixture: `district-mfa-status.json`. The Kotlin mirror of the Swift core's
 * `MfaStatusResponse`.
 *
 * ⚠️ COUNTS AND BOOLEANS ONLY, BY THE SERVICE'S DESIGN: the secret leaves it once, at enrolment, and
 * the recovery codes once, at confirmation, so nothing here needs redacting.
 */
@Serializable
data class MfaStatusResponse(
    val enrolled: Boolean,
    /** When the factor was turned on, ISO-8601 UTC, or null while it is off. */
    val enrolledAt: String? = null,
    val backupCodesRemaining: Int,
    /**
     * Whether the WEBSITE would let this account turn the factor off: false for an account without
     * a password, because a browser re-proves the password to disable.
     *
     * ⚠️ NOT A LIMIT ON AN APP. An app's `2fa-change` proof stands in for the password, so a
     * passwordless account turns it off here after a fresh provider sign-in.
     */
    val canDisable: Boolean,
)

/**
 * `POST /api/auth/mfa/enroll`: a new, not yet confirmed authenticator secret. Fixture:
 * `district-mfa-enroll.json`.
 *
 * ⛔ ALL THREE FIELDS ARE THE SECRET: [otpauthUri] carries it as a query item and [qrDataUrl] is that
 * URI as a picture. [toString] prints none of them, so logging this value never leaks it. The
 * service hands it over once and has no read path that shows it again.
 */
@Serializable
data class MfaEnrollResponse(
    /** The base32 key, for typing into an authenticator by hand. */
    val secret: String,
    /** The `otpauth://` URI an authenticator app opens. */
    val otpauthUri: String,
    /** A `data:image/png;base64,...` QR code of [otpauthUri], rendered by the service. */
    val qrDataUrl: String,
) {
    override fun toString(): String =
        "MfaEnrollResponse(secret=<redacted>, otpauthUri=<redacted>, qrDataUrl=<redacted>)"
}

/**
 * `POST /api/auth/mfa/confirm`: the factor is on, and these are its recovery codes. Fixture:
 * `district-mfa-confirm.json`.
 *
 * ⛔ THE RECOVERY CODES ARE CREDENTIALS, shown once (the service stores only their hashes). Only
 * their count reaches [toString].
 */
@Serializable
data class MfaConfirmResponse(
    val enrolled: Boolean,
    val backupCodes: List<String>,
) {
    override fun toString(): String =
        "MfaConfirmResponse(enrolled=$enrolled, backupCodes=<${backupCodes.size} redacted>)"
}

/** `POST /api/auth/mfa/disable`: the factor and its recovery codes are gone. Fixture: `district-mfa-disable.json`. */
@Serializable
data class MfaDisableResponse(
    val enrolled: Boolean,
)
