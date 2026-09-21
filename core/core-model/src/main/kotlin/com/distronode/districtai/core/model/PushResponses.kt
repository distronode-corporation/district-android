package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/district/devices/register` and `POST /api/district/devices/unregister`.
 *
 * ⛔ ONE DTO FOR BOTH ROUTES, WHICH IS ONLY SAFE BECAUSE THEIR BODIES ARE THE SAME OBJECT AND THE
 * CONTRACT TEST PINS THAT. `district-device-register.json` and `district-device-unregister.json`
 * are byte-identical `{"success":true}` today; if either grows a field, the fixture test is what
 * fails, and the answer is to split this type rather than to widen it — the two routes have
 * opposite meanings and a caller that read a register's field off an unregister's body would be
 * reading a default.
 *
 * ⛔ `success` IS THE ONLY THING THESE ROUTES SAY, AND ON THE UNREGISTER PATH IT IS LOAD-BEARING
 * RATHER THAN CEREMONIAL. The server answers `{success:true}` even when there was no row to
 * delete (deliberately — a route that 404'd for an unregistered installation would be a membership
 * oracle over the device-id space), but a THROWN delete answers 500 and means the row may still be
 * live and the handset may still receive pushes. So "the flag was true" is the whole difference
 * between "push is off for this device" and "we do not know", and `PushTokenRepository` refuses to
 * report the first without it.
 *
 * ⚠️ NEITHER ROUTE ECHOES THE TOKEN, THE DEVICE ID OR THE PLATFORM BACK. That is the server's
 * decision and it is the right one — an FCM registration token identifies one installation, and a
 * response that repeated it would put it in one more log. Nothing here should grow a field for it.
 */
@Serializable
data class PushRegistrationResponse(
    val success: Boolean = false,
)

/**
 * `POST /api/district/calls/{callId}/answer` — the credential for a call this device is ringing on.
 *
 * ⛔ THE SAME CLASS OF PAYLOAD AS [DialResponse], AND THE SAME RULE APPLIES: [url] AND [token] ARE
 * USED VERBATIM. The room was created by whatever carried the call — the US SIP bridge for every
 * inbound call — and the server resolves the deployment BY ROOM rather than by workspace precisely
 * because an EU workspace has rooms on both buses. A client that derived a URL from its own region
 * would create an empty room of the same name on the wrong bus and sit in it alone while the caller
 * waited, with nothing anywhere reporting an error.
 *
 * ⛔ AND UNLIKE THE DIAL RESPONSE, THIS ONE ARRIVES FOR A `call_` ROOM. There is no prefix
 * assertion to make: a `direct_` room is one this app placed, whereas an answered call joins the
 * EXISTING room that already holds the caller and the AI. The metadata the server stamps on this
 * token (`role: supervisor`, `mode: barge-in`, `handoff: true`) is what stops the agent
 * unsubscribing the human who just answered — none of which is visible here, because the metadata
 * is inside the JWT and this client neither reads nor could change it.
 *
 * ⚠️ [roomName] IS RETURNED FOR DIAGNOSTICS AND FOR NOTHING ELSE. The engine joins with the
 * url/token pair; the name is what makes a log line about a failed join identifiable. ⛔ Do not
 * build a join from it — see [DialResponse] for why this client never constructs a room name.
 *
 * ⚠️ THE TOKEN'S TTL IS 70 MINUTES, sized like the dial route's rather than like `calls/token`'s
 * 30, because the answerer is a PARTY to the call rather than a supervisor dropping in. It
 * authorises the JOIN; an established connection is not re-checked against it.
 */
@Serializable
data class CallAnswerResponse(
    val success: Boolean = false,
    /** ⛔ The deployment holding the ROOM, not the workspace's. Used verbatim. */
    val url: String = "",
    /** The answerer's own LiveKit token, ~70 minutes. Used verbatim. */
    val token: String = "",
    /** ⚠️ Diagnostics only. See the class doc. */
    val roomName: String = "",
)
