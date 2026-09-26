package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.KnowledgeRepository
import com.distronode.districtai.core.model.KB_MODE_INTERNAL
import com.distronode.districtai.core.model.KB_MODE_LINKED
import com.distronode.districtai.core.model.KnowledgeCreateResponse
import com.distronode.districtai.core.model.KnowledgeDocument
import com.distronode.districtai.core.model.KnowledgeListResponse
import com.distronode.districtai.core.model.KnowledgeModeResponse
import com.distronode.districtai.core.model.WorkspaceRole
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The knowledge screen's state machine.
 *
 * ⛔ TWO THINGS HERE COST REAL MONEY OR REAL PRIVACY, AND BOTH HAVE THEIR OWN TESTS. An upload buys
 * one embedding run over every chunk the content produced, so a double-tap must not buy two; and
 * the mode decides whether a customer's questions leave their region, so a failed mode READ must
 * never render as a chosen mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val documents = listOf(
        KnowledgeDocument(id = "doc-1", title = "Refund policy", status = "ready", chunkCount = 4),
        KnowledgeDocument(id = "doc-2", title = "Service area", status = "processing"),
    )

    private fun api() = TestDistrictApi().apply {
        knowledgeListResult = ApiResult.Success(
            KnowledgeListResponse(success = true, documents = documents),
        )
        knowledgeModeResult = ApiResult.Success(
            KnowledgeModeResponse(success = true, mode = "internal"),
        )
    }

    private fun viewModel(
        api: TestDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
    ) = KnowledgeViewModel(KnowledgeRepository(api), "ws-1", role)

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun `the two reads land independently`() = runTest {
        val vm = viewModel(api())
        advanceUntilIdle()

        assertEquals(documents, (vm.state.value.list as KnowledgeListState.Ready).documents)
        assertEquals("internal", vm.state.value.mode)
        assertFalse(vm.state.value.modeUnavailable)
        assertTrue(vm.state.value.canChangeMode)
    }

    @Test
    fun `a failed MODE read withholds the selector and leaves the documents alone`() = runTest {
        // ⛔ "WE COULD NOT READ THE MODE" AND "THE MODE IS INTERNAL" ARE DIFFERENT CLAIMS ABOUT
        // WHERE A CUSTOMER'S QUESTIONS GO. Rendering the default as selected would be the second
        // claim made from the first fact, and the radio the operator then leaves alone would look
        // like a choice they made.
        val api = api().apply {
            knowledgeModeResult = ApiResult.NetworkFailure(IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertNull("null, NOT the default", vm.state.value.mode)
        assertTrue(vm.state.value.modeUnavailable)
        assertFalse(vm.state.value.canChangeMode)
        assertTrue("the documents still loaded", vm.state.value.list is KnowledgeListState.Ready)
    }

    @Test
    fun `a failed DOCUMENT read leaves the mode alone`() = runTest {
        val api = api().apply {
            knowledgeListResult = ApiResult.NetworkFailure(IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        assertTrue(vm.state.value.list is KnowledgeListState.Failed)
        assertEquals("internal", vm.state.value.mode)
    }

    // ── Adding ───────────────────────────────────────────────────────────────

    @Test
    fun `an add is refused unless BOTH a title and content are present`() = runTest {
        // ⚠️ THE ROUTE'S OWN RULE (400 "Missing title or content"), checked here so an operator is
        // not charged a round trip to be told, and so the button is honest.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editTitle("Holiday hours")
        vm.addDocument()
        advanceUntilIdle()

        assertTrue(vm.state.value.addRejected)
        assertTrue("nothing may have been sent", api.knowledgeCreates.isEmpty())

        vm.editContent("Closed on the 25th.")
        assertFalse(vm.state.value.addRejected)
    }

    @Test
    fun `a successful add clears the draft and RE-READS the list`() = runTest {
        // ⛔ THE LIST IS RE-READ RATHER THAN PATCHED. The create's echoed row omits `sourceUrl`
        // (the POST's `select` is one field shorter), so appending it would show a document whose
        // source silently disappeared until the next full read.
        val api = api().apply {
            knowledgeCreateResult = ApiResult.Success(
                KnowledgeCreateResponse(
                    success = true,
                    document = KnowledgeDocument(id = "doc-new", title = "Holiday hours"),
                ),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editTitle("Holiday hours")
        vm.editContent("Closed on the 25th.")
        vm.addDocument()
        advanceUntilIdle()

        assertEquals(SaveState.Saved, vm.state.value.addSave)
        assertEquals("", vm.state.value.draftTitle)
        assertEquals("", vm.state.value.draftContent)
        // Two reads: the initial load and the re-read after the write.
        assertEquals(listOf("ws-1", "ws-1"), api.knowledgeListRequests)
    }

    @Test
    fun `a second tap during an in-flight upload cannot buy a second embedding run`() = runTest {
        // ⛔ THE GUARD IS A SPENDING CONTROL, NOT A LOADING AFFORDANCE. One call is one embedding
        // run over every chunk the content produced, and the caller sets the size of that bill.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editTitle("Holiday hours")
        vm.editContent("Closed on the 25th.")
        vm.addDocument()
        // ⚠️ No advanceUntilIdle between them: the first is still in flight, which is exactly the
        // window a double tap lands in.
        vm.addDocument()
        advanceUntilIdle()

        assertEquals(1, api.knowledgeCreates.size)
    }

    @Test
    fun `a failed add KEEPS the pasted text`() = runTest {
        // ⚠️ Losing a pasted document because the upload failed would be two losses for one fault.
        val api = api().apply {
            knowledgeCreateResult = ApiResult.HttpFailure(429, "Too many knowledge uploads")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.editTitle("Holiday hours")
        vm.editContent("Closed on the 25th.")
        vm.addDocument()
        advanceUntilIdle()

        assertTrue(vm.state.value.addSave is SaveState.Failed)
        assertEquals("Closed on the 25th.", vm.state.value.draftContent)
    }

    // ── Deleting ─────────────────────────────────────────────────────────────

    @Test
    fun `a delete names the document and RE-READS rather than dropping the row`() = runTest {
        // ⛔ SUCCESS DOES NOT PROVE A ROW WAS REMOVED — the route's `deleteMany` never reads its
        // count, so another tenant's id answers identically.
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.deleteDocument("doc-2")
        advanceUntilIdle()

        assertEquals(listOf("ws-1" to "doc-2"), api.knowledgeDeletes)
        assertEquals(SaveState.Saved, vm.state.value.deleteSave)
        assertEquals(listOf("ws-1", "ws-1"), api.knowledgeListRequests)
    }

    @Test
    fun `a failed delete still re-reads, so the row's real state is what is shown`() = runTest {
        val api = api().apply {
            knowledgeDeleteResult = ApiResult.HttpFailure(500, "Internal Server Error")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.deleteDocument("doc-2")
        advanceUntilIdle()

        assertTrue(vm.state.value.deleteSave is SaveState.Failed)
        assertEquals(2, api.knowledgeListRequests.size)
    }

    // ── The mode ─────────────────────────────────────────────────────────────

    @Test
    fun `setMode adopts the server's echo rather than the value it asked for`() = runTest {
        // ⛔ THE ROUTE RE-READS THROUGH ITS SANITISER BEFORE ANSWERING, so the echo is what a later
        // read will see. Adopting the request would report a residency choice the server did not
        // store.
        val api = api().apply {
            saveKnowledgeModeResult = ApiResult.Success(
                KnowledgeModeResponse(success = true, mode = "internal"),
            )
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setMode("linked")
        advanceUntilIdle()

        assertEquals("linked", api.knowledgeModePatches.single().mode)
        assertEquals("internal", vm.state.value.mode)
        assertEquals(SaveState.Saved, vm.state.value.modeSave)
    }

    @Test
    fun `choosing the mode that is already stored is a no-op`() = runTest {
        val api = api()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setMode("internal")
        advanceUntilIdle()

        assertTrue("no request for a change nobody made", api.knowledgeModePatches.isEmpty())
    }

    @Test
    fun `the mode cannot be changed when it was never read`() = runTest {
        // ⛔ AN EDIT AGAINST AN UNKNOWN VALUE. Without a successful read there is nothing to change
        // FROM, and the one thing worse than not offering the control is offering it seeded from a
        // guess about where a customer's questions currently go.
        val api = api().apply {
            knowledgeModeResult = ApiResult.NetworkFailure(IOException("offline"))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setMode("linked")
        advanceUntilIdle()

        assertTrue(api.knowledgeModePatches.isEmpty())
    }

    @Test
    fun `a failed mode write is surfaced and the stored mode is unchanged`() = runTest {
        val api = api().apply {
            saveKnowledgeModeResult = ApiResult.HttpFailure(500, "Could not save the knowledge source.")
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.setMode("linked")
        advanceUntilIdle()

        assertTrue(vm.state.value.modeSave is SaveState.Failed)
        assertEquals("internal", vm.state.value.mode)
    }

    @Test
    fun `isResidencyChange is true only when moving TO linked`() = runTest {
        // ⚠️ WHAT SELECTS THE CONFIRMATION. Re-selecting `linked` on a workspace that already chose
        // it is not a residency change and must not ask again.
        val vm = viewModel(api())
        advanceUntilIdle()

        assertTrue(vm.state.value.isResidencyChange("linked"))
        assertFalse(vm.state.value.isResidencyChange("internal"))
    }

    @Test
    fun `a viewer reads both halves and cannot reach a single write`() = runTest {
        // ⛔ THE UI GATE IS NOT THE ONLY GATE. Both reads admit `viewer` server-side and all three
        // writes exclude one, and a viewer genuinely arrives here through the settings hub.
        // Hiding a button is a UX decision; this is the
        // assertion that the ViewModel refuses the call even when something else invokes it.
        val api = api()
        val model = viewModel(api, WorkspaceRole.VIEWER)
        advanceUntilIdle()

        assertFalse(model.state.value.canWrite)
        assertEquals(documents, model.state.value.documents)
        assertEquals(KB_MODE_INTERNAL, model.state.value.mode)
        assertFalse(model.state.value.canChangeMode)

        model.editTitle("Refunds")
        model.editContent("Thirty days.")
        // ⚠️ `canAdd` IS FALSE FOR A VIEWER EVEN WITH BOTH FIELDS FILLED, so the button is honest
        // and the guard is real.
        assertFalse(model.state.value.canAdd)

        model.addDocument()
        model.deleteDocument("doc-1")
        model.setMode(KB_MODE_LINKED)
        advanceUntilIdle()

        assertTrue(api.knowledgeCreates.isEmpty())
        assertTrue(api.knowledgeDeletes.isEmpty())
        assertTrue(api.knowledgeModePatches.isEmpty())
    }

    @Test
    fun `an unparseable role is treated as a viewer rather than as a client`() = runTest {
        // ⛔ FAILS CLOSED. `WorkspaceRole.fromWire` answers null for a value this build does not
        // know; assuming CLIENT would show write controls to a viewer whose role string arrived
        // misspelled, and every one of those actions would 403.
        val model = viewModel(api(), role = null)
        advanceUntilIdle()

        assertFalse(model.state.value.canWrite)
        assertFalse(model.state.value.canChangeMode)
    }
}
