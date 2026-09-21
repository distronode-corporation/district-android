package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.CallHangUpResponse

/**
 * Ending a live call at the CARRIER, which is the half a media SDK cannot do.
 *
 * ⛔ IT BELONGS ON `DialApi` AND IS HERE ONLY BECAUSE `DistrictApi.kt` IS THE ONE GUARANTEED MERGE
 * CONFLICT while several people are adding endpoints. See [ExtraPaths].
 */
interface CallControlApi {

    /**
     * Tell the SERVER to end a direct softphone call, carrier leg included.
     *
     * ⛔ WITHOUT THIS THE APP CANNOT HANG UP, AND THE FAILURE IS A BILL RATHER THAN AN ERROR.
     * Ending a call locally is `Room.disconnect()`, which removes THIS device from the room and
     * says nothing to the SIP participant; the carrier leg goes on ringing or talking to an empty
     * room and goes on being billed. A call ended locally in under a second can bill roughly 90
     * seconds at the carrier, with nothing failing and nothing logged.
     *
     * ⛔ IDEMPOTENT, WHICH IS THE EXACT OPPOSITE OF ITS NEIGHBOUR ONE SEGMENT AWAY. `calls/dial`
     * may never be re-sent because a re-send places a second call; this one is DESIGNED to be sent
     * on endings that have already happened, because the case it exists for is a teardown racing a
     * dial response that has not arrived yet. A second send answers `{success: true, ended: false}`.
     * ⚠️ That is a licence to send it on paths that may already have sent it, NOT a licence to add
     * a retry: a retry loop on a teardown path would spend requests after a call nobody is on.
     *
     * @param callId the `Call.callSid` from `DialResponse.callId`. ⛔ Not a room name and not
     *   derivable from one.
     */
    suspend fun hangUpCall(workspaceId: String, callId: String): ApiResult<CallHangUpResponse>
}
