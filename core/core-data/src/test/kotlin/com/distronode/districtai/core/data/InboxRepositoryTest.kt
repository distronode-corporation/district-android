package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.ConversationLastMessage
import com.distronode.districtai.core.model.ConversationSummary
import com.distronode.districtai.core.model.ConversationsResponse
import com.distronode.districtai.core.model.SendMessageResponse
import com.distronode.districtai.core.model.TimelineEvent
import com.distronode.districtai.core.model.TimelinePageInfo
import com.distronode.districtai.core.model.TimelineResponse
import com.distronode.districtai.core.network.ApiResult
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⛔ THREE OF THESE GUARD THINGS THAT WOULD OTHERWISE LIE TO AN OPERATOR:
 *
 *   `a 200 with success false is a refusal` — the send route answers that way for a provider
 *   rejection it handled, so treating every 2xx as sent tells the operator their message went out
 *   when it did not. That is worse than an error: they stop chasing it.
 *
 *   `an exhausted scan window is reported as partial` — the Inbox can legitimately be a truncation,
 *   and rendering one as the whole account is the same class of mistake that can send a paying
 *   customer to a checkout page.
 *
 *   `a thread is sorted oldest first` — the server merges two tables and this layer does not trust
 *   the order. A conversation read backwards is unreadable, and a silent order change server-side
 *   would be invisible until someone noticed the replies were reversed.
 *
 * ⛔ AND A FOURTH, ADDED WITH PAGING: `events sharing a timestamp are ordered by id`. The cursor for
 * the next page is read off index 0, so a tie broken differently from the server's own tiebreak
 * hands back the wrong id and the following page skips that event's sibling — silently, and
 * permanently for that thread.
 */
class InboxRepositoryTest {

    private fun summary(unread: Int = 0) = ConversationSummary(
        threadKey = "contact:c1",
        counterpart = "+14165550142",
        contactId = "c1",
        contactName = "Ada",
        unreadCount = unread,
        lastMessage = ConversationLastMessage(body = "hi", direction = "inbound"),
    )

    private fun event(id: String, ts: String) = TimelineEvent(
        id = id,
        type = "sms",
        timestamp = ts,
        direction = "inbound",
        body = id,
    )

    // ── The partial-list signal ──────────────────────────────────────────────

    @Test
    fun `an exhausted scan window is reported as partial`() = runTest {
        val api = FakeDistrictApi().apply {
            conversationsResult = ApiResult.Success(
                ConversationsResponse(
                    success = true,
                    conversations = listOf(summary()),
                    scanned = 500,
                    scanLimit = 500,
                ),
            )
        }

        val result = InboxRepository(api).conversations("ws-1")

        assertTrue(result is ApiResult.Success)
        assertTrue("scanned == scanLimit means the list is a truncation", (result as ApiResult.Success).value.partial)
    }

    @Test
    fun `a window with room left is not partial`() = runTest {
        val api = FakeDistrictApi().apply {
            conversationsResult = ApiResult.Success(
                ConversationsResponse(success = true, conversations = listOf(summary()), scanned = 12, scanLimit = 500),
            )
        }

        val result = InboxRepository(api).conversations("ws-1") as ApiResult.Success

        assertFalse(result.value.partial)
    }

    @Test
    fun `a server that reports no scan limit is never called partial`() = runTest {
        // ⚠️ An older deployment that omits scanLimit must not read as "everything is truncated" —
        // `0 >= 0` would be true, so the guard is on scanLimit being positive.
        val api = FakeDistrictApi().apply {
            conversationsResult = ApiResult.Success(
                ConversationsResponse(success = true, conversations = listOf(summary()), scanned = 0, scanLimit = 0),
            )
        }

        val result = InboxRepository(api).conversations("ws-1") as ApiResult.Success

        assertFalse(result.value.partial)
    }

    @Test
    fun `a failure is passed through rather than becoming an empty inbox`() = runTest {
        // ⛔ "We could not read" and "you have no conversations" are different facts.
        val api = FakeDistrictApi().apply {
            conversationsResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }

        assertTrue(InboxRepository(api).conversations("ws-1") is ApiResult.NetworkFailure)
    }

    // ── Thread ordering and paging ───────────────────────────────────────────

