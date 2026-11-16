package com.distronode.districtai.core.network.testing

import com.distronode.districtai.core.model.HqProposalCancelResponse
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoiceContextResponse
import com.distronode.districtai.core.model.HqVoiceDiscardResponse
import com.distronode.districtai.core.model.HqVoiceE2ee
import com.distronode.districtai.core.model.HqVoiceEndResponse
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.model.HqVoiceSettingsResponse
import com.distronode.districtai.core.model.HqVoiceTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.HqVoiceApi
import kotlinx.coroutines.CompletableDeferred

/**
 * A settable [HqVoiceApi] for the voice repository and the voice session alike.
 *
 * ⚠️ RECORDS EVERY CALL IN ONE ORDERED LOG ([calls]), because the order is the contract: a session
 * that never went live is discarded and only then, if the discard is refused, ended.
 *
 * ⚠️ [tokenGate], when set, holds the token answer until completed, so a test can stop or replace
 * a session while its token is in flight.
 */
class FakeHqVoiceApi : HqVoiceApi {

    var tokenResult: ApiResult<HqVoiceTokenResponse> = ApiResult.Success(CREDENTIAL)
    var tokenGate: CompletableDeferred<Unit>? = null
    var contextResult: ApiResult<HqVoiceContextResponse> = ApiResult.Success(HqVoiceContextResponse(success = true))
    var settingsResult: ApiResult<HqVoiceSettingsResponse> =
        ApiResult.Success(HqVoiceSettingsResponse(success = true, settings = SETTINGS))
    var saveResult: ApiResult<HqVoiceSettingsResponse>? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var cancelResult: ApiResult<HqProposalCancelResponse> =
        ApiResult.Success(HqProposalCancelResponse(success = true))
    var endResult: ApiResult<HqVoiceEndResponse> = ApiResult.Success(HqVoiceEndResponse(success = true, ended = true))
    var discardResult: ApiResult<HqVoiceDiscardResponse> =
        ApiResult.Success(HqVoiceDiscardResponse(success = true, discarded = true))

    /** Every call, in order: `token`, `context`, `settings`, `save`, `cancel:<id>`, `end:<id>`, `discard:<id>`. */
    val calls = mutableListOf<String>()
    val tokenRequests = mutableListOf<Triple<String, String, List<HqTurn>>>()
    val savedSettings = mutableListOf<HqVoiceSettings>()
    val contextReports = mutableListOf<Pair<Int, String>>()

    override suspend fun hqVoiceToken(
        workspaceId: String,
        locale: String,
        history: List<HqTurn>,
    ): ApiResult<HqVoiceTokenResponse> {
        calls += "token"
        tokenRequests += Triple(workspaceId, locale, history)
        tokenGate?.await()
        return tokenResult
    }

    override suspend fun hqVoiceContext(
        workspaceId: String,
        roomName: String,
        version: Int,
        page: String,
    ): ApiResult<HqVoiceContextResponse> {
        calls += "context"
        contextReports += version to page
        return contextResult
    }

    override suspend fun hqVoiceSettings(workspaceId: String): ApiResult<HqVoiceSettingsResponse> {
        calls += "settings"
        return settingsResult
    }

    override suspend fun saveHqVoiceSettings(
        workspaceId: String,
        settings: HqVoiceSettings,
    ): ApiResult<HqVoiceSettingsResponse> {
        calls += "save"
        savedSettings += settings
        saveGate?.await()
        return saveResult ?: ApiResult.Success(HqVoiceSettingsResponse(success = true, settings = settings))
    }

    override suspend fun hqProposalCancel(
        workspaceId: String,
        proposalId: String,
    ): ApiResult<HqProposalCancelResponse> {
        calls += "cancel:$proposalId"
        return cancelResult
    }

    override suspend fun hqVoiceEnd(workspaceId: String, sessionId: String): ApiResult<HqVoiceEndResponse> {
        calls += "end:$sessionId"
        return endResult
    }

    override suspend fun hqVoiceDiscard(workspaceId: String, sessionId: String): ApiResult<HqVoiceDiscardResponse> {
        calls += "discard:$sessionId"
        return discardResult
    }

    companion object {
        /** A joinable credential, shaped as `district-hq-voice-token.json` is. */
        val CREDENTIAL = HqVoiceTokenResponse(
            success = true,
            token = "jwt",
            url = "wss://media.example",
            roomName = "hq_ws-1_s1",
            processingRegion = "us",
            micMode = "open",
            sessionId = "s1",
            e2ee = HqVoiceE2ee("key"),
        )

        val SETTINGS = HqVoiceSettings("Puck", "normal", "normal", "portal", "open")
    }
}
