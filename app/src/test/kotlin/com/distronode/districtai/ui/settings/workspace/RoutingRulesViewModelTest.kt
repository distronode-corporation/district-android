package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.RoutingRuleField
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The routing-rules editor's state machine.
 *
 * ⛔ THE FIXTURE-SHAPED CASE IS THE ONE THAT MATTERS. Stored rules are not necessarily in the shape
 * the web's rule builder writes — the committed contract fixture carries `{id, match, action,
 * target}` rows — because the column is `Json` and the route's per-rule schema is `.passthrough()`.
 * A client that modelled the builder's seven fields and wrote back only those would delete every
 * other key, through a route that replaces the whole array, with a 200.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingRulesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    /** ⚠️ One foreign-shaped rule and one builder-shaped one, as the real fixture carries. */
    private val storedRules: JsonElement = Json.parseToJsonElement(
        """
        [
          {"id":"rule-1","match":"billing","action":"transfer","target":"+14165550188"},
          {"id":"rule-2","field":"industry","operator":"contains","value":"tech",
           "voice":"Fenrir","instruction":"Be brisk.","model":""}
        ]
        """.trimIndent(),
    )

    private fun api(rules: JsonElement? = storedRules) = FakeDistrictApi().apply {
        workspaceConfigResult = ApiResult.Success(
            WorkspaceConfigResponse(success = true, config = WorkspaceConfig(routingRules = rules)),
        )
    }

    private fun viewModel(api: FakeDistrictApi, id: String = "rule-new") =
        RoutingRulesViewModel(WorkspaceConfigRepository(api), "ws-1") { id }

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load leaves no draft and refuses every edit`() = runTest {
        val api = api().apply {
            workspaceConfigResult = ApiResult.NetworkFailure(IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.addRule()
        vm.edit(0, RoutingRuleField.VOICE, "Kore")
        vm.remove(0)
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.load is ConfigState.LoadFailed)
        assertNull(vm.state.value.draft)
        assertFalse(vm.state.value.canSave)
        assertTrue(api.routingPatches.isEmpty())
    }

    @Test
    fun `an unmodellable array withholds the editor without claiming the read failed`() = runTest {
        val vm = viewModel(api(Json.parseToJsonElement("""[42]""")))
        advanceUntilIdle()

        assertTrue(vm.state.value.load is ConfigState.Ready)
        assertTrue(vm.state.value.unmodellable)
        assertFalse(vm.state.value.editable)
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    @Test
    fun `a foreign-shaped rule renders with empty builder fields and keeps every key`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        // ⚠️ HONEST RATHER THAN INVENTED. This editor genuinely cannot display `match`/`action`, so
        // the builder boxes are empty — it does not guess a mapping.
        assertEquals("", vm.state.value.rules[0].value(RoutingRuleField.FIELD))

        vm.edit(0, RoutingRuleField.VOICE, "Kore")
        vm.save()
        advanceUntilIdle()

        val sent = api.routingPatches.single().routingRules[0] as JsonObject
        // ⛔ ADDED, NOT REPLACED.
        assertEquals("Kore", (sent["voice"] as JsonPrimitive).content)
        assertEquals("billing", (sent["match"] as JsonPrimitive).content)
        assertEquals("transfer", (sent["action"] as JsonPrimitive).content)
    }

    @Test
    fun `a new rule carries the web builder's defaults and the injected id`() = runTest {
        val vm = viewModel(api(), id = "rule-42")
        advanceUntilIdle()

        vm.addRule()

        val added = vm.state.value.rules[2]
        assertEquals("rule-42", added.id)
        assertEquals("industry", added.value(RoutingRuleField.FIELD))
        assertEquals("Puck", added.value(RoutingRuleField.VOICE))
        assertTrue(vm.state.value.dirty)
    }

    @Test
    fun `removing is local until the save`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.remove(1)

        assertEquals(1, vm.state.value.rules.size)
        assertTrue(api.routingPatches.isEmpty())
    }

    @Test
    fun `an out-of-range index is ignored`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.remove(7)
        vm.edit(7, RoutingRuleField.VALUE, "x")

        assertEquals(2, vm.state.value.rules.size)
        assertNull(vm.state.value.draft)
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    @Test
    fun `an edit and its inverse leave the array byte-identical and NOT dirty`() = runTest {
        // ⛔ THE ROUND-TRIP PROPERTY, DRIVEN THE WAY AN OPERATOR ACTUALLY REACHES IT: change
        // something, change it back. A model rebuilt from the seven builder fields would silently
        // drop `match`, `action` and `target` right here — and the form would then report itself
        // dirty against an array that only differs by what the client forgot.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(1, RoutingRuleField.VOICE, "Kore")
        vm.edit(1, RoutingRuleField.VOICE, "Fenrir")

        val rebuilt = JsonArray(vm.state.value.rules.map { it.raw })
        assertEquals(
            Json.encodeToString(JsonArray.serializer(), storedRules as JsonArray),
            Json.encodeToString(JsonArray.serializer(), rebuilt),
        )
        assertFalse(vm.state.value.dirty)

        vm.save()
        advanceUntilIdle()
        assertTrue("a clean form must not spend a wholesale replace", api.routingPatches.isEmpty())
    }

    @Test
    fun `saving with no rules is expressible and flagged as the wipe it is`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.remove(0)
        vm.remove(0)

        assertTrue(vm.state.value.savingEmptiesRules)

        vm.save()
        advanceUntilIdle()

        assertEquals(0, api.routingPatches.single().routingRules.size)
    }

    @Test
    fun `a successful save adopts the re-read and drops the draft`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(1, RoutingRuleField.VALUE, "healthcare")
        vm.save()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, vm.state.value.save)
        assertNull(vm.state.value.draft)
        assertEquals(listOf("ws-1", "ws-1"), api.configRequests)
    }

    @Test
    fun `a landed write whose re-read failed is SavedButStale`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(1, RoutingRuleField.VALUE, "healthcare")
        api.workspaceConfigResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.save is SaveState.SavedButStale)
    }

    @Test
    fun `a rejected voice is surfaced and the draft survives`() = runTest {
        // ⚠️ A 400 HERE NAMES THE VALUE THE WORKSPACE REFUSED. This client cannot see the
        // allow-list and deliberately does not pre-validate, so the message is the whole answer —
        // and the rules the operator built must still be on screen to fix.
        val api = api().apply {
            saveRoutingResult = ApiResult.HttpFailure(400, "Invalid voice identifier: Kore")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(1, RoutingRuleField.VOICE, "Kore")
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.save is SaveState.Failed)
        assertEquals("Kore", vm.state.value.rules[1].value(RoutingRuleField.VOICE))
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `nothing can change the rules while a save is in flight`() = runTest {
        // ⛔ THE ARRAY ON THE WIRE IS THE ONE THE OPERATOR CONFIRMED. See the directory editor's
        // test of the same name: a change landing mid-save would be shown and then thrown away.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(1, RoutingRuleField.VOICE, "Kore")
        vm.save()
        vm.addRule()
        vm.edit(0, RoutingRuleField.VOICE, "Charon")
        vm.remove(0)

        assertEquals(SaveState.Saving, vm.state.value.save)
        assertEquals(2, vm.state.value.rules.size)
        assertEquals("", vm.state.value.rules[0].value(RoutingRuleField.VOICE))
        advanceUntilIdle()

        val sent = api.routingPatches.single().routingRules
        assertEquals(2, sent.size)
        assertEquals(JsonPrimitive("Kore"), (sent[1] as JsonObject)["voice"])
    }

    @Test
    fun `removing a rule that does not exist changes nothing`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.remove(-1)
        vm.remove(2)

        assertNull(vm.state.value.draft)
        assertEquals(2, vm.state.value.rules.size)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `the factory builds a ViewModel whose new rules get fresh random ids`() = runTest {
        // ⚠️ THE PRODUCTION DEFAULT, which mirrors the web builder's `crypto.randomUUID()`: two
        // added rules must never share an id, or the builder could not tell them apart.
        val api = api()
        val vm = RoutingRulesViewModel
            .factory(WorkspaceConfigRepository(api), "ws-1")
            .create(RoutingRulesViewModel::class.java, CreationExtras.Empty)
        advanceUntilIdle()

        vm.addRule()
        vm.addRule()

        assertEquals(listOf("ws-1"), api.configRequests)
        val ids = vm.state.value.rules.drop(2).map { it.id }
        assertEquals(2, ids.toSet().size)
        ids.forEach { id -> java.util.UUID.fromString(id) }
    }
}