    @Test
    fun `a thread is sorted oldest first regardless of the order it arrived in`() = runTest {
        val api = FakeDistrictApi().apply {
            timelineResult = ApiResult.Success(
                TimelineResponse(
                    success = true,
                    timeline = listOf(
                        event("third", "2026-08-15T12:00:00.000Z"),
                        event("first", "2026-08-15T09:00:00.000Z"),
                        event("second", "2026-08-15T10:30:00.000Z"),
                    ),
                ),
            )
        }

        val result = InboxRepository(api).thread("ws-1", "c1", null) as ApiResult.Success

        assertEquals(listOf("first", "second", "third"), result.value.events.map { it.id })
    }

    @Test
    fun `events sharing a timestamp are ordered by id, matching the server's own tiebreak`() =
        runTest {
            // ⛔ THE CURSOR IS READ OFF INDEX 0, so this is a paging correctness assertion rather
            // than a cosmetic one. The server sorts by timestamp then id; a client that sorted on
            // timestamp alone would keep whatever arrival order a STABLE sort preserved, hand back
            // the wrong one of a same-millisecond pair as `oldestId`, and the next page would skip
            // its sibling permanently. The fixture arrives in the wrong order deliberately.
            val api = FakeDistrictApi().apply {
                timelineResult = ApiResult.Success(
                    TimelineResponse(
                        success = true,
                        timeline = listOf(
                            event("m_c", "2026-08-15T09:00:00.000Z"),
                            event("m_a", "2026-08-15T09:00:00.000Z"),
                            event("m_b", "2026-08-15T09:00:00.000Z"),
                        ),
                    ),
                )
            }

            val result = InboxRepository(api).thread("ws-1", "c1", null) as ApiResult.Success

            assertEquals(listOf("m_a", "m_b", "m_c"), result.value.events.map { it.id })
        }

    @Test
    fun `a page carries the server's cursor rather than a locally derived one`() = runTest {
        val api = FakeDistrictApi().apply {
            timelineResult = ApiResult.Success(
                TimelineResponse(
                    success = true,
                    timeline = listOf(event("only", "2026-08-15T09:00:00.000Z")),
                    pageInfo = TimelinePageInfo(
                        hasMore = true,
                        oldest = "2026-08-15T08:00:00.000Z",
                        oldestId = "somewhere-else",
                    ),
                ),
            )
        }

        val page = (InboxRepository(api).thread("ws-1", "c1", null) as ApiResult.Success).value

        // ⛔ NOT RE-DERIVED FROM `events.first()`. The two agree in production, but the server's
        // value is the one its own comparison ran against — a client that recomputed the cursor
        // would be asserting its sort matches the server's, which is the thing it cannot know.
        assertTrue(page.hasMore)
        assertEquals("2026-08-15T08:00:00.000Z", page.oldest)
        assertEquals("somewhere-else", page.oldestId)
        assertEquals(ThreadCursor("2026-08-15T08:00:00.000Z", "somewhere-else"), page.cursor)
    }

    @Test
    fun `an empty page offers no cursor to page from`() = runTest {
        // ⛔ There is no anchor, so there is nothing to ask for. A cursor invented here (an empty
        // string, "now") would be a `before` the server never emitted.
        val api = FakeDistrictApi()

        val page = (InboxRepository(api).thread("ws-1", "c1", null) as ApiResult.Success).value

        assertFalse(page.hasMore)
        assertNull(page.cursor)
    }

    @Test
    fun `a server with no pageInfo reads as a thread with nothing behind it`() = runTest {
        // ⚠️ A DEPLOYMENT OLDER THAN THE PAGING CHANGE omits the key entirely. The DTO's defaults
        // must make that "no page behind this one", which is exactly what such a server has —
        // never a decode failure, which would present as an empty thread.
        val api = FakeDistrictApi().apply {
            timelineResult = ApiResult.Success(
                TimelineResponse(
                    success = true,
                    timeline = listOf(event("only", "2026-08-15T09:00:00.000Z")),
                ),
            )
        }

        val page = (InboxRepository(api).thread("ws-1", "c1", null) as ApiResult.Success).value

        assertEquals(listOf("only"), page.events.map { it.id })
        assertFalse(page.hasMore)
        assertNull(page.cursor)
    }

