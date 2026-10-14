package com.distronode.districtai.ui.contacts

import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.data.ContactBlockingRepository
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeContactBlockingApi
import com.distronode.districtai.core.network.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedContactsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val api = FakeContactBlockingApi()
    private val store = BlockedContactsStore(ContactBlockingRepository(api))
    private val ada = BlockedContact("c1", "Ada", "+14165550142", "2026-10-13T12:00:00.000Z")

    private fun content(vm: BlockedContactsViewModel) = vm.state.value as BlockedContactsUiState.Content

    @Test
    fun `it reads the list and shows it`() = runTest(dispatcher) {
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(true, listOf(ada)))
        val vm = BlockedContactsViewModel(store, "ws", WorkspaceRole.AGENCY)
        assertEquals(BlockedContactsUiState.Loading, vm.state.value)
        advanceUntilIdle()
        assertEquals(BlockedContactsUiState.Content(listOf(ada)), vm.state.value)
        assertTrue(vm.canMutate)
    }

    @Test
    fun `a failed read is a failure, never an empty list, and a retry recovers`() = runTest(dispatcher) {
        api.blockedResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        val vm = BlockedContactsViewModel(store, "ws", WorkspaceRole.CLIENT)
        advanceUntilIdle()
        assertTrue(vm.state.value is BlockedContactsUiState.Failed)
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(true))
        vm.load()
        advanceUntilIdle()
        assertEquals(BlockedContactsUiState.Content(emptyList()), vm.state.value)
    }

    @Test
    fun `an unblock takes the row away, one at a time`() = runTest(dispatcher) {
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(true, listOf(ada)))
        val vm = BlockedContactsViewModel(store, "ws", WorkspaceRole.AGENCY)
        advanceUntilIdle()
        api.writeGate = CompletableDeferred()
        vm.unblock("c1")
        advanceUntilIdle()
        assertEquals("c1", content(vm).unblocking)
        vm.unblock("c1")
        api.writeGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.writes.size)
        assertFalse(api.writes.single().blocked)
        assertEquals(BlockedContactsUiState.Content(emptyList()), vm.state.value)
    }

    @Test
    fun `a failed unblock keeps the row and says why, until dismissed`() = runTest(dispatcher) {
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(true, listOf(ada)))
        val vm = BlockedContactsViewModel(store, "ws", WorkspaceRole.AGENCY)
        advanceUntilIdle()
        api.writeResult = ApiResult.RateLimited("Try again shortly.")
        vm.unblock("c1")
        advanceUntilIdle()
        assertEquals(listOf(ada), content(vm).contacts)
        assertNotNull(content(vm).failure)
        vm.clearFailure()
        advanceUntilIdle()
        assertNull(content(vm).failure)
    }

    @Test
    fun `a viewer cannot unblock, and another workspace's list is not shown`() = runTest(dispatcher) {
        val vm = BlockedContactsViewModel(store, "ws", WorkspaceRole.VIEWER)
        advanceUntilIdle()
        vm.unblock("c1")
        advanceUntilIdle()
        assertEquals(0, api.writes.size)
        store.setBlocked("other", BlockSubject.Contact("c5"), true)
        advanceUntilIdle()
        assertEquals(emptyList<BlockedContact>(), content(vm).contacts)
    }
}
