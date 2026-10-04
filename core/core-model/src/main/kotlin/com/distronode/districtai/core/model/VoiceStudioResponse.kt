package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/district/workspace/persona/voice-studio?workspaceId=`: everything the native Voice
 * Studio draws and everything it needs to build a save, in one read.
 *
 * ⛔ EVERY LABEL ARRIVES LOCALIZED, AND IS RENDERED VERBATIM. The server answers in the reader's
 * PORTAL locale ([locale]), which is the documented exception to "native apps are English": a
 * French-preference user sees French Studio labels even where the app's own chrome is English.
 * Nothing in this client translates, shortens or re-derives one of them. Wire VALUES (ids, keys,
 * channels, stages, leg names) never localize.
 *
 * ⛔ EVERY OBJECT OF ONE KIND CARRIES THE SAME KEYS, and a value that does not apply is `null`
 * rather than absent, so these types have no defaults except where the server documents an
 * ABSENT key: the `?` keys of [EngineMix] (see there).
 *
 * ⛔ NO NUMBER HERE IS A CONSTANT. The fixture's latencies come from the server's TEST latency
 * table; a value copied from it into Kotlin would be a figure nobody measured.
 *
 * ⚠️ REGION-KEYED BY THE WORKSPACE. [region] is the workspace's own, and every residency sentence
 * is a claim about it, so one workspace's answer is never cached or shown for another.
 */
@Serializable
data class VoiceStudioResponse(
    val success: Boolean,
    val region: String,
    val locale: String,
    /** The persona language, `"en-US"` when unset. */
    val language: String,
    /** Whether Preview-channel models are offered here (false in `eu`). */
    val previewAllowed: Boolean,
    val labels: VoiceStudioLabels,
    val current: VoiceStudioCurrent,
    /** The CURRENT engine's time-to-first-word meter, plus what a client needs to recompute one. */
    val latency: VoiceStudioCurrentMeter,
    /** Tile order. */
    val recipeIds: List<String>,
    /** Both tiers, stable first. A recipe that cannot be honoured here is simply missing. */
    val recipes: List<VoiceStudioRecipe>,
    val catalog: VoiceStudioCatalog,
    val voices: List<VoiceStudioVoiceList>,
    val advanced: List<VoiceStudioTuningKey>,
)

/** The Studio's own copy, in the portal locale. */
@Serializable
data class VoiceStudioLabels(
    val heading: String,
    val description: String,
    val tierLabel: String,
    val tierStable: String,
    val tierLatest: String,
    val tierDescription: String,
    val recipesLabel: String,
    val defaultBadge: String,
    val reset: String,
    val chainLabel: String,
    val editLeg: String,
    val edit: String,
    val meterHeading: String,
    val meterDescription: String,
    val residencyHeading: String,
    /** This region's "Every part of this call stays in ..." sentence. */
    val allInRegion: String,
    val leavesRegion: String,
    val providerLabel: String,
    val modelLabel: String,
    val locationLabel: String,
    val voiceLabel: String,
    val voicePlaceholder: String,
    val listen: String,
    val stopListening: String,
    val advanced: String,
    val interruptions: String,
    val notMeasured: String,
    val previewNote: String,
    val save: String,
    val saved: String,
    val saveFailed: String,
    val unsaved: String,
    val allSaved: String,
    val legs: VoiceStudioLegLabels,
    val stages: VoiceStudioStageLabels,
    val channels: VoiceStudioChannelLabels,
    // Templates and one value for text the client builds itself (the unsaved-edit meter and the
    // "Based on" line). Each placeholder is replaced once, as literal text; the unit and its
    // no-break space are already in the template.
    /** `About {ms} ms`: the unsaved meter when every stage is measured. */
    val meterAbout: String,
    /** `At least {ms} ms`: the unsaved meter when some stage is not measured. */
    val meterAtLeast: String,
    /** The unsaved meter when nothing is measured. */
    val meterNone: String,
    /** Shown under [meterAtLeast]. */
    val meterPartial: String,
    /** A VALUE, not a template: inserted between 3-digit groups of a whole-ms total. */
    val numberGrouping: String,
    /** `Based on {recipe}, 1 change.` */
    val basedOnOne: String,
    /** `Based on {recipe}, {n} changes.` */
    val basedOnMany: String,
)

@Serializable
data class VoiceStudioLegLabels(
    val stt: String,
    val turn: String,
    val llm: String,
    val tts: String,
)

@Serializable
data class VoiceStudioStageLabels(
    val eou: String,
    @SerialName("llm_ttft") val llmTtft: String,
    @SerialName("tts_ttfb") val ttsTtfb: String,
    @SerialName("realtime_ttft") val realtimeTtft: String,
)

@Serializable
data class VoiceStudioChannelLabels(
    val stable: String,
    val latest: String,
    val preview: String,
    val legacy: String,
)

/**
 * One leg's number: a measured median or a lab probe.
 *
 * ⛔ NULLABLE AT EVERY USE, AND NULL MEANS "NOT MEASURED YET". A client shows
 * [VoiceStudioLabels.notMeasured] and never estimates.
 */
@Serializable
data class VoiceStudioLatency(
    /** `"measured"` or `"lab"`. Only a measured number counts towards a meter. */
    val source: String,
    val ms: Double,
    /** Calls behind a measured median; null for a lab probe. */
    val samples: Int?,
    /** Already formatted for the locale ("Median 420 ms over 268 calls"). */
    val text: String,
)

/** Where one leg, location or recipe is processed. */
@Serializable
data class VoiceStudioResidency(
    /** A closed label: `US`, `Canada`, `EU`, `APAC` or `Global (Google)`. */
    val processedIn: String,
    val inRegion: Boolean,
    /** "Processed in Canada" / "Leaves your region: United States". */
    val text: String,
    val vendorsOutOfRegion: List<String>,
)

/** One stage of a time-to-first-word reading. */
@Serializable
data class VoiceStudioStage(
    /** `eou`, `llm_ttft`, `tts_ttfb` or `realtime_ttft`. */
    val stage: String,
    val label: String,
    val ms: Double?,
    val samples: Int?,
    /** The measured sentence, or [VoiceStudioLabels.notMeasured]. */
    val text: String,
)

/** A time-to-first-word meter for a recipe. */
@Serializable
data class VoiceStudioMeter(
    /** The sum of the measured medians; null when nothing is measured. */
    val ms: Double?,
    /** A stage is missing, so the headline says "at least". */
    val atLeast: Boolean,
    /** "About 970 ms" / "At least 300 ms" / "Not measured yet". */
    val text: String,
    /** The "some steps are not measured" sentence, when [atLeast] and [ms] is set. */
    val note: String?,
    /** Call order: a chain's eou, llm_ttft, tts_ttfb; a realtime engine's eou, realtime_ttft. */
    val stages: List<VoiceStudioStage>,
)

/**
 * The CURRENT engine's meter, which carries what a client needs to recompute one after an edit.
 *
 * ⚠️ A SEPARATE TYPE FROM [VoiceStudioMeter], NOT ONE WITH OPTIONAL KEYS. The server sends these
 * four keys on the top-level meter and never on a recipe's, and a decoder that accepted either
 * shape for both could not tell a recipe meter that lost them from one that never had them.
 */
@Serializable
data class VoiceStudioCurrentMeter(
    val ms: Double?,
    val atLeast: Boolean,
    val text: String,
    val note: String?,
    val stages: List<VoiceStudioStage>,
    /** The region's end-of-turn median, the first stage of every chain. */
    val eou: VoiceStudioLatency?,
    /** "Measured on live calls over 30 days, to Oct 3, 2026. ..." */
    val sourceText: String,
    /** `YYYY-MM-DD`. */
    val measuredThrough: String,
    val measuredDays: Int,
)

/**
 * A persona PATCH body for the Studio's keys, as the server computes it. Null is "do not send".
 *
 * ⚠️ `current.fields` IS THE BASELINE AN EDIT IS DIFFED AGAINST, and after every save the client
 * re-reads it and compares it with what it sent: the PATCH answers 200 for several things it
 * silently ignores, and this comparison is the only way to see them.
 */
@Serializable
data class VoiceStudioFields(
    val modelId: String,
    val voice: String,
    /** Only with `modelId` `custom-pipeline`. */
    val engineMix: EngineMix?,
    /** Chains only. */
    val preemptiveTts: Boolean?,
    /** Realtime only, 0-1. */
    val temperature: Double?,
    /** Only on `custom-pipeline` / `gemini-3.8-live` with an English or French persona. */
    val bilingual: Boolean?,
    /** `gemini-live-2.5-flash-native-audio` only, once chosen. */
    val voiceStyle: String?,
)

/** One block of the signal chain: Ear, Turn-taking, Brain, Voice, or one realtime block. */
@Serializable
data class VoiceStudioBlock(
    /** `stt`, `turn`, `llm`, `tts` or `realtime`. */
    val leg: String,
    val title: String,
    val role: String,
    /** The model's display name. */
    val model: String,
    val channel: String,
    val channelLabel: String,
    /** The residency sentence. */
    val where: String,
    /** Null for the turn detector, which runs in the voice agent. */
    val inRegion: Boolean?,
    /** The Preview note on a Preview realtime model. */
    val note: String?,
    val latency: VoiceStudioLatency?,
)

/** The whole call's residency for one engine. */
@Serializable
data class VoiceStudioChainResidency(
    val inRegion: Boolean,
    val text: String,
    /** One sentence per leg that leaves the region. */
    val legsOut: List<String>,
)

/** A resolved engine: a chain of legs, or one realtime model. */
@Serializable
data class VoiceStudioChain(
    /** `chained` or `realtime`. */
    val kind: String,
    /** Set when [kind] is `chained`. */
    val engineMix: EngineMix?,
    /** Set when [kind] is `realtime`. */
    val realtimeModelId: String?,
    val voice: String,
    /** Four for a chain, one for a realtime engine. */
    val blocks: List<VoiceStudioBlock>,
    val residency: VoiceStudioChainResidency,
)

/** What the persona holds today, as the Studio holds it. */
@Serializable
data class VoiceStudioCurrent(
    /** The stored `aiPersona.modelId`; null when never set. */
    val modelId: String?,
    /** The stored mix, sanitized as the PATCH would accept it, else null. */
    val engineMix: EngineMix?,
    val preemptiveTts: Boolean,
    /** The realtime temperature as the Studio holds it, 0-1. */
    val temperature: Double,
    val bilingual: Boolean,
    val voiceStyle: String?,
    /** The tile the saved engine matches, `custom` when none. */
    val recipeId: String,
    /** `stable` or `latest`. */
    val tier: String,
    val fields: VoiceStudioFields,
    val chain: VoiceStudioChain,
)

/** One recipe tile. */
@Serializable
data class VoiceStudioRecipe(
    /** `in-region`, `fastest`, `natural`, `bilingual`, `realtime` or `custom`. */
    val id: String,
    val tier: String,
    val name: String,
    val description: String,
    /** The region's default tile. */
    val isDefault: Boolean,
    val channel: String,
    val channelLabel: String,
    val note: String?,
    val bilingual: Boolean,
    /** The whole call, by its weakest leg. */
    val residency: VoiceStudioResidency,
    val timeToFirstWord: VoiceStudioMeter,
    /** The engine the tile applies. */
    val chain: VoiceStudioChain,
    /** The PATCH body that applies it, computed against the SAVED engine's voice. */
    val save: VoiceStudioFields,
)

@Serializable
data class VoiceStudioPreset(
    val modelId: String,
    val engineMix: EngineMix,
)

@Serializable
data class VoiceStudioLocation(
    val value: String,
    val label: String,
    val residency: VoiceStudioResidency,
)

@Serializable
data class VoiceStudioOption(
    val value: String,
    val label: String,
)

@Serializable
data class VoiceStudioSttModel(
    val provider: String,
    val providerLabel: String,
    val model: String,
    val label: String,
    val channel: String,
    val channelLabel: String,
    /** Vendor readiness. */
    val available: Boolean,
    /** Shown in the picker to THIS account in THIS region. */
    val offered: Boolean,
    /** Transcribes the persona language. */
    val forLanguage: Boolean,
    /** Proven on both English and French: pick from these when bilingual is on. */
    val forBilingual: Boolean,
    /** The ear decides end of turn (Flux). */
    val takesTurns: Boolean,
    /** Accepts `engineMix.stt.keyterms`. */
    val keyterms: Boolean,
    val defaultLocation: String?,
    /** Empty for a vendor endpoint. */
    val locations: List<VoiceStudioLocation>,
    /** At [defaultLocation]. */
    val residency: VoiceStudioResidency,
    val latency: VoiceStudioLatency?,
)

@Serializable
data class VoiceStudioTurnDetector(
    val label: String,
    val where: String,
)

@Serializable
data class VoiceStudioTurnEar(
    val label: String,
)

@Serializable
data class VoiceStudioTurnCatalog(
    /** Every ear but Flux. */
    val detector: VoiceStudioTurnDetector,
    /** Flux's own end of turn. */
    val ear: VoiceStudioTurnEar,
    /** The region's end-of-turn median, shown on Turn-taking for every ear. */
    val latency: VoiceStudioLatency?,
)

@Serializable
data class VoiceStudioLlmModel(
    val model: String,
    val label: String,
    val channel: String,
    val channelLabel: String,
    val available: Boolean,
    val offered: Boolean,
    /** `auto` where the region serves the model, else its first location. */
    val defaultLocation: String,
    val locations: List<VoiceStudioLocation>,
    val thinking: List<VoiceStudioOption>,
    val defaultThinking: String,
    val temperatureMin: Double,
    val temperatureMax: Double,
    /** At [defaultLocation] and [defaultThinking]. */
    val residency: VoiceStudioResidency,
    val latency: VoiceStudioLatency?,
)

@Serializable
data class VoiceStudioTtsModel(
    val provider: String,
    val providerLabel: String,
    val model: String,
    val label: String,
    val channel: String,
    val channelLabel: String,
    val available: Boolean,
    val offered: Boolean,
    val forLanguage: Boolean,
    val forBilingual: Boolean,
    /** The voice a switch to this model starts on, for the persona language. */
    val defaultVoice: String,
    val defaultLocation: String?,
    val locations: List<VoiceStudioLocation>,
    val residency: VoiceStudioResidency,
    /** At [defaultVoice]. */
    val latency: VoiceStudioLatency?,
)

@Serializable
data class VoiceStudioRealtimeModel(
    val model: String,
    val label: String,
    val channel: String,
    val channelLabel: String,
    val available: Boolean,
    /** Pickable here. */
    val offered: Boolean,
    /** Refused in this region (`gemini-3.8-live` in `eu`). */
    val refusedInRegion: Boolean,
    val note: String?,
    val residency: VoiceStudioResidency,
    val latency: VoiceStudioLatency?,
)

@Serializable
data class VoiceStudioCatalog(
    /** The fixed chained engines, as chains. Order matters: the first equal preset wins. */
    val presets: List<VoiceStudioPreset>,
    val stt: List<VoiceStudioSttModel>,
    val turn: VoiceStudioTurnCatalog,
    val llm: List<VoiceStudioLlmModel>,
    val tts: List<VoiceStudioTtsModel>,
    val realtime: List<VoiceStudioRealtimeModel>,
)

@Serializable
data class VoiceStudioVoiceOption(
    /** The voice id to save. */
    val value: String,
    val label: String,
    /** A site-relative path of a pre-rendered sample. */
    val clip: String?,
    /** Measured time to first audio for this voice in this region, ms. */
    val p50: Double?,
)

@Serializable
data class VoiceStudioVoiceGroup(
    /** A localized heading; `""` means no heading. */
    val label: String,
    val options: List<VoiceStudioVoiceOption>,
)

/** One mouth's (or one realtime model's) voices. */
@Serializable
data class VoiceStudioVoiceList(
    /** `tts` or `realtime`. */
    val kind: String,
    /** `""` for realtime. */
    val provider: String,
    val model: String,
    val groups: List<VoiceStudioVoiceGroup>,
)

/** A model that honours a tuning key, with that model's own range. */
@Serializable
data class VoiceStudioHonouredBy(
    val provider: String,
    val model: String,
    val min: Double?,
    val max: Double?,
    val default: Double?,
    val useDefaultLabel: String?,
)

/**
 * One tuning control.
 *
 * ⛔ [key] IS A PATH INTO THE PERSONA PATCH, NOT A LABEL. Keys this client does not know are not
 * drawn: a control that wrote to a path nobody mapped would save nothing and say it had.
 */
@Serializable
data class VoiceStudioTuningKey(
    val key: String,
    /** `stt`, `turn`, `llm`, `tts` or `realtime`. */
    val leg: String,
    /** `main` (on the leg editor) or `advanced` (behind the Advanced section). */
    val section: String,
    /** `slider`, `select`, `checkbox` or `lines`. */
    val control: String,
    val label: String,
    val description: String?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    /** Where the slider starts when first set. */
    val start: Double?,
    /** The value in force while unset; null is the model's own. */
    val default: Double?,
    val useDefaultLabel: String?,
    /** Null (or absent) means the default. */
    val nullable: Boolean,
    val options: List<VoiceStudioOption>?,
    val maxCount: Int?,
    val maxLength: Int?,
    /** Null: every engine of that leg. */
    val honouredBy: List<VoiceStudioHonouredBy>?,
)

/**
 * `aiPersona.engineMix` version 1, exactly as the server's catalogue defines it.
 *
 * ⛔ THE `= null` PROPERTIES ARE THE SERVER'S ABSENT-WHEN-UNSET KEYS. A mix the server emits
 * never carries them as `null`, it leaves them out, so they must default or decoding a stored mix
 * fails. Every other property is always present.
 *
 * ⚠️ ON THE WIRE (a PATCH body) the production encoder omits every null, the always-present ones
 * included. That is accepted: the server reads an absent key in a mix exactly as `null`.
 */
@Serializable
data class EngineMix(
    val v: Int,
    val stt: EngineMixStt,
    val llm: EngineMixLlm,
    val tts: EngineMixTts,
    val turn: EngineMixTurn,
    val preemptiveTts: Boolean,
    val userAwayTimeout: Double? = null,
)

@Serializable
data class EngineMixStt(
    val provider: String,
    val model: String,
    val language: String?,
    val location: String?,
    val keyterms: List<String>? = null,
)

@Serializable
data class EngineMixLlm(
    val model: String,
    /** `auto` or a Vertex location. */
    val location: String,
    val thinking: String,
    val temperature: Double?,
)

@Serializable
data class EngineMixTts(
    val provider: String,
    val model: String,
    val voice: String,
    val speed: Double?,
    val location: String?,
    val stability: Double? = null,
    val expressivity: Double? = null,
)

@Serializable
data class EngineMixTurn(
    val minDelay: Double?,
    val maxDelay: Double?,
    val eotThreshold: Double?,
    /** `dynamic` or `fixed`; absent is automatic. */
    val mode: String? = null,
    val eagerEotThreshold: Double? = null,
    val eotTimeoutMs: Double? = null,
    val interruption: EngineMixInterruption? = null,
)

@Serializable
data class EngineMixInterruption(
    val minDuration: Double?,
    val minWords: Double?,
    val resume: Boolean?,
    val falseTimeout: Double?,
)
