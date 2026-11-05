package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.CallMonitorTokenRequest
import com.distronode.districtai.core.model.CallMonitorTokenResponse
import com.distronode.districtai.core.model.CallTakeoverRequest
import com.distronode.districtai.core.model.CallTakeoverResponse
import com.distronode.districtai.core.model.CallWhisperRequest
import com.distronode.districtai.core.model.CallWhisperResponse

/**
 * Live supervise: listening to, whispering into and taking over one live call. The Live calls
 * list itself is the call log (`calls`), filtered on the live statuses: `calls/active` answers whole
 * database rows with no contract, so this client does not read it.
 *
 * ⛔ THE THREE ACTIONS ARE NEVER RETRIED, BY ANYTHING. Not here, not by a repository and not by the
 * transport: `DistrictHttp` builds the client with `retryOnConnectionFailure(false)`, and nothing
 * on this surface loops. A whisper is a reliable data packet published BEFORE the route answers,
 * so a 5xx or a lost response may follow a line the receptionist already acted on, and a retry
 * would say it twice. A takeover or a listen re-sent after a failure mints a second credential
 * for a customer's call. ⚠️ The one re-send [DistrictApiClient] does make is the 401 refresh,
 * and it cannot repeat any of these: the auth guard answers 401 before the handler runs, so the
 * refused attempt never reached a room (the reason `HttpDialApi` records for `calls/dial`).
 *
 * ⛔ THE NAMES MATCH district-core-swift's `EndpointID` (`monitorCall`, `takeOverCall`, `whisper`),
 * so the parity gate lines the three up across the clients.
 *
 * ⚠️ A SIBLING INTERFACE, NOT A SECTION OF [DistrictApi], for the merge reason [ExtraPaths] gives.
 */
interface SuperviseApi {

    /**
     * The listen-only credential for live call [callId] (`POST calls/token`).
     *
     * ⛔ [callId] MUST BE A `Call.id`. The same route mints a PARTICIPANT token for a `meet_` or
     * `video_` room name, and a `video_` join starts a billed avatar session, so a caller must
     * refuse an id with an underscore before calling this (the repository does).
     *
     * ⚠️ `call_ended` (409) when the call is over. Admits `viewer`.
     */
    suspend fun monitorCall(callId: String): ApiResult<CallMonitorTokenResponse>

    /**
     * The microphone-only credential that takes live call [callId] over from the AI.
     *
     * ⚠️ `call_has_no_receptionist` (409) on a team member's own call, `call_not_in_progress` (409)
     * on one that has ended. Refuses `viewer`.
     */
    suspend fun takeOverCall(workspaceId: String, callId: String): ApiResult<CallTakeoverResponse>

    /**
     * One private line to the receptionist on live call [callId].
     *
     * ⚠️ 1 to `MAX_WHISPER_CHARS` characters. `call_has_no_receptionist` (409) on a team member's
     * own call; `call_not_in_progress` (400 or 409) on one that has ended. Refuses `viewer`.
     */
    suspend fun whisper(workspaceId: String, callId: String, text: String): ApiResult<CallWhisperResponse>
}

/** [SuperviseApi] over [DistrictApiClient]. ⛔ One request per call, and no retry: see the interface. */
class HttpSuperviseApi(private val client: DistrictApiClient) : SuperviseApi {

    override suspend fun monitorCall(callId: String): ApiResult<CallMonitorTokenResponse> =
        client.send(
            method = "POST",
            segments = DistrictPaths.CALLS_TOKEN,
            serializer = CallMonitorTokenResponse.serializer(),
            body = CallMonitorTokenRequest(
                roomName = callId,
                identity = SUPERVISOR_IDENTITY,
            ).toJson(CallMonitorTokenRequest.serializer()),
        )

    override suspend fun takeOverCall(workspaceId: String, callId: String): ApiResult<CallTakeoverResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.callTakeover(callId),
            serializer = CallTakeoverResponse.serializer(),
            body = CallTakeoverRequest(workspaceId = workspaceId)
                .toJson(CallTakeoverRequest.serializer()),
        )

    override suspend fun whisper(workspaceId: String, callId: String, text: String): ApiResult<CallWhisperResponse> =
        client.send(
            method = "POST",
            segments = ExtraPaths.CALLS_WHISPER,
            serializer = CallWhisperResponse.serializer(),
            body = CallWhisperRequest(
                workspaceId = workspaceId,
                callId = callId,
                whisper = text,
            ).toJson(CallWhisperRequest.serializer()),
        )

    private companion object {
        /**
         * ⚠️ THE SERVER IGNORES THIS. `calls/token` derives a stable participant identity from the
         * session (route.ts:167-194), so the same person joining again replaces themselves; the body
         * value only has to be a non-empty string, which the route still requires.
         */
        const val SUPERVISOR_IDENTITY = "supervisor-android"
    }
}
