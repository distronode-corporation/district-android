package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * The 401 `POST /api/auth/native/apple` answers when the account behind a verified Apple ID has an
 * authenticator (TOTP) enrolled. Fixture: `district-native-apple-mfa-required.json`. The Kotlin
 * mirror of the Swift core's `NativeMfaRequiredResponse`.
 *
 * ⛔ THIS CLIENT NEVER RECEIVES IT TODAY, AND THE MODEL EXISTS FOR THE CONTRACT GATE. Android has no
 * native Sign in with Apple: it signs in through the browser and `POST /api/auth/native/token`,
 * where the web sign-in has already asked for the authenticator code before a code is minted. The
 * DTO is here so the corpus this app vendors is decoded strictly rather than recorded as debt, and
 * so a native Apple leg, if one is ever added, starts from the pinned shape.
 *
 * ⛔ NOT A REFUSAL. The Apple identity is proven and the server is asking for the second factor;
 * no session exists until `POST /api/auth/native/mfa` is answered with [mfaTicket] and a code.
 *
 * ⛔ [mfaTicket] IS A SHORT-LIVED CREDENTIAL (single use, 300 s, bound to the install and platform).
 * Never logged and never persisted.
 *
 * ⚠️ [mfaTicketExpiresAt] IS AN ISO-8601 STRING, like every timestamp in this module, which owns
 * no date parsing. ⚠️ [error] (`"mfa_required"`) is the discriminator a client keys on; [code] and
 * [message] are carried so the strict decoder accepts the body.
 *
 * ⚠️ NO DEFAULTS. Every key is required, so a body missing the ticket fails to decode rather than
 * yielding a blank ticket a client would post and lose.
 */
@Serializable
data class NativeMfaRequiredResponse(
    val error: String,
    val code: String,
    /** The server's own sentence. The app words the step itself. */
    val message: String,
    val mfaTicket: String,
    val mfaTicketExpiresAt: String,
)

/** The value of [NativeMfaRequiredResponse.error] that asks for the authenticator step. */
const val NATIVE_MFA_REQUIRED_ERROR: String = "mfa_required"

/**
 * The five-key native grant: what `POST /api/auth/native/token`, `.../apple` and `.../mfa` answer
 * on success. Fixture: `district-native-mfa.json`. The Kotlin mirror of the Swift core's
 * `NativeTokenResponse`.
 *
 * ⚠️ THE LIVE PARSER IS NOT THIS CLASS. `NativeAuthApi` in `:core:core-auth` reads the same body
 * with `ignoreUnknownKeys = true` (a server-added key must not break sign-in on installed apps);
 * this strict DTO is the drift detector for the shape, and the server pins the body byte for byte
 * in its own `native-grant.test.ts`.
 *
 * ⛔ BOTH EXPIRIES ARE EPOCH MILLISECONDS. Dividing would read every token as expired in 1970.
 */
@Serializable
data class NativeTokenResponse(
    val tokenType: String,
    val accessToken: String,
    val accessTokenExpiresAt: Long,
    val refreshToken: String,
    val refreshTokenExpiresAt: Long,
)
