package com.distronode.districtai.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proof that an edit round trip through the two destructive-array editors loses nothing.
 *
 * ⛔ WHY THIS FILE IS THE MOST IMPORTANT ONE IN THE PACKAGE. `PATCH workspace/directory` and
 * `POST workspace/routing-rules` both replace their stored array with EXACTLY what they receive,
 * and both validate rows with a zod `.passthrough()` — the schemas name two keys each and carry
 * whatever else is stored. So the only thing standing between a phone edit and silent data loss is
 * that the client carries the rows it did not model. A kotlinx `@Serializable data class` would
 * not: unmodelled keys are dropped on decode and never come back.
 *
 * Every assertion here runs against the REAL committed fixture rather than a hand-written literal,
 * because the fixture is generated from the route handlers and carries rows in a shape the web's
 * own rule builder does not write (`{id, match, action, target}`). A hand-written test would have
 * been written by the same person who wrote the model, and would have modelled the same things.
 */
class WorkspaceEditModelsTest {

    private val json: Json = ContractFixtures.json

    private fun config(): WorkspaceConfig =
        json.decodeFromString<WorkspaceConfigResponse>(
            ContractFixtures.read("district-workspace-config.json"),
        ).config!!

    /** ⚠️ Canonical form on BOTH sides, so the comparison is about keys and order, not whitespace. */
    private fun canonical(element: JsonArray): String =
        json.encodeToString(JsonArray.serializer(), element)

    // ── The round trip ───────────────────────────────────────────────────────

    @Test
    fun `an untouched directory re-encodes byte-identically, unknown keys and all`() {
        val stored = config().callDirectory as JsonArray
        val entries = directoryEntries(stored)
        assertNotNull("the fixture directory must be modellable", entries)

        val request = directoryPatch("ws-1", entries!!)

        // ⛔ THE PROPERTY THAT MAKES AN ACCIDENTAL SAVE HARMLESS. Loading and saving without
        // touching anything must produce the identical array — including the `extension` key on the
        // second entry, which the route's schema does not name and which a typed model would drop.
        assertEquals(canonical(stored), canonical(request.callDirectory))
        assertTrue(
            "the fixture must keep carrying an unmodelled key, or this test proves nothing",
            canonical(stored).contains("extension"),
        )
    }

    @Test
    fun `an untouched rules array re-encodes byte-identically, foreign shapes and all`() {
        val stored = config().routingRules as JsonArray
        val rules = routingRules(stored)
        assertNotNull("the fixture rules must be modellable", rules)

        val request = routingRulesRequest("ws-1", rules!!)

        assertEquals(canonical(stored), canonical(request.routingRules))
        // ⛔ THE FIXTURE CARRIES RULES THE BUILDER DOES NOT WRITE. `match`/`action`/`target` are not
        // in the route's schema at all, and a client that dropped them would leave a workspace of
        // rules that match nothing — with a 200.
        assertTrue(canonical(stored).contains("\"match\""))
        assertTrue(canonical(stored).contains("\"action\""))
    }

    @Test
    fun `editing one field of a foreign-shaped rule keeps every other key`() {
        val rules = routingRules(config().routingRules)!!
        val foreign = rules[0]
        assertEquals("", foreign.value(RoutingRuleField.FIELD))

        val edited = foreign.with(RoutingRuleField.VOICE, "Kore")

        // ⛔ ADDED, NOT REPLACED. The rule now carries a `voice` it did not have AND every key it
        // did — which is the honest outcome for a shape this editor cannot fully display.
        assertEquals("Kore", edited.value(RoutingRuleField.VOICE))
        assertEquals("billing", (edited.raw["match"] as JsonPrimitive).content)
        assertEquals("transfer", (edited.raw["action"] as JsonPrimitive).content)
        assertEquals("rule-contract-1", edited.id)
    }

    @Test
    fun `editing a directory entry overwrites in place and keeps the key order`() {
        val entries = directoryEntries(config().callDirectory)!!
        val withUnknownKey = entries[1]

        val edited = withUnknownKey.with(DirectoryField.PHONE_NUMBER, "+14165550123")

        assertEquals("+14165550123", edited.value(DirectoryField.PHONE_NUMBER))
        assertEquals("On-call engineer", edited.value(DirectoryField.NAME))
        // ⚠️ `Map.plus` keeps an existing key in its ORIGINAL position, so only the value moved.
        // Order matters here because the array is stored verbatim and re-read by other clients.
        assertEquals(listOf("name", "phoneNumber", "extension"), edited.raw.keys.toList())
    }

