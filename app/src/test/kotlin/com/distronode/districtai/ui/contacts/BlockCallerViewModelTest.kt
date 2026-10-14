package com.distronode.districtai.ui.contacts

import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.data.ContactBlockingRepository
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.BlockedContactsResponse
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
class BlockCallerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val main = MainDispatcherRule(dispatcher)

    private val api = FakeContactBlockingApi()
    private val store = BlockedContactsStore(ContactBlockingRepository(api))

    @Test
    fun `the state shown is the store's`() = runTest(dispatcher) {
        val vm = BlockCallerViewModel(store, "ws", BlockSubject.Contact("c1"))
        api.blockedResult = ApiResult.Success(
            BlockedContactsResponse(true, listOf(BlockedContact("c1", "Ada", null, "2026-10-13T12:00:00.000Z"))),
        )
        vm.refresh()
        advanceUntilIdle()
        assertTrue(vm.blocked.value)
    }

    @Test
    fun `a confirmed block calls back, an unblock does not`() = runTest(dispatcher) {
        val vm = BlockCallerViewModel(store, "ws", BlockSubject.Contact("c1"))
        var blockedCalls = 0
        vm.setBlocked(true) { blockedCalls++ }
        advanceUntilIdle()
        assertEquals(1, blockedCalls)
        assertTrue(vm.blocked.value)

        vm.setBlocked(false) { blockedCalls++ }
        advanceUntilIdle()
        assertEquals(1, blockedCalls)
        assertFalse(vm.blocked.value)
    }

    @Test
    fun `a second write while one is in flight does nothing`() = runTest(dispatcher) {
        val vm = BlockCallerViewModel(store, "ws", BlockSubject.Phone("+15550142"))
        api.writeGate = CompletableDeferred()
        vm.setBlocked(true)
        advanceUntilIdle()
        assertTrue(vm.busy.value)
        vm.setBlocked(true)
        api.writeGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, api.writes.size)
        assertFalse(vm.busy.value)
        // A caller known only by number never reads as blocked here.
        assertFalse(vm.blocked.value)
    }

    @Test
    fun `a failure is kept until dismissed, and calls nothing back`() = runTest(dispatcher) {
        val vm = BlockCallerViewModel(store, "ws", BlockSubject.Contact("c1"))
        api.writeResult = ApiResult.Forbidden("Viewers cannot block callers.")
        var called = false
        vm.setBlocked(true) { called = true }
        advanceUntilIdle()
        assertFalse(called)
        assertNotNull(vm.failure.value)
        vm.clearFailure()
        assertNull(vm.failure.value)
    }
}