    @Test
    fun `a cursor is sent as before and beforeId, and omitted entirely when absent`() = runTest {
        // ⛔ THE NO-CURSOR CALL MUST SEND NEITHER. `beforeId` without `before` is a 400 from the
        // route, and a present-but-empty `before` is an Invalid Date, also a 400 — so an opening
        // read that leaked either parameter would break every thread rather than just paging.
        val api = FakeDistrictApi()
        val repository = InboxRepository(api)

        repository.thread("ws-1", "c1", null)
        repository.thread("ws-1", "c1", null, ThreadCursor("2026-08-15T08:00:00.000Z", "evt-9"))
        repository.thread("ws-1", "c1", null, ThreadCursor("2026-08-15T07:00:00.000Z", null))

        assertEquals(
            listOf(
                null to null,
                "2026-08-15T08:00:00.000Z" to "evt-9",
                // ⚠️ A cursor whose tiebreak is null is legal: `before` alone is the whole
                // contract when the caller has no id to break a tie at.
                "2026-08-15T07:00:00.000Z" to null,
            ),
            api.timelineCursors,
        )
    }

    @Test
    fun `a thread read failure is passed through rather than becoming an empty page`() = runTest {
        // ⛔ Same fact as the Inbox above: "we could not read" is not "this conversation is empty".
        val api = FakeDistrictApi().apply {
            timelineResult = ApiResult.NetworkFailure(java.io.IOException("offline"))
        }

        assertTrue(InboxRepository(api).thread("ws-1", "c1", null) is ApiResult.NetworkFailure)
    }

    // ── Send ─────────────────────────────────────────────────────────────────

    @Test
    fun `a 200 with success false is a refusal, not a send`() = runTest {
        // ⛔ See the class doc. This is the assertion that stops the app claiming a message was sent.
        val api = FakeDistrictApi().apply {
            sendResult = ApiResult.Success(
                SendMessageResponse(success = false, error = "Sender not verified."),
            )
        }

        val result = InboxRepository(api).send("ws-1", "+1416", "hi", "sms")

        assertTrue(result is ApiResult.HttpFailure)
        assertEquals("Sender not verified.", (result as ApiResult.HttpFailure).message)
    }

    @Test
    fun `a refusal with no sentence is still a refusal, with an empty message`() = runTest {
        val api = FakeDistrictApi().apply {
            sendResult = ApiResult.Success(SendMessageResponse(success = false))
        }

        val result = InboxRepository(api).send("ws-1", "+1416", "hi", "sms")

        assertEquals(ApiResult.HttpFailure(status = 200, message = ""), result)
    }

    @Test
    fun `a genuine send succeeds and carries the request through verbatim`() = runTest {
        val api = FakeDistrictApi().apply {
            sendResult = ApiResult.Success(SendMessageResponse(success = true))
        }

        val result = InboxRepository(api).send("ws-1", "+14165550142", "on our way", "sms")

        assertTrue(result is ApiResult.Success)
        assertEquals(1, api.sends.size)
        assertEquals("+14165550142", api.sends.single().to)
        assertEquals("on our way", api.sends.single().body)
        assertEquals("sms", api.sends.single().channel)
    }

    @Test
    fun `a rate limit is surfaced rather than absorbed`() = runTest {
        // ⛔ NEVER RETRIED HERE. A retry would spend a customer's money on this client's own
        // initiative; the server caps a workspace at 30/min precisely because sends are billable.
        val api = FakeDistrictApi().apply {
            sendResult = ApiResult.RateLimited("Too many messages.")
        }

        val result = InboxRepository(api).send("ws-1", "+1416", "hi", "sms")

        assertTrue(result is ApiResult.RateLimited)
        assertEquals(1, api.sends.size)
    }

    // ── Mark read ────────────────────────────────────────────────────────────

    @Test
    fun `mark read reports how many rows it touched`() = runTest {
        val api = FakeDistrictApi().apply {
            markReadResult = ApiResult.Success(
                com.distronode.districtai.core.model.MarkReadResponse(success = true, marked = 3),
            )
        }

        assertEquals(3, (InboxRepository(api).markRead("ws-1", "c1", null) as ApiResult.Success).value)
    }

    @Test
    fun `a failed mark read is passed through rather than read as zero`() = runTest {
        // ⚠️ Zero is a real answer (nothing marked); a failure must not look like it.
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        val api = FakeDistrictApi().apply { markReadResult = offline }

        assertEquals(offline, InboxRepository(api).markRead("ws-1", null, "+14165550142"))
    }
}
