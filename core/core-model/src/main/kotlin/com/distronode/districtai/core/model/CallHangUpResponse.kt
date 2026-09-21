package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * `POST /api/district/calls/{callId}/hangup` — end a direct softphone call's CARRIER leg, not just
 * this device's own participant.
 *
 * ⛔ WITHOUT THIS THE APP CANNOT HANG UP, AND THE FAILURE IS A BILL RATHER THAN AN ERROR. Ending a
 * call locally is `Room.disconnect()`, which removes THIS device from the room and says nothing to
 * the SIP participant; the carrier leg goes on ringing or talking to an empty room and goes on
 * being billed. Two calls ended locally in under a second can each bill roughly 90 seconds at the
 * carrier, with nothing failing, nothing logged, and the call log's own duration the only place
 * it shows.
 *
 * ⛔ IDEMPOTENT, WHICH IS THE EXACT OPPOSITE OF ITS NEIGHBOUR ONE SEGMENT AWAY. `calls/dial` may
 * never be re-sent because a re-send places a second call; this one is DESIGNED to be sent on
 * endings that have already happened, because the case it exists for is a teardown racing a dial
 * that has not come back yet. ⚠️ That is a licence to send it on paths that may already have sent
 * it, NOT a licence to add a retry.
 *
 * ⚠️ 404 IS "no such call for this workspace" AND 409 IS "not a direct softphone call". Neither is
 * a fault a screen can act on: by the time this is sent the local teardown has already happened.
 */
@Serializable
data class CallHangUpResponse(
    val success: Boolean = false,
    /**
     * ⛔ `false` IS A SUCCESS. No deployment reported the room, which means it is gone — the callee
     * hung up, the room emptied, or a previous hangup already ran. ⚠️ An UNCONFIGURED LiveKit is a
     * 503 rather than `ended: false`, because "I could not check" must never be reported as "it is
     * over".
     */
    val ended: Boolean = false,
)

/**
 * The body. ⛔ The route's zod schema requires `workspaceId` and refuses an empty string, so this
 * is non-nullable rather than merged with an ambient selection server-side.
 */
@Serializable
data class CallHangUpRequest(
    val workspaceId: String,
)
