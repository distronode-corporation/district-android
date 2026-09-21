package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.WorkspaceConfigRepository
import com.distronode.districtai.core.model.AiPersona
import com.distronode.districtai.core.model.ToolConfig
import com.distronode.districtai.core.model.WorkspaceConfig
import com.distronode.districtai.core.model.WorkspaceConfigResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.TestDistrictApi
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The capability allowlist's state machine — the destructive half of workspace settings.
 *
 * ⛔ `PATCH workspace/tools` SETS `toolConfig.allowedTools` TO EXACTLY WHAT IT RECEIVES. Every test
 * here is a variation on one question: can this ViewModel ever produce a list that is shorter, or
 * differently ordered, than what the workspace actually has? The three ways it could —
 * saving without a load, rebuilding from the hardcoded catalog, and reading an absent list as an
 * empty one — each have a test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * ⛔ DELIBERATELY NOT IN CATALOG ORDER, AND IT CARRIES AN ID THE CATALOG DOES NOT KNOW.
     * `transfer_to_creator` is a retired id that real workspaces still store, so this
     * is the shape the client has to survive rather than an invented edge case.
     */
    private val storedTools = listOf(
        "search_knowledge_base",
        "transfer_to_agent",
        "transfer_to_creator",
        "leave_message",
    )

    private val storedConfig = WorkspaceConfig(
        aiPersona = AiPersona(name = "Ada", dgiEnabled = false),
        toolConfig = ToolConfig(allowedTools = storedTools, supportPhoneNumber = "+14165550123"),
    )

    private fun api(config: WorkspaceConfig = storedConfig) = TestDistrictApi().apply {
        workspaceConfigResult =
            ApiResult.Success(WorkspaceConfigResponse(success = true, config = config))
    }

    private fun viewModel(api: TestDistrictApi) =
        CapabilitiesViewModel(WorkspaceConfigRepository(api), workspaceId = "ws-1")

    // ── The load gate ────────────────────────────────────────────────────────

    @Test
    fun `a failed load reaches no editable state, and cannot write an allowlist`() = runTest {
        // ⛔ THE ONE THAT MATTERS MOST ON THIS SCREEN. With no loaded baseline there is no list to
        // send, and a route that replaces the array wholesale must never receive one this client
        // assembled from nothing.
        val api = api().apply { workspaceConfigResult = ApiResult.NetworkFailure(IOException("down")) }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.load is ConfigState.LoadFailed)
        assertTrue("no rows are drawable", state.rows.isEmpty())
        assertNull("and there is no list to send", state.pendingTools)
        assertFalse(state.canSaveTools)
        assertFalse(state.canSaveEnrichment)

        vm.toggleTool("leave_message", false)
        assertEquals(emptyMap<String, Boolean>(), vm.state.value.toolToggles)

        vm.saveTools()
        vm.saveEnrichment()
        advanceUntilIdle()
        assertTrue(api.toolsPatches.isEmpty())
        assertTrue(api.personaPatches.isEmpty())
    }

    // ── The baseline ─────────────────────────────────────────────────────────

    @Test
    fun `an untouched form saves the IDENTICAL array it loaded`() = runTest {
        // ⛔ THE PROPERTY THAT MAKES AN ACCIDENTAL SAVE HARMLESS. Order and content both, because
        // the server stores the array verbatim — a reorder is a change to stored data.
        val vm = viewModel(api())
        advanceUntilIdle()

        assertEquals(storedTools, vm.state.value.pendingTools)
        assertFalse("and nothing is dirty, so the button is disabled anyway", vm.state.value.toolsDirty)
    }

    @Test
    fun `an id the catalog does not know is still shown and still saved`() = runTest {
        // ⛔ HIDING IT WOULD DELETE IT ON THE NEXT SAVE. It renders as a row with no label, which
        // the screen draws by its raw id.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        val unknown = vm.state.value.rows.single { it.id == "transfer_to_creator" }
        assertNull("the catalog has no name for it", unknown.labelRes)
        assertTrue("but it is enabled, because the workspace stores it", unknown.enabled)

        // Turn an unrelated tool ON; the unknown id must survive untouched.
        vm.toggleTool("dispatch_email", true)
        vm.saveTools()
        advanceUntilIdle()

        assertTrue(
            "the retired id must survive a wholesale replace",
            "transfer_to_creator" in api.toolsPatches.single().allowedTools,
        )
    }

    @Test
    fun `an ABSENT allowlist means the DEFAULTS are on, not none and not everything`() = runTest {
        // ⛔ THE FRESH-WORKSPACE WIPE, PREVENTED. `toolConfig` is null on day one and the web reads
        // that as the nine default-on capabilities; reading it as none and saving would switch the
        // agent off for someone who opened the screen to look at it.
        //
        // ⛔ AND THE MIRROR-IMAGE WIPE. Comparing against CAPABILITY_CATALOG would go on passing as
        // the catalog grew, silently blessing a default of "everything", including a caller-facing
        // cancel and reschedule, on a workspace nobody had configured. Compare against the DEFAULT
        // list and assert the difference explicitly, so growing the catalog cannot slip past this.
        val vm = viewModel(api(WorkspaceConfig()))
        advanceUntilIdle()

        assertEquals(DEFAULT_ALLOWED_TOOL_IDS, vm.state.value.pendingTools)
        assertFalse("the defaults are a strict subset of the catalog", DEFAULT_ALLOWED_TOOL_IDS == CAPABILITY_CATALOG)
        assertFalse("and it is not dirty, so nothing is written on entry", vm.state.value.toolsDirty)
    }

    @Test
    fun `the scheduling tools are OFF for an unconfigured workspace`() = runTest {
        // ⛔ These four act on a real caller's real booking. Switching them on by inference, from a
        // default nobody chose, on a workspace that may not even have a scheduling tenancy, is the
        // one outcome this whole default/catalog split exists to prevent. Asserted on the ROWS as
        // well as the list, because the rows are what an operator sees and then saves.
        val vm = viewModel(api(WorkspaceConfig()))
        advanceUntilIdle()

        for (id in SCHEDULING_TOOL_IDS) {
            assertFalse("$id must default OFF", id in vm.state.value.pendingTools.orEmpty())
            val row = vm.state.value.rows.single { it.id == id }
            assertFalse("$id row must render off", row.enabled)
            assertNotNull("$id must be labelled, not rendered as a raw id", row.labelRes)
        }
    }

    @Test
    fun `an EXPLICITLY empty allowlist stays empty`() = runTest {
        // ⚠️ The other half of the same distinction: this operator turned everything off on
        // purpose, and re-enabling it all on their behalf would be just as wrong.
        val vm = viewModel(api(WorkspaceConfig(toolConfig = ToolConfig(allowedTools = emptyList()))))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), vm.state.value.pendingTools)
        assertTrue(vm.state.value.rows.none { it.enabled })
    }

    // ── The tools write ──────────────────────────────────────────────────────

    @Test
    fun `turning one off sends the full remaining list, in its stored order`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleTool("transfer_to_agent", false)
        assertTrue(vm.state.value.toolsDirty)
        vm.saveTools()
        advanceUntilIdle()

        assertEquals(
            listOf("search_knowledge_base", "transfer_to_creator", "leave_message"),
            api.toolsPatches.single().allowedTools,
        )
        assertEquals("ws-1", api.toolsPatches.single().workspaceId)
    }

    @Test
    fun `turning one on appends it and leaves the loaded order alone`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleTool("book_appointment", true)
        vm.saveTools()
        advanceUntilIdle()

        assertEquals(storedTools + "book_appointment", api.toolsPatches.single().allowedTools)
    }

    @Test
    fun `a toggle flipped and flipped back is not dirty`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.toggleTool("leave_message", false)
        assertTrue(vm.state.value.toolsDirty)
        vm.toggleTool("leave_message", true)
        assertFalse(vm.state.value.toolsDirty)
        assertFalse(vm.state.value.canSaveTools)
    }

    @Test
    fun `a failed tools save keeps the toggles`() = runTest {
        val api = api().apply { saveToolsResult = ApiResult.HttpFailure(500, "boom") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleTool("transfer_to_agent", false)
        vm.saveTools()
        advanceUntilIdle()

        assertTrue(vm.state.value.toolsSave is SaveState.Failed)
        assertTrue("the operator's intent is still on screen", vm.state.value.toolsDirty)
        assertFalse(vm.state.value.rows.single { it.id == "transfer_to_agent" }.enabled)
    }

    @Test
    fun `a tools write that landed but could not be re-read reports SavedButStale`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleTool("transfer_to_agent", false)
        api.workspaceConfigResult = ApiResult.NetworkFailure(IOException("offline"))
        vm.saveTools()
        advanceUntilIdle()

        assertTrue(vm.state.value.toolsSave is SaveState.SavedButStale)
        assertEquals(1, api.toolsPatches.size)
    }

    // ── The enrichment consent flag ──────────────────────────────────────────

    @Test
    fun `the enrichment toggle saves through the PERSONA route, and sends only that field`() =
        runTest {
            // ⛔ IT IS A PERSONA FIELD, exactly as the web's EnrichmentSettingsForm treats it —
            // which is also why it is safe to live on this screen: the persona route merges, so
            // every other persona key is preserved by being absent.
            val api = api()
            val vm = viewModel(api)
            advanceUntilIdle()

            vm.toggleEnrichment(true)
            vm.saveEnrichment()
            advanceUntilIdle()

            val sent = api.personaPatches.single()
            assertEquals(true, sent.dgiEnabled)
            assertNull("nothing else may ride along", sent.name)
            assertNull(sent.greeting)
            assertNull(sent.personality)
            assertTrue("and the allowlist is untouched", api.toolsPatches.isEmpty())
        }

    @Test
    fun `an untouched enrichment switch never sends false`() = runTest {
        // ⛔ THE PUBLISHED SUB-PROCESSOR LIST PROMISES THIS IS OFF UNLESS A WORKSPACE TURNS IT ON,
        // so writing `false` for a form nobody touched would be recording a decision nobody made.
        // Null stored and false stored both render as off, and neither is dirty.
        val vm = viewModel(api(WorkspaceConfig(aiPersona = AiPersona(dgiEnabled = null))))
        advanceUntilIdle()

        assertFalse("null renders as off", vm.state.value.enrichmentEnabled)
        assertFalse(vm.state.value.enrichmentDirty)
        assertFalse(vm.state.value.canSaveEnrichment)
    }

    @Test
    fun `the two sections have independent dirty state and independent save state`() = runTest {
        // ⚠️ TWO ROUTES, TWO BUTTONS, mirroring the web. A failure on one must not report on the
        // other, and either being dirty must trip the leave-the-screen guard.
        val api = api().apply { savePersonaResult = ApiResult.HttpFailure(500, "boom") }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.toggleEnrichment(true)
        assertTrue(vm.state.value.hasUnsavedChanges)
        assertFalse("the allowlist is untouched", vm.state.value.toolsDirty)

        vm.saveEnrichment()
        advanceUntilIdle()

        assertTrue(vm.state.value.enrichmentSave is SaveState.Failed)
        assertEquals("the tools section is unaffected", SaveState.Idle, vm.state.value.toolsSave)
    }

    @Test
    fun `a save in one section disables the other while it is in flight`() = runTest {
        // ⚠️ Two writes racing would leave the re-reads racing too, and the loser would overwrite
        // the winner's baseline.
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.toggleTool("leave_message", false)
        vm.toggleEnrichment(true)
        vm.saveTools()

        assertEquals(SaveState.Saving, vm.state.value.toolsSave)
        assertFalse(vm.state.value.canSaveEnrichment)

        advanceUntilIdle()
        assertTrue(vm.state.value.toolsSave is SaveState.Saved)
    }

    @Test
    fun `a fresh load clears both drafts and both banners`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        vm.toggleTool("leave_message", false)
        vm.toggleEnrichment(true)
        vm.load()
        advanceUntilIdle()

        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals(SaveState.Idle, vm.state.value.toolsSave)
        assertEquals(SaveState.Idle, vm.state.value.enrichmentSave)
    }
}
