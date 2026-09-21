package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `GET /api/district/workspace/config` — the read a native mutation form must hydrate from
 * BEFORE it is allowed to save.
 *
 * ⛔ WHY THIS ROUTE EXISTS AT ALL, BECAUSE IT EXPLAINS EVERY DECISION BELOW. The web settings
 * page is a server component: it reads the workspace row during render and threads it into each
 * form as props, so every form opens pre-hydrated. A phone has no SSR and no such prop, and
 * three of the save routes it must reach are WHOLESALE REPLACE rather than merge —
 * `workspace/directory` writes `callDirectory: callDirectory || []`, `workspace/routing-rules`
 * replaces the whole array, and `workspace/tools` replaces `toolConfig.allowedTools`. A form
 * that opened empty and saved would not "save nothing": it would DELETE the transfer directory
 * the voice agent routes live callers through, or the agent's tool allowlist. So a save is only
 * ever built on top of a SUCCESSFUL load of this.
 *
 * ⛔ AND THE ROLE GATE IS NARROWER THAN ITS SIBLINGS. Every other read on this surface
 * (`workspace/usage`, `workspace/list`) admits `viewer`; this one answers 403. The payload is
 * why: [WorkspaceConfig.callDirectory] is the list of staff PHONE NUMBERS the agent transfers
 * live callers to. A viewer has no mutation form to hydrate, so admitting them would hand out
 * internal numbers for no functional gain.
 */
@Serializable
data class WorkspaceConfigResponse(
    val success: Boolean = false,
    /**
     * ⚠️ NULLABLE ONLY BECAUSE EVERY FIELD IN THIS MODULE IS. The route always emits it, so a
     * null here is contract drift rather than a state — `WorkspaceConfigRepository` reports it
     * as [com.distronode.districtai.core.network.ApiResult.DecodeFailure] rather than handing a
     * form an empty config to save from, which is the exact failure this whole read prevents.
     */
    val config: WorkspaceConfig? = null,
)

/**
 * The workspace settings row, redacted.
 *
 * ⛔ THE KEY SET IS IDENTICAL WHETHER THE WORKSPACE IS CONFIGURED OR BRAND NEW. The route nulls a
 * missing optional rather than dropping the key, which is what lets this type require nothing and
 * default everything without either branch surprising a strict decoder. Both branches are pinned:
 * `district-workspace-config.json` and `district-workspace-config-sparse.json`.
 *
 * ⛔ NULL AND EMPTY ARE DIFFERENT ANSWERS HERE AND CONFLATING THEM IS DESTRUCTIVE. A null
 * [toolConfig] means "never configured", which the web reads as EVERY TOOL ON
 * (`initialData || AVAILABLE_TOOLS.map(t => t.id)`); an empty [ToolConfig.allowedTools] means
 * "explicitly none". Through a route that replaces the array wholesale, a client that read the
 * first as the second would switch every capability off for a workspace whose operator had only
 * opened the screen to look at it.
 *
 * ⚠️ FOUR OF THESE FIELDS ARE OPAQUE [JsonElement]s AND THEY STAY THAT WAY EVEN NOW THAT TWO OF
 * THEM ARE EDITABLE. `routingRules` and `callDirectory` are read through the edit models in
 * `WorkspaceEditModels.kt`, which are value classes over the RAW [kotlinx.serialization.json
 * .JsonObject] rather than typed data classes — because both save routes validate with a zod
 * `.passthrough()` and a typed model would drop every unmodelled key on the round trip, through a
 * route that writes back exactly what it receives. `messagingConfig` and `campaignSettings` have
 * no native form at all and are never written from here. Opaque means "carried, never rewritten",
 * which is the only safe state for a value whose key set nothing bounds.
 */
@Serializable
data class WorkspaceConfig(
    /** ⚠️ Null for a workspace nobody has configured. See the ⛔ on the class. */
    val aiPersona: AiPersona? = null,
    /** ⚠️ Null means NEVER CONFIGURED, which is not the same as an empty allowlist. */
    val toolConfig: ToolConfig? = null,
    /** ⛔ Replaced wholesale by `workspace/routing-rules`. Edited through [routingRules]. */
    val routingRules: JsonElement? = null,
    /** ⛔ Replaced wholesale by `workspace/directory`. Staff numbers. Edited via [directoryEntries]. */
    val callDirectory: JsonElement? = null,
    /** ⚠️ Opaque and read-only here — already narrowed server-side by `redactWorkspaceSecrets`. */
    val messagingConfig: JsonElement? = null,
    /** ⚠️ Opaque and read-only here. No native form edits campaign settings. */
    val campaignSettings: JsonElement? = null,
    /**
     * ⚠️ A COLUMN, NOT A `toolConfig` KEY, even though `workspace/tools` PATCH is what writes it.
     * Nothing in this client sends it, so it is carried for display only.
     */
    val creatorCellNumber: String? = null,
    val plan: String? = null,
    val subscriptionTier: String? = null,
    /** ⚠️ An ISO-8601 STRING. This module deliberately owns no date parsing. */
    val updatedAt: String? = null,
)

