package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.EngineMixInterruption

/** A number inside a mix, read and written by its PATCH path. */
class NumberSlot(val get: (EngineMix) -> Double?, val set: (EngineMix, Double?) -> EngineMix)

/** A choice inside a mix, as the select's value. */
class ChoiceSlot(val get: (EngineMix) -> String, val set: (EngineMix, String) -> EngineMix)

/**
 * Every tuning key this client can read and write, by its PATCH path.
 *
 * ⛔ A TABLE OF PATHS, NOT A SWITCH, so adding a key is one line and a key the server publishes
 * that is not in this table is simply not drawn ([KNOWN]). A control that wrote nowhere would
 * report a save that changed nothing.
 *
 * ⚠️ NULL IS "USE THE DEFAULT" FOR EVERY NUMBER HERE. The absent-when-unset keys
 * (`eagerEotThreshold`, `eotTimeoutMs`, `stability`, `expressivity`) become absent again because
 * their DTO default is null and the production encoder omits nulls; the always-present ones
 * (`minDelay`, `maxDelay`, `eotThreshold`, `llm.temperature`, `speed`) are null on the wire, which
 * the server reads the same way.
 */
object StudioTuningValues {

    const val KEYTERMS: String = "engineMix.stt.keyterms"
    const val PREEMPTIVE_TTS: String = "engineMix.preemptiveTts"
    const val REALTIME_TEMPERATURE: String = "temperature"
    const val VOICE_STYLE: String = "voiceStyle"
    const val THINKING: String = "engineMix.llm.thinking"
    private const val MODE: String = "engineMix.turn.mode"
    private const val RESUME: String = "engineMix.turn.interruption.resume"

    /** `turn.mode`: automatic is the ABSENT key. */
    private const val MODE_AUTO = "auto"

    /** `interruption.resume`: `default` is null, `on` true, `off` false. */
    private const val RESUME_DEFAULT = "default"
    private const val RESUME_ON = "on"
    private const val RESUME_OFF = "off"

    private val NUMBERS: Map<String, NumberSlot> = mapOf(
        "engineMix.turn.minDelay" to NumberSlot({ it.turn.minDelay }) { m, v ->
            m.copy(turn = m.turn.copy(minDelay = v))
        },
        "engineMix.turn.maxDelay" to NumberSlot({ it.turn.maxDelay }) { m, v ->
            m.copy(turn = m.turn.copy(maxDelay = v))
        },
        "engineMix.turn.eotThreshold" to NumberSlot({ it.turn.eotThreshold }) { m, v ->
            m.copy(turn = m.turn.copy(eotThreshold = v))
        },
        "engineMix.turn.eagerEotThreshold" to NumberSlot({ it.turn.eagerEotThreshold }) { m, v ->
            m.copy(turn = m.turn.copy(eagerEotThreshold = v))
        },
        "engineMix.turn.eotTimeoutMs" to NumberSlot({ it.turn.eotTimeoutMs }) { m, v ->
            m.copy(turn = m.turn.copy(eotTimeoutMs = v))
        },
        "engineMix.turn.interruption.minDuration" to NumberSlot({ it.turn.interruption?.minDuration }) { m, v ->
            interruption(m) { it.copy(minDuration = v) }
        },
        "engineMix.turn.interruption.minWords" to NumberSlot({ it.turn.interruption?.minWords }) { m, v ->
            interruption(m) { it.copy(minWords = v) }
        },
        "engineMix.turn.interruption.falseTimeout" to NumberSlot({ it.turn.interruption?.falseTimeout }) { m, v ->
            interruption(m) { it.copy(falseTimeout = v) }
        },
        "engineMix.llm.temperature" to NumberSlot({ it.llm.temperature }) { m, v ->
            m.copy(llm = m.llm.copy(temperature = v))
        },
        "engineMix.tts.speed" to NumberSlot({ it.tts.speed }) { m, v -> m.copy(tts = m.tts.copy(speed = v)) },
        "engineMix.tts.stability" to NumberSlot({ it.tts.stability }) { m, v ->
            m.copy(tts = m.tts.copy(stability = v))
        },
        "engineMix.tts.expressivity" to NumberSlot({ it.tts.expressivity }) { m, v ->
            m.copy(tts = m.tts.copy(expressivity = v))
        },
    )

    private val CHOICES: Map<String, ChoiceSlot> = mapOf(
        MODE to ChoiceSlot({ it.turn.mode ?: MODE_AUTO }) { m, v ->
            m.copy(turn = m.turn.copy(mode = v.takeUnless { it == MODE_AUTO }))
        },
        RESUME to ChoiceSlot({ m ->
            when (m.turn.interruption?.resume) {
                null -> RESUME_DEFAULT
                true -> RESUME_ON
                false -> RESUME_OFF
            }
        }) { m, v ->
            interruption(m) { it.copy(resume = if (v == RESUME_DEFAULT) null else v == RESUME_ON) }
        },
        THINKING to ChoiceSlot({ it.llm.thinking }) { m, v -> m.copy(llm = m.llm.copy(thinking = v)) },
    )

    /** The keys of a chain: paths inside its mix. */
    private val CHAIN_KEYS: Set<String> = NUMBERS.keys + CHOICES.keys + setOf(KEYTERMS, PREEMPTIVE_TTS)

    /** The keys of a realtime engine: top-level persona keys. */
    private val REALTIME_KEYS: Set<String> = setOf(REALTIME_TEMPERATURE, VOICE_STYLE)

    /** Every key a control can be drawn for. */
    val KNOWN: Set<String> = CHAIN_KEYS + REALTIME_KEYS

    /**
     * The keys a control may be drawn for on [engine].
     *
     * ⛔ A MIX PATH IS NEVER DRAWN ON A REALTIME ENGINE, NOR A PERSONA KEY ON A CHAIN, whatever leg
     * the server files it under: a chain control on a realtime engine would have no mix to write.
     */
    fun keysOf(engine: StudioEngine): Set<String> =
        if (engine is StudioEngine.Realtime) REALTIME_KEYS else CHAIN_KEYS

    fun numberSlot(key: String): NumberSlot? = NUMBERS[key]

    fun choiceSlot(key: String): ChoiceSlot? = CHOICES[key]

    /**
     * `turn.interruption` with one field changed.
     *
     * ⚠️ ALL FOUR NULL IS NO OBJECT AT ALL, as the server stores it: an empty interruption object
     * would read back as absent and the save as failed.
     */
    private fun interruption(mix: EngineMix, edit: (EngineMixInterruption) -> EngineMixInterruption): EngineMix {
        val next = edit(mix.turn.interruption ?: EngineMixInterruption(null, null, null, null))
        val empty = listOf(next.minDuration, next.minWords, next.resume, next.falseTimeout).all { it == null }
        return mix.copy(turn = mix.turn.copy(interruption = next.takeUnless { empty }))
    }
}
