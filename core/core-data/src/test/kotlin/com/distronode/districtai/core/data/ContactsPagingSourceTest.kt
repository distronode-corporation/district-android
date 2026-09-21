package com.distronode.districtai.core.data

import androidx.paging.PagingSource
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.ContactListResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What contacts paging does DIFFERENTLY from the call log.
 *
 * ⚠️ The shared deduplication and offset-advance rules are covered by [CallsPagingSourceTest] against
 * the same [OffsetPagingSource]; this file covers only the difference: contacts reports a real
 * `total`, so end-of-list is KNOWN rather than inferred from a short page. Getting that wrong either
 * wastes a request per list or — worse — ends the list early and hides contacts.
 */
class ContactsPagingSourceTest {

    private class ContactsApi(
        private val pages: (limit: Int, offset: Int) -> ApiResult<ContactListResponse>,
    ) : FakeDistrictApi() {
        val requests: MutableList<Pair<Int, Int>> = mutableListOf()

        override suspend fun contacts(
            workspaceId: String,
            limit: Int,
            offset: Int,
        ): ApiResult<ContactListResponse> {
            requests += limit to offset
            return pages(limit, offset)
        }
    }

    private fun contact(id: String) = Contact(
        id = id,
        workspaceId = "ws-1",
        name = "Contact $id",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun page(count: Int, offset: Int, total: Int) = ApiResult.Success(
        ContactListResponse(
            success = true,
            contacts = (0 until count).map { contact("c${offset + it}") },
            total = total,
            limit = count,
            offset = offset,
        ),
    )

    private fun source(api: ContactsApi) = ContactsPagingSource(api, workspaceId = "ws-1")

    private suspend fun refresh(src: OffsetPagingSource<Contact>, size: Int = 10) =
        src.load(PagingSource.LoadParams.Refresh(null, size, false))

    private suspend fun append(src: OffsetPagingSource<Contact>, key: Int, size: Int = 10) =
        src.load(PagingSource.LoadParams.Append(key, size, false))

    @Test
    fun `stops at the reported total without an extra empty request`() {
        // ⛔ THE WHOLE POINT OF PASSING A TOTAL. The calls feed has none, so it must fetch one more
        // page to discover the end. Here the count is authoritative: a full page that reaches the
        // total must terminate immediately.
        runTest {
            val api = ContactsApi { limit, offset -> page(limit, offset, total = 20) }
            val src = source(api)

            val first = refresh(src) as PagingSource.LoadResult.Page
            assertEquals(10, first.nextKey)

            val second = append(src, first.nextKey!!) as PagingSource.LoadResult.Page
            assertEquals(10, second.data.size)
            assertNull("offset 10 + 10 rows == total 20, so paging must end here", second.nextKey)
            assertEquals("and no wasted third request", listOf(10 to 0, 10 to 10), api.requests)
        }
    }

    @Test
    fun `a short page still ends the list even when the total disagrees`() {
        // Defensive: the total is a separate count query, so it can be stale relative to the page (a
        // row deleted between the two reads). A short page must still terminate rather than trusting
        // an inflated total and paging into emptiness forever.
        runTest {
            val api = ContactsApi { _, offset -> page(3, offset, total = 999) }

            val first = refresh(source(api)) as PagingSource.LoadResult.Page

            assertEquals(3, first.data.size)
            assertNull(first.nextKey)
        }
    }

    @Test
    fun `an empty first page ends the list`() {
        runTest {
            val api = ContactsApi { _, offset -> page(0, offset, total = 0) }

            val first = refresh(source(api)) as PagingSource.LoadResult.Page

            assertTrue(first.data.isEmpty())
            assertNull(first.nextKey)
        }
    }

    @Test
    fun `deduplicates a row the shifting window serves twice`() {
        // ⚠️ Matters MORE here than for calls, not less: bulk-create inserts an entire import in one
        // statement, so many rows can appear between two page loads. A duplicate key crashes the list.
        runTest {
            val api = ContactsApi { limit, offset ->
                val ids = if (offset == 0) {
                    (0..9).map { "c$it" }
                } else {
                    // c9 repeats because rows were inserted above it.
                    listOf("c9") + (10..18).map { "c$it" }
                }
                ApiResult.Success(
                    ContactListResponse(
                        success = true,
                        contacts = ids.take(limit).map(::contact),
                        total = 100,
                    ),
                )
            }
            val src = source(api)

            val first = refresh(src) as PagingSource.LoadResult.Page
            val second = append(src, first.nextKey!!) as PagingSource.LoadResult.Page

            assertEquals(9, second.data.size)
            val allIds = (first.data + second.data).map { it.id }
            assertEquals("no duplicate keys may reach the UI", allIds.size, allIds.toSet().size)
            // Offset still advanced by the RAW page size, or paging would loop on the dropped row.
            assertEquals(20, second.nextKey)
        }
    }

    @Test
    fun `a failure carries its reason rather than becoming an empty page`() {
        runTest {
            val failure = ApiResult.RegionsDegraded("unreachable", listOf("eu"))
            val api = ContactsApi { _, _ -> failure }

            val result = refresh(source(api))

            assertTrue(result is PagingSource.LoadResult.Error)
            val cause = (result as PagingSource.LoadResult.Error).throwable
            assertEquals(failure, (cause as PagedLoadException).failure)
        }
    }
}
