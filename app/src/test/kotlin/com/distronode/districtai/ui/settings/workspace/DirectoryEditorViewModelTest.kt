package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.viewmodel.CreationExtras
import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.DirectoryField
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
 * The transfer directory editor's state machine.
 *
 * ⛔ THE ARRAY THIS BUILDS BECOMES THE WORKSPACE'S COMPLETE LIST OF TRANSFER TARGETS. `PATCH
 * workspace/directory` writes `callDirectory || []`, so there are three ways to cause real damage
 * and each has a test here: editing against no baseline, dropping a key the client does not model,
 * and saving an empty list without the operator having said so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DirectoryEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    /** ⚠️ The SECOND entry carries `extension`, a key neither the schema nor this client names. */
    private val storedDirectory: JsonElement = Json.parseToJsonElement(
        """
        [
          {"name":"Ops desk","phoneNumber":"+14165550177"},
          {"name":"On-call engineer","phoneNumber":"+14165550166","extension":"402"}
        ]
        """.trimIndent(),
    )

    private fun api(directory: JsonElement? = storedDirectory) = FakeDistrictApi().apply {
        workspaceConfigResult = ApiResult.Success(
            WorkspaceConfigResponse(
                success = true,
                config = WorkspaceConfig(callDirectory = directory),
            ),
        )
    }

    private fun viewModel(api: FakeDistrictApi) =
        DirectoryEditorViewModel(WorkspaceConfigRepository(api), "ws-1")

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load leaves no draft and refuses every edit`() = runTest {
        // ⛔ THE ASSERTION THE WHOLE PACKAGE EXISTS FOR. With no baseline, an accumulated edit is
        // the input to a wholesale replace — so the mutators must be inert, not merely unrendered.
        val api = api().apply {
            workspaceConfigResult = ApiResult.NetworkFailure(IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editNewEntry(DirectoryField.NAME, "Night desk")
        vm.editNewEntry(DirectoryField.PHONE_NUMBER, "+14165550100")
        vm.addEntry()
        vm.edit(0, DirectoryField.NAME, "hijacked")
        vm.remove(0)
        vm.save()
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.load is ConfigState.LoadFailed)
        assertNull("no draft may exist without a baseline", state.draft)
        assertFalse(state.editable)
        assertFalse(state.canSave)
        assertTrue("nothing may have been written", api.directoryPatches.isEmpty())
    }

    @Test
    fun `a stored array this client cannot model is editable=false and NOT a load failure`() =
        runTest {
            // ⛔ A THIRD STATE. The read succeeded, so a retry would return the same value — but the
            // array holds an element that is not an object, which the column permits because it was
            // a bare `Json` write before the route gained validation. Editing around it would delete
            // it on the next save.
            val vm = viewModel(api(Json.parseToJsonElement("""["not-an-object"]""")))
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state.load is ConfigState.Ready)
            assertTrue(state.unmodellable)
            assertFalse(state.editable)
            assertFalse(state.canSave)
        }

    @Test
    fun `an absent directory hydrates as an empty editable list`() = runTest {
        // ⚠️ NOT UNMODELLABLE. The save route writes `callDirectory || []`, so "never configured"
        // and "explicitly empty" store identically — refusing here would leave a new workspace
        // unable to add its first transfer target from a phone.
        val vm = viewModel(api(directory = null))
        advanceUntilIdle()

        assertTrue(vm.state.value.editable)
        assertEquals(0, vm.state.value.entries.size)
    }

    // ── Editing ──────────────────────────────────────────────────────────────

    @Test
    fun `hydration comes only from the load and an untouched form is not dirty`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(2, state.entries.size)
        assertEquals("Ops desk", state.entries[0].value(DirectoryField.NAME))
        assertNull("nothing was edited, so there is no draft", state.draft)
        assertFalse(state.dirty)
        assertFalse(state.canSave)
    }

    @Test
    fun `an add is refused unless BOTH boxes are filled, exactly as the web form refuses it`() =
        runTest {
            val vm = viewModel(api())
            advanceUntilIdle()

            vm.editNewEntry(DirectoryField.NAME, "Night desk")
            vm.addEntry()

            assertTrue("a blank number must be refused", vm.state.value.addRejected)
            assertEquals(2, vm.state.value.entries.size)

            // ⚠️ Typing clears the rejection, so the warning belongs to the attempt that earned it.
            vm.editNewEntry(DirectoryField.PHONE_NUMBER, "+14165550100")
            assertFalse(vm.state.value.addRejected)

            vm.addEntry()
            assertEquals(3, vm.state.value.entries.size)
            assertEquals("Night desk", vm.state.value.entries[2].value(DirectoryField.NAME))
            // ⚠️ The pending boxes clear so the next add starts empty rather than re-adding.
            assertEquals("", vm.state.value.newName)
        }

    @Test
    fun `a phone number in any format is accepted, because the server accepts one`() = runTest {
        // ⚠️ NO E.164 RULE HERE. Real directories carry extensions and national formats, and the
        // schema has both fields `.nullish()`. A stricter client would refuse data the product
        // already stores.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.editNewEntry(DirectoryField.NAME, "Reception")
        vm.editNewEntry(DirectoryField.PHONE_NUMBER, "416-555-0101 x402")
        vm.addEntry()

        assertFalse(vm.state.value.addRejected)
        assertEquals("416-555-0101 x402", vm.state.value.entries[2].value(DirectoryField.PHONE_NUMBER))
    }

    @Test
    fun `editing one field keeps the row's unmodelled keys`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.edit(1, DirectoryField.NAME, "On-call (primary)")

        val edited = vm.state.value.entries[1]
        assertEquals("On-call (primary)", edited.value(DirectoryField.NAME))
        // ⛔ THE KEY THE CLIENT DOES NOT MODEL SURVIVED. Rebuilding the entry from name and number
        // would delete it on the next save, with a 200 and no error anywhere.
        assertEquals("402", (edited.raw["extension"] as JsonPrimitive).content)
    }

    @Test
    fun `removing is local until the save`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.remove(0)

        assertEquals(1, vm.state.value.entries.size)
        assertTrue(vm.state.value.dirty)
        assertTrue("a removal must not reach the server on its own", api.directoryPatches.isEmpty())
    }

    @Test
    fun `an out-of-range index is ignored rather than crashing`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.remove(9)
        vm.edit(9, DirectoryField.NAME, "x")

        assertEquals(2, vm.state.value.entries.size)
        assertNull(vm.state.value.draft)
    }

    @Test
    fun `an incomplete row is flagged but never blocks the save`() = runTest {
        // ⚠️ THE DELIBERATE GAP AGAINST THE WEB. Its form has no edit control at all, so it has no
        // edit-time rule to copy — and blanking a field is legal server-side. Warn, do not refuse.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.edit(0, DirectoryField.PHONE_NUMBER, "")

        assertEquals(1, vm.state.value.incompleteCount)
        assertTrue("an incomplete row must still be savable", vm.state.value.canSave)
    }

    // ── Saving ───────────────────────────────────────────────────────────────

    @Test
    fun `the save sends the FULL array, unmodelled keys and all`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(0, DirectoryField.PHONE_NUMBER, "+14165550123")
        vm.save()
        advanceUntilIdle()

        val sent = api.directoryPatches.single()
        assertEquals("ws-1", sent.workspaceId)
        assertEquals(2, sent.callDirectory.size)
        assertEquals(
            "+14165550123",
            ((sent.callDirectory[0] as JsonObject)["phoneNumber"] as JsonPrimitive).content,
        )
        // ⛔ THE UNTOUCHED ROW WENT BACK WHOLE.
        assertEquals(
            "402",
            ((sent.callDirectory[1] as JsonObject)["extension"] as JsonPrimitive).content,
        )
    }

    @Test
    fun `an edit and its inverse leave the array byte-identical and NOT dirty`() = runTest {
        // ⛔ THE PROPERTY THAT MAKES AN ACCIDENTAL SAVE HARMLESS, driven the way an operator
        // actually reaches it: change something, change it back. Two things have to hold at once —
        // the raw JSON must be identical (a model that rebuilt the entry would have dropped
        // `extension` and produced a different array), and the form must report itself CLEAN, which
        // is what stops the save button from offering a wholesale replace for no change at all.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(0, DirectoryField.NAME, "temporary")
        vm.edit(0, DirectoryField.NAME, "Ops desk")

        val rebuilt = JsonArray(vm.state.value.entries.map { it.raw })
        assertEquals(
            Json.encodeToString(JsonArray.serializer(), storedDirectory as JsonArray),
            Json.encodeToString(JsonArray.serializer(), rebuilt),
        )
        assertFalse("an array equal to the baseline is not a change", vm.state.value.dirty)
        assertFalse(vm.state.value.canSave)

        vm.save()
        advanceUntilIdle()
        assertTrue("a clean form must not spend a wholesale replace", api.directoryPatches.isEmpty())
    }

    @Test
    fun `an empty list is savable and reaches the wire as an empty array`() = runTest {
        // ⛔ THIS IS THE WIPE, AND IT IS EXPRESSIBLE ON PURPOSE. The guard is the screen's
        // confirmation, whose wording names the consequence — `savingEmptiesDirectory` is what
        // selects it, and it is asserted here rather than left to the screen alone.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.remove(0)
        vm.remove(0)

        assertTrue(vm.state.value.savingEmptiesDirectory)
        assertTrue(vm.state.value.canSave)

        vm.save()
        advanceUntilIdle()

        assertEquals(0, api.directoryPatches.single().callDirectory.size)
    }

    @Test
    fun `a successful save adopts the re-read and drops the draft`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(0, DirectoryField.NAME, "Ops (day)")
        vm.save()
        advanceUntilIdle()

        val state = vm.state.value
        assertEquals(SaveState.Saved, state.save)
        assertNull("the draft must clear or the screen keeps reading as changed", state.draft)
        assertFalse(state.dirty)
        // ⚠️ Two reads: the initial load and the mandatory re-read after a write that echoes nothing.
        assertEquals(listOf("ws-1", "ws-1"), api.configRequests)
    }

    @Test
    fun `a landed write whose re-read failed is SavedButStale, never a failure`() = runTest {
        // ⛔ THE DANGEROUS DIRECTION IS REPORTING THIS AS UNSAVED. An operator told their change did
        // not land will change the form back and save again — replacing the stored directory from
        // state the client can no longer vouch for.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(0, DirectoryField.NAME, "Ops (day)")
        api.workspaceConfigResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.save is SaveState.SavedButStale)
        assertNull("the write landed, so the draft is no longer pending", vm.state.value.draft)
    }

    @Test
    fun `a failed save KEEPS the operator's edits`() = runTest {
        val api = api().apply {
            saveDirectoryResult = ApiResult.HttpFailure(500, "Internal Server Error")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(0, DirectoryField.NAME, "Ops (day)")
        vm.save()
        advanceUntilIdle()

        assertTrue(vm.state.value.save is SaveState.Failed)
        assertEquals("Ops (day)", vm.state.value.entries[0].value(DirectoryField.NAME))
        assertTrue("a failed save must stay retryable", vm.state.value.canSave)
    }

    @Test
    fun `a reload discards the draft, which is the safe direction`() = runTest {
        // ⚠️ A session change replays the LOAD. The client's belief about the stored array is no
        // longer something it can vouch for, so dropping the draft is correct even though it loses
        // typing — the alternative is saving a wholesale replace from a stale baseline.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.edit(0, DirectoryField.NAME, "half-typed")
        vm.load()
        advanceUntilIdle()

        assertNull(vm.state.value.draft)
        assertEquals("Ops desk", vm.state.value.entries[0].value(DirectoryField.NAME))
    }

    @Test
    fun `nothing can change the list while a save is in flight`() = runTest {
        // ⛔ THE ARRAY ON THE WIRE IS THE ONE THE OPERATOR CONFIRMED. An add, an edit or a removal
        // landing between the tap and the answer would leave the screen showing a list the server
        // never received, and the re-read would then quietly throw it away.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.edit(0, DirectoryField.NAME, "Ops (day)")
        vm.editNewEntry(DirectoryField.NAME, "Front desk")
        vm.editNewEntry(DirectoryField.PHONE_NUMBER, "+14165550100")
        vm.save()
        vm.addEntry()
        vm.edit(1, DirectoryField.NAME, "late edit")
        vm.remove(0)

        assertEquals(SaveState.Saving, vm.state.value.save)
        assertEquals(2, vm.state.value.entries.size)
        assertEquals("Ops (day)", vm.state.value.entries[0].value(DirectoryField.NAME))
        assertEquals("On-call engineer", vm.state.value.entries[1].value(DirectoryField.NAME))
        assertEquals("the pending row is not consumed", "Front desk", vm.state.value.newName)
        advanceUntilIdle()

        val sent = api.directoryPatches.single().callDirectory
        assertEquals(2, sent.size)
        assertEquals(JsonPrimitive("Ops (day)"), (sent[0] as JsonObject)["name"])
    }

    @Test
    fun `removing a row that does not exist changes nothing`() = runTest {
        // ⚠️ Both ends of the range: a stale index from a recomposition must not become a deletion
        // of some other row, and must not make an untouched list dirty.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.remove(-1)
        vm.remove(2)

        assertNull(vm.state.value.draft)
        assertEquals(2, vm.state.value.entries.size)
        assertFalse(vm.state.value.hasUnsavedChanges)
    }

    @Test
    fun `the factory builds a ViewModel that reads the workspace it was given`() = runTest {
        val api = api()
        val vm = DirectoryEditorViewModel
            .factory(WorkspaceConfigRepository(api), "ws-1")
            .create(DirectoryEditorViewModel::class.java, CreationExtras.Empty)
        advanceUntilIdle()

        assertEquals(listOf("ws-1"), api.configRequests)
        assertEquals("Ops desk", vm.state.value.entries[0].value(DirectoryField.NAME))
    }
}
