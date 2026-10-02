package com.distronode.districtai.core.network

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * The request-body helpers every API file in this module shares.
 *
 * ⚠️ A FILE OF ITS OWN, NOT A SECTION OF `HttpDistrictApi.kt`. That file is the guaranteed merge
 * conflict when several people add endpoints at once (see [ExtraPaths]), which is how each family
 * file came to carry its own copy of these. One copy here is the fix that needs no edit there.
 */

/**
 * Encode a request body the way every write in this client is encoded.
 *
 * ⛔ `explicitNulls = false` IS THE LOAD-BEARING SETTING, NOT TIDINESS: a null field is OMITTED
 * rather than sent as an explicit null. `contacts/update` treats an explicit null as "clear the
 * column", `workspace/persona` merges presence-wise so a null would be a value,
 * `messages/mark-read` validates that one of `contactId`/`counterpart` is PRESENT, and
 * `call-handling` refuses a body carrying only the workspace. MockWebServer tests pin each one by
 * asserting a key's ABSENCE.
 *
 * ⚠️ Encoded through a [JsonElement] rather than a raw string so the setting applies, and so
 * [DistrictApiClient.send] takes one body type for every route.
 */
internal fun <T> T.toJson(serializer: SerializationStrategy<T>): JsonElement =
    BODY_JSON.encodeToJsonElement(serializer, this)

/**
 * ⛔ ONE INSTANCE FOR THE WHOLE MODULE. Two used to exist with the same configuration, which is how
 * one of them ends up with `explicitNulls` left at the default. Kept separate from the client's
 * decoding Json so a change there cannot silently start sending explicit nulls.
 */
private val BODY_JSON = Json { explicitNulls = false }

/**
 * A JSON object with the null-valued pairs DROPPED.
 *
 * ⛔ THE DROP IS THE POINT AND IT IS NOT THE SAME AS `explicitNulls = false` ON A SERIALIZER,
 * because this one can be BYPASSED at a single call site: a pair whose value is [JsonNull] is
 * KEPT. That is what makes the desk's "clear the brand name" expressible while "leave it alone"
 * stays absent, and it is why the desk and support build their bodies here rather than through
 * [toJson].
 */
internal fun jsonObjectOf(vararg pairs: Pair<String, JsonElement?>): JsonObject =
    JsonObject(pairs.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

/**
 * `workspaceId` as a query parameter.
 *
 * ⛔ FOR THE SURFACES THAT TAKE IT IN THE QUERY, WHICH IS NOT ALL OF THEM. Every desk route takes
 * it there (the multipart logo upload included), and so does support; other routes read it from
 * the body or from a multipart field (see [DistrictApiClient.sendMultipart]). Expressed once so no
 * call site on those surfaces puts it in a body by habit.
 */
internal fun workspaceQuery(workspaceId: String): Map<String, String?> =
    mapOf("workspaceId" to workspaceId)
