package com.distronode.districtai.ui.contacts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One renderable row of a DGI dossier.
 *
 * ⛔ THE DOSSIER HAS NO SCHEMA AND CANNOT BE GIVEN ONE. `Contact.intelligence` is a Prisma `Json?`
 * column written by a model whose prompt changes; the keys it carries today are not the keys it
 * carried last month, and pinning a data class here would break the screen on every prompt
 * revision — silently, since a decode failure on a nullable field just yields null. So the shape
 * is walked at render time instead, and this is the intermediate the walk produces.
 *
 * ⛔ AND IT MUST NEVER THROW. This is the one place in the app rendering data no gate validates:
 * the contract fixtures pin the CONTAINER (`intelligence` is an object) and say nothing about
 * what is inside it. Every branch below therefore has a fallback, and the fallback is always
 * "show the raw JSON" rather than "show nothing" — an operator who can see an unfamiliar shape
 * can report it, whereas a silently dropped field looks like the enrichment found nothing.
 *
 * @param value the scalar, or a joined list. Null when this field is a heading for [children].
 * @param children exactly ONE level of nesting, as [DossierEntry] rows, which always carry a value
 *   and never children of their own. See [dossierFields] for why it stops there.
 * @param raw true when [value] is formatted JSON rather than prose, so the renderer can pick a
 *   monospace-ish presentation and the reader can tell "this is a shape we did not recognise"
 *   from "this is what the model wrote".
 */
internal data class DossierField(
    val label: String,
    val value: String? = null,
    val children: List<DossierEntry> = emptyList(),
    val raw: Boolean = false,
)

/**
 * One row under a dossier heading, or a top-level list before it becomes a [DossierField].
 *
 * ⚠️ ITS OWN TYPE SO THE DEPTH CAP IS IN THE TYPES: an entry always has a value and can have no
 * children, so a renderer never has to handle a heading one level down.
 */
internal data class DossierEntry(val label: String, val value: String, val raw: Boolean = false)

/**
 * Flatten a dossier blob into rows.
 *
 * ⛔ ONE LEVEL DEEP, DELIBERATELY, AND THE SECOND LEVEL DEGRADES TO RAW JSON RATHER THAN
 * RECURSING. Unbounded recursion over a model-authored object is unbounded UI: a deeply nested
 * blob would render as an accordion of nested cards that nobody can read and that no test can
 * pin. Depth two is where a human stops finding the nesting informative, and the raw fallback
 * keeps the data visible without pretending to structure it.
 *
 * ⚠️ NULLS AND EMPTIES ARE DROPPED. A key whose value is JSON null, an empty array or an empty
 * object carries no information, and a row reading "Objections: —" is worse than its absence.
 *
 * ⚠️ ORDER IS THE OBJECT'S OWN. kotlinx.serialization preserves insertion order for a decoded
 * JsonObject, so the dossier reads in the order the model wrote it — which is the only ordering
 * available, since nothing here knows which keys matter.
 */
internal fun dossierFields(source: JsonObject?): List<DossierField> {
    if (source == null) return emptyList()
    return source.entries.mapNotNull { (key, element) -> topLevelField(key, element) }
}

private fun topLevelField(key: String, element: JsonElement): DossierField? {
    val label = humanizeKey(key)
    return when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.contentOrNullIfBlank()?.let { DossierField(label, it) }
        is JsonArray -> arrayField(label, element)?.let { DossierField(it.label, it.value, raw = it.raw) }
        is JsonObject -> objectField(label, element)
    }
}

/**
 * ⚠️ AN ARRAY OF STRINGS IS A LIST; ANYTHING ELSE IS A SHAPE. The dossier's arrays are usually
 * `["wants thursday", "budget confirmed"]`, which reads as bullet-ish prose. An array of objects
 * is not something this renderer can flatten honestly, so it degrades to raw JSON instead of, for
 * instance, showing `[object Object]`-style noise.
 */
private fun arrayField(label: String, element: JsonArray): DossierEntry? {
    if (element.isEmpty()) return null
    // ⚠️ `filterIsInstance` rather than a nullable map, so the all-scalar branch holds primitives by
    // type instead of re-checking for a null that the size comparison has already ruled out.
    val scalars = element.filterIsInstance<JsonPrimitive>()
    return if (scalars.size == element.size) {
        val values = scalars.mapNotNull { it.contentOrNullIfBlank() }
        values.takeIf { it.isNotEmpty() }?.let { DossierEntry(label, it.joinToString(LIST_JOIN)) }
    } else {
        DossierEntry(label, prettyJson(element), raw = true)
    }
}

/**
 * ⚠️ A NESTED OBJECT BECOMES A HEADING PLUS SCALAR CHILDREN. A child that is ITSELF a container
 * is not descended into — it becomes a raw-JSON child — which is the depth cap this function
 * enforces. An object whose every value is empty produces no children and is dropped whole,
 * rather than leaving an empty heading.
 */
private fun objectField(label: String, element: JsonObject): DossierField? {
    if (element.isEmpty()) return null
    val children = element.entries.mapNotNull { (key, child) -> childField(key, child) }
    return children.takeIf { it.isNotEmpty() }?.let { DossierField(label, children = it) }
}

private fun childField(key: String, element: JsonElement): DossierEntry? {
    val label = humanizeKey(key)
    return when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.contentOrNullIfBlank()?.let { DossierEntry(label, it) }
        is JsonArray -> arrayField(label, element)
        // ⛔ THE DEPTH CAP. A grandchild object is shown as JSON, never as a third card level.
        is JsonObject -> element.takeIf { it.isNotEmpty() }
            ?.let { DossierEntry(label, prettyJson(it), raw = true) }
    }
}

/**
 * ⚠️ `content`, NOT `toString()`. `JsonPrimitive.toString()` re-serialises a string WITH its
 * quotes, so every value in the dossier would render wrapped in `"`. Numbers and booleans are
 * unaffected, which is exactly what makes the bug easy to miss in a spot check.
 */
private fun JsonPrimitive.contentOrNullIfBlank(): String? = content.takeIf { it.isNotBlank() }

/**
 * "executiveSummary" -> "Executive summary".
 *
 * ⚠️ SPLITS ON camelCase AND ON snake_case, because the dossier has carried both. A key that
 * matches neither is returned with only its first letter capitalised rather than mangled — the
 * point is legibility, not a guarantee of prose.
 */
private fun humanizeKey(key: String): String {
    val spaced = key
        .replace('_', ' ')
        .replace(CAMEL_BOUNDARY) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        .trim()
    if (spaced.isEmpty()) return key
    // ⚠️ The first character by `substring`, not `replaceFirstChar`: the emptiness check is already
    // made above, and the inline one inside `replaceFirstChar` was a branch nothing could take.
    val first = spaced.substring(0, 1).uppercase() + spaced.substring(1)
    // Lowercase the remaining words so "Executive Summary" reads as a label, not a title.
    return first.first() + first.drop(1).lowercase()
}

/**
 * ⚠️ Pretty-printed on purpose: the raw fallback exists to be READ by a person deciding whether
 * an unfamiliar shape is a problem, and a single-line blob is not.
 */
private fun prettyJson(element: JsonElement): String = RAW_JSON.encodeToString(
    JsonElement.serializer(),
    element,
)

private val RAW_JSON = Json { prettyPrint = true }
private val CAMEL_BOUNDARY = Regex("([a-z0-9])([A-Z])")
private const val LIST_JOIN = " · "
