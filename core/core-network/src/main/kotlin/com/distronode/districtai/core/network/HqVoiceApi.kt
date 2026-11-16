package com.distronode.districtai.core.network

import com.distronode.districtai.core.model.HqProposalCancelResponse
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoiceContextResponse
import com.distronode.districtai.core.model.HqVoiceDiscardResponse
import com.distronode.districtai.core.model.HqVoiceEndResponse
import com.distronode.districtai.core.model.HqVoiceSettings
import com.distronode.districtai.core.model.HqVoiceSettingsResponse
import com.distronode.districtai.core.model.HqVoiceTokenResponse

/**
 * District HQ by voice: the routes the iOS, Windows and Linux apps call, named as iOS's
 * `EndpointID` names them.
 *
 * ⚠️ A SIBLING OF [HqApi], NOT A WIDENING OF IT, for the reason [DistrictApi] gives: a screen
 * depends on the slice it uses, and a fake implements only that slice. The click confirm of a
 * voice proposal is [HqApi.hqConfirm] with a `proposalId`, the same route as the text console's.
 */
interface HqVoiceApi {
    /**
     * A credential for one voice session.
     *
     * ⛔ EACH CALL STARTS A BILLED SESSION: only on the member's tap, never as a prefetch.
     * ⚠️ [history] is bounded as the service bounds it and left out when empty. The client declares
     * no `clientCaps`: it renders no screen commands, as on iOS and the desktop.
     *
     * @param locale `fr` for French, `en` otherwise (the member's voice settings may override it).
     */
    suspend fun hqVoiceToken(
        workspaceId: String,
        locale: String,
        history: List<HqTurn>,
    ): ApiResult<HqVoiceTokenResponse>

    /** What is on the member's screen, for the voice agent. Fire and forget. */
    suspend fun hqVoiceContext(
        workspaceId: String,
        roomName: String,
        version: Int,
        page: String,
    ): ApiResult<HqVoiceContextResponse>

    /** The member's own voice settings in the workspace (the defaults when none are saved). */
    suspend fun hqVoiceSettings(workspaceId: String): ApiResult<HqVoiceSettingsResponse>

    /** Saves the member's voice settings and answers them as stored. */
    suspend fun saveHqVoiceSettings(
        workspaceId: String,
        settings: HqVoiceSettings,
    ): ApiResult<HqVoiceSettingsResponse>

    /** Cancels a proposal the voice agent made, by a tap. */
    suspend fun hqProposalCancel(workspaceId: String, proposalId: String): ApiResult<HqProposalCancelResponse>

    /** Ends a running session, so only the minutes used are counted. Idempotent on the service. */
    suspend fun hqVoiceEnd(workspaceId: String, sessionId: String): ApiResult<HqVoiceEndResponse>

    /** Discards a session that never went live. ⚠️ The service discards only within 90 s of the token. */
    suspend fun hqVoiceDiscard(workspaceId: String, sessionId: String): ApiResult<HqVoiceDiscardResponse>
}