/**
 * `Workspace.aiPersona` — what the voice agent renders into every system prompt.
 *
 * ⛔ EVERY FIELD IS MODELLED, BUT ONLY FOUR ARE EDITABLE FROM THIS CLIENT, AND THE SPLIT IS THE
 * POINT. `PATCH workspace/persona` merges per field (`x !== undefined ? x : existing`), so an
 * omitted key is preserved — which means the safe native form is one that sends ONLY what the
 * operator actually changed. The fields it may change are the three free-text identity strings
 * ([name], [greeting], [personality]) plus the [dgiEnabled] consent flag. Everything else is
 * read-only on the phone, deliberately:
 *
 *   - [voice], [language], [modelId], [voiceStyle], [videoModelId] are drawn from server-side
 *     vocabularies (the pipeline registry, the Gemini Live voice list, the Tavus face gallery).
 *     Most of them COERCE rather than reject — an unrecognised `modelId` is silently rewritten to
 *     `deepgram-pipeline`, an unrecognised `voice` is stored verbatim and the agent then speaks
 *     in a voice nobody chose. A free-text field on a phone is how that happens, and it happens
 *     with a 200 and no error anywhere.
 *   - [responseLength] IS A PER-ENGINE MAP KEYED BY [modelId], and the route only writes it when
 *     BOTH arrive together and both are valid. Sending one without the other silently retunes
 *     whichever engine happened to be stored.
 *   - [temperature] is clamped to 0..1 server-side; the avatar fields belong to a form this
 *     client does not have.
 *
 * They are still MODELLED rather than dropped, because the contract fixture is decoded with
 * `ignoreUnknownKeys = false` — so "the phone leaves the engine choice alone" is checkable here
 * rather than being a claim about code nobody reads.
 *
 * ⚠️ ABSENT KEYS ARE FINE ON THE SHIPPED PARSER (`ignoreUnknownKeys = true`), and dropping an
 * unmodelled persona key on READ costs nothing precisely because the WRITE is a per-field merge:
 * a key this client never learned about is one it never sends, so the server keeps it.
 */
@Serializable
data class AiPersona(
    /** ✏️ Editable. Free text, no server-side vocabulary. */
    val name: String? = null,
    /** ✏️ Editable. Free text — the agent's opening line. */
    val greeting: String? = null,
    /** ✏️ Editable. Free text — rendered into the system prompt. */
    val personality: String? = null,
    /**
     * ✏️ Editable, from the capabilities screen. The external-lead-enrichment opt-in, which the
     * published sub-processor list promises is OFF by default.
     *
     * ⛔ ONLY AN EXPLICIT BOOLEAN CHANGES IT SERVER-SIDE — a non-boolean is IGNORED rather than
     * coerced, and still answers 200. So a client that sent a string here would see success and
     * no change, which is why this is typed and never stringified.
     *
     * ⚠️ NULLABLE, AND NULL IS NOT `false`. Null means the workspace has never answered; the UI
     * renders it as off (matching the web's `initialData?.dgiEnabled === true`) but must not send
     * `false` for it unless the operator actually toggled it.
     */
    val dgiEnabled: Boolean? = null,
    /** 🔒 Read-only here: a pipeline-registry voice id. See the ⛔ on the class. */
    val voice: String? = null,
    /** 🔒 Read-only here: engine-constrained (nova-3 carries only en-US and es-ES). */
    val language: String? = null,
    /** 🔒 Read-only here: selects the CALL BRAIN, and an unknown value coerces silently. */
    val modelId: String? = null,
    /** 🔒 Read-only here: per-ENGINE spoken-turn length, keyed by [modelId]. */
    val responseLength: Map<String, String>? = null,
    /** 🔒 Read-only here: clamped to 0..1 server-side. */
    val temperature: Double? = null,
    /** 🔒 Read-only here: capped at 100 characters server-side. */
    val voiceStyle: String? = null,
    /** 🔒 Read-only here: paid speculative synthesis. Only an explicit boolean may change it. */
    val preemptiveTts: Boolean? = null,
    /** 🔒 Read-only here: the avatar form's territory. */
    val videoEnabled: Boolean? = null,
    /** 🔒 Read-only here: "inherit", or the Gemini Live realtime id that turns vision on. */
    val videoModelId: String? = null,
    /** 🔒 Read-only here: a Tavus face id, validated against the gallery server-side. */
    val replicaId: String? = null,
    /**
     * 🔒 Read-only here, and ⛔ REGION-KEYED SERVER-SIDE. An EU workspace gets the disclosure PAL
     * (EU AI Act); an explicit value in a request body still wins, which is precisely why no
     * client of ours ever sends one.
     */
    val personaId: String? = null,
    /** 🔒 Read-only here. */
    val videoVoice: String? = null,
    /** 🔒 Read-only here. */
    val videoResolution: String? = null,
    /** 🔒 Read-only here. */
    val videoBackgroundUrl: String? = null,
    /** 🔒 Read-only here. */
    val videoRecording: Boolean? = null,
)

