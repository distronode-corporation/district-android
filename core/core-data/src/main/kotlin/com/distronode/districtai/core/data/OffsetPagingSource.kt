package com.distronode.districtai.core.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offset-keyed paging over any District list endpoint.
 *
 * ⛔ THIS IS SHARED BECAUSE THE RULES BELOW ARE SUBTLE AND WERE ALREADY DERIVED ONCE. Every paged
 * endpoint in this API is offset-based over live, newest-first data with no cursor. Three
 * consequences follow, and each of them silently corrupts a list if reimplemented slightly
 * differently in the next section:
 *
 * ⛔ 1. THE SAME ROW CAN ARRIVE ON TWO PAGES, AND A DUPLICATE KEY CRASHES A LAZY LIST. A row inserted
 * between two page loads shifts every subsequent window down, so the row at the old boundary is
 * served again — and `LazyColumn` throws IllegalArgumentException on a duplicate key. **Reproduced
 * against the real server**, not theorised: fetch page 1 of the calls feed, insert a call, fetch page
 * 2, and the boundary id comes back. For contacts it is worse, because `bulk-create` inserts an
 * entire import in one statement. [seenIds] is what prevents it.
 *
 * ⛔ 2. THE OFFSET MUST ADVANCE BY THE RAW PAGE SIZE, NOT THE DEDUPLICATED SIZE. The server counts
 * offsets over its own rows, so skipping ahead by a smaller number re-requests exactly the rows just
 * dropped — which are dropped again, forever. That is a list that never ends and never stops
 * fetching.
 *
 * ⛔ 3. DEDUPLICATION IS PER INSTANCE, AND THAT IS LOAD-BEARING. Paging builds a new PagingSource on
 * every invalidation, so a shared set would make a pull-to-refresh filter out the very rows it just
 * fetched and present an empty list.
 *
 * ⚠️ END-OF-LIST HAS TWO FORMS because the endpoints disagree. `/api/district/calls` is a bare array
 * with no total, so a short page is the only signal available. `/api/district/contacts` returns a real
 * `total`, so the end is KNOWN. [OffsetPage.total] carries that difference: null means "infer".
 */
class OffsetPagingSource<T : Any>(
    private val idOf: (T) -> String,
    private val fetch: suspend (limit: Int, offset: Int) -> ApiResult<OffsetPage<T>>,
    /**
     * Where [fetch] and the page arithmetic run.
     *
     * ⛔ PAGING DOES NOT MOVE `load` OFF THE MAIN THREAD FOR YOU — IT PUTS THAT ON THE
     * PagingSource. `PageFetcher` invokes `load` on the collector's own dispatcher, which for a
     * `cachedIn(viewModelScope)` flow is `Dispatchers.Main.immediate`. Room's own PagingSource
     * does its `withContext` internally for exactly this reason; a hand-written one that omits
     * it runs the network fetch, the body read and the decode on the frame clock. The call log's
     * FIRST load is `initialLoadSize` = 75 rows with transcripts, which is well past the point
     * where okio has to hit the socket again and Android's `PENALTY_DEATH_ON_NETWORK` turns the
     * read into a crash.
     *
     * ⚠️ The dedup set is still touched only from here, so confining `load` to one dispatcher
     * keeps [seenIds] single-threaded without needing a concurrent set.
     */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PagingSource<Int, T>() {

    /** Ids already emitted by THIS instance. See the ⛔ items on the class. */
    private val seenIds = mutableSetOf<String>()

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> = withContext(io) {
        val offset = params.key ?: 0
        // ⛔ params.loadSize, NEVER a configured page size. Paging asks for a LARGER first page
        // (3 × pageSize by default) to fill the viewport in one round trip, so requesting the page
        // size on a refresh under-fetches and leaves a visible gap.
        val requested = params.loadSize

        when (val result = fetch(requested, offset)) {
            is ApiResult.Success -> toPage(result.value, offset, requested)
            // ⛔ Surfaced as an error carrying the ApiResult, NEVER as an empty page. An empty page
            // renders as "nothing here", which for a degraded region or an expired session is a lie
            // the user cannot act on.
            is ApiResult.Failure -> LoadResult.Error(PagedLoadException(result))
        }
    }

    private fun toPage(page: OffsetPage<T>, offset: Int, requested: Int): LoadResult.Page<Int, T> {
        val received = page.items.size
        val fresh = page.items.filter { seenIds.add(idOf(it)) }

        val nextOffset = offset + received
        val exhausted = when (val total = page.total) {
            // No total: a short page is the only end-of-list signal. Costs one extra empty request on
            // an exactly-divisible feed, which is the right price for not guessing a total.
            null -> received < requested
            // A real total: the end is known, so no wasted request.
            else -> received < requested || nextOffset >= total
        }

        return LoadResult.Page(
            data = fresh,
            // Forward-only: these feeds are newest-first and a refresh restarts them.
            prevKey = null,
            nextKey = if (exhausted) null else nextOffset,
        )
    }

    /**
     * ⚠️ Anchor-based refresh is deliberately NOT implemented — returning null restarts from the top.
     * For a newest-first feed that is what a pull-to-refresh should do, and it is also the only honest
     * answer: an anchor position cannot be translated back into an offset that still means the same
     * thing once rows have been inserted above it.
     */
    override fun getRefreshKey(state: PagingState<Int, T>): Int? = null
}

/**
 * One page of an offset-paged endpoint.
 *
 * @param total the workspace-wide count, or **null** when the endpoint does not report one. See the
 *   ⚠️ on [OffsetPagingSource]: the calls feed has no total and contacts does, and conflating them
 *   either wastes a request or ends the list early.
 */
data class OffsetPage<T>(val items: List<T>, val total: Int? = null)

/**
 * Carries an [ApiResult.Failure] through Paging's `Throwable`-shaped error channel.
 *
 * ⛔ EXISTS BECAUSE PAGING'S API IS EXCEPTION-BASED AND THIS APP'S ERRORS ARE NOT. `LoadResult.Error`
 * takes a Throwable, so without this the difference between "session expired", "region unreachable"
 * and "you are offline" would have to be recovered by parsing an exception message — and the UI
 * genuinely needs it, because those are three different screens.
 */
class PagedLoadException(val failure: ApiResult.Failure) : Exception(failure.toString())
