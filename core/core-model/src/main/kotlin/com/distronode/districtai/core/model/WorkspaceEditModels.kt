package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The edit models for the two arrays a phone may REPLACE WHOLESALE: `callDirectory` and
 * `routingRules`.
 *
 * ⛔ THEY ARE BUILT ON [JsonObject] RATHER THAN ON KOTLIN DATA CLASSES, AND THAT IS FORCED BY THE
 * SERVER'S OWN SCHEMAS RATHER THAN CHOSEN FOR CONVENIENCE. Both save routes validate their rows
 * with a zod `.passthrough()` object, and both say in their own comments why:
 *
 *   - `workspace/directory` names only `name` and `phoneNumber` and adds
 *     "`.passthrough()` on the entry: only name and phoneNumber are named today, and stripping
 *     anything else would delete fields a newer client saves."
 *   - `workspace/routing-rules` names only `voice` and `model` and adds
 *     "The per-rule schema MUST stay `.passthrough()`. Only `voice` and `model` are inspected
 *     here, but a rule also carries id/field/operator/value/instruction, and zod's default `strip`
 *     would silently delete every one of them on save."
 *
 * Neither schema BOUNDS the key set, and both columns are Prisma `Json`. So the stored row is
 * whatever anyone ever wrote, and a kotlinx `@Serializable data class` would DROP every key it
 * does not declare on decode — which, through a route that writes back exactly what it receives,
 * is a silent deletion with a 200 and no error anywhere. The committed fixture is the proof rather
 * than the claim: `district-workspace-config.json` carries routing rows shaped
 * `{id, match, action, target}`, which is not the shape the web's rule builder edits at all.
 *
 * ⛔ SO THE RULE FOR EVERY EDIT HERE IS: START FROM THE STORED OBJECT AND OVERWRITE ONE KEY.
 * `Map.plus` keeps an existing key in its original position, so an untouched row re-encodes
 * BYTE-IDENTICALLY and an edited one differs in exactly the value that was edited.
 * `WorkspaceEditModelsTest` asserts the byte-identical round trip against the real fixture.
 *
 * ⚠️ TYPED ACCESSORS, NOT A TYPED MODEL. Both value classes expose the fields their editor draws
 * and nothing else; anything else in the object is carried and never read.
 */

/** ⚠️ A blank string is stored as-is server-side; only an ABSENT or non-string key reads as null. */
private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * ⛔ THE ONE MUTATION PRIMITIVE, AND IT OVERWRITES RATHER THAN REBUILDS. `this + (key to value)`
 * copies into a `LinkedHashMap` and puts — so an existing key keeps its position and every key this
 * client has never heard of survives untouched.
 */
private fun JsonObject.withString(key: String, value: String): JsonObject =
    JsonObject(this + (key to JsonPrimitive(value)))

/**
 * ⛔ NULL MEANS "DO NOT OFFER AN EDITOR", NOT "EMPTY". The column can hold anything — a string, a
 * number, an object where an array was expected — because it was a bare `Json` write until the two
 * save routes gained validation. A client that quietly dropped an element it could not model would
 * delete it on the next save, so a shape this editor cannot represent losslessly refuses to be
 * edited at all. An ABSENT or null column IS empty, though: the directory route writes
 * `callDirectory || []`, so "never configured" and "explicitly empty" store identically.
 */
private fun JsonElement?.objectRows(): List<JsonObject>? = when (this) {
    null -> emptyList()
    is JsonArray -> takeIf { array -> array.all { it is JsonObject } }?.map { it as JsonObject }
    else -> null
}

/** Which key of a directory entry an edit targets. Matched to the route's schema, not invented. */
enum class DirectoryField(val key: String) {
    NAME("name"),
    PHONE_NUMBER("phoneNumber"),
}

/**
 * One transfer target: a person the voice agent will put a LIVE CALLER through to.
 *
 * ⚠️ Both fields are `.nullish()` server-side, so a half-filled row is legal and already exists in
 * real data. The editor flags one rather than refusing it — see `DirectoryEditorState`.
 */
@JvmInline
value class DirectoryEntry(val raw: JsonObject) {

    fun value(field: DirectoryField): String = raw.stringOrNull(field.key).orEmpty()

    fun with(field: DirectoryField, value: String): DirectoryEntry =
        DirectoryEntry(raw.withString(field.key, value))

    /** ⚠️ True for a row the server would accept but the agent could not use. */
    val incomplete: Boolean
        get() = value(DirectoryField.NAME).isBlank() || value(DirectoryField.PHONE_NUMBER).isBlank()

    companion object {

        /**
         * ⚠️ EXACTLY THE TWO KEYS THE WEB FORM POSTS. `CallDirectorySettings` builds
         * `{ name, phoneNumber }` and nothing else, so a new row from this client is
         * indistinguishable from a new row made on the web.
         */
        fun newEntry(name: String, phoneNumber: String): DirectoryEntry = DirectoryEntry(
            JsonObject(
                mapOf(
                    DirectoryField.NAME.key to JsonPrimitive(name),
                    DirectoryField.PHONE_NUMBER.key to JsonPrimitive(phoneNumber),
                ),
            ),
        )
    }
}

/**
 * Which key of a routing rule an edit targets.
 *
 * ⚠️ THE SEVEN THE WEB'S STRUCTURED BUILDER EDITS, in its order. `PersonaRoutingForm` writes
 * id/field/operator/value/voice/instruction/model; a rule may carry more, and it is preserved.
 */