    // ── The refusal ──────────────────────────────────────────────────────────

    @Test
    fun `an array whose elements are not objects refuses to be edited at all`() {
        // ⛔ NULL MEANS "WITHHOLD THE EDITOR", NOT "EMPTY". Both columns were bare `Json` writes
        // before their save routes gained schemas, so a stored array of strings is possible in real
        // data — and an editor that quietly skipped such an element would delete it on the next
        // save, because the save replaces the whole array.
        val hostile = json.parseToJsonElement("""["not-an-object", {"name":"Ops"}]""")

        assertNull(directoryEntries(hostile))
        assertNull(routingRules(hostile))
    }

    @Test
    fun `an absent column is an empty list rather than a refusal`() {
        // ⚠️ THE DIRECTORY ROUTE WRITES `callDirectory || []`, so "never configured" and
        // "explicitly empty" are the same stored value. Treating null as unmodellable would leave a
        // brand-new workspace unable to add its first transfer target from a phone.
        assertEquals(emptyList<DirectoryEntry>(), directoryEntries(null))
        assertEquals(emptyList<RoutingRule>(), routingRules(null))
    }

    @Test
    fun `a non-string value reads as absent rather than being coerced`() {
        // ⚠️ The column holds anything. A numeric phone number is not a string this editor can
        // show, and rendering `4165550177` as text would then SAVE it as a string — changing the
        // stored type of a value nothing asked it to touch.
        val entry = DirectoryEntry(
            JsonObject(mapOf("name" to JsonPrimitive("Ops"), "phoneNumber" to JsonPrimitive(4165)))
        )

        assertEquals("", entry.value(DirectoryField.PHONE_NUMBER))
        assertTrue("a row this editor cannot fully read is flagged", entry.incomplete)
    }

    // ── New rows ─────────────────────────────────────────────────────────────

    @Test
    fun `a new directory entry carries exactly the two keys the web form posts`() {
        val entry = DirectoryEntry.newEntry("Night desk", "+14165550100")

        // ⚠️ Exactly two: a row added on a phone must be indistinguishable from one added on the
        // web, or the two clients would slowly grow different directory shapes.
        assertEquals(listOf("name", "phoneNumber"), entry.raw.keys.toList())
        assertEquals("Night desk", entry.value(DirectoryField.NAME))
    }

    @Test
    fun `a new rule carries the web builder's own defaults`() {
        val rule = RoutingRule.newRule("rule-new")

        assertEquals("rule-new", rule.id)
        assertEquals("industry", rule.value(RoutingRuleField.FIELD))
        assertEquals("contains", rule.value(RoutingRuleField.OPERATOR))
        assertEquals("Puck", rule.value(RoutingRuleField.VOICE))
        assertEquals("", rule.value(RoutingRuleField.VALUE))
        assertEquals("", rule.value(RoutingRuleField.MODEL))
    }

    @Test
    fun `the offered vocabularies match the web builder's own lists`() {
        // ⚠️ These three are HARDCODED on the web (unlike the model list, which is derived from a
        // region-dependent server catalogue and is therefore read-only on the phone). Mirroring a
        // hardcoded list cannot drift the way mirroring a derived one would — but it can be edited,
        // so it is pinned.
        assertEquals(
            listOf("industry", "estimatedValue", "callerType", "lineType", "isDecisionMaker", "seniority"),
            ROUTING_FIELDS,
        )
        assertEquals(listOf("contains", "equals"), ROUTING_OPERATORS)
        assertEquals(listOf("Puck", "Fenrir", "Aoede", "Charon", "Kore"), ROUTING_VOICES)
    }

    @Test
    fun `a patch request serialises its array as a bare JSON array`() {
        val entries = directoryEntries(config().callDirectory)!!
        val encoded = json.encodeToString(
            DirectoryPatchRequest.serializer(),
            directoryPatch("ws-1", entries),
        )

        // ⛔ THE KEY IS `callDirectory` AND IT IS NEVER OMITTED. The route treats an ABSENT array
        // exactly like an empty one and wipes the directory, so the request type's non-nullable
        // field is what makes that unreachable — asserted on the wire rather than on the type.
        assertTrue(encoded.contains("\"callDirectory\":["))
        assertTrue(encoded.contains("\"workspaceId\":\"ws-1\""))
    }
}
