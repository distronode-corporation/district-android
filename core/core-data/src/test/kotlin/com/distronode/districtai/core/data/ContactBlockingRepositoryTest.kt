package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.BlockedContactsResponse
import com.distronode.districtai.core.model.ContactBlockResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.testing.FakeContactBlockingApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactBlockingRepositoryTest {

    private val api = FakeContactBlockingApi()
    private val repository = ContactBlockingRepository(api)

    @Test
    fun `a subject sends exactly one identity`() = runTest {
        repository.setBlocked("ws", BlockSubject.Contact("c1"), blocked = true)
        repository.setBlocked("ws", BlockSubject.Phone("+14165550142"), blocked = false)
        assertEquals(
            listOf(
                FakeContactBlockingApi.Write("ws", "c1", null, true),
                FakeContactBlockingApi.Write("ws", null, "+14165550142", false),
            ),
            api.writes,
        )
    }

    @Test
    fun `a write that does not affirm success is drift, and a failure passes through`() = runTest {
        api.writeResult = ApiResult.Success(ContactBlockResponse(success = false))
        assertTrue(repository.setBlocked("ws", BlockSubject.Contact("c1"), true) is ApiResult.DecodeFailure)
        api.writeResult = ApiResult.Forbidden("viewer")
        assertEquals(ApiResult.Forbidden("viewer"), repository.setBlocked("ws", BlockSubject.Contact("c1"), true))
    }

    @Test
    fun `the list keeps only live blocks, and an empty list is an answer`() = runTest {
        val live = BlockedContact("c1", "Ada", null, "2026-10-13T12:00:00.000Z")
        api.blockedResult = ApiResult.Success(
            BlockedContactsResponse(success = true, blocked = listOf(live, BlockedContact("c2", "Bo", null, null))),
        )
        assertEquals(ApiResult.Success(listOf(live)), repository.blocked("ws"))
        assertEquals(listOf("ws"), api.reads)

        api.blockedResult = ApiResult.Success(BlockedContactsResponse(success = true))
        assertEquals(ApiResult.Success(emptyList<BlockedContact>()), repository.blocked("ws"))
    }

    @Test
    fun `a list read that does not affirm success is drift, and a failure passes through`() = runTest {
        api.blockedResult = ApiResult.Success(BlockedContactsResponse(success = false))
        assertTrue(repository.blocked("ws") is ApiResult.DecodeFailure)
        api.blockedResult = ApiResult.RateLimited("slow")
        assertEquals(ApiResult.RateLimited("slow"), repository.blocked("ws"))
    }
}
