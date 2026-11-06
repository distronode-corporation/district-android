package com.distronode.districtai.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** The call log, plus the two per-call reads the detail screen makes on demand. */
class CallsRepository(
    private val api: DistrictApi,
    /** The Live calls list's clock, for where its paging stops ([liveCalls]); a test pins it. */
    private val now: () -> Instant = Instant::now,
) {

    /**
     * A paged call log for [workspaceId], newest first.
     *
     * ⚠️ A NEW Pager PER WORKSPACE. Switching workspace must produce a new flow rather than
     * invalidating the old one, because the paging source's deduplication set and offsets are only
     * meaningful within one tenant. Collect this keyed on the workspace id.
     */
    fun callLog(workspaceId: String): Flow<PagingData<CallSummary>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            // ⚠️ Kept at or below the SERVER's ceiling. `limit` is clamped to 100 server-side, and
            // Paging requests `initialLoadSize` (3 × pageSize by default) on the first load — at
            // pageSize 25 that is 75, comfortably under. Raising pageSize past 33 would make the
            // initial request silently exceed the clamp and return a short page, which this source
            // reads as END OF LIST. The whole log would appear to be one page long.
            initialLoadSize = PAGE_SIZE * INITIAL_LOAD_MULTIPLIER,
            // No placeholders: the feed has no total, so Paging cannot know how many to draw.
            enablePlaceholders = false,
        ),
        pagingSourceFactory = { CallsPagingSource(api, workspaceId) },
    ).flow

    /**
     * The newest [limit] rows, unpaged, for the dialler's call-back list.
     *
     * ⛔ NOT `callLog(...).first()`, AND THE DIFFERENCE IS NOT STYLE. A `Pager` is a stateful
     * object whose deduplication set and offsets belong to one scrolling surface; borrowing one to
     * read ten rows would give the dialler a second Pager for the same feed, invalidating
     * independently of the call log's. This is one GET with a small `limit`, which is what the
     * endpoint is for.
     *
     * ⛔ INBOUND ROWS ONLY, AND THAT FILTER IS LOAD-BEARING RATHER THAN TIDY. [CallSummary.from] is
     * the raw `Call.from` column, which for an OUTBOUND row is the workspace's OWN number — the
     * dial route writes `from: fromNumber` and puts the callee in `callerName`. So "redial" built
     * from `from` on an outbound row would dial the workspace's own line: it would connect, it
     * would bill, and it would look like a bug in the carrier rather than in this list. Rows with
     * no `direction` at all are excluded for the same reason — an unknown direction is not
     * evidence that `from` is a callee.
     *
     * ⚠️ SO THIS IS "CALL THEM BACK", NOT "REDIAL", and the UI must say so. A screen that labelled
     * it redial would be promising to repeat outbound calls it cannot repeat.
     *
     * ⚠️ THE ROWS ARE NOT DEDUPLICATED BY NUMBER. One person who called three times appears three
     * times, because each row is a distinct call with its own time and outcome, and collapsing them
     * would misreport the log. The caller decides whether to collapse for display.
     */
    suspend fun recentCallbacks(workspaceId: String, limit: Int = CALLBACK_LIMIT): ApiResult<List<CallSummary>> =
        when (val result = api.calls(workspaceId, limit = limit, offset = 0)) {
            // ⚠️ NO ENVELOPE TO CHECK — this route is a bare JSON array, like the meetings list. An
            // empty result is a legitimate "nobody has called yet", which no guard could tell from
            // a broken read anyway.
            is ApiResult.Success -> ApiResult.Success(
                result.value.filter { it.direction == INBOUND && !it.from.isNullOrBlank() },
            )
            is ApiResult.Failure -> result
        }

    /**
     * The calls in progress or ringing, newest first, for the Live calls screen.
     *
     * ⛔ THE CALL LOG, NOT `calls/active`. That route answers whole database rows with no contract,
     * so a field can leak and the shape can change unannounced; the call log is the contracted row
     * (`district-calls.json`), and it carries `receptionistOnCall`.
     *
     * ⚠️ THE STATUS IS ALREADY THE SERVER'S DISPLAY STATUS: a call whose terminal webhook was lost
     * reads `no-answer` once it is older than the stuck-call ceiling (`displayCallStatus`), so these
     * two values are exactly the server's `isLiveStatus` over rows it still treats as live.
     *
     * ⛔ PAGED BACK TO THE LIVE WINDOW, NOT ONE PAGE. The web's Live page lists every call the server
     * still counts as live, which is every in-progress or ringing row younger than the stuck-call
     * ceiling ([LIVE_WINDOW]). One page of [LIVE_SCAN_LIMIT] stopped short of that in a busy
     * workspace, and a call pushed past it dropped off the list and took its listen or takeover with
     * it. So this reads page after page (newest first, the route's `createdAt`, `id` order) and stops
     * at the first of: a short page (the end of the log), a page whose oldest row is older than the
     * window (nothing past it can read live: the server already shows those as `no-answer`), or
     * [MAX_LIVE_PAGES].
     *
     * ⚠️ THE WINDOW IS MEASURED ON THIS DEVICE'S CLOCK, WITH [CLOCK_SLACK] ADDED, so a phone whose
     * clock runs ahead reads a page further rather than stopping early. It only decides where to stop
     * reading; which rows are live is still the server's display status.
     *
     * ⚠️ A CALL THAT STARTS DURING THE SCAN SHIFTS THE OFFSETS BY ONE, so a row can come back twice
     * on consecutive pages; it is kept once. It cannot be skipped: a new row only pushes older rows
     * further down.
     *
     * ⚠️ A FAILED PAGE FAILS THE WHOLE READ, never a partial list: a list missing the calls past page
     * one would end their sessions exactly as the one-page read did.
     */
    suspend fun liveCalls(workspaceId: String): ApiResult<List<CallSummary>> {
        val cutoff = now().minus(LIVE_WINDOW).minus(CLOCK_SLACK)
        val seen = LinkedHashMap<String, CallSummary>()
        var offset = 0
        for (page in 1..MAX_LIVE_PAGES) {
            val rows = when (val result = api.calls(workspaceId, limit = LIVE_SCAN_LIMIT, offset = offset)) {
                is ApiResult.Success -> result.value
                is ApiResult.Failure -> return result
            }
            rows.forEach { seen.putIfAbsent(it.id, it) }
            if (rows.size < LIVE_SCAN_LIMIT || olderThan(rows.last(), cutoff)) break
            offset += LIVE_SCAN_LIMIT
        }
        return ApiResult.Success(seen.values.filter { it.isLive })
    }

    /**
     * Whether [row] started before [cutoff]. ⚠️ A `createdAt` that does not parse is never "older":
     * reading one page more is the safe mistake, and [MAX_LIVE_PAGES] bounds it.
     */
    private fun olderThan(row: CallSummary, cutoff: Instant): Boolean =
        runCatching { Instant.parse(row.createdAt) }.getOrNull()?.isBefore(cutoff) == true

    /**
     * One call, fetched by id.
     *
     * ⚠️ THE DETAIL SCREEN FETCHES RATHER THAN RECEIVING THE ROW, and that is what makes it survive
     * process death and be openable from a push notification, where an id is all the app has.
     *
     * ⚠️ A missing call arrives as [ApiResult.NotFound], and that does NOT imply a malformed id:
     * the server reads by id and checks ownership afterwards so another tenant's id looks exactly
     * like one that does not exist. Word any message accordingly.
     */
    suspend fun detail(workspaceId: String, callId: String): ApiResult<CallSummary> =
        when (val result = api.callDetail(workspaceId, callId)) {
            // ⚠️ The envelope is asserted BEFORE the payload is examined, so a `{}` body reports
            // "the server did not affirm success" rather than the more specific and misleading
            // "success response carried no call". See [rejectedEnvelope].
            is ApiResult.Success -> rejectedEnvelope(DETAIL_ENVELOPE, result.value.success)
                // A 2xx with no `call` is a malformed response, not an absence — absence is a 404.
                // Reporting it as NotFound would hide a server bug behind an ordinary empty state.
                ?: result.value.call?.let { ApiResult.Success(it) }
                ?: ApiResult.DecodeFailure(
                    IllegalStateException("success response carried no call"),
                    result.value.toString().take(DECODE_PREVIEW_CHARS),
                )
            is ApiResult.Failure -> result
        }

    /**
     * A call's transcript.
     *
     * ⚠️ Fetched on demand rather than read from the feed row. The feed's copy is whatever existed
     * when that page loaded, and a transcript can still be written after a call ends.
     */
    suspend fun transcript(workspaceId: String, callId: String): ApiResult<String> =
        when (val result = api.callTranscript(workspaceId, callId)) {
            // ⛔ THE ENVELOPE MATTERS MOST ON THIS ONE, because "" is the legitimate value for a
            // call with no transcript. Without the flag, a structurally wrong 200 is
            // indistinguishable from "nothing was said" and the screen renders an empty
            // transcript for a call that has one.
            //
            // ⚠️ Unwrapped to the string, and an absent transcript is "" rather than null — the
            // handler does `call.transcript || ""`, so emptiness is the "nothing to show" test.
            is ApiResult.Success -> rejectedEnvelope(TRANSCRIPT_ENVELOPE, result.value.success)
                ?: ApiResult.Success(result.value.transcript)
            is ApiResult.Failure -> result
        }

    private companion object {
        /**
         * 25 matches one of the page sizes the web console's own selector offers ([10, 25, 50,
         * 100]), so the two surfaces request comparable pages.
         */
        const val PAGE_SIZE = 25

        /** Paging's own default. Named here because the server-clamp arithmetic above depends on it. */
        const val INITIAL_LOAD_MULTIPLIER = 3

        /** Short on purpose: a malformed-response preview must not carry a whole transcript. */
        const val DECODE_PREVIEW_CHARS = 200

        /**
         * ⚠️ SMALL BECAUSE IT IS FILTERED AFTERWARDS. The server has no `direction` filter, so this
         * asks for a page and keeps the inbound rows from it — a workspace whose last twenty calls
         * were all outbound legitimately shows an empty call-back list, which the screen states
         * rather than treating as a failure. Twenty is one server page and comfortably under the
         * route's clamp of 100.
         */
        const val CALLBACK_LIMIT = 20

        /** The `Call.direction` value whose `from` is somebody else's number. See [recentCallbacks]. */
        const val INBOUND = "inbound"

        /** The server's `limit` ceiling: one page of the Live calls list's scan. */
        const val LIVE_SCAN_LIMIT = 100

        /**
         * The server's `STALE_LIVE_CALL_MS` (the stuck-call ceiling, 4 h 15 min): a row older than
         * this never reads live, and it is the window the web's Live page lists.
         */
        val LIVE_WINDOW: Duration = Duration.ofMinutes(4 * 60 + 15)

        /** Room for a device clock that runs ahead of the server's. See [liveCalls]. */
        val CLOCK_SLACK: Duration = Duration.ofMinutes(30)

        /**
         * ⚠️ A BOUND, NOT AN EXPECTATION: 2,000 calls inside the live window. Past it the list stops
         * reading, and the ViewModel still keeps a supervised call by reading it by id.
         */
        const val MAX_LIVE_PAGES = 20

        const val DETAIL_ENVELOPE = "CallDetailResponse"
        const val TRANSCRIPT_ENVELOPE = "CallTranscriptResponse"
    }
}

/** The server's `isLiveStatus` values, as the call log's display status spells them. */
private val LIVE_STATUSES = setOf("in-progress", "ringing")

/** In progress or ringing, by the server's display status: a row the Live calls list shows. */
val CallSummary.isLive: Boolean get() = status in LIVE_STATUSES
