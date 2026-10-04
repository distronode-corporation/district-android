package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioLatency
import com.distronode.districtai.core.model.VoiceStudioLlmModel
import com.distronode.districtai.core.model.VoiceStudioRealtimeModel
import com.distronode.districtai.core.model.VoiceStudioResidency
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.VoiceStudioSttModel
import com.distronode.districtai.core.model.VoiceStudioTtsModel
import com.distronode.districtai.ui.settings.workspace.studio.StudioLegFacts.MEASURED

/**
 * The readout for an engine the server has not described: an unsaved edit.
 *
 * ⛔ ASSEMBLED, NEVER ESTIMATED. Every sentence is one the same response already carries (a
 * model's residency, a location's residency, a leg's latency text); every number is a measured
 * median the server sent. A stage with no measured median is MISSING, and a missing stage makes
 * the meter "at least". This is section 4 of the server's spec, ported: the chain meter is the
 * region's end-of-turn median, plus the brain's only at the region's own location with thinking
 * off (the setting it was measured on), plus the mouth's (per voice for Deepgram, per model for
 * the rest); a realtime meter is a missing end of turn plus the model's.
 */
internal object StudioLocal {

    private const val REALTIME_LEG = "realtime"

    /** The catalogue entries a chain's three model legs name, and where each is processed. */
    class Legs(
        val ear: VoiceStudioSttModel,
        val brain: VoiceStudioLlmModel,
        val mouth: VoiceStudioTtsModel,
        val earPlace: VoiceStudioResidency,
        val brainPlace: VoiceStudioResidency,
        val mouthPlace: VoiceStudioResidency,
    )

    /** ⚠️ Null when a leg names a model the catalogue does not list, which the server never holds. */
    fun resolve(mix: EngineMix, studio: VoiceStudioResponse): Legs? {
        val catalog = studio.catalog
        val ear = catalog.stt.firstOrNull { it.provider == mix.stt.provider && it.model == mix.stt.model }
            ?: return null
        val brain = catalog.llm.firstOrNull { it.model == mix.llm.model } ?: return null
        val mouth = catalog.tts.firstOrNull { it.provider == mix.tts.provider && it.model == mix.tts.model }
            ?: return null
        return Legs(
            ear = ear,
            brain = brain,
            mouth = mouth,
            earPlace = StudioLegFacts.placed(ear.locations, mix.stt.location, ear.residency),
            brainPlace = StudioLegFacts.placed(brain.locations, mix.llm.location, brain.residency),
            mouthPlace = StudioLegFacts.placed(mouth.locations, mix.tts.location, mouth.residency),
        )
    }

    fun blocks(engine: StudioEngine, studio: VoiceStudioResponse): List<BlockView> = when (engine) {
        is StudioEngine.Chained -> resolve(engine.mix, studio)?.let { chainBlocks(engine.mix, it, studio) }.orEmpty()
        is StudioEngine.Realtime -> realtime(engine, studio)?.let { listOf(realtimeBlock(it, studio)) }.orEmpty()
    }

    fun meter(engine: StudioEngine, studio: VoiceStudioResponse): MeterView {
        val labels = studio.labels.stages
        val stages: List<Pair<String, VoiceStudioLatency?>> = when (engine) {
            is StudioEngine.Chained -> {
                val legs = resolve(engine.mix, studio)
                listOf(
                    labels.eou to studio.latency.eou?.takeIf { it.source == MEASURED },
                    labels.llmTtft to legs?.let { StudioLegFacts.brainMeasured(engine.mix, it.brain) },
                    labels.ttsTtfb to legs?.let { StudioLegFacts.mouthMeasured(engine.mix, it.mouth, studio) },
                )
            }
            is StudioEngine.Realtime -> listOf(
                labels.eou to null,
                labels.realtimeTtft to realtime(engine, studio)?.latency?.takeIf { it.source == MEASURED },
            )
        }
        val measured = stages.mapNotNull { it.second?.ms }
        val missing = measured.size < stages.size
        val headline = if (measured.isEmpty()) MeterHeadline.None else MeterHeadline.Local(measured.sum(), missing)
        // The "some steps are not measured" sentence is the server's; every meter carries the same one.
        val note = studio.recipes.map { it.timeToFirstWord.note }.plus(studio.latency.note).firstOrNull { it != null }
        return MeterView(
            headline = headline,
            note = note.takeIf { missing && measured.isNotEmpty() },
            stages = stages.map { (label, latency) -> StageView(label, StudioLegFacts.stageText(latency)) },
        )
    }

