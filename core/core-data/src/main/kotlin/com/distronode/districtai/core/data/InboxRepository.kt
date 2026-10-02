package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.MarkReadRequest
import com.distronode.districtai.core.model.SendMessageRequest
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * The unified Inbox.
 *
 * ⛔ THE LIST IS NOT PAGED AND THE THREAD IS, WHICH IS NOT AN INCONSISTENCY. [conversations] cannot
 * be: the server scans a bounded window of recent messages (500) and GROUPS them into threads, so a
 * thread's position depends on rows that may fall outside the window and there is no stable offset
 * to page on. Wrapping it in a `PagingSource` would invent an offset the server does not honour,
 * and the second page would repeat or skip threads. [thread] can be, because it is a flat
 * chronological list of one contact's events with a real cursor — see [ThreadCursor]. The call log
 * and the CRM are paged for the same reason [thread] is.
 *
 * ⛔ AND NEITHER USES `Pager`. [thread]'s cursor is expand-only (there is no `after`), pages may
 * overlap by contract, and the merge has to dedupe by event id — none of which a `PagingSource`'s
 * prepend/append model expresses without fighting it. The seam is one suspend call returning one
 * [ThreadPage]; the accumulation lives in the ViewModel that owns the thread.
 *
 * ⛔ READS ADMIT `viewer`, THE WRITES DO NOT. [send] and [markRead] are agency/client only
 * server-side, so gate them on the same role the app already threads through for contacts mutations.
 */
class InboxRepository(private val api: DistrictApi) {

    /**
     * The Inbox list, plus whether it is complete.
     *
     * ⚠️ RETURNS THE PARTIAL FLAG RATHER THAN HIDING IT. `scanned >= scanLimit` means an older thread
     * with no recent traffic fell outside the scan window entirely — the list is a truncation, not an
     * empty account. That is the same class of lie as reporting a degraded region's absence as "you
     * have no workspaces", which can send a paying customer to a checkout page. There is no page to
     * request; the UI says the list is partial instead.
     */
    suspend fun conversations(workspaceId: String): ApiResult<InboxPage> =
        when (val result = api.conversations(workspaceId)) {
            is ApiResult.Success -> ApiResult.Success(
                InboxPage(
                    conversations = result.value.conversations,
                    partial = result.value.scanLimit > 0 &&
                        result.value.scanned >= result.value.scanLimit,
                ),
            )
            is ApiResult.Failure -> result
        }

    /**
     * One page of a thread's history — messages AND calls, interleaved, oldest first for reading.
     *
     * ⚠️ THE SERVER'S ORDER IS NOT ASSUMED. `getTimelinePageForContact` builds a merged view from
     * two tables, and this layer sorts explicitly: a conversation read newest-first is unreadable,
     * and a silent order change server-side would be invisible until someone noticed the replies
     * ran backwards.
     *
     * ⛔ THE SORT NOW BREAKS TIES ON `id`, AND THAT IS A PAGING CORRECTNESS FIX RATHER THAN
     * TIDINESS. The server's cursor is read off index 0 of ITS ascending list, and its sort breaks
     * the same tie the same way. A client that sorted on timestamp alone could order two events
     * written in the same millisecond differently, hand back the wrong one of the pair as
     * `beforeId`, and the next page would skip that event's sibling for good. `sortedBy` is a
     * stable sort, so the disagreement would depend on the order the server happened to emit them
     * in — invisible until it bit.
     *
     * ⚠️ Sorted on the ISO-8601 STRING, which is only safe because the server emits a normalised
     * offset. Do not extend this to a locally-formatted timestamp — see [TimelineEvent.timestamp].
     *
     * @param olderThan the previous page's [ThreadPage.cursor], to read the window BEFORE it. Null
     *   reads the newest window, which is what opening a thread does.
     */
    suspend fun thread(
        workspaceId: String,
        contactId: String?,
        address: String?,
        olderThan: ThreadCursor? = null,
    ): ApiResult<ThreadPage> =
        when (
            val result = api.timeline(
                workspaceId = workspaceId,
                contactId = contactId,
                address = address,
                before = olderThan?.before,
                beforeId = olderThan?.beforeId,
            )
        ) {
            is ApiResult.Success -> ApiResult.Success(
                ThreadPage(
                    events = result.value.timeline
                        .sortedWith(compareBy({ it.timestamp }, { it.id })),
                    hasMore = result.value.pageInfo.hasMore,
                    oldest = result.value.pageInfo.oldest,
                    oldestId = result.value.pageInfo.oldestId,
                ),
            )
            is ApiResult.Failure -> result
        }

