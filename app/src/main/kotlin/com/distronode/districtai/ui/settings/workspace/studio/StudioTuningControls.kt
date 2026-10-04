package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.VoiceStudioTuningKey

/**
 * One tuning control as drawn, built outside composition with its callbacks ready.
 *
 * ⚠️ SEE [StudioLegControls] FOR WHY THESE ARE PLAIN KOTLIN: the rules are tested without a UI,
 * and the composables that draw them write no lambdas of their own.
 */
sealed interface TuningControl {

    val key: VoiceStudioTuningKey

    /** A number. [onUseDefault] is null for a number that always has a value (realtime temperature). */
    class Number(
        override val key: VoiceStudioTuningKey,
        val value: Double?,
        val range: TuningRange,
        val onSlide: (Float) -> Unit,
        val onUseDefault: ((Boolean) -> Unit)?,
    ) : TuningControl

    class Choice(
        override val key: VoiceStudioTuningKey,
        val options: List<PickerOption>,
        val selected: String?,
        val onSelect: (String) -> Unit,
    ) : TuningControl

    class Flag(override val key: VoiceStudioTuningKey, val checked: Boolean, val onChange: (Boolean) -> Unit) :
        TuningControl

    /** Key terms, one per line. */
    class Lines(override val key: VoiceStudioTuningKey, val terms: List<String>, val onText: (String) -> Unit) :
        TuningControl {

        /**
         * What the box shows: the typed text while it still parses to the held list (so a trailing
         * newline or a space being typed is not eaten), else the held list (after a reset).
         */
        fun shown(typed: String): String =
            typed.takeIf { StudioTuning.parseKeyterms(it, key) == terms } ?: terms.joinToString("\n")
    }
}

/** Builds the [TuningControl] for each key a leg shows. */
object StudioTuningControls {

    private const val SLIDER = "slider"
    private const val SELECT = "select"
    private const val CHECKBOX = "checkbox"
    private const val INTERRUPTION_PREFIX = "engineMix.turn.interruption."

    /**
     * The control for [key], or null for one that cannot be drawn (a slider with no range).
     *
     * ⚠️ A CHAIN KEY IS ONLY EVER ASKED FOR ON A CHAIN: [StudioTuning.keysFor] filters by
     * [StudioTuningValues.keysOf], so the mix is always there.
     */
    fun of(key: VoiceStudioTuningKey, state: VoiceStudioUiState.Ready, actions: VoiceStudioActions): TuningControl? =
        when (key.control) {
            SLIDER -> number(key, state, actions)
            SELECT -> choice(key, state, actions)
            CHECKBOX -> TuningControl.Flag(key, mix(state).preemptiveTts) { on ->
                actions.editMix { it.copy(preemptiveTts = on) }
            }
            else -> TuningControl.Lines(key, mix(state).stt.keyterms.orEmpty()) { text ->
                val terms = StudioTuning.parseKeyterms(text, key)
                actions.editMix { it.copy(stt = it.stt.copy(keyterms = terms.ifEmpty { null })) }
            }
        }

    /** The Advanced control above which the "Interruptions" heading goes. */
    fun firstInterruption(controls: List<TuningControl>): TuningControl? =
        controls.firstOrNull { it.key.key.startsWith(INTERRUPTION_PREFIX) }

    private fun number(
        key: VoiceStudioTuningKey,
        state: VoiceStudioUiState.Ready,
        actions: VoiceStudioActions,
    ): TuningControl.Number? {
        val range = StudioTuning.range(key, state.held.engine) ?: return null
        if (key.key == StudioTuningValues.REALTIME_TEMPERATURE) {
            return TuningControl.Number(key, state.held.realtimeTemperature, range, { raw ->
                actions.updateHeld { it.copy(realtimeTemperature = StudioTuning.snap(raw.toDouble(), range)) }
            }, null)
        }
        val slot = StudioTuningValues.numberSlot(key.key) ?: return null
        val studio = state.studio
        val set = { value: Double? -> actions.editMix { StudioTuning.conform(slot.set(it, value), studio) } }
        return TuningControl.Number(
            key = key,
            value = slot.get(mix(state)),
            range = range,
            onSlide = { raw -> set(StudioTuning.snap(raw.toDouble(), range)) },
            onUseDefault = { useDefault -> set(range.start.takeUnless { useDefault }) },
        )
    }

    private fun choice(
        key: VoiceStudioTuningKey,
        state: VoiceStudioUiState.Ready,
        actions: VoiceStudioActions,
    ): TuningControl.Choice? {
        if (key.key == StudioTuningValues.VOICE_STYLE) {
            return TuningControl.Choice(key, options(key), state.held.voiceStyle) { style ->
                actions.updateHeld { it.copy(voiceStyle = style) }
            }
        }
        val slot = StudioTuningValues.choiceSlot(key.key) ?: return null
        val mix = mix(state)
        // ⚠️ THINKING'S OPTIONS ARE PER BRAIN, so they come from the catalogue, not the key.
        val options = if (key.key == StudioTuningValues.THINKING) {
            state.studio.catalog.llm.firstOrNull { it.model == mix.llm.model }?.thinking.orEmpty()
                .map { PickerOption(it.value, it.label) }
        } else {
            options(key)
        }
        return TuningControl.Choice(key, options, slot.get(mix)) { value -> actions.editMix { slot.set(it, value) } }
    }

    private fun options(key: VoiceStudioTuningKey): List<PickerOption> =
        key.options.orEmpty().map { PickerOption(it.value, it.label) }

    private fun mix(state: VoiceStudioUiState.Ready): EngineMix = (state.held.engine as StudioEngine.Chained).mix
}
