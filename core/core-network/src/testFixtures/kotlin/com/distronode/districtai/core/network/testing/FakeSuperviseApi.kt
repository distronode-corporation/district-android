package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.CallMonitorTokenResponse
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.model.CallTakeoverResponse
import com.distronode.districtai.core.model.CallWhisperResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SuperviseApi
import kotlinx.coroutines.CompletableDeferred

/**
 * A [SuperviseApi] that records every request and answers as configured.
 *
 * ⚠️ By default every action succeeds with the fixtures' values.
 */
class FakeSuperviseApi : SuperviseApi {

    val monitors: MutableList<String> = mutableListOf()

    /** `workspaceId/callId`. */
    val takeovers: MutableList<String> = mutableListOf()

    /** `workspaceId/callId/text`. */
    val whispers: MutableList<String> = mutableListOf()

    var monitorResult: ApiResult<CallMonitorTokenResponse> =
        ApiResult.Success(CallMonitorTokenResponse(success = true, token = "monitor-jwt", url = ROOM_URL))
    var takeoverResult: ApiResult<CallTakeoverResponse> = ApiResult.Success(
        CallTakeoverResponse(success = true, url = ROOM_URL, token = "takeover-jwt", roomName = AI_ROOM),
    )
    var whisperResult: ApiResult<CallWhisperResponse> = ApiResult.Success(CallWhisperResponse(success = true))

    /** When set, a whisper waits for it before answering: a whisper still in flight. */
    var whisperGate: CompletableDeferred<Unit>? = null

    /** When set, a listen waits for it before answering. */
    var monitorGate: CompletableDeferred<Unit>? = null

    /** When set, a takeover waits for it before answering: a grant still on its way. */
    var takeoverGate: CompletableDeferred<Unit>? = null

    override suspend fun monitorCall(callId: String): ApiResult<CallMonitorTokenResponse> {
        monitors += callId
        monitorGate?.await()
        return monitorResult
    }

    override suspend fun takeOverCall(workspaceId: String, callId: String): ApiResult<CallTakeoverResponse> {
        takeovers += "$workspaceId/$callId"
        takeoverGate?.await()
        return takeoverResult
    }

    override suspend fun whisper(workspaceId: String, callId: String, text: String): ApiResult<CallWhisperResponse> {
        whispers += "$workspaceId/$callId/$text"
        whisperGate?.await()
        return whisperResult
    }

    companion object {
        /** The room's deployment, which differs from any workspace default on purpose. */
        const val ROOM_URL: String = "wss://livekit-us.example.test"
        const val AI_ROOM: String = "call_ws-1-CA0123"

        /** A live call the receptionist is on, as the call log answers it. */
        val AI_CALL: CallSummary = testCall(id = "cmaicall1", status = "in-progress")
            .copy(number = "Dana Smith", callerName = "Dana Smith", from = "+15550100")

        /** A team member's own softphone call: live, and the receptionist is not on it. */
        val TEAM_CALL: CallSummary = testCall(id = "cmteamcall1", status = "ringing").copy(
            type = "outbound",
            number = "+15550142",
            callerName = "+15550142",
            direction = "outbound",
            receptionistOnCall = false,
        )

        /** A call that is over: never on the Live calls list. */
        val ENDED_CALL: CallSummary = testCall(id = "cmdonecall1", status = "completed")
    }
}