    /**
     * Send a reply.
     *
     * ⛔ BILLABLE, AND NEVER RETRIED AUTOMATICALLY. Each send is SMS/MMS segments or a Postmark email,
     * capped server-side at 30/min per WORKSPACE — keyed per workspace, not per user, because the cost
     * lands on the workspace either way. An automatic retry would spend a customer's money on this
     * client's own initiative, and a 429 mistaken for a transport blip is exactly how that happens, so
     * a rate limit surfaces to the user rather than being absorbed.
     *
     * ⛔ A 200 WITH `success: false` IS A REFUSAL, NOT A SEND. The route answers that way for a
     * provider rejection it handled, so treating every 2xx as sent would show the operator their
     * message went out when it did not. The server's own text is carried through verbatim: its
     * refusals are specific (unverified sender, exhausted A2P registration, rate limit) and "could not
     * send" discards all of it.
     */
    suspend fun send(
        workspaceId: String,
        to: String,
        body: String,
        channel: String,
        subject: String? = null,
        /**
         * MMS attachment URLs, each one a `messages/media` upload's `media.url`.
         *
         * ⛔ DOES NOT MAKE [body] OPTIONAL. The route's first guard is `!workspaceId || !to ||
         * !body`, which knows nothing about attachments, so a picture with no caption is a 400
         * "Missing required parameters" rather than a message. The caller refuses locally instead.
         */
        mediaUrls: List<String> = emptyList(),
    ): ApiResult<Unit> {
        val request = SendMessageRequest(
            workspaceId = workspaceId,
            to = to,
            body = body,
            channel = channel,
            subject = subject,
            mediaUrls = mediaUrls,
        )
        return when (val result = api.sendMessage(request)) {
            is ApiResult.Success ->
                if (result.value.success) {
                    ApiResult.Success(Unit)
                } else {
                    refusedEnvelope(result.value.error)
                }
            is ApiResult.Failure -> result
        }
    }

    /**
     * Mark a thread read.
     *
     * ⚠️ THE CALLER DOES NOT BLOCK THE UI ON THIS. Opening a thread IS the read; failing to record it
     * leaves a stale badge, not a lost message, and blocking the render on a bookkeeping write would
     * make the common case slower to serve the rare one.
     */
    suspend fun markRead(
        workspaceId: String,
        contactId: String?,
        counterpart: String?,
    ): ApiResult<Int> {
        val request = MarkReadRequest(
            workspaceId = workspaceId,
            contactId = contactId,
            counterpart = counterpart,
        )
        return when (val result = api.markRead(request)) {
            is ApiResult.Success -> ApiResult.Success(result.value.marked)
            is ApiResult.Failure -> result
        }
    }
}

/**
 * The Inbox list and whether the server could see all of it.
 *
 * ⚠️ [partial] is NOT an error state. It means "these are the recent threads"; the UI says so rather
 * than implying the list is the whole account.
 */
data class InboxPage(
    val conversations: List<ConversationSummary>,
    val partial: Boolean,
)

/**
 * One expand-only window of a thread, plus where it ended.
 *
 * ⛔ A PAGE RATHER THAN A BARE LIST, BECAUSE A LIST CANNOT SAY WHETHER IT IS THE WHOLE THREAD. The
 * same distinction [InboxPage.partial] exists for: a truncation rendered as the complete history is
 * a lie the operator has no way to detect, and here it is worse than in the Inbox — a thread simply
 * appears to start on the day the window happens to reach back to.
 *
 * ⚠️ [hasMore] IS THE SERVER'S "AT LEAST ONE SOURCE FILLED ITS WINDOW", so it can be true with
 * nothing actually behind it: the next page comes back empty and the thread ends there. That is the
 * server's deliberate choice of which way to be wrong, and a caller must treat an empty older page
 * as the end rather than as a fault.
 */
data class ThreadPage(
    val events: List<TimelineEvent>,
    val hasMore: Boolean,
    val oldest: String?,
    val oldestId: String?,
) {
    /**
     * What to hand [InboxRepository.thread] to read the window before this one.
     *
     * ⚠️ Null on an EMPTY page and only then — an empty page has no oldest event, so there is no
     * anchor to page from and nothing older to ask for.
     */
    val cursor: ThreadCursor? get() = oldest?.let { ThreadCursor(before = it, beforeId = oldestId) }
}

/**
 * The point a thread is expanded backwards from.
 *
 * ⛔ A TYPE RATHER THAN TWO PARAMETERS, BECAUSE THE ROUTE 400s AN ID WITH NO TIMESTAMP. `beforeId`
 * alone cannot say which timestamp it breaks a tie at, so the server refuses it — and a pair of
 * nullable arguments makes that refusal constructible at every call site. Here [before] is
 * non-null by declaration and [beforeId] is the optional half, so the invalid combination cannot
 * be written down. The same reasoning that put `to` and `channel` inside `ReplyTarget`.
 *
 * ⚠️ BOTH VALUES ARE THE SERVER'S OWN, ECHOED BACK VERBATIM. [before] is an ISO-8601 string the
 * server emitted, never one this client formatted: the route parses it with `new Date(...)` and
 * compares it against its own column, so a locally re-rendered timestamp is a cursor that quietly
 * points somewhere else.
 */
data class ThreadCursor(
    val before: String,
    val beforeId: String?,
)
