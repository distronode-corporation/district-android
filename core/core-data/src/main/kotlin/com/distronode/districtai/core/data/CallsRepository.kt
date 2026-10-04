package com.distronode.districtai.core.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi
import kotlinx.coroutines.flow.Flow

/** The call log, plus the two per-call reads the detail screen makes on demand. */
class CallsRepository(private val api: DistrictApi) {

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

        const val DETAIL_ENVELOPE = "CallDetailResponse"
        const val TRANSCRIPT_ENVELOPE = "CallTranscriptResponse"
    }
}
