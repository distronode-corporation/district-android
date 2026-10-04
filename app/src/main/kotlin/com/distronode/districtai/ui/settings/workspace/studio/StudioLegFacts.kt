package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioLatency
import com.distronode.districtai.core.model.VoiceStudioLlmModel
import com.distronode.districtai.core.model.VoiceStudioLocation
import com.distronode.districtai.core.model.VoiceStudioResidency
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.VoiceStudioTtsModel

/** The per-leg facts [StudioLocal] assembles a readout from. See the ⛔ there. */
internal object StudioLegFacts {

    const val MEASURED: String = "measured"
    private const val AUTO = "auto"
    private const val THINKING_OFF = "off"
    private const val PER_VOICE_VENDOR = "deepgram"

    fun placed(
        locations: List<VoiceStudioLocation>,
        location: String?,
        fallback: VoiceStudioResidency,
    ): VoiceStudioResidency = locations.firstOrNull { it.value == location }?.residency ?: fallback

    fun brainLocal(mix: EngineMix, brain: VoiceStudioLlmModel): Boolean {
        if (mix.llm.thinking != THINKING_OFF) return false
        if (mix.llm.location == AUTO) return true
        val auto = brain.locations.firstOrNull { it.value == AUTO }?.residency ?: return false
        val chosen = placed(brain.locations, mix.llm.location, brain.residency)
        return chosen.inRegion && chosen.processedIn == auto.processedIn
    }

    fun brainMeasured(mix: EngineMix, brain: VoiceStudioLlmModel): VoiceStudioLatency? =
        brain.latency?.takeIf { it.source == MEASURED && brainLocal(mix, brain) }

    /** The mouth's measured median: per voice for Deepgram, the model's for every other vendor. */
    fun mouthMeasured(mix: EngineMix, mouth: VoiceStudioTtsModel, studio: VoiceStudioResponse): VoiceStudioLatency? {
        val model = mouth.latency?.takeIf { it.source == MEASURED }
        if (mix.tts.provider != PER_VOICE_VENDOR || mix.tts.voice == mouth.defaultVoice) return model
        val p50 = voiceP50(mix, studio) ?: return null
        return VoiceStudioLatency(source = MEASURED, ms = p50, samples = null, text = "")
    }

    fun mouthText(mix: EngineMix, mouth: VoiceStudioTtsModel, studio: VoiceStudioResponse): LatencyText {
        if (mix.tts.provider != PER_VOICE_VENDOR || mix.tts.voice == mouth.defaultVoice) {
            return LatencyText.of(mouth.latency)
        }
        return voiceP50(mix, studio)?.let(LatencyText::Millis) ?: LatencyText.None
    }

    fun voiceP50(mix: EngineMix, studio: VoiceStudioResponse): Double? =
        StudioRecipes.ttsVoices(mix.tts.provider, mix.tts.model, studio)?.groups.orEmpty()
            .flatMap { it.options }
            .firstOrNull { it.value == mix.tts.voice }
            ?.p50

    /** A stage's words: the server's sentence where it wrote one, else the bare measured number. */
    fun stageText(latency: VoiceStudioLatency?): LatencyText = when {
        latency == null -> LatencyText.None
        latency.text.isEmpty() -> LatencyText.Millis(latency.ms)
        else -> LatencyText.Server(latency.text)
    }
}
