package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioLocation
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.VoiceStudioSttModel
import com.distronode.districtai.core.model.VoiceStudioTtsModel

/** One row of a picker: a value to hold and the words to show for it. */
data class PickerOption(
    val value: String,
    val label: String,
    /** The channel badge's text, for a model; null for a vendor or a location. */
    val channelLabel: String? = null,
    /** The Preview note, for a Preview model. */
    val note: String? = null,
) {
    /** "Gemini 3.8 Flash · Latest": the channel in TEXT, and a Preview model's note. */
    val text: String get() = listOfNotNull(label, channelLabel, note).joinToString(" · ")

    companion object {
        /** What a closed picker shows: the held option, or the held value itself when it is not listed. */
        fun subtitle(options: List<PickerOption>, selected: String?): String =
            options.firstOrNull { it.value == selected }?.text ?: selected.orEmpty()
    }
}

/**
 * What each leg's pickers offer.
 *
 * ⛔ THE WEB'S PICKER RULE, PORTED: a model is listed when it is `offered` to this account in this
 * region OR it is the one held now (a stored choice must still show as selected). Ear and mouth
 * models are filtered by the persona language (`forLanguage`), or by `forBilingual` while the
 * Studio holds bilingual on. A vendor appears when at least one of its models is listable.
 *
 * ⛔ EVERY LIST IS THE SERVER'S. Nothing here names a model.
 */
object StudioPickers {

    private const val PREVIEW = "preview"

    fun earVendors(mix: EngineMix, bilingual: Boolean, studio: VoiceStudioResponse): List<PickerOption> =
        studio.catalog.stt
            .filter { listableEar(it, mix, bilingual) }
            .distinctBy { it.provider }
            .map { PickerOption(it.provider, it.providerLabel) }

    fun earModels(mix: EngineMix, bilingual: Boolean, studio: VoiceStudioResponse): List<PickerOption> =
        studio.catalog.stt
            .filter { it.provider == mix.stt.provider && listableEar(it, mix, bilingual) }
            .map { PickerOption(it.model, it.label, it.channelLabel, previewNote(it.channel, studio)) }

    fun brainModels(mix: EngineMix, studio: VoiceStudioResponse): List<PickerOption> =
        studio.catalog.llm
            .filter { it.offered || it.model == mix.llm.model }
            .map { PickerOption(it.model, it.label, it.channelLabel, previewNote(it.channel, studio)) }

    fun voiceVendors(mix: EngineMix, bilingual: Boolean, studio: VoiceStudioResponse): List<PickerOption> =
        studio.catalog.tts
            .filter { listableMouth(it, mix, bilingual) }
            .distinctBy { it.provider }
            .map { PickerOption(it.provider, it.providerLabel) }

    fun voiceModels(mix: EngineMix, bilingual: Boolean, studio: VoiceStudioResponse): List<PickerOption> =
        studio.catalog.tts
            .filter { it.provider == mix.tts.provider && listableMouth(it, mix, bilingual) }
            .map { PickerOption(it.model, it.label, it.channelLabel, previewNote(it.channel, studio)) }

    /** The held leg's locations, empty for a vendor endpoint. */
    fun locations(leg: String, mix: EngineMix, studio: VoiceStudioResponse): List<PickerOption> {
        val catalog = studio.catalog
        val locations: List<VoiceStudioLocation> = when (leg) {
            StudioLegEdits.EAR ->
                catalog.stt
                    .firstOrNull { it.provider == mix.stt.provider && it.model == mix.stt.model }
                    ?.locations
            StudioLegEdits.BRAIN -> catalog.llm.firstOrNull { it.model == mix.llm.model }?.locations
            else -> catalog.tts.firstOrNull { it.provider == mix.tts.provider && it.model == mix.tts.model }
                ?.locations
        }.orEmpty()
        return locations.map { PickerOption(it.value, it.label) }
    }

    /**
     * The realtime models a workspace may pick: offered and not refused in its region, plus the
     * one held now.
     */
    fun realtimeModels(engine: StudioEngine.Realtime, studio: VoiceStudioResponse): List<PickerOption> =
        studio.catalog.realtime
            .filter { (it.offered && !it.refusedInRegion) || it.model == engine.modelId }
            .map { PickerOption(it.model, it.label, it.channelLabel, it.note) }

    private fun listableEar(model: VoiceStudioSttModel, mix: EngineMix, bilingual: Boolean): Boolean =
        (model.provider == mix.stt.provider && model.model == mix.stt.model) ||
            (model.offered && if (bilingual) model.forBilingual else model.forLanguage)

    private fun listableMouth(model: VoiceStudioTtsModel, mix: EngineMix, bilingual: Boolean): Boolean =
        (model.provider == mix.tts.provider && model.model == mix.tts.model) ||
            (model.offered && if (bilingual) model.forBilingual else model.forLanguage)

    private fun previewNote(channel: String, studio: VoiceStudioResponse): String? =
        studio.labels.previewNote.takeIf { channel == PREVIEW }
}
