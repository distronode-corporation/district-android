package com.distronode.districtai.core.data

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offset paging over a live, cursor-less feed.
 *
 * ⛔ THE CRASH THIS FILE EXISTS FOR. `GET /api/district/calls` orders by `createdAt desc` with no
 * cursor, so a call arriving between two page loads shifts every window down by one and the row at
 * the old boundary is served TWICE. A `LazyColumn` throws on a duplicate key, which means the app
 * crashes when a call comes in while the user is scrolling their call log — the single most
 * ordinary thing that can happen on this screen.
 */
class CallsPagingSourceTest {

    /**
     * Records the (limit, offset) pairs asked for, so the offset arithmetic can be asserted.
     *
     * ⚠️ Extends the module's FakeDistrictApi and overrides ONLY `calls`. Implementing the whole
     * interface here would mean adding a stub to this file every time an endpoint is added anywhere.
     */
    private class PagingApi(
        private val pages: (limit: Int, offset: Int) -> ApiResult<List<CallSummary>>,
    ) : FakeDistrictApi() {
        val requests: MutableList<Pair<Int, Int>> = mutableListOf()

        override suspend fun calls(
            workspaceId: String,
            limit: Int,
            offset: Int,
        ): ApiResult<List<CallSummary>> {
            requests += limit to offset
            return pages(limit, offset)
        }
    }

    private fun call(id: String) = CallSummary(
        id = id,
        type = "inbound",
        number = id,
        status = "completed",
        duration = "0s",
        time = "Aug 15, 02:30 PM",
        aiSummary = "",
        hasTranscript = false,
        callerName = id,
        summary = "",
        createdAt = "2026-08-15T14:30:00.000Z",
    )

    private fun source(api: FakeDistrictApi) = CallsPagingSource(api, workspaceId = "ws-1")

    private suspend fun refresh(src: OffsetPagingSource<CallSummary>, size: Int = 10) =
        src.load(PagingSource.LoadParams.Refresh(key = null, loadSize = size, placeholdersEnabled = false))

    private suspend fun append(src: OffsetPagingSource<CallSummary>, key: Int, size: Int = 10) =
        src.load(PagingSource.LoadParams.Append(key = key, loadSize = size, placeholdersEnabled = false))

    // ── Offset arithmetic and end-of-list ────────────────────────────────────

    @Test
    fun `a full page advances the offset and promises another`() = runTest {
        val api = PagingApi { limit, offset -> ApiResult.Success((0 until limit).map { call("c${offset + it}") }) }

        val page = refresh(source(api)) as PagingSource.LoadResult.Page

        assertEquals(10, page.data.size)
        assertEquals("first request starts at offset 0", listOf(10 to 0), api.requests)
        assertEquals(10, page.nextKey)
    }

    @Test
    fun `a short page ends the list`() = runTest {
        // ⛔ THE ONLY END-OF-LIST SIGNAL AVAILABLE. The response is a bare array with no total and
        // no hasMore, so a page smaller than requested is all there is to go on.
        val api = PagingApi { _, _ -> ApiResult.Success(listOf(call("c0"), call("c1"))) }

        val page = refresh(source(api)) as PagingSource.LoadResult.Page

        assertEquals(2, page.data.size)
        assertNull("a short page must terminate paging", page.nextKey)
    }

    @Test
    fun `an empty page ends the list`() = runTest {
        val api = PagingApi { _, _ -> ApiResult.Success(emptyList()) }

        val page = refresh(source(api)) as PagingSource.LoadResult.Page

        assertTrue(page.data.isEmpty())
        assertNull(page.nextKey)
    }

    @Test
    fun `an exactly-divisible feed costs one extra empty request`() = runTest {
        // ⚠️ Documented rather than fixed: without a total there is no way to know the last full
        // page WAS the last. One wasted request is the correct price for not guessing.
        val api = PagingApi { limit, offset ->
            ApiResult.Success(if (offset == 0) (0 until limit).map { call("c$it") } else emptyList())
        }
        val src = source(api)

        val first = refresh(src) as PagingSource.LoadResult.Page
        assertEquals(10, first.nextKey)

        val second = append(src, first.nextKey!!) as PagingSource.LoadResult.Page
        assertTrue(second.data.isEmpty())
        assertNull(second.nextKey)
        assertEquals(listOf(10 to 0, 10 to 10), api.requests)
    }

    @Test
    fun `honours the larger initial load size paging asks for`() = runTest {
        // ⚠️ Paging requests 3 × pageSize on a refresh to fill the viewport in one round trip.
        // Using pageSize for the limit would under-fetch and leave a visible gap.
        val api = PagingApi { limit, offset -> ApiResult.Success((0 until limit).map { call("c${offset + it}") }) }

        val page = refresh(source(api), size = 30) as PagingSource.LoadResult.Page

        assertEquals(30, page.data.size)
        assertEquals(listOf(30 to 0), api.requests)
        assertEquals("offset advances by the rows RECEIVED, not by pageSize", 30, page.nextKey)
    }

