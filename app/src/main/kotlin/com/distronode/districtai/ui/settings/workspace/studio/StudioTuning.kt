package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioHonouredBy
import com.distronode.districtai.core.model.VoiceStudioResponse
import com.distronode.districtai.core.model.VoiceStudioTuningKey
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where a slider runs, where it starts when first set, and what its "use the default" box says. */
data class TuningRange(
    val min: Double,
    val max: Double,
    val step: Double?,
    val start: Double,
    val useDefaultLabel: String?,
) {
    /** A whole-number slider (words to interrupt, the end-of-turn timeout) shows no decimals. */
    val whole: Boolean get() = (step ?: 0.0) >= 1.0

    /** The stops BETWEEN the ends, as a Compose slider counts them; 0 is continuous. */
    val steps: Int get() = step?.let { ((max - min) / it).roundToInt() - 1 }?.coerceAtLeast(0) ?: 0
}

/**
 * Which tuning controls a leg shows, over what range, and keeping a mix within them.
 *
 * ⛔ A CONTROL APPEARS ONLY FOR THE LEG AND MODEL THAT HONOURS IT (`honouredBy` null, or naming
 * the held provider and model), and a key this client cannot map is not drawn at all. A value on a
 * model that does not honour it is dropped by the server WITH A 200, which the re-read would then
 * report as a failed save, so it is never offered and never kept ([conform]).
 *
 * ⚠️ THE TURN-TAKING KEYS ARE HONOURED BY THE EAR. Flux decides end of turn itself, so the
 * end-of-turn keys name ear models in `honouredBy`.
 */
object StudioTuning {

    /** The keys a leg's editor shows for the held engine, in the server's order. */
    fun keysFor(leg: String, engine: StudioEngine, studio: VoiceStudioResponse): List<VoiceStudioTuningKey> =
        studio.advanced.filter { it.leg == leg && it.key in StudioTuningValues.keysOf(engine) && honoured(it, engine) }

    fun honoured(key: VoiceStudioTuningKey, engine: StudioEngine): Boolean =
        key.honouredBy == null || entry(key, engine) != null

    /**
     * The range a slider runs over for the held model: the model's own where `honouredBy` gives
     * one, else the key's. Null when neither says, which no published slider is.
     */
    fun range(key: VoiceStudioTuningKey, engine: StudioEngine): TuningRange? {
        val own = entry(key, engine)
        val min = own?.min ?: key.min ?: return null
        val max = own?.max ?: key.max ?: return null
        return TuningRange(
            min = min,
            max = max,
            step = key.step,
            start = key.start ?: own?.default ?: key.default ?: min,
            useDefaultLabel = own?.useDefaultLabel ?: key.useDefaultLabel,
        )
    }

    /**
     * A slider's raw value moved onto its step and rounded off.
     *
     * ⚠️ A Compose slider reports a Float, so 0.8 arrives as 0.800000011920929. Sent as is, the
     * re-read would hold a different number than the screen and the save would read as failed.
     */
    fun snap(value: Double, range: TuningRange): Double {
        val stepped = range.step?.let { step -> range.min + ((value - range.min) / step).roundToLong() * step } ?: value
        return (stepped.coerceIn(range.min, range.max) * SNAP_SCALE).roundToLong() / SNAP_SCALE
    }

    /**
     * The mix with every tuning number its models do not honour dropped, every other one clamped
     * into the held model's range, and the longest wait never below the shortest (the server
     * raises it; a raised value would read back as a failed save).
     */
    fun conform(mix: EngineMix, studio: VoiceStudioResponse): EngineMix {
        var out = mix
        studio.advanced.forEach { key ->
            val slot = StudioTuningValues.numberSlot(key.key) ?: return@forEach
            val value = slot.get(out) ?: return@forEach
            val engine = StudioEngine.Chained(out)
            val kept = if (honoured(
                    key,
                    engine
                )
            ) {
                range(key, engine)?.let { value.coerceIn(it.min, it.max) } ?: value
            } else {
                null
            }
            out = slot.set(out, kept)
        }
        val shortest = out.turn.minDelay
        val longest = out.turn.maxDelay
        return if (shortest != null && longest != null && longest < shortest) {
            out.copy(turn = out.turn.copy(maxDelay = shortest))
        } else {
            out
        }
    }

    /**
     * Key terms as typed, one per line: trimmed, cut to the longest a term may be, de-duplicated,
     * and no more than the most there may be. The server refuses the WHOLE mix past either limit.
     */
    fun parseKeyterms(text: String, key: VoiceStudioTuningKey): List<String> {
        val terms = text.lines().map { line -> line.trim().let { key.maxLength?.let(it::take) ?: it } }
            .filter { it.isNotEmpty() }
            .distinct()
        return key.maxCount?.let(terms::take) ?: terms
    }

    /** The `honouredBy` row naming the held model for this key's leg. */
    private fun entry(key: VoiceStudioTuningKey, engine: StudioEngine): VoiceStudioHonouredBy? {
        val (provider, model) = when (engine) {
            is StudioEngine.Realtime -> "" to engine.modelId
            is StudioEngine.Chained -> when (key.leg) {
                StudioLegEdits.BRAIN -> "" to engine.mix.llm.model
                StudioLegEdits.MOUTH -> engine.mix.tts.provider to engine.mix.tts.model
                else -> engine.mix.stt.provider to engine.mix.stt.model
            }
        }
        return key.honouredBy?.firstOrNull { it.provider == provider && it.model == model }
    }

    private const val SNAP_SCALE: Double = 10_000.0
}
