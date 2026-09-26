package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * The two encodings a DTO has, and the check that a decoded fixture survives both of them.
 *
 * WHY A ROUND TRIP ALONE IS NOT ENOUGH. `decode(encode(x)) == x` proves the model is lossless
 * against ITSELF: a property whose serial name drifted away from the server's key would still
 * round-trip, because the same wrong name is used both ways. [assertMirrors] therefore also holds
 * the encoded tree against the bytes the server actually sent:
 *
 *   - every key the server sent comes back out of the VERBOSE encoding with the same value, so
 *     nothing is renamed, reshaped or dropped on the way through the model;
 *   - everything the TERSE encoding writes, the server sent with that same value, so the
 *     omit-defaults shape never invents a key or a value.
 *
 * [terse] is not a test convenience: it is the production encoder's configuration
 * (`DistrictApiClient.DEFAULT_JSON` writes no defaults and no nulls), so it is also the exact
 * shape every request body leaves the app in.
 *
 * NUMBERS ARE COMPARED AS NUMBERS. A `Double` property holding a whole number writes `45.0` where
 * the server wrote `45`; the two are the same JSON number, and a text comparison would call that
 * drift.
 */
internal object WireMirror {

    /** Every property written, and every null written as an explicit `null`. */
    val verbose: Json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    /**
     * The production encoder's shape: defaults and nulls omitted.
     *
     * ⚠️ A COPY, BECAUSE THE ORIGINAL IS OUT OF REACH. `DistrictApiClient.DEFAULT_JSON` lives in
     * :core:core-network, which depends on this module, so this test source set cannot name it
     * without adding that module (and its locked dependency set) to this module's test classpath.
     * `DefaultJsonShapeTest` in :core:core-network holds DEFAULT_JSON to this exact declaration;
     * change the two together.
     */
    val terse: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    /** Decode [name] strictly, then hold both encodings of the result against the fixture's bytes. */
    fun <T> assertMirrors(serializer: KSerializer<T>, name: String): T =
        assertMirrorsElement(serializer, ContractFixtures.json.parseToJsonElement(ContractFixtures.read(name)), name)

    /**
     * The same check for one ROW of a fixture, read through the row type's own serializer.
     *
     * [path] is dot-separated: object keys by name, array elements by index (`contacts.0.company`).
     * A path that leads nowhere, or to anything but an object, fails rather than skipping, so a
     * fixture that stops carrying the row cannot turn this into a pass over nothing.
     */
    fun <T> assertRowMirrors(serializer: KSerializer<T>, name: String, path: String): T {
        val root = ContractFixtures.json.parseToJsonElement(ContractFixtures.read(name))
        val row = path.split('.').fold(root) { node, step ->
            when (node) {
                is JsonArray -> node.getOrNull(step.toInt())
                is JsonObject -> node[step]
                else -> null
            } ?: fail("$name has nothing at $path")
        }
        assertTrue("$name at $path must be an object, found $row", row is JsonObject)
        return assertMirrorsElement(serializer, row, "$name at $path")
    }

    private fun <T> assertMirrorsElement(serializer: KSerializer<T>, sent: JsonElement, label: String): T {
        val decoded = ContractFixtures.json.decodeFromJsonElement(serializer, sent)
        assertCovered(
            "$label: a key the server sent did not come back out of the verbose encoding unchanged",
            sent,
            verbose.encodeToJsonElement(serializer, decoded),
        )
        assertCovered(
            "$label: the omit-defaults encoding wrote something the server did not send",
            terse.encodeToJsonElement(serializer, decoded),
            sent,
        )
        assertRoundTrips(serializer, decoded, label)
        return decoded
    }

    /** [value] must decode back to itself, strictly, from both of its encodings. */
    fun <T> assertRoundTrips(serializer: KSerializer<T>, value: T, label: String) {
        assertEquals(
            "$label must survive the verbose encoding",
            value,
            ContractFixtures.json.decodeFromString(serializer, verbose.encodeToString(serializer, value)),
        )
        assertEquals(
            "$label must survive the omit-defaults encoding",
            value,
            ContractFixtures.json.decodeFromString(serializer, terse.encodeToString(serializer, value)),
        )
    }

    /**
     * The production encoding of [value] must be exactly [expected], and must decode back to [value].
     *
     * The comparison is between parsed trees, so key order and whitespace in [expected] are free,
     * while a missing key, an extra key or a changed value is not.
     */
    fun <T> assertWire(serializer: KSerializer<T>, value: T, expected: String) {
        val encoded = terse.encodeToJsonElement(serializer, value)
        assertEquals(ContractFixtures.json.parseToJsonElement(expected), encoded)
        assertRoundTrips(serializer, value, expected)
    }

    /**
     * [minimal] is a row built from its REQUIRED properties only. Its production encoding must be
     * exactly [expected], and every key of [expected] must really be required: the same body with
     * any one of them removed has to be refused, not decoded into a default.
     *
     * That second half is the contract the scheduling rows state in their own documentation (a
     * structurally wrong 200 must not decode into a row with no name), and it is what a property
     * quietly widened to a default would break without failing any decode.
     */
    fun <T> assertRequiredKeys(serializer: KSerializer<T>, minimal: T, expected: String) {
        assertWire(serializer, minimal, expected)
        val body = ContractFixtures.json.parseToJsonElement(expected) as JsonObject
        assertTrue("a required-keys body must name at least one key", body.isNotEmpty())
        body.keys.forEach { key ->
            val without = JsonObject(body - key)
            val decoded = runCatching { ContractFixtures.json.decodeFromJsonElement(serializer, without) }
            assertTrue(
                "`$key` must be required, but a body without it decoded to ${decoded.getOrNull()}",
                decoded.exceptionOrNull() is SerializationException,
            )
        }
    }

    /** Every key of [part] exists in [whole] with the same value, recursively. */
    fun assertCovered(message: String, part: JsonElement, whole: JsonElement, path: String = "$") {
        when (part) {
            is JsonObject -> {
                val target = whole as? JsonObject ?: fail("$message: $path is not an object in $whole")
                part.forEach { (key, value) ->
                    val counterpart = target[key]
                        ?: fail("$message: $path.$key is missing from ${target.keys}")
                    assertCovered(message, value, counterpart, "$path.$key")
                }
            }
            is JsonArray -> {
                val target = whole as? JsonArray ?: fail("$message: $path is not an array in $whole")
                assertEquals("$message: $path has a different length", part.size, target.size)
                part.forEachIndexed { index, value -> assertCovered(message, value, target[index], "$path[$index]") }
            }
            is JsonPrimitive -> assertTrue(
                "$message: $path is $part on one side and $whole on the other",
                whole is JsonPrimitive && samePrimitive(part, whole),
            )
        }
    }

    private fun samePrimitive(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a is JsonNull || b is JsonNull) return a is JsonNull && b is JsonNull
        if (a.isString || b.isString) return a.isString == b.isString && a.content == b.content
        val left = a.content.toBigDecimalOrNull()
        val right = b.content.toBigDecimalOrNull()
        return if (left != null && right != null) left.compareTo(right) == 0 else a.content == b.content
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
