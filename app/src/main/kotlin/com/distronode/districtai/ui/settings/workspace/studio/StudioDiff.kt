package com.distronode.districtai.ui.settings.workspace.studio

import com.distronode.districtai.core.model.EngineMix
import com.distronode.districtai.core.model.PersonaPatchRequest
import com.distronode.districtai.core.model.VoiceStudioFields
import kotlin.math.abs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What differs: between the saved fields and the held ones (what a save sends), between what was
 * sent and what was read back (whether it landed), and between two engines (how far an edit is
 * from the recipe it started from).
 */
object StudioDiff {

    /**
     * The keys of [current] that differ from [saved]: what the PATCH sends.
     *
     * ⛔ ONLY CHANGED KEYS, so a teammate's save of a key this screen never touched is not undone.
     * ⛔ `modelId` AND `engineMix` TRAVEL TOGETHER: the route reads a mix only beside the id it
     * belongs to. ⚠️ An absent bilingual flag and `false` say the same thing; only a real change
     * is sent. A key [current] does not carry (null) is never sent.
     */
    fun changedKeys(saved: VoiceStudioFields, current: VoiceStudioFields): Set<StudioKey> {
        val changed = StudioKey.entries.filter { key ->
            val now = valueOf(current, key)
            now != null && normalised(key, valueOf(saved, key)) != normalised(key, now)
        }.toMutableSet()
        if (StudioKey.MODEL_ID in changed || StudioKey.ENGINE_MIX in changed) {
            changed += StudioKey.MODEL_ID
            if (current.engineMix != null) changed += StudioKey.ENGINE_MIX
        }
        return changed
    }

    /** The PATCH body that sends [keys] of [fields]. */
    fun request(workspaceId: String, fields: VoiceStudioFields, keys: Set<StudioKey>): PersonaPatchRequest =
        PersonaPatchRequest(
            workspaceId = workspaceId,
            modelId = fields.modelId.takeIf { StudioKey.MODEL_ID in keys },
            voice = fields.voice.takeIf { StudioKey.VOICE in keys },
            engineMix = fields.engineMix.takeIf { StudioKey.ENGINE_MIX in keys },
            preemptiveTts = fields.preemptiveTts.takeIf { StudioKey.PREEMPTIVE_TTS in keys },
            temperature = fields.temperature.takeIf { StudioKey.TEMPERATURE in keys },
            bilingual = fields.bilingual.takeIf { StudioKey.BILINGUAL in keys },
            voiceStyle = fields.voiceStyle.takeIf { StudioKey.VOICE_STYLE in keys },
        )

    /**
     * Whether the re-read holds what was sent, key by key.
     *
     * ⛔ THE ONLY WAY TO SEE A SILENT REFUSAL. The PATCH answers 200 for an unknown `modelId`
     * (coerced), a wrong-typed `temperature` (ignored) and more; comparing the re-read with the
     * body is the client rule the server's spec states.
     */
    fun landed(sent: VoiceStudioFields, keys: Set<StudioKey>, reread: VoiceStudioFields): Boolean =
        keys.all { key ->
            val want = normalised(key, valueOf(sent, key))
            val got = normalised(key, valueOf(reread, key))
            if (want is Double && got is Double) abs(want - got) < NUMBER_EPSILON else want == got
        }

    private fun valueOf(fields: VoiceStudioFields, key: StudioKey): Any? = when (key) {
        StudioKey.MODEL_ID -> fields.modelId
        StudioKey.VOICE -> fields.voice
        StudioKey.ENGINE_MIX -> fields.engineMix
        StudioKey.PREEMPTIVE_TTS -> fields.preemptiveTts
        StudioKey.TEMPERATURE -> fields.temperature
        StudioKey.BILINGUAL -> fields.bilingual
        StudioKey.VOICE_STYLE -> fields.voiceStyle
    }

    private fun normalised(key: StudioKey, value: Any?): Any? =
        if (key == StudioKey.BILINGUAL) value == true else value

    /**
     * How many settings differ between two engines ("Based on Fastest, 2 changes").
     *
     * ⚠️ COUNTED OVER LEAVES, as the web counts them: a key present on one side and absent on the
     * other is a change, and a null is a value. The encoder writes a required null and omits an
     * absent-when-unset key, which is exactly the web object's shape.
     */
    fun countChanges(base: StudioEngine, current: StudioEngine): Int {
        val a = leaves(encode(base), "", mutableMapOf())
        val b = leaves(encode(current), "", mutableMapOf())
        return (a.keys + b.keys).count { a[it] != b[it] }
    }

    private val LEAF_JSON = Json {
        explicitNulls = true
        encodeDefaults = false
    }

    private fun encode(engine: StudioEngine): JsonObject = when (engine) {
        is StudioEngine.Chained -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("chained"),
                "mix" to LEAF_JSON.encodeToJsonElement(EngineMix.serializer(), engine.mix),
            ),
        )
        is StudioEngine.Realtime -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("realtime"),
                "modelId" to JsonPrimitive(engine.modelId),
                "voice" to JsonPrimitive(engine.voice),
            ),
        )
    }

    private fun leaves(value: JsonElement, path: String, out: MutableMap<String, String>): Map<String, String> {
        if (value is JsonObject) {
            value.forEach { (key, child) -> leaves(child, "$path.$key", out) }
        } else {
            out[path] = value.toString()
        }
        return out
    }

    /** ⚠️ A slider's worth of float noise: a wire round trip of a double is not bit-identical. */
    private const val NUMBER_EPSILON: Double = 0.0005
}
