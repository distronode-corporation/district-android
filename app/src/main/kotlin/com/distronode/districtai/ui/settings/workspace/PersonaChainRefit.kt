package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.VoiceStudioRepository
import com.distronode.districtai.core.data.VoiceStudioSave
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.settings.workspace.studio.StudioRefit
import com.distronode.districtai.ui.settings.workspace.studio.StudioSave
import com.distronode.districtai.ui.toFailureText

/**
 * Fitting a chain of the member's own to a new language, after the persona save that changed it
 * (district-linux's `PersonaRefit` flow, and the web form's): read the Studio for the new
 * language; when it no longer accepts the chain, move ear and voice to the nearest offered models
 * that speak it ([StudioRefit]), save, and read back to confirm.
 */
internal class PersonaChainRefit(
    private val studio: VoiceStudioRepository,
    private val workspaceId: String,
) {

    /**
     * The outcome, or null when the chain still fits and there is nothing to say. [before] is the
     * Studio's read from before the language save: the chain as it was stored.
     */
    suspend fun after(before: ApiResult<VoiceStudioResponse>): PersonaRefit? =
        when (val after = studio.load(workspaceId)) {
            is ApiResult.Failure -> PersonaRefit.Failed(after.toFailureText())
            is ApiResult.Success -> if (fits(after.value)) null else moved(before, after.value)
        }

    private suspend fun moved(before: ApiResult<VoiceStudioResponse>, after: VoiceStudioResponse): PersonaRefit {
        val from = when (before) {
            is ApiResult.Failure -> return PersonaRefit.Failed(before.toFailureText())
            is ApiResult.Success -> before.value.current.engineMix
        }
        val mix = from?.let { StudioRefit.refit(it, after) } ?: return PersonaRefit.NoFit
        val saved = studio.save(
            PersonaPatchRequest(
                workspaceId = workspaceId,
                modelId = StudioSave.CUSTOM_PIPELINE,
                voice = mix.tts.voice,
                engineMix = mix,
            ),
        )
        return when (saved) {
            is VoiceStudioSave.Saved -> if (fits(saved.studio)) PersonaRefit.Refitted else PersonaRefit.NotSeen
            is VoiceStudioSave.SavedButStale -> PersonaRefit.NotSeen
            is VoiceStudioSave.NotSaved -> PersonaRefit.Failed(saved.failure.toFailureText())
        }
    }

    /** Whether the server accepts the stored engine for the persona's language. */
    private fun fits(read: VoiceStudioResponse): Boolean =
        read.current.modelId != StudioSave.CUSTOM_PIPELINE || read.current.engineMix != null
}
