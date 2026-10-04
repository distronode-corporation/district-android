package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.VoiceStudioBlock
import com.distronode.districtai.core.model.VoiceStudioChain
import com.distronode.districtai.core.model.VoiceStudioLatency
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.VoiceStudioStage

/**
 * A leg's number as the screen shows it.
 *
 * ⛔ THREE CASES AND NO FOURTH: the server's own sentence, a measured per-voice median the server
 * sent as a bare number, or nothing ("not measured yet"). There is no estimate anywhere.
 */
sealed interface LatencyText {

    data class Server(val text: String) : LatencyText

    data class Millis(val ms: Double) : LatencyText

    data object None : LatencyText

    companion object {
        fun of(latency: VoiceStudioLatency?): LatencyText = latency?.let { Server(it.text) } ?: None
    }
}

/** One block of the signal chain, as drawn. */
data class BlockView(
    val leg: String,
    val title: String,
    val role: String,
    val model: String,
    val channel: String,
    val channelLabel: String,
    val where: String,
    /** Null for the turn detector, which runs in the voice agent. */
    val inRegion: Boolean?,
    val note: String?,
    val latency: LatencyText,
)

/** One stage of the meter. */
data class StageView(val label: String, val value: LatencyText)

/** The meter's headline. */
sealed interface MeterHeadline {

    /** The server's own sentence ("About 970 ms"). */
    data class Server(val text: String) : MeterHeadline

    /**
     * A sum of measured medians for an engine the server has not described (an unsaved edit).
     * [atLeast] when a stage is missing.
     */
    data class Local(val ms: Double, val atLeast: Boolean) : MeterHeadline

    /** Nothing is measured. */
    data object None : MeterHeadline
}

data class MeterView(val headline: MeterHeadline, val note: String?, val stages: List<StageView>)

data class ResidencyView(val inRegion: Boolean, val text: String, val legsOut: List<String>)

/**
 * What the chain strip, the meter and the residency summary say for the engine the Studio holds.
 *
 * ⛔ THE SERVER'S OWN WORDS WHENEVER IT HAS DESCRIBED THIS ENGINE. When the held engine IS the
 * saved one, or IS a recipe's, every block, number and sentence is the server's, verbatim. Only an
 * unsaved edit the server never described is assembled here ([StudioLocal]), from the per-leg
 * catalogue the same response carries, and the server's numbers win again after the save.
 */
object StudioReadout {

    fun blocks(engine: StudioEngine, studio: VoiceStudioResponse): List<BlockView> =
        serverChain(engine, studio)?.blocks?.map(::fromServer) ?: StudioLocal.blocks(engine, studio)

    fun meter(engine: StudioEngine, studio: VoiceStudioResponse): MeterView {
        if (engine == StudioEngine.of(studio.current.chain)) {
            val meter = studio.latency
            return MeterView(MeterHeadline.Server(meter.text), meter.note, meter.stages.map(::stage))
        }
        val recipe = studio.recipes.firstOrNull { StudioEngine.of(it.chain) == engine }
            ?: return StudioLocal.meter(engine, studio)
        val meter = recipe.timeToFirstWord
        return MeterView(MeterHeadline.Server(meter.text), meter.note, meter.stages.map(::stage))
    }

    fun residency(engine: StudioEngine, studio: VoiceStudioResponse): ResidencyView =
        serverChain(engine, studio)?.residency?.let { ResidencyView(it.inRegion, it.text, it.legsOut) }
            ?: StudioLocal.residency(engine, studio)

    private fun serverChain(engine: StudioEngine, studio: VoiceStudioResponse): VoiceStudioChain? =
        studio.current.chain.takeIf { StudioEngine.of(it) == engine }
            ?: studio.recipes.map { it.chain }.firstOrNull { StudioEngine.of(it) == engine }

    private fun fromServer(block: VoiceStudioBlock): BlockView = BlockView(
        leg = block.leg,
        title = block.title,
        role = block.role,
        model = block.model,
        channel = block.channel,
        channelLabel = block.channelLabel,
        where = block.where,
        inRegion = block.inRegion,
        note = block.note,
        latency = LatencyText.of(block.latency),
    )

    private fun stage(stage: VoiceStudioStage): StageView = StageView(stage.label, LatencyText.Server(stage.text))
}
