package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_AGENT_ERROR
import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_CALL_ENDED
import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_HANDED_OFF
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_FORBIDDEN_ROLE
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_NOT_LIVE
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_RATE_LIMITED
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.live.TranscriptCommand as Cmd
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptReducerTest {

    private fun reducer() = TranscriptReducer(CALL)

    /** A reducer that has had its first snapshot, live, with [lines]. */
    private fun live(vararg lines: com.distronode.districtai.core.model.TranscriptSegment): TranscriptReducer =
        reducer().apply { apply(snapshotEvent(lines.toList()), 0) }

    private fun TranscriptReducer.ids(): List<String> = lines.map { it.segmentId }

    @Test
    fun `a live snapshot replaces the state and goes live`() {
        val reducer = reducer()
        assertEquals(LiveTranscriptPhase.Subscribing, reducer.phase)
        assertEquals(emptyList<Cmd>(), reducer.apply(snapshotEvent(listOf(segment("b", 1, 2), segment("a", 0, 1))), 0))
        assertEquals(LiveTranscriptPhase.Live, reducer.phase)
        assertEquals(listOf("a", "b"), reducer.ids())
        assertEquals(CALL, reducer.callId)
    }

    @Test
    fun `events for another call change nothing`() {
        val reducer = reducer()
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("a", 0, 1), callId = "other"), 0))
        assertEquals(emptyList<String>(), reducer.ids())
    }

    @Test
    fun `lines are ordered by epoch, index, then id`() {
        val reducer = live(
            segment("z", 0, 1, epoch = 2_000),
            segment("y", 1, 2),
            segment("x", 1, 3),
        )
        assertEquals(listOf("x", "y", "z"), reducer.ids())
    }

    @Test
    fun `segments in order apply, and a duplicate is dropped`() {
        val reducer = live()
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("a", 0, 1)), 0))
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("a", 0, 1, text = "dup")), 0))
        assertEquals("line a", reducer.lines.single().text)
        // A seq below one is not one the server sends.
        reducer.apply(segmentEvent(segment("bad", 1, 0)), 0)
        assertEquals(listOf("a"), reducer.ids())
    }

    @Test
    fun `a gap opens a check, late frames fill it, and a closed gap asks for nothing`() {
        val reducer = live()
        reducer.apply(segmentEvent(segment("a", 0, 1)), 0)
        assertEquals(listOf(Cmd.CheckGap(2_000)), reducer.apply(segmentEvent(segment("e", 4, 5)), 100))
        // A second frame while the gap is open starts no second check.
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("f", 5, 6)), 200))
        // Fill the middle of 2..4 first, then each end.
        reducer.apply(segmentEvent(segment("c", 2, 3)), 300)
        reducer.apply(segmentEvent(segment("b", 1, 2)), 400)
        reducer.apply(segmentEvent(segment("d", 3, 4)), 500)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), reducer.ids())
        assertEquals(emptyList<Cmd>(), reducer.gapCheck(2_100))
    }

    @Test
    fun `a gap left open heals by subscribing again, once`() {
        val reducer = live()
        reducer.apply(segmentEvent(segment("a", 0, 1)), 0)
        reducer.apply(segmentEvent(segment("c", 2, 5)), 1_000)
        // Early: re-arm for the rest.
        assertEquals(listOf(Cmd.CheckGap(500)), reducer.gapCheck(2_500))
        assertEquals(listOf(Cmd.Resubscribe(0)), reducer.gapCheck(3_000))
        // ⛔ One heal in flight: its snapshot settles the gap.
        assertEquals(emptyList<Cmd>(), reducer.gapCheck(9_000))
        reducer.apply(snapshotEvent(listOf(segment("a", 0, 1), segment("b", 1, 2), segment("c", 2, 5))), 9_100)
        assertEquals(emptyList<Cmd>(), reducer.gapCheck(20_000))
        assertEquals(listOf("a", "b", "c"), reducer.ids())
    }

    @Test
    fun `seqs at or below the snapshot's mark are duplicates, and older epochs are closed`() {
        val reducer = live(segment("a", 0, 1, epoch = 2_000), segment("b", 1, 2, epoch = 2_000)).apply {
            apply(snapshotEvent(listOf(segment("a", 0, 1, epoch = 2_000)), epoch = 2_000, lastSeq = 3), 0)
        }
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("b", 1, 3, epoch = 2_000)), 0))
        reducer.apply(segmentEvent(segment("old", 0, 9, epoch = 1_000)), 0)
        assertEquals(listOf("a"), reducer.ids())
        reducer.apply(segmentEvent(segment("c", 2, 4, epoch = 2_000)), 0)
        assertEquals(listOf("a", "c"), reducer.ids())
    }

    @Test
    fun `the latest revision wins, and a final beats an interim at any revision`() {
        val reducer = live()
        reducer.apply(segmentEvent(segment("a", 0, 1, rev = 1, isFinal = false, text = "hel")), 0)
        reducer.apply(segmentEvent(segment("a", 0, 2, rev = 0, isFinal = false, text = "older")), 0)
        assertEquals("hel", reducer.lines.single().text)
        reducer.apply(segmentEvent(segment("a", 0, 3, rev = 2, isFinal = false, text = "hello")), 0)
        assertEquals("hello", reducer.lines.single().text)
        reducer.apply(segmentEvent(segment("a", 0, 4, rev = 0, isFinal = true, text = "Hello.")), 0)
        assertEquals("Hello.", reducer.lines.single().text)
        reducer.apply(segmentEvent(segment("a", 0, 5, rev = 9, isFinal = false, text = "late")), 0)
        assertEquals("Hello.", reducer.lines.single().text)
    }

    @Test
    fun `snapshot parts are put together and replace the state once the last arrives`() {
        val reducer = live(segment("x", 0, 1))
        assertEquals(
            emptyList<Cmd>(),
            reducer.apply(snapshotEvent(listOf(segment("a", 0, 1)), lastSeq = 2, more = true), 0)
        )
        assertEquals(listOf("x"), reducer.ids())
        assertEquals(
            emptyList<Cmd>(),
            reducer.apply(snapshotEvent(listOf(segment("b", 1, 2)), lastSeq = 2, part = 1, more = false), 0),
        )
        assertEquals(listOf("a", "b"), reducer.ids())
    }

    @Test
    fun `a broken snapshot is asked for again`() {
        // A part out of order.
        val skipped = live()
        skipped.apply(snapshotEvent(lastSeq = 2, more = true), 0)
        assertEquals(listOf(Cmd.Resubscribe(0)), skipped.apply(snapshotEvent(lastSeq = 2, part = 2), 0))
        // A header that changed between parts.
        val changed = live()
        changed.apply(snapshotEvent(lastSeq = 2, more = true), 0)
        assertEquals(listOf(Cmd.Resubscribe(0)), changed.apply(snapshotEvent(lastSeq = 3, part = 1), 0))
        // A later part with no part 0, through every header field.
        listOf(
            snapshotEvent(part = 1, live = false),
            snapshotEvent(part = 1, complete = false),
            snapshotEvent(part = 1, epoch = 7),
            snapshotEvent(part = 1, endedReason = TRANSCRIPT_ENDED_CALL_ENDED),
        ).forEach { assertEquals(listOf(Cmd.Resubscribe(0)), live().apply(it, 0)) }
        // A header field changing mid-assembly.
        listOf(
            snapshotEvent(part = 1, live = false),
            snapshotEvent(part = 1, complete = false),
            snapshotEvent(part = 1, epoch = 7),
            snapshotEvent(part = 1, endedReason = "x"),
        ).forEach { second ->
            val reducer = live()
            reducer.apply(snapshotEvent(more = true), 0)
            assertEquals(listOf(Cmd.Resubscribe(0)), reducer.apply(second, 0))
        }
    }

    @Test
    fun `any other frame between parts drops the assembly and asks again`() {
        listOf(
            segmentEvent(segment("a", 0, 1)),
            endedEvent(seq = 1, reason = TRANSCRIPT_ENDED_HANDED_OFF),
            retractedEvent(listOf("a")),
        ).forEach { between ->
            val reducer = live()
            reducer.apply(snapshotEvent(more = true), 0)
            assertEquals(Cmd.Resubscribe(0), reducer.apply(between, 0).first())
        }
        // An error is about an op, not the flow, and leaves the assembly alone.
        val reducer = live()
        reducer.apply(snapshotEvent(more = true), 0)
        assertEquals(emptyList<Cmd>(), reducer.apply(errorEvent("x", op = "transcript.unsubscribe"), 0))
        assertEquals(
            emptyList<Cmd>(),
            reducer.apply(snapshotEvent(listOf(segment("a", 0, 1)), lastSeq = 0, part = 1), 0)
        )
        assertEquals(listOf("a"), reducer.ids())
    }

    @Test
    fun `reconnecting drops a half-received snapshot and keeps the lines`() {
        val reducer = live(segment("a", 0, 1))
        reducer.apply(snapshotEvent(more = true), 0)
        reducer.reconnected()
        assertEquals(listOf(Cmd.Resubscribe(0)), reducer.apply(snapshotEvent(part = 1), 0))
        assertEquals(listOf("a"), reducer.ids())
    }

    @Test
    fun `an incomplete snapshot says so`() {
        val reducer = reducer()
        reducer.apply(snapshotEvent(complete = false), 0)
        assertFalse(reducer.complete)
    }

    @Test
    fun `a terminal end fetches the full transcript with backoff until it has text`() {
        val reducer = live(segment("a", 0, 1))
        assertEquals(
            listOf(Cmd.FetchFinal(2_000)),
            reducer.apply(endedEvent(seq = 2, reason = TRANSCRIPT_ENDED_CALL_ENDED), 0),
        )
        assertEquals(LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_CALL_ENDED), reducer.phase)
        assertEquals(FinalTranscriptState.Fetching(1), reducer.finalTranscript)
        assertEquals(listOf(Cmd.FetchFinal(4_000)), reducer.finalFetched(ApiResult.Success("  ")))
        assertEquals(
            listOf(Cmd.FetchFinal(8_000)),
            reducer.finalFetched(ApiResult.NetworkFailure(IOException("offline"))),
        )
        assertEquals(listOf(Cmd.Unsubscribe), reducer.finalFetched(ApiResult.Success("Full text")))
        assertEquals(FinalTranscriptState.Loaded("Full text"), reducer.finalTranscript)
        // Nothing is being fetched any more.
        assertEquals(emptyList<Cmd>(), reducer.finalFetched(ApiResult.Success("again")))
        // A second end asks for nothing more.
        assertEquals(emptyList<Cmd>(), reducer.callEnded())
    }

    @Test
    fun `a final fetch failure is given up at once, and an empty one after every attempt`() {
        val refused = live()
        refused.callEnded()
        val failure = ApiResult.NotFound("gone")
        assertEquals(listOf(Cmd.Unsubscribe), refused.finalFetched(failure))
        assertEquals(FinalTranscriptState.Failed(failure), refused.finalTranscript)

        val empty = live()
        empty.callEnded()
        repeat(TranscriptReducer.FINAL_FETCH_ATTEMPTS - 1) { empty.finalFetched(ApiResult.Success("")) }
        assertEquals(listOf(Cmd.Unsubscribe), empty.finalFetched(ApiResult.Success("")))
        assertEquals(FinalTranscriptState.Empty, empty.finalTranscript)

        val flaky = live()
        flaky.callEnded()
        val offline = ApiResult.NetworkFailure(IOException("offline"))
        repeat(TranscriptReducer.FINAL_FETCH_ATTEMPTS) { flaky.finalFetched(offline) }
        assertEquals(FinalTranscriptState.Failed(offline), flaky.finalTranscript)
    }

    @Test
    fun `the final fetch waits 2 s, doubling to 30 s`() {
        assertEquals(2_000L, TranscriptReducer.finalFetchDelayMillis(0))
        assertEquals(2_000L, TranscriptReducer.finalFetchDelayMillis(1))
        assertEquals(16_000L, TranscriptReducer.finalFetchDelayMillis(4))
        assertEquals(30_000L, TranscriptReducer.finalFetchDelayMillis(5))
        assertEquals(30_000L, TranscriptReducer.finalFetchDelayMillis(50))
    }

    @Test
    fun `agent error is not the end, and a new epoch revives it`() {
        val reducer = live(segment("a", 0, 1))
        assertEquals(emptyList<Cmd>(), reducer.apply(endedEvent(seq = 2, reason = TRANSCRIPT_ENDED_AGENT_ERROR), 0))
        assertEquals(LiveTranscriptPhase.Reconnecting, reducer.phase)
        // The old epoch's line again: not a fresh assistant.
        reducer.apply(segmentEvent(segment("b", 1, 3)), 0)
        assertEquals(LiveTranscriptPhase.Reconnecting, reducer.phase)
        reducer.apply(segmentEvent(segment("n", 0, 1, epoch = 5_000)), 0)
        assertEquals(LiveTranscriptPhase.Live, reducer.phase)
        assertEquals(listOf("a", "b", "n"), reducer.ids())
    }

    @Test
    fun `a late end of an older epoch is counted and nothing more`() {
        val reducer = live(segment("a", 0, 1))
        reducer.apply(segmentEvent(segment("n", 0, 1, epoch = 5_000)), 0)
        assertEquals(emptyList<Cmd>(), reducer.apply(endedEvent(seq = 2, reason = TRANSCRIPT_ENDED_CALL_ENDED), 0))
        assertEquals(LiveTranscriptPhase.Live, reducer.phase)
        // A duplicate end is dropped by seq.
        reducer.apply(endedEvent(seq = 1, reason = TRANSCRIPT_ENDED_CALL_ENDED, epoch = 5_000), 0)
        assertEquals(LiveTranscriptPhase.Live, reducer.phase)
    }

    @Test
    fun `agent error after a terminal end leaves it ended`() {
        val reducer = live()
        reducer.callEnded()
        reducer.apply(segmentEvent(segment("a", 0, 1)), 0)
        reducer.apply(endedEvent(seq = 2, reason = TRANSCRIPT_ENDED_AGENT_ERROR), 0)
        assertEquals(LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_CALL_ENDED), reducer.phase)
    }

    @Test
    fun `a not-live snapshot reads its reason`() {
        val handedOff = reducer()
        assertEquals(
            listOf(Cmd.FetchFinal(2_000)),
            handedOff.apply(snapshotEvent(live = false, endedReason = TRANSCRIPT_ENDED_HANDED_OFF), 0),
        )
        assertEquals(LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_HANDED_OFF), handedOff.phase)
        // ⚠️ A final end stays final, whatever a later snapshot says.
        assertEquals(emptyList<Cmd>(), handedOff.apply(snapshotEvent(live = true), 0))
        assertEquals(LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_HANDED_OFF), handedOff.phase)

        val agent = reducer()
        assertEquals(
            emptyList<Cmd>(),
            agent.apply(snapshotEvent(live = false, endedReason = TRANSCRIPT_ENDED_AGENT_ERROR), 0)
        )
        assertEquals(LiveTranscriptPhase.Reconnecting, agent.phase)
        assertEquals(FinalTranscriptState.NotRequested, agent.finalTranscript)
    }

    @Test
    fun `a not-live snapshot with no reason is an end a newer epoch may undo`() {
        val reducer = reducer()
        assertEquals(listOf(Cmd.FetchFinal(2_000)), reducer.apply(snapshotEvent(live = false), 0))
        assertEquals(LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_CALL_ENDED), reducer.phase)
        reducer.apply(segmentEvent(segment("n", 0, 1, epoch = 9_000)), 0)
        assertEquals(LiveTranscriptPhase.Live, reducer.phase)
        assertEquals(FinalTranscriptState.NotRequested, reducer.finalTranscript)

        // Purged and not live: no epoch to compare, so any frame revives it.
        val purged = reducer()
        purged.apply(snapshotEvent(live = false, epoch = null, lastSeq = null), 0)
        purged.apply(segmentEvent(segment("p", 0, 4)), 0)
        assertEquals(LiveTranscriptPhase.Live, purged.phase)
    }

    @Test
    fun `after a purge the first frame of an epoch sets the baseline`() {
        val reducer = reducer()
        reducer.apply(snapshotEvent(epoch = null, lastSeq = null), 0)
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("a", 0, 40)), 0))
        assertEquals(emptyList<Cmd>(), reducer.apply(segmentEvent(segment("b", 1, 41)), 0))
        assertEquals(listOf("a", "b"), reducer.ids())
    }

    @Test
    fun `retractions drop lines for good, counted or not`() {
        val reducer = live(segment("a", 0, 1), segment("b", 1, 2), segment("c", 2, 3))
        assertEquals(emptyList<Cmd>(), reducer.apply(retractedEvent(listOf("a")), 0))
        assertEquals(listOf("b", "c"), reducer.ids())
        // A late copy of a retracted line cannot bring it back, live or in a snapshot.
        reducer.apply(segmentEvent(segment("a", 0, 4)), 0)
        reducer.apply(snapshotEvent(listOf(segment("a", 0, 1), segment("b", 1, 2), segment("c", 2, 3)), lastSeq = 4), 0)
        assertEquals(listOf("b", "c"), reducer.ids())
        // The agent's carries a seq, which is counted (here, a gap).
        assertEquals(listOf(Cmd.CheckGap(2_000)), reducer.apply(retractedEvent(listOf("b"), epoch = EPOCH, seq = 9), 0))
        assertEquals(listOf("c"), reducer.ids())
        reducer.apply(retractedEvent(all = true, epoch = EPOCH, seq = 9), 0)
        assertEquals(emptyList<String>(), reducer.ids())
    }

    @Test
    fun `rate limited re-subscribes after the server's wait, or the fallback`() {
        val reducer = reducer()
        assertEquals(
            listOf(Cmd.Resubscribe(750)),
            reducer.apply(errorEvent(TRANSCRIPT_ERROR_RATE_LIMITED, retryAfterMs = 750), 0)
        )
        assertEquals(listOf(Cmd.Resubscribe(2_000)), reducer.apply(errorEvent(TRANSCRIPT_ERROR_RATE_LIMITED), 0))
    }

    @Test
    fun `not live is final for its subscribe, until a call-status signal`() {
        val reducer = reducer()
        assertEquals(listOf(Cmd.Unsubscribe), reducer.apply(errorEvent(TRANSCRIPT_ERROR_NOT_LIVE), 0))
        assertEquals(LiveTranscriptPhase.Unavailable(TRANSCRIPT_ERROR_NOT_LIVE), reducer.phase)
        // ⚠️ Nothing for a call with no live transcript.
        assertEquals(emptyList<Cmd>(), reducer.callEnded())
        assertEquals(listOf(Cmd.Subscribe), reducer.callShownInProgress(100_000))
        assertEquals(LiveTranscriptPhase.Subscribing, reducer.phase)
        reducer.apply(errorEvent(TRANSCRIPT_ERROR_NOT_LIVE), 0)
        // At most once per 30 s.
        assertEquals(emptyList<Cmd>(), reducer.callShownInProgress(110_000))
        assertEquals(listOf(Cmd.Subscribe), reducer.callShownInProgress(130_000))
        // While anything but not live, it asks for nothing.
        assertEquals(emptyList<Cmd>(), reducer.callShownInProgress(500_000))
    }

    @Test
    fun `not live after an end leaves the transcript ended`() {
        val reducer = live()
        reducer.callEnded()
        assertEquals(emptyList<Cmd>(), reducer.apply(errorEvent(TRANSCRIPT_ERROR_NOT_LIVE), 0))
        assertEquals(LiveTranscriptPhase.Ended(TRANSCRIPT_ENDED_CALL_ENDED), reducer.phase)
    }

    @Test
    fun `any other refused subscribe makes it unavailable, and other ops change nothing`() {
        val reducer = reducer()
        assertEquals(emptyList<Cmd>(), reducer.apply(errorEvent(TRANSCRIPT_ERROR_FORBIDDEN_ROLE, op = null), 0))
        assertEquals(listOf(Cmd.Unsubscribe), reducer.apply(errorEvent(TRANSCRIPT_ERROR_FORBIDDEN_ROLE), 0))
        assertEquals(LiveTranscriptPhase.Unavailable(TRANSCRIPT_ERROR_FORBIDDEN_ROLE), reducer.phase)
    }
}
