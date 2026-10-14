package com.distronode.districtai.ui.contacts

import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.data.ContactBlockingRepository
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeContactBlockingApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedContactsStoreTest {

    private val api = FakeContactBlockingApi()
    private val store = BlockedContactsStore(ContactBlockingRepository(api))
    private val ada = BlockedContact("c1", "Ada", "+14165550142", "2026-10-13T12:00:00.000Z")

    @Test
    fun `a read replaces the list, and a failed one keeps it`() = runTest {
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(success = true, blocked = listOf(ada)))
        store.refresh("ws")
        assertEquals(BlockedContactsStore.Snapshot("ws", listOf(ada)), store.state.value)

        api.blockedResult = ApiResult.NotFound("gone")
        assertEquals(ApiResult.NotFound("gone"), store.refresh("ws"))
        assertEquals(listOf(ada), store.state.value.contacts)
    }

    @Test
    fun `a block is adopted from the reply, before any read`() = runTest {
        store.setBlocked("ws", BlockSubject.Phone("+15550142"), blocked = true)
        val snapshot = store.state.value
        assertEquals("ws", snapshot.workspaceId)
        assertEquals(listOf("contact-for-+15550142"), snapshot.contacts.map { it.contactId })
        assertTrue(snapshot.isBlocked("ws", "contact-for-+15550142"))
    }

    @Test
    fun `an unblock removes the caller, and a block in another workspace starts afresh`() = runTest {
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(success = true, blocked = listOf(ada)))
        store.refresh("ws")
        store.setBlocked("ws", BlockSubject.Contact("c2"), blocked = true)
        assertEquals(setOf("c1", "c2"), store.state.value.idsFor("ws"))
        store.setBlocked("ws", BlockSubject.Contact("c1"), blocked = false)
        assertEquals(setOf("c2"), store.state.value.idsFor("ws"))

        store.setBlocked("ws-2", BlockSubject.Contact("c9"), blocked = true)
        assertEquals(setOf("c9"), store.state.value.idsFor("ws-2"))
        assertEquals(emptySet<String>(), store.state.value.idsFor("ws"))
    }

    @Test
    fun `a failed write changes nothing`() = runTest {
        api.writeResult = ApiResult.Forbidden("viewer")
        assertEquals(ApiResult.Forbidden("viewer"), store.setBlocked("ws", BlockSubject.Contact("c1"), true))
        assertEquals(BlockedContactsStore.Snapshot(), store.state.value)
    }

    @Test
    fun `an unknown workspace or no contact reads as not blocked`() {
        val snapshot = BlockedContactsStore.Snapshot("ws", listOf(ada))
        assertTrue(snapshot.isBlocked("ws", "c1"))
        assertFalse(snapshot.isBlocked("other", "c1"))
        assertFalse(snapshot.isBlocked("ws", null))
        assertFalse(snapshot.isBlocked("ws", "c2"))
    }
}