enum class RoutingRuleField(val key: String) {
    FIELD("field"),
    OPERATOR("operator"),
    VALUE("value"),
    VOICE("voice"),
    MODEL("model"),
    INSTRUCTION("instruction"),
}

/**
 * One dynamic-persona routing rule: which callers get which voice, model and extra instruction.
 *
 * ⛔ `voice` AND `model` ARE THE ONLY TWO THE SERVER INSPECTS, and it rejects a value outside the
 * workspace's allow-list with a **400** rather than coercing it — but ONLY when that workspace
 * restricts them (`allowedVoiceIds`/`allowedModelIds` non-empty). This client cannot see either
 * list, so it does not pre-validate: refusing locally would block a value the workspace allows.
 */
@JvmInline
value class RoutingRule(val raw: JsonObject) {

    /** ⚠️ The list key. Absent on a rule written by something other than the web builder. */
    val id: String? get() = raw.stringOrNull("id")

    fun value(field: RoutingRuleField): String = raw.stringOrNull(field.key).orEmpty()

    fun with(field: RoutingRuleField, value: String): RoutingRule =
        RoutingRule(raw.withString(field.key, value))

    companion object {

        /**
         * ⚠️ THE WEB FORM'S OWN DEFAULTS FOR A NEW RULE, key for key: `industry`/`contains`/empty
         * value, voice `Puck`, empty instruction and an empty model override. A different set here
         * would make a rule added on a phone behave differently from one added on the web.
         */
        fun newRule(id: String): RoutingRule = RoutingRule(
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive(id),
                    RoutingRuleField.FIELD.key to JsonPrimitive(DEFAULT_ROUTING_FIELD),
                    RoutingRuleField.OPERATOR.key to JsonPrimitive(DEFAULT_ROUTING_OPERATOR),
                    RoutingRuleField.VALUE.key to JsonPrimitive(""),
                    RoutingRuleField.VOICE.key to JsonPrimitive(DEFAULT_ROUTING_VOICE),
                    RoutingRuleField.INSTRUCTION.key to JsonPrimitive(""),
                    RoutingRuleField.MODEL.key to JsonPrimitive(""),
                ),
            ),
        )
    }
}

/** The web builder's `AVAILABLE_FIELDS` values, in its order. Display labels live in the UI layer. */
val ROUTING_FIELDS: List<String> = listOf(
    "industry",
    "estimatedValue",
    "callerType",
    "lineType",
    "isDecisionMaker",
    "seniority",
)

/** The web builder's `AVAILABLE_OPERATORS`. */
val ROUTING_OPERATORS: List<String> = listOf("contains", "equals")

/**
 * The web builder's `AVAILABLE_VOICES`.
 *
 * ⚠️ A DISPLAY LIST, NOT AN AUTHORITY. The server's allow-list is per workspace and is not exposed
 * to this client, so a stored voice outside this list is rendered as-is and never silently changed.
 */
val ROUTING_VOICES: List<String> = listOf("Puck", "Fenrir", "Aoede", "Charon", "Kore")

internal const val DEFAULT_ROUTING_FIELD = "industry"
internal const val DEFAULT_ROUTING_OPERATOR = "contains"
internal const val DEFAULT_ROUTING_VOICE = "Puck"

/** Decode the stored directory. ⛔ Null means "cannot be edited losslessly" — see [objectRows]. */
fun directoryEntries(raw: JsonElement?): List<DirectoryEntry>? =
    raw.objectRows()?.map(::DirectoryEntry)

/** Decode the stored rules. ⛔ Null means "cannot be edited losslessly" — see [objectRows]. */
fun routingRules(raw: JsonElement?): List<RoutingRule>? = raw.objectRows()?.map(::RoutingRule)

/**
 * The body of `PATCH /api/district/workspace/directory`.
 *
 * ⛔ WHOLESALE REPLACE, AND AN OMITTED OR EMPTY ARRAY IS A WIPE THAT ANSWERS `{success:true}`. The
 * handler writes `callDirectory: (callDirectory || [])`, so there is no "save nothing" — every
 * request here sets the stored value to exactly what it carries. That is why the field is a
 * non-nullable [JsonArray] built by [directoryPatch] from entries that were LOADED.
 */
@Serializable
data class DirectoryPatchRequest(val workspaceId: String, val callDirectory: JsonArray)

/**
 * The body of `POST /api/district/workspace/routing-rules`.
 *
 * ⛔ ALSO WHOLESALE REPLACE, with one difference worth knowing: the route requires
 * `Array.isArray(routingRules)` and answers **400 "Invalid payload"** without it, so an omitted
 * array is refused rather than treated as empty. An EMPTY array is accepted and wipes every rule.
 */
@Serializable
data class RoutingRulesRequest(val workspaceId: String, val routingRules: JsonArray)

/** ⚠️ Rebuilt from the raw objects, so nothing this client does not model is dropped on the way out. */
fun directoryPatch(workspaceId: String, entries: List<DirectoryEntry>): DirectoryPatchRequest =
    DirectoryPatchRequest(workspaceId, JsonArray(entries.map { it.raw }))

/** See [directoryPatch]. */
fun routingRulesRequest(workspaceId: String, rules: List<RoutingRule>): RoutingRulesRequest =
    RoutingRulesRequest(workspaceId, JsonArray(rules.map { it.raw }))