/**
 * `Workspace.toolConfig` — what the agent may DO on a call, and the accounts it does it through.
 *
 * ⛔ [allowedTools] IS THE ONE FIELD ON THIS WHOLE SURFACE THAT IS WRITTEN WHOLESALE. Every other
 * key here is merged per field by `PATCH workspace/tools`, so omitting it preserves it — but
 * `allowedTools` is set to exactly what arrived, and it is REQUIRED (omitting it is a 400, not a
 * no-op). That asymmetry is the whole reason this DTO exists rather than the screen reading a
 * `JsonElement`: the loaded list has to be carried back with its ORDER AND CONTENT intact, and a
 * type that could not express "absent" would destroy a fresh workspace's defaults.
 */
@Serializable
data class ToolConfig(
    /**
     * ⛔ NULL MEANS ABSENT, WHICH MEANS "EVERY TOOL IS ON", NOT "NO TOOL IS ON". The web form
     * reads it as `initialData || AVAILABLE_TOOLS.map(t => t.id)`, so a brand-new workspace has
     * every capability enabled without ever having stored a list. Reading null as an empty
     * allowlist and saving through the wholesale-replace route would switch the agent off
     * entirely for an operator who opened the screen to look at it.
     *
     * ⚠️ AND AN EMPTY LIST IS A REAL, DIFFERENT ANSWER: the operator turned everything off on
     * purpose. `List<String>?` is what keeps those two distinguishable.
     */
    val allowedTools: List<String>? = null,
    /** ⚠️ Read-only here — merged server-side, so an omitted key preserves it. */
    val calendarId: String? = null,
    /** ⚠️ Read-only here. "google" or "microsoft" server-side; free text on the wire. */
    val calendarProvider: String? = null,
    /**
     * ⚠️ Read-only here, and displayed: it is the observable prerequisite for the
     * `transfer_to_agent` capability, exactly as the web form's TOOL_PREREQUISITES states.
     */
    val supportPhoneNumber: String? = null,
    /** ⚠️ Read-only here. ⛔ Sending `""` would CLEAR it, which is why nothing here sends it. */
    val customEmailDomain: String? = null,
    /** ⚠️ Read-only here. Same clearing hazard as [customEmailDomain]. */
    val customEmailSenderName: String? = null,
)

/**
 * The body of `PATCH /api/district/workspace/persona`.
 *
 * ⛔ EVERY OPTIONAL FIELD IS OMITTED FROM THE WIRE WHEN NULL, AND THAT IS LOAD-BEARING RATHER
 * THAN COSMETIC. The client encodes bodies with `explicitNulls = false`, and the server merges
 * `x !== undefined ? x : existing` — so an omitted key is PRESERVED. Null here therefore means
 * "the operator did not touch this", and it is the only reason a phone can save a greeting
 * without overwriting the engine choice, the avatar configuration and the tuning parameters with
 * whatever defaults it happened to hold.
 *
 * ⛔ SO `null` IS NOT HOW A FIELD IS CLEARED — AN EMPTY STRING IS. `greeting = ""` is stored
 * verbatim (the route's schema is `.nullish()` and the merge is presence-based), which matches
 * the web form exactly: its state initialises to `initialData?.greeting || ""` and it posts that
 * string. A client that sent null for a cleared box would silently leave the old greeting in
 * place and report success.
 *
 * ⚠️ RATE LIMITED AT 30/MIN PER WORKSPACE. Sized for an operator tuning a form, so it does not
 * bite real use; a client that auto-saved on every keystroke would trip it.
 */