    // ── The duplicate-row crash ──────────────────────────────────────────────

    @Test
    fun `drops a row the shifting window serves twice`() = runTest {
        // A call arrives between the two loads, so every window shifts down by one and c9 —
        // already shown as the last row of page one — reappears as the first row of page two.
        //
        // ⛔ WITHOUT THE DEDUPLICATION THIS IS AN IllegalArgumentException FROM LazyColumn, not a
        // duplicated row. "Key was already used" crashes the screen.
        val api = PagingApi { limit, offset ->
            val ids = if (offset == 0) {
                (0..9).map { "c$it" }
            } else {
                // Shifted: c9 repeats because a new row was inserted above it.
                listOf("c9") + (10..18).map { "c$it" }
            }
            ApiResult.Success(ids.take(limit).map(::call))
        }
        val src = source(api)

        val first = refresh(src) as PagingSource.LoadResult.Page
        val second = append(src, first.nextKey!!) as PagingSource.LoadResult.Page

        assertEquals(10, first.data.size)
        assertEquals("the repeated row must not be emitted again", 9, second.data.size)

        val allIds = (first.data + second.data).map { it.id }
        assertEquals("no duplicate keys may reach the UI", allIds.size, allIds.toSet().size)
        assertTrue("c9" !in second.data.map { it.id })
    }

    @Test
    fun `advances the offset by the raw page size, not the deduplicated size`() = runTest {
        // ⛔ IF THE OFFSET ADVANCED BY THE DEDUPLICATED COUNT, PAGING WOULD LOOP. Skipping ahead by
        // 9 instead of 10 re-requests the row just dropped, which is dropped again, forever — a
        // list that never reaches the end and never stops fetching.
        val api = PagingApi { limit, offset ->
            val ids = if (offset == 0) (0..9).map { "c$it" } else listOf("c9") + (10..18).map { "c$it" }
            ApiResult.Success(ids.take(limit).map(::call))
        }
        val src = source(api)

        val first = refresh(src) as PagingSource.LoadResult.Page
        val second = append(src, first.nextKey!!) as PagingSource.LoadResult.Page

        assertEquals(10, first.nextKey)
        assertEquals("offset must count SERVER rows, of which 10 were returned", 20, second.nextKey)
    }

    @Test
    fun `a fresh instance does not filter rows a previous one had seen`() = runTest {
        // ⛔ DEDUPLICATION IS PER-INSTANCE, AND THAT IS LOAD-BEARING. Paging builds a new
        // PagingSource on every invalidation, so a shared set would make a pull-to-refresh filter
        // out exactly the rows it just fetched and present an empty log.
        val api = PagingApi { limit, _ -> ApiResult.Success((0 until limit).map { call("c$it") }) }

        val firstLoad = refresh(source(api)) as PagingSource.LoadResult.Page
        val afterRefresh = refresh(source(api)) as PagingSource.LoadResult.Page

        assertEquals(10, firstLoad.data.size)
        assertEquals("a refreshed source must return the same rows, not nothing", 10, afterRefresh.data.size)
    }

    // ── Failures ─────────────────────────────────────────────────────────────

    @Test
    fun `a failure becomes a paging error carrying the reason, never an empty page`() = runTest {
        // ⛔ An empty page renders as "no calls yet". For a degraded region or an expired session
        // that is a lie the user cannot act on, so the reason has to survive into the UI.
        val failure = ApiResult.RegionsDegraded("unreachable", listOf("eu"))
        val api = PagingApi { _, _ -> failure }

        val result = refresh(source(api))

        assertTrue("expected LoadResult.Error, got $result", result is PagingSource.LoadResult.Error)
        val cause = (result as PagingSource.LoadResult.Error).throwable
        assertTrue(cause is PagedLoadException)
        assertEquals(failure, (cause as PagedLoadException).failure)
    }

    @Test
    fun `an unauthorized failure survives to the UI as itself`() = runTest {
        val failure = ApiResult.Unauthorized(reason = null)
        val api = PagingApi { _, _ -> failure }

        val result = refresh(source(api)) as PagingSource.LoadResult.Error

        assertEquals(failure, (result.throwable as PagedLoadException).failure)
    }

    @Test
    fun `refresh restarts from the top rather than guessing an anchor offset`() = runTest {
        // ⚠️ Deliberate: an anchor position cannot be translated back into an offset that still
        // means the same thing once rows have been inserted above it, and for a newest-first feed
        // starting over is what a pull-to-refresh should do anyway.
        val api = PagingApi { limit, _ -> ApiResult.Success((0 until limit).map { call("c$it") }) }
        val src = source(api)

        val emptyState = PagingState<Int, CallSummary>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 10),
            leadingPlaceholderCount = 0,
        )

        assertNull(src.getRefreshKey(emptyState))
    }
}