    fun residency(engine: StudioEngine, studio: VoiceStudioResponse): ResidencyView {
        val labels = studio.labels
        val legs: List<Pair<String, VoiceStudioResidency>>? = when (engine) {
            is StudioEngine.Chained -> resolve(engine.mix, studio)?.let { legs ->
                listOf(
                    labels.legs.stt to legs.earPlace,
                    labels.legs.llm to legs.brainPlace,
                    labels.legs.tts to legs.mouthPlace,
                )
            }
            is StudioEngine.Realtime -> realtime(
                engine,
                studio
            )?.let { listOf(realtimeTitle(it, studio) to it.residency) }
        }
        // ⛔ AN ENGINE NOTHING CAN DESCRIBE IS NOT CLAIMED TO STAY IN REGION.
        val out = legs?.filterNot { it.second.inRegion }?.map { (title, place) -> "$title: ${place.text}" }
            ?: return ResidencyView(inRegion = false, text = labels.leavesRegion, legsOut = emptyList())
        return ResidencyView(
            inRegion = out.isEmpty(),
            text = if (out.isEmpty()) labels.allInRegion else labels.leavesRegion,
            legsOut = out,
        )
    }

    private fun chainBlocks(mix: EngineMix, legs: Legs, studio: VoiceStudioResponse): List<BlockView> {
        val labels = studio.labels
        val roles = roles(studio)
        val ear = legs.ear
        val brain = legs.brain
        val mouth = legs.mouth
        val turn = studio.catalog.turn
        val turnBlock = if (ear.takesTurns) {
            BlockView(
                "turn", labels.legs.turn, role(roles, "turn"), turn.ear.label, ear.channel, ear.channelLabel,
                legs.earPlace.text, legs.earPlace.inRegion, null, LatencyText.of(turn.latency),
            )
        } else {
            BlockView(
                "turn", labels.legs.turn, role(roles, "turn"), turn.detector.label, StudioRecipes.STABLE,
                labels.channels.stable, turn.detector.where, null, null, LatencyText.of(turn.latency),
            )
        }
        val brainLatency = brain.latency?.takeIf { it.source != MEASURED || StudioLegFacts.brainLocal(mix, brain) }
        return listOf(
            BlockView(
                "stt", labels.legs.stt, role(roles, "stt"), "${ear.providerLabel} ${ear.label}", ear.channel,
                ear.channelLabel, legs.earPlace.text, legs.earPlace.inRegion, null, LatencyText.of(ear.latency),
            ),
            turnBlock,
            BlockView(
                "llm", labels.legs.llm, role(roles, "llm"), brain.label, brain.channel, brain.channelLabel,
                legs.brainPlace.text, legs.brainPlace.inRegion, null, LatencyText.of(brainLatency),
            ),
            BlockView(
                "tts", labels.legs.tts, role(roles, "tts"), "${mouth.providerLabel} ${mouth.label}", mouth.channel,
                mouth.channelLabel, legs.mouthPlace.text, legs.mouthPlace.inRegion, null,
                StudioLegFacts.mouthText(mix, mouth, studio),
            ),
        )
    }

    private fun realtimeBlock(model: VoiceStudioRealtimeModel, studio: VoiceStudioResponse): BlockView = BlockView(
        leg = REALTIME_LEG,
        title = realtimeTitle(model, studio),
        role = role(roles(studio), REALTIME_LEG),
        model = model.label,
        channel = model.channel,
        channelLabel = model.channelLabel,
        where = model.residency.text,
        inRegion = model.residency.inRegion,
        note = model.note,
        latency = LatencyText.of(model.latency),
    )

    private fun realtime(engine: StudioEngine.Realtime, studio: VoiceStudioResponse): VoiceStudioRealtimeModel? =
        studio.catalog.realtime.firstOrNull { it.model == engine.modelId }

    /** The realtime block's title ("All-in-one") is only in the server's blocks; else the model's name. */
    private fun realtimeTitle(model: VoiceStudioRealtimeModel, studio: VoiceStudioResponse): String =
        roles(studio)[REALTIME_LEG]?.first ?: model.label

    /** Each leg's title and role ("Speech recognition"), which only the server's blocks carry. */
    private fun roles(studio: VoiceStudioResponse): Map<String, Pair<String, String>> =
        (studio.current.chain.blocks + studio.recipes.flatMap { it.chain.blocks })
            .associate { it.leg to (it.title to it.role) }

    private fun role(roles: Map<String, Pair<String, String>>, leg: String): String = roles[leg]?.second.orEmpty()
}