@Serializable
data class PersonaPatchRequest(
    val workspaceId: String,
    /** ⚠️ `""` clears; null omits. See the ⛔ on the class. */
    val name: String? = null,
    /** ⚠️ `""` clears; null omits. */
    val greeting: String? = null,
    /** ⚠️ `""` clears; null omits. */
    val personality: String? = null,
    /** ⚠️ Only an explicit boolean changes it server-side; null omits. */
    val dgiEnabled: Boolean? = null,
    /**
     * ⛔ THE SEVEN VOCABULARY FIELDS BELOW MUST COME FROM [PersonaOptionsResponse]. The route
     * COERCES rather than refuses: an unrecognised [modelId] is rewritten to `deepgram-pipeline`
     * and an unrecognised [voice] is stored verbatim and then replaced by the agent's own fallback
     * at synthesis time. Both answer 200, so a value typed by hand does not fail — it produces a
     * persona nobody chose, with nothing anywhere reporting the substitution.
     *
     * ⚠️ A pipeline-registry voice id, not a display name.
     */
    val voice: String? = null,
    val language: String? = null,
    /** ⛔ Coerced to `deepgram-pipeline` when unrecognised. See the ⛔ on [voice]. */
    val modelId: String? = null,
    /**
     * ⛔ `responseLength` WITHOUT `modelId` IS SILENTLY DISCARDED. The route stores the level under
     * `aiPersona.responseLength[modelId]` and refuses to guess at the stored engine, so with no
     * accepted engine id in the SAME request there is no key to write under — it writes nothing
     * and answers 200. Callers send the pair or neither.
     */
    val responseLength: String? = null,
    /** ⚠️ Clamped to `0..1` server-side. */
    val temperature: Double? = null,
    /** ⚠️ Gemini Live only; every other engine accepts, stores and ignores it. */
    val voiceStyle: String? = null,
    /** ⚠️ Every engine EXCEPT Gemini Live, which has no TTS stage to speculate ahead of. */
    val preemptiveTts: Boolean? = null,
)

/**
 * The body of `PATCH /api/district/workspace/tools`.
 *
 * ⛔ [allowedTools] IS NON-NULLABLE HERE ON PURPOSE, SO IT CANNOT BE OMITTED BY ACCIDENT. The
 * route replaces the stored array with exactly this value and rejects a body without it, so the
 * type system is where "you must have loaded before you may save" is cheapest to enforce. The
 * dangerous shape is not an omitted key — that is a 400 — it is a PRESENT-BUT-SHORT list built
 * from a form that never hydrated.
 *
 * ⛔ AND NOTHING ELSE IS SENT, DELIBERATELY. `calendarId`, `supportPhoneNumber`,
 * `customEmailDomain` and `customEmailSenderName` are all merged per field, and all four are
 * `.trim()`-ed and written when PRESENT — so sending `""` for any of them CLEARS it. Omitting
 * them is the only way to leave them alone, and this client has no form for any of them.
 */
@Serializable
data class ToolsPatchRequest(
    val workspaceId: String,
    /** ⛔ WHOLESALE REPLACE. Order and content are the contract. */
    val allowedTools: List<String>,
)

/**
 * What BOTH workspace-settings writes answer.
 *
 * ⛔ `{success:true}` AND NOTHING ELSE — NEITHER ROUTE ECHOES THE UPDATED CONFIG, which is the
 * entire reason [com.distronode.districtai.core.data.WorkspaceConfigRepository] re-reads after a
 * save instead of adopting a response body. There is no body to adopt, and a client that assumed
 * one would keep rendering its own optimistic edit as though the server had confirmed it. If
 * either route ever grows a `config` key, `district-persona-patch.json` /
 * `district-tools-patch.json` are where that shows up, and the re-read can then be dropped.
 *
 * ⚠️ ONE DTO FOR TWO ROUTES, WHICH IS ONLY SAFE WHILE THEIR SHAPES AGREE — the same trade
 * `DeviceRevokeResponse` makes. The contract test decodes both fixtures through this type, so a
 * divergence fails there rather than on a phone.
 */
@Serializable
data class WorkspaceConfigSaveResponse(
    val success: Boolean = false,
)
