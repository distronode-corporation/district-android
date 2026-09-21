package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workspace-settings half of the contract gate.
 *
 * ⛔ THE FIXTURES BEHIND THIS CLASS PROTECT THE ONE PART OF THE APP THAT CAN DESTROY A CUSTOMER'S
 * CONFIGURATION. `PATCH workspace/tools` replaces `toolConfig.allowedTools` with exactly the array
 * it receives, so the client's obligation is to carry back what it LOADED — order, content, and
 * every id including the ones this app cannot name. Every assertion here exists to make one of
 * those three fail loudly rather than on a phone.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` RATHER THAN MORE METHODS ON IT, for the reason
 * `DeviceContractFixtureTest` states: that class reached detekt's LargeClass ceiling and the
 * healthy answer is another class, not a raised threshold. The strict decoder and the fixture
 * loader are shared through [ContractFixtures] so the split cannot make one of them lenient.
 */
class WorkspaceConfigContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    private fun config(name: String): WorkspaceConfig {
        val response = json.decodeFromString<WorkspaceConfigResponse>(fixture(name))
        assertEquals(true, response.success)
        // ⚠️ Asserted rather than defaulted: the route always emits `config`, so a null one is
        // contract drift — and defaulting it to an empty [WorkspaceConfig] here would let a
        // regression that stopped emitting it pass this whole file.
        assertNotNull("$name must carry a config", response.config)
        return response.config!!
    }

    // ── The configured workspace ─────────────────────────────────────────────

    @Test
    fun `a configured workspace decodes, engine fields and all`() {
        val config = config("district-workspace-config.json")

        assertNotNull("the fixture must carry a persona", config.aiPersona)
        val persona = config.aiPersona!!
        assertEquals("Ada", persona.name)
        assertEquals("Warm, concise, and never oversells.", persona.personality)

        // ⛔ THE OPT-IN, IN ITS TRUE BRANCH. `dgiEnabled` is the sub-processor consent flag, and a
        // fixture that only ever carried it absent would let a client type it non-null and then
        // throw on the ordinary row.
        assertEquals(true, persona.dgiEnabled)

        // ⛔ THE ENGINE CONFIGURATION NO NATIVE FORM EDITS, ASSERTED BECAUSE IT MUST STILL DECODE.
        // This module reads the fixtures with `ignoreUnknownKeys = false`, so these fields are the
        // reason "the phone leaves the call brain alone" is checkable rather than a claim. The
        // per-engine response-length map especially: it is keyed by `modelId`, and the route only
        // writes it when both arrive together and both are valid.
        assertEquals("deepgram-pipeline", persona.modelId)
        assertEquals(
            mapOf("deepgram-pipeline" to "concise", "openai-pipeline" to "balanced"),
            persona.responseLength,
        )
        assertEquals(0.7, persona.temperature!!, 0.0001)
        assertEquals(true, persona.preemptiveTts)
        assertEquals("en-GB-Studio-B", persona.voiceStyle)
    }

    @Test
    fun `the allowlist survives a round trip with its order and its unknown ids intact`() {
        // ⛔ THE ASSERTION THIS WHOLE FILE EXISTS FOR. `workspace/tools` writes exactly what it
        // receives, so a decode that sorted, deduplicated or dropped an entry would silently
        // rewrite a customer's stored configuration on the next save. Compared as an ORDERED list,
        // because a set comparison would pass for a client that reordered — which is a change to a
        // value the server stores verbatim.
        val expected = listOf(
            "search_knowledge_base",
            "transfer_to_agent",
            "book_appointment",
            "transfer_to_creator",
            "leave_message",
            "dispatch_email",
        )
        val config = config("district-workspace-config.json")
        assertEquals(expected, config.toolConfig?.allowedTools)

        // ⛔ AND ONE OF THEM IS NOT IN THIS CLIENT'S CATALOG. `transfer_to_creator` is retired, and
        // workspaces that still store it are migrated on load by the voice agent: real data, not a
        // hypothetical. A capability list rebuilt from a hardcoded catalog would
        // drop it, with a 200 and no error anywhere.
        assertTrue(
            "the fixture must carry an id the client's catalog does not know",
            "transfer_to_creator" in expected,
        )

        // Re-encoding and re-decoding must not disturb it either — the shape a save is built from.
        val terse = Json {
            encodeDefaults = false
            explicitNulls = false
        }
        val reEncoded = json.decodeFromString(
            ToolConfig.serializer(),
            terse.encodeToString(ToolConfig.serializer(), config.toolConfig!!),
        )
        assertEquals(expected, reEncoded.allowedTools)
    }

    @Test
    fun `the fixture models every key it carries, so a strict decode covers the whole persona`() {
        // ⛔ PROVES THE DTO IS NOT QUIETLY NARROWER THAN THE WIRE. The decode above already fails on
        // an unmodelled key (`ignoreUnknownKeys = false`), but that only covers keys the fixture
        // HAPPENS to carry — so this asserts the fixture is rich rather than convenient. A
        // regeneration against a thin persona would silently reduce what the gate checks.
        val raw = fixture("district-workspace-config.json")
        listOf(
            "\"voice\"", "\"voiceStyle\"", "\"modelId\"", "\"responseLength\"",
            "\"preemptiveTts\"", "\"temperature\"", "\"language\"", "\"videoEnabled\"",
            "\"videoModelId\"", "\"dgiEnabled\"",
        ).forEach { key ->
            assertTrue("the persona fixture must keep exercising $key", raw.contains(key))
        }
    }

    @Test
    fun `the destructive arrays are carried opaquely and are populated`() {
        // ⚠️ `routingRules` and `callDirectory` STAY `JsonElement` even now that both are editable.
        // Their editors are value classes over the RAW objects (`WorkspaceEditModels.kt`), because
        // both save routes validate with a zod `.passthrough()` and both write back exactly what
        // they receive — a typed data class would drop every unmodelled key on decode and then
        // delete it on the next save. "Carried, never rewritten" is still the rule; the edit models
        // overwrite one key at a time rather than rebuilding the object.
        //
        // They are asserted POPULATED because `WorkspaceEditModelsTest` round-trips this same
        // fixture — a thinner one would silently reduce that coverage rather than failing here.
        val config = config("district-workspace-config.json")
        assertEquals(3, (config.routingRules as JsonArray).size)
        assertEquals(2, (config.callDirectory as JsonArray).size)
        assertEquals("+14165550101", config.creatorCellNumber)
        assertEquals("studio", config.plan)
    }

    // ── The brand-new workspace ──────────────────────────────────────────────

    @Test
    fun `a fresh workspace decodes with an ABSENT allowlist, which is not an empty one`() {
        // ⛔ THE BRANCH THAT DECIDES WHETHER THE PHONE DESTROYS DATA, and it is the ordinary state
        // of a workspace on its first day. `toolConfig` is null, so `allowedTools` is ABSENT — and
        // the web reads absent as EVERY TOOL ON (`initialData || AVAILABLE_TOOLS.map(t => t.id)`).
        // A client that decoded absent into an empty list and saved would switch every capability
        // off for an operator who opened the screen only to look at it.
        val config = config("district-workspace-config-sparse.json")

        assertNull("a fresh workspace has no persona at all", config.aiPersona)
        assertNull("a fresh workspace has no toolConfig at all", config.toolConfig)
        assertNull("and therefore no allowlist", config.toolConfig?.allowedTools)

        // ⛔ EMPTY IS NOT ABSENT, AND BOTH SHAPES ARE IN THIS ONE FIXTURE. The route publishes real
        // empty arrays for these two (`[] ?? null` is `[]`), so a decoder that mapped both to the
        // same thing would lose the distinction the wholesale-replace routes turn on.
        assertEquals(0, (config.routingRules as JsonArray).size)
        assertEquals(0, (config.callDirectory as JsonArray).size)

        assertNull(config.plan)
        assertNull(config.creatorCellNumber)
    }

    @Test
    fun `an explicitly empty allowlist stays distinguishable from an absent one`() {
        // ⛔ THE OTHER HALF OF THE SAME DISTINCTION, AND IT HAS NO FIXTURE BECAUSE NO ROUTE
        // PRODUCES IT ON DEMAND — it is what a workspace looks like after an operator deliberately
        // turned everything off. Both readings have to survive the DTO, so the empty case is
        // pinned against a literal here rather than left to a fixture that would have to be
        // hand-written anyway.
        val absent = json.decodeFromString<ToolConfig>("""{"calendarId":"primary"}""")
        val empty = json.decodeFromString<ToolConfig>("""{"allowedTools":[]}""")

        assertNull("absent must stay null — it means EVERY tool is on", absent.allowedTools)
        assertEquals("empty must stay empty — it means the operator turned them off", 0, empty.allowedTools!!.size)
    }

    // ── The two writes ───────────────────────────────────────────────────────

    @Test
    fun `neither save route echoes the config it wrote`() {
        // ⛔ THIS IS WHY THE REPOSITORY RE-READS AFTER EVERY SAVE. There is no body to adopt, so a
        // client that assumed one would keep rendering its own optimistic edit as though the server
        // had confirmed it. If either fixture ever grows a `config` key, this is where the re-read
        // becomes droppable — and until then, a `revoked`-style extra field here would mean the
        // routes have diverged and can no longer share one DTO.
        listOf("district-persona-patch.json", "district-tools-patch.json").forEach { name ->
            val raw = fixture(name)
            val decoded = json.decodeFromString<WorkspaceConfigSaveResponse>(raw)
            assertEquals("$name must affirm success", true, decoded.success)
            assertFalse("$name must not echo the config", raw.contains("config"))
            assertEquals(
                "$name must carry nothing but the flag",
                """{"success":true}""",
                raw.replace(Regex("\\s"), ""),
            )
        }
    }

    // ── The gate guarding itself ─────────────────────────────────────────────

    @Test
    fun `the workspace-config fixtures survive a round trip in both encodings`() {
        // ⛔ THE SPARSE ONE ESPECIALLY. Its nulls are written as ABSENT by the terse encoding —
        // the same shape a fresh workspace genuinely produces — so a default that swallowed one
        // (an empty list for `allowedTools`, say) would decode and re-encode to something
        // different. That is precisely the substitution that would wipe a new workspace's
        // capabilities, and no decode assertion above can catch it.
        val verbose = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val terse = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        fun <T> roundTrip(serializer: KSerializer<T>, fixtureName: String) {
            val decoded = json.decodeFromString(serializer, fixture(fixtureName))
            assertEquals(
                "$fixtureName must survive an explicit-nulls encoding",
                decoded,
                json.decodeFromString(serializer, verbose.encodeToString(serializer, decoded)),
            )
            assertEquals(
                "$fixtureName must survive the server's own omit-defaults shape",
                decoded,
                json.decodeFromString(serializer, terse.encodeToString(serializer, decoded)),
            )
        }

        roundTrip(WorkspaceConfigResponse.serializer(), "district-workspace-config.json")
        roundTrip(WorkspaceConfigResponse.serializer(), "district-workspace-config-sparse.json")
        roundTrip(WorkspaceConfigSaveResponse.serializer(), "district-persona-patch.json")
        roundTrip(WorkspaceConfigSaveResponse.serializer(), "district-tools-patch.json")
    }

    @Test
    fun `an unmodelled persona field is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS FOR THIS FILE'S DECODER TOO. Duplicated from the sibling
        // contract classes deliberately: they share one `Json` instance, so if this ever passes,
        // the shared decoder has been relaxed and EVERY half of the gate has quietly stopped
        // protecting anything.
        val withExtraField =
            """{"success":true,"config":{"aiPersona":{"name":"Ada","brandNewServerField":"boom"}}}"""

        val failure = runCatching { json.decodeFromString<WorkspaceConfigResponse>(withExtraField) }
        assertTrue(
            "Decoding an unknown key MUST fail. It succeeded, which means ignoreUnknownKeys is " +
                "no longer false and contract drift can now ship silently.",
            failure.isFailure,
        )
    }
}
