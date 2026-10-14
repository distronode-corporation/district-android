package com.distronode.districtai.ui.settings.workspace

import com.distronode.districtai.core.data.KnowledgeRepository
import com.distronode.districtai.core.model.KnowledgeListResponse
import com.distronode.districtai.core.model.KnowledgeModeResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Read a text file" on the knowledge screen, as District AI for Windows does it.
 *
 * ⛔ THE ASSERTION THAT MATTERS: A PICK NEVER SENDS. The add is the billed call, so a file only
 * fills the draft and the operator presses Add after reading it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeFileViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private fun api() = FakeDistrictApi().apply {
        knowledgeListResult = ApiResult.Success(KnowledgeListResponse(success = true, documents = emptyList()))
        knowledgeModeResult = ApiResult.Success(KnowledgeModeResponse(success = true, mode = "internal"))
    }

    private fun viewModel(
        api: FakeDistrictApi,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        reader: KnowledgeFileReader,
    ) = KnowledgeViewModel(KnowledgeRepository(api), "ws-1", role, reader)

    private fun readerOf(name: String, text: String) =
        KnowledgeFileReader { PickedTextFile(name, text.toByteArray()) }

    @Test
    fun `a text file fills a new draft and sends nothing`() = runTest {
        val api = api()
        val uris = mutableListOf<String>()
        val vm = viewModel(api) { uri ->
            uris += uri
            PickedTextFile("Refunds.txt", "30 days.\r\n".toByteArray())
        }
        advanceUntilIdle()

        vm.readFile("content://docs/1")
        assertTrue("the add waits for the read", vm.state.value.fileReading)
        assertFalse(vm.state.value.canPickFile)
        advanceUntilIdle()

        assertEquals(listOf("content://docs/1"), uris)
        assertEquals("Refunds", vm.state.value.draftTitle)
        assertEquals("30 days.\n", vm.state.value.draftContent)
        assertFalse(vm.state.value.fileReading)
        assertNull(vm.state.value.fileRefusal)
        assertTrue(vm.state.value.canAdd)
        assertTrue("nothing is uploaded until Add", api.knowledgeCreates.isEmpty())
    }

    @Test
    fun `a title already typed is kept, and the text replaces the draft's`() = runTest {
        val vm = viewModel(api(), reader = readerOf("hours.md", "Open 9 to 5"))
        advanceUntilIdle()
        vm.editTitle("Opening hours")
        vm.editContent("old text")

        vm.readFile("content://docs/2")
        advanceUntilIdle()

        assertEquals("Opening hours", vm.state.value.draftTitle)
        assertEquals("Open 9 to 5", vm.state.value.draftContent)
    }

    @Test
    fun `a refused file leaves the draft as it was and says why until the next edit`() = runTest {
        val vm = viewModel(api(), reader = readerOf("scan.txt", "%PDF-1.7"))
        advanceUntilIdle()
        vm.editTitle("Kept")

        vm.readFile("content://docs/3")
        advanceUntilIdle()

        assertEquals(KnowledgeFileRefusal.NOT_TEXT, vm.state.value.fileRefusal)
        assertEquals("Kept", vm.state.value.draftTitle)
        assertEquals("", vm.state.value.draftContent)

        vm.editContent("typed instead")
        assertNull("an edit clears the refusal", vm.state.value.fileRefusal)
        vm.readFile("content://docs/3")
        advanceUntilIdle()
        vm.editTitle("Kept, renamed")
        assertNull(vm.state.value.fileRefusal)
    }

    @Test
    fun `a document the provider cannot give is could-not-read`() = runTest {
        val vm = viewModel(api(), reader = { null })
        advanceUntilIdle()

        vm.readFile("content://docs/gone")
        advanceUntilIdle()

        assertEquals(KnowledgeFileRefusal.UNREADABLE, vm.state.value.fileRefusal)
        assertFalse(vm.state.value.fileReading)
    }

    @Test
    fun `an add clears a standing refusal`() = runTest {
        val api = api()
        val vm = viewModel(api, reader = readerOf("big.txt", ""))
        advanceUntilIdle()
        vm.readFile("content://docs/empty")
        advanceUntilIdle()
        assertEquals(KnowledgeFileRefusal.EMPTY, vm.state.value.fileRefusal)

        // A refusal can stand while both boxes already hold text.
        vm.editTitle("Hours")
        vm.editContent("Nine to five")
        vm.readFile("content://docs/empty")
        advanceUntilIdle()
        assertEquals(KnowledgeFileRefusal.EMPTY, vm.state.value.fileRefusal)

        vm.addDocument()
        assertNull(vm.state.value.fileRefusal)
        advanceUntilIdle()
        assertEquals(1, api.knowledgeCreates.size)
    }

    @Test
    fun `a second pick or an add while a file is being read is ignored`() = runTest {
        val api = api()
        val gate = CompletableDeferred<PickedTextFile?>()
        var reads = 0
        val vm = viewModel(api) {
            reads++
            gate.await()
        }
        advanceUntilIdle()
        vm.editTitle("Hours")
        vm.editContent("Nine to five")
        assertTrue(vm.state.value.canAdd)

        vm.readFile("content://docs/1")
        advanceUntilIdle()
        assertFalse("the add waits for the read", vm.state.value.canAdd)
        vm.readFile("content://docs/2")
        vm.addDocument()
        advanceUntilIdle()

        assertEquals(1, reads)
        assertTrue(api.knowledgeCreates.isEmpty())

        gate.complete(PickedTextFile("hours.txt", "Ten to six".toByteArray()))
        advanceUntilIdle()
        assertEquals("Ten to six", vm.state.value.draftContent)
        assertTrue(vm.state.value.canAdd)
    }

    @Test
    fun `a file that lands while a delete is in flight still fills the draft`() = runTest {
        // ⚠️ Neither a delete nor a mode change touches the draft, and the add waits for the read,
        // so the pick is applied rather than silently lost.
        val api = api()
        val gate = CompletableDeferred<PickedTextFile?>()
        val vm = viewModel(api, reader = { gate.await() })
        advanceUntilIdle()

        vm.readFile("content://docs/1")
        advanceUntilIdle()
        vm.deleteDocument("doc-1")
        assertTrue(vm.state.value.busy)
        gate.complete(PickedTextFile("hours.txt", "Ten to six".toByteArray()))
        advanceUntilIdle()

        assertEquals("hours", vm.state.value.draftTitle)
        assertEquals("Ten to six", vm.state.value.draftContent)
    }

    @Test
    fun `no pick is read while another write is in flight, nor for a viewer`() = runTest {
        var reads = 0
        val reader = KnowledgeFileReader {
            reads++
            null
        }
        val vm = viewModel(api(), reader = reader)
        advanceUntilIdle()
        vm.deleteDocument("doc-1")
        assertFalse(vm.state.value.canPickFile)
        vm.readFile("content://docs/1")

        val viewer = viewModel(api(), WorkspaceRole.VIEWER, reader)
        viewer.readFile("content://docs/1")
        advanceUntilIdle()

        assertEquals(0, reads)
        assertNull(viewer.state.value.fileRefusal)
    }
}
