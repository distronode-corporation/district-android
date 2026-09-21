package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * One installation with a live refresh-token chain on this account.
 *
 * ⛔ `deviceName` IS CLIENT-SUPPLIED AND UNTRUSTED. The token route's own schema comment says
 * so, and the device-list route repeats it: this string is whatever the app that signed in
 * sent (here, `Build.MANUFACTURER + Build.MODEL`), echoed back for display. It is not a device
 * attestation and nothing may be decided from it — least of all which row is "this phone",
 * which is answered by comparing [deviceId] against `DeviceIdentity.deviceId()`.
 *
 * ⛔ `lastUsedAt` IS "LAST REFRESHED", NOT "LAST USED", AND THE LABEL MUST NOT OVERSTATE IT.
 * The server stamps it only on ROTATION (`rotateNativeSession`), and the access token lives
 * ten minutes with a sixty-second early margin — so a phone in continuous use reports a value
 * up to about ten minutes stale, and a phone that was opened once and left alone reports the
 * moment of that open forever after. It is good enough for "which of these is the one I am
 * holding" and useless for anything finer.
 *
 * ⚠️ BOTH NULLABLE FIELDS ARE GENUINELY NULLABLE ON THE WIRE. `deviceName` is `String | null`
 * server-side because the field is optional on token exchange, and `lastUsedAt` is
 * `Date | null` because a session that has never been refreshed has never been stamped — which
 * is every session for its first ten minutes, i.e. exactly the row a user sees right after
 * signing in. `district-devices.json` carries one row of each shape so a decoder that
 * regressed to non-null is caught by the contract test rather than on a phone.
 *
 * ⚠️ The two timestamps are ISO-8601 STRINGS, not instants: `NextResponse.json` serialises a
 * `Date` through `JSON.stringify`. Kept as strings for the same reason `CallSummary.createdAt`
 * is — this module deliberately owns no date parsing.
 */
@Serializable
data class NativeDevice(
    val deviceId: String,
    val deviceName: String? = null,
    /** "android" or "ios". Free text on the wire; the server's zod enum is the real gate. */
    val platform: String = "",
    /** ⛔ Last ROTATION, not last use. See the class doc. Null until the first refresh. */
    val lastUsedAt: String? = null,
    val createdAt: String = "",
)

/**
 * `GET /api/auth/native/devices`
 *
 * ⚠️ THE LIST CAN LAG A ROTATION. The server filters on `rotatedAt: null`, so a chain caught
 * mid-refresh has its old row already stamped and its successor not yet visible to this query.
 * A momentarily short list — including an EMPTY one on a single-device account — is therefore a
 * legitimate transient rather than "you are signed out everywhere", and the screen renders it
 * as an explanatory empty state rather than as a failure.
 */
@Serializable
data class DeviceListResponse(
    val success: Boolean = false,
    val devices: List<NativeDevice> = emptyList(),
)

/**
 * `POST /api/auth/native/devices/revoke` and `POST /api/auth/native/revoke-all`
 *
 * ⛔ `revoked: 0` IS A SUCCESS, NOT A FAILURE, AND THE CLIENT MUST NOT RENDER IT AS ONE. The
 * per-device route answers `{success:true, revoked:0}` for a device id that is not yours,
 * deliberately: device ids are client-generated and opaque, so answering 404 for a stranger's
 * id and 200 for a real one would turn the route into a membership oracle over the id space.
 * Zero is also the honest answer for the ordinary races — a row already revoked from another
 * device, or a chain that rotated between the list read and the tap. All of those resolve to
 * the same user-facing action: re-read the list and show what is actually there.
 *
 * ⚠️ REVOKES REFRESH TOKENS, NOT ACCESS TOKENS. A revoked device keeps working for the rest of
 * its access token's ≤10 minutes. That bound is the server's, documented in
 * `lib/auth/native-token.ts`, and no client-side count can shorten it.
 */
@Serializable
data class DeviceRevokeResponse(
    val success: Boolean = false,
    val revoked: Int = 0,
)
