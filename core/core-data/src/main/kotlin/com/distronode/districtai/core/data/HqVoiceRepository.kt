// SPDX-FileCopyrightText: Distronode Corporation
//
// SPDX-License-Identifier: Apache-2.0

package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.HqConfirmAction
import com.distronode.districtai.core.model.HqConfirmRequest
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoiceProposal
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.model.HqVoiceTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.HqApi
import com.distronode.districtai.core.network.HqVoiceApi

/**
 * District HQ by voice: the credential, the screen report, the member's settings, the two ends of
 * a session and the two ways a voice proposal is resolved by a tap. The room itself is the app's.
 *
 * ⛔ EVERY ANSWER IS HELD TO ITS `success: true` ENVELOPE, as [HqRepository] holds the console's.
 */
class HqVoiceRepository(
    private val voice: HqVoiceApi,
    private val hq: HqApi,
) {

    /**
     * A credential for one voice session, or the service's refusal.
     *
     * ⛔ EACH CALL STARTS A BILLED SESSION: call it only on the member's tap. A credential that is
     * not [HqVoiceTokenResponse.isJoinable] is answered as a decoding failure, so no caller can join
     * a room without its key. ⚠️ The allowance and capacity refusals are an
     * [ApiResult.HttpFailure] whose `code` names them and whose message is the service's sentence.
     */
    suspend fun voiceToken(
        workspaceId: String,
        locale: String,
        history: List<HqTurn>,
    ): ApiResult<HqVoiceTokenResponse> =
        when (val result = voice.hqVoiceToken(workspaceId, locale, history)) {
            is ApiResult.Success -> rejectedEnvelope(TOKEN, result.value.success)
                ?: if (result.value.isJoinable) {
                    result
                } else {
                    ApiResult.DecodeFailure(
                        IllegalStateException("$TOKEN is not an encrypted HQ room"),
                        "$TOKEN{joinable=false}",
                    )
                }
            is ApiResult.Failure -> result
        }

    /** Reports the member's screen to the voice agent. Fire and forget: the next report replaces it. */
    suspend fun reportScreen(workspaceId: String, roomName: String, version: Int, page: String): ApiResult<Unit> =
        when (val result = voice.hqVoiceContext(workspaceId, roomName, version, page)) {
            is ApiResult.Success -> rejectedEnvelope(CONTEXT, result.value.success) ?: ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /** The member's own voice settings in the workspace. */
    suspend fun settings(workspaceId: String): ApiResult<HqVoiceSettings> =
        when (val result = voice.hqVoiceSettings(workspaceId)) {
            is ApiResult.Success ->
                rejectedEnvelope(SETTINGS, result.value.success) ?: ApiResult.Success(result.value.settings)
            is ApiResult.Failure -> result
        }

    /** Saves the member's voice settings and answers them as the service stored them. */
    suspend fun saveSettings(workspaceId: String, settings: HqVoiceSettings): ApiResult<HqVoiceSettings> =
        when (val result = voice.saveHqVoiceSettings(workspaceId, settings)) {
            is ApiResult.Success ->
                rejectedEnvelope(SETTINGS, result.value.success) ?: ApiResult.Success(result.value.settings)
            is ApiResult.Failure -> result
        }

    /**
     * Applies a voice proposal by a tap: its tool and arguments untouched, with its id, so the
     * service records the proposal as resolved.
     *
     * ⛔ THE ECHO IS CHECKED, as [HqRepository.confirm] checks it: a different tool is drift.
     */
    suspend fun confirm(workspaceId: String, proposal: HqVoiceProposal): ApiResult<HqConfirmation> {
        val request = HqConfirmRequest(
            workspaceId = workspaceId,
            confirm = HqConfirmAction(tool = proposal.tool, args = proposal.args, proposalId = proposal.proposalId),
        )
        return when (val result = hq.hqConfirm(request)) {
            is ApiResult.Success -> rejectedEnvelope(CONFIRM, result.value.success)
                ?: if (result.value.tool == proposal.tool) {
                    ApiResult.Success(HqConfirmation(result.value.tool, result.value.executed))
                } else {
                    ApiResult.DecodeFailure(
                        IllegalStateException("confirm echoed a different tool than the one proposed"),
                        "$CONFIRM{proposed=${proposal.tool},applied=${result.value.tool}}",
                    )
                }
            is ApiResult.Failure -> result
        }
    }

    /** Cancels a voice proposal by a tap. */
    suspend fun cancel(workspaceId: String, proposalId: String): ApiResult<Unit> =
        when (val result = voice.hqProposalCancel(workspaceId, proposalId)) {
            is ApiResult.Success -> rejectedEnvelope(CANCEL, result.value.success) ?: ApiResult.Success(Unit)
            is ApiResult.Failure -> result
        }

    /** Ends a running session. Answers whether this call ended it. */
    suspend fun end(workspaceId: String, sessionId: String): ApiResult<Boolean> =
        when (val result = voice.hqVoiceEnd(workspaceId, sessionId)) {
            is ApiResult.Success -> rejectedEnvelope(END, result.value.success) ?: ApiResult.Success(result.value.ended)
            is ApiResult.Failure -> result
        }

    /** Discards a session that never went live. Answers whether it was discarded. */
    suspend fun discard(workspaceId: String, sessionId: String): ApiResult<Boolean> =
        when (val result = voice.hqVoiceDiscard(workspaceId, sessionId)) {
            is ApiResult.Success ->
                rejectedEnvelope(DISCARD, result.value.success) ?: ApiResult.Success(result.value.discarded)
            is ApiResult.Failure -> result
        }

    private companion object {
        const val TOKEN = "HqVoiceTokenResponse"
        const val CONTEXT = "HqVoiceContextResponse"
        const val SETTINGS = "HqVoiceSettingsResponse"
        const val CONFIRM = "HqConfirmResponse"
        const val CANCEL = "HqProposalCancelResponse"
        const val END = "HqVoiceEndResponse"
        const val DISCARD = "HqVoiceDiscardResponse"
    }
}
