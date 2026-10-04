package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi
import com.distronode.districtai.core.network.PersonaApi

/**
 * The Voice Studio: one read, and a save through the persona PATCH that is always followed by
 * that read.
 *
 * ⛔ THE RE-READ IS THE ONLY WAY TO KNOW WHAT A SAVE DID. The PATCH answers a bare
 * `{"success": true}` and does not echo the persona, and it answers 200 for things it silently
 * ignores (an unknown `modelId` is coerced, a wrong-typed `temperature` is dropped). So a save is
 * never reported until the Studio has been read back; comparing what came back with what was
 * sent is the caller's job (the ViewModel), because only it knows which keys it sent.
 *
 * ⚠️ NOTHING IS CACHED. Every residency sentence in the answer is a claim about one workspace's
 * region, and labels follow the reader's portal locale, which can change between two reads.
 */
class VoiceStudioRepository(
    private val personaApi: PersonaApi,
    private val api: DistrictApi,
) {

    suspend fun load(workspaceId: String): ApiResult<VoiceStudioResponse> =
        when (val result = personaApi.personaVoiceStudio(workspaceId)) {
            is ApiResult.Success -> rejectedEnvelope(STUDIO_ENVELOPE, result.value.success) ?: result
            is ApiResult.Failure -> result
        }

    /**
     * Send [request], then re-read the Studio.
     *
     * ⛔ A REFUSED CHAIN (400 `invalid_engine_mix`) WROTE NOTHING, so it is [VoiceStudioSave.NotSaved]
     * and the operator keeps their edits. A write that landed with a re-read that did not is
     * [VoiceStudioSave.SavedButStale], never a failure: told "not saved", an operator re-saves.
     */
    suspend fun save(request: PersonaPatchRequest): VoiceStudioSave {
        val written = when (val result = api.savePersona(request)) {
            is ApiResult.Success -> rejectedEnvelope(SAVE_ENVELOPE, result.value.success)
            is ApiResult.Failure -> result
        }
        if (written != null) return VoiceStudioSave.NotSaved(written)
        return when (val reread = load(request.workspaceId)) {
            is ApiResult.Success -> VoiceStudioSave.Saved(reread.value)
            is ApiResult.Failure -> VoiceStudioSave.SavedButStale(reread)
        }
    }

    private companion object {
        const val STUDIO_ENVELOPE = "VoiceStudioResponse"
        const val SAVE_ENVELOPE = "WorkspaceConfigSaveResponse"
    }
}

/** What a Studio save did. Three cases, for the reason [SaveOutcome] has three. */
sealed interface VoiceStudioSave {

    /** The write landed and the Studio was read back. */
    data class Saved(val studio: VoiceStudioResponse) : VoiceStudioSave

    /** ⛔ The write landed; only the read back failed. */
    data class SavedButStale(val failure: ApiResult.Failure) : VoiceStudioSave

    /** Nothing was written. */
    data class NotSaved(val failure: ApiResult.Failure) : VoiceStudioSave
}
