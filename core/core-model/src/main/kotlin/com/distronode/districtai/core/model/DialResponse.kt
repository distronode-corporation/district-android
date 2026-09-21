package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/district/calls/dial` — the outbound softphone's one server call.
 *
 * ⛔ THIS IS A CREDENTIAL PAIR, NOT A DATA SHAPE, AND THAT CHANGES WHAT A DECODE FAILURE COSTS.
 * Everywhere else in this client a renamed field means a screen renders wrong and the user tries
 * again. Here the server has ALREADY written the Call row and ALREADY told the carrier to dial by
 * the time it mints this body — so a field this app cannot read is a telephone that rings with
 * nobody on the other end, billed, with the operator looking at an error. There is no second round
 * trip and nothing to retry onto: retrying places a SECOND call.
 *
 * ⛔ [url] IS THE TRUNK'S DEPLOYMENT AND MUST BE USED VERBATIM — and it is a different answer from
 * the one `RoomTokenResponse.url` gives for a standalone room. A `direct_` room is created by the
 * SIP dial on whichever bus the server's `SipClient` points at (eu → the EU bus,
 * everything else → us), which is not necessarily the deployment that serves the workspace. A
 * client that derived this from its own region would join a bus that has never heard of the room,
 * and the by-room resolver cannot rescue it either: the room does not exist until the dial lands.
 *
 * ⛔ [roomName] IS `direct_…`, WHICH IS A ROUTING DECISION RATHER THAN A LABEL. The voice agent
 * auto-dispatches into every room it does not refuse, and `request_fnc` refuses this prefix BY
 * NAME — a `call_` room here would put the AI on a human's own call, talking over them. This
 * client never constructs a room name; see [DIRECT_ROOM_PREFIX], which exists to ASSERT the
 * server's answer rather than to build one.
 *
 * ⚠️ [callId] IS THE `Call.callSid`, WHICH IS THE ROW ALREADY IN THE WORKSPACE'S CALL LOG. It is
 * written before the dial with `status: "in-progress"`, `direction: "outbound"` and
 * `summary: "direct:softphone"`. Nothing in this app updates that row: there is no hang-up route,
 * and the terminal status belongs to the SIP/webhook pipeline. See the ⚠️ on
 * `DialerViewModel.hangUp`.
 *
 * ⚠️ The token's TTL is 70 minutes — long, deliberately, because the operator is a PARTY to the
 * call rather than a supervisor dropping in, and the platform permits calls up to an hour. It
 * authorises the JOIN; an established connection is not re-checked against it.
 */
@Serializable
data class DialResponse(
    val success: Boolean = false,
    val callId: String = "",
    /** ⛔ Always `direct_…`. See the class doc. */
    val roomName: String = "",
    /** The operator's own LiveKit token, ~70 minutes. Used verbatim. */
    val token: String = "",
    /** ⛔ The TRUNK deployment's websocket URL, not the workspace's. Used verbatim. */
    val url: String = "",
)

/**
 * The prefix every direct-dial room carries.
 *
 * ⛔ FOR ASSERTION, NEVER FOR CONSTRUCTION. `MeetRoomName` exists because this client MINTS `meet_`
 * names; nothing here mints a `direct_` one, because the name embeds a server-generated call id.
 * The constant is here so a test can prove the server's answer still carries the prefix that keeps
 * the AI off the line, which is the only thing standing between a human's call and an agent that
 * joins every room it is not told to skip.
 */
const val DIRECT_ROOM_PREFIX: String = "direct_"

/**
 * `code: "subscription_inactive"`, HTTP 402.
 *
 * ⛔ MATCHED ON THE CODE, NEVER ON THE SENTENCE, for the reason `MembersRepository` states about
 * its two 409s: the server's message is written for an operator and it is free to reword it, so a
 * client that substring-matched English would lose the branch on a copy edit. What the branch buys
 * is a message that names the remedy — the plan lapsed and billing has to be fixed on the web —
 * rather than the generic "that did not work" a bare 402 would produce.
 *
 * ⚠️ Emitted by the SHARED `requireActiveSubscription` guard, so it can appear on any billable
 * route rather than only this one. Pinned by `district-dial-subscription.json`.
 */
const val CODE_SUBSCRIPTION_INACTIVE: String = "subscription_inactive"

/**
 * `code: "overage_cap_reached"`, HTTP 409.
 *
 * ⛔ NOT A BILLING FAILURE, AND WORDING IT AS ONE WOULD BE WRONG. The workspace is in good
 * standing; it chose a HARD CAP on overage and has used its included voice minutes. The remedy is a
 * deliberate policy change (switch to auto-bill, or upgrade), not "update your card" — so it is a
 * separate case from [CODE_SUBSCRIPTION_INACTIVE] rather than a second flavour of it.
 */
const val CODE_OVERAGE_CAP_REACHED: String = "overage_cap_reached"
