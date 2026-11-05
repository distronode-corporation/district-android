package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.ActiveCall
import com.distronode.districtai.core.model.ActiveCallsResponse
import com.distronode.districtai.core.model.CallMonitorTokenResponse
import com.distronode.districtai.core.model.CallTakeoverResponse
import com.distronode.districtai.core.model.CallWhisperResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.SuperviseApi
import kotlinx.coroutines.CompletableDeferred

/**
 * A [SuperviseApi] that records every request and answers as configured.
 *
 * ⚠️ By default every action succeeds with the fixtures' values and the list holds one live AI call
 * ([AI_CALL]) and one team member's softphone call ([TEAM_CALL]).
 */
class FakeSuperviseApi : SuperviseApi {

    val activeReads: MutableList<String> = mutableListOf()
    val monitors: MutableList<String> = mutableListOf()

    /** `workspaceId/callId`. */
    val takeovers: MutableList<String> = mutableListOf()

    /** `workspaceId/callId/text`. */
    val whispers: MutableList<String> = mutableListOf()

    var activeResult: ApiResult<ActiveCallsResponse> =
        ApiResult.Success(ActiveCallsResponse(success = true, calls = listOf(AI_CALL, TEAM_CALL)))
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

    override suspend fun activeCalls(workspaceId: String): ApiResult<ActiveCallsResponse> {
        activeReads += workspaceId
        return activeResult
    }

    override suspend fun monitorCall(callId: String): ApiResult<CallMonitorTokenResponse> {
        monitors += callId
        monitorGate?.await()
        return monitorResult
    }

    override suspend fun takeOverCall(workspaceId: String, callId: String): ApiResult<CallTakeoverResponse> {
        takeovers += "$workspaceId/$callId"
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

        val AI_CALL: ActiveCall = ActiveCall(
            id = "cmaicall1",
            from = "+15550100",
            to = "+15550199",
            callerName = "Dana Smith",
            direction = "inbound",
            status = "in-progress",
            roomName = AI_ROOM,
            createdAt = "2026-11-05T14:00:00.000Z",
        )

        val TEAM_CALL: ActiveCall = ActiveCall(
            id = "cmteamcall1",
            from = "+15550199",
            to = "+15550142",
            direction = "outbound",
            status = "in-progress",
            roomName = "direct_ws-1-CA0456",
            createdAt = "2026-11-05T14:02:00.000Z",
        )
    }
}
