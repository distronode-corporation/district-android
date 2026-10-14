package com.distronode.districtai.core.network.live

import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_AGENT_ERROR
import com.distronode.districtai.core.model.TRANSCRIPT_ENDED_CALL_ENDED
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_NOT_LIVE
import com.distronode.districtai.core.model.TRANSCRIPT_ERROR_RATE_LIMITED
import com.distronode.districtai.core.model.TranscriptEndedData
import com.distronode.districtai.core.model.TranscriptErrorData
import com.distronode.districtai.core.model.TranscriptRetractedData
import com.distronode.districtai.core.model.TranscriptSegment
import com.distronode.districtai.core.model.TranscriptSnapshotData
import com.distronode.districtai.core.network.ApiResult

/**
 * One call's live transcript, as a pure state machine: the client algorithm of the `transcript`
 * v1 contract (§4.6, with the §4.12 clarifications), ported from district-core-swift's
 * `TranscriptReducer` rule for rule.
 *
 * ⛔ NO SOCKET, NO TIMER AND NO NETWORK CALL IN HERE: events in, commands out. The app performs
 * the commands.
 *
 * The rules:
 *
 * 1. **Stale frames.** Per epoch, a high-water mark and the set of missing seqs below it. A frame
 *    above the mark raises it, and the seqs it skipped join the missing set; a frame whose seq is
 *    missing fills its gap and is applied; anything else is a duplicate and is dropped.
 * 2. **Revisions.** Each segment keeps its latest revision by `rev`; a final beats an interim.
 * 3. **Order.** (epoch, index), then the segment id so ties are stable.
 * 4. **Gaps.** A gap open for [GAP_HEAL_MILLIS] subscribes again, and the snapshot replaces the
 *    state. ⛔ ONE HEAL IN FLIGHT: no subscribe goes out while an earlier one is unanswered.
 * 5. **Snapshot.** Its parts' union replaces the state once the last arrives; every older epoch
 *    is closed. A broken snapshot is asked for again.
 * 6. **End.** `call_ended` or `handed_off` is terminal, and the full transcript is fetched (with
 *    backoff, since it is written only after the call's session closes). ⚠️ `agent_error` is not:
 *    the call may get a fresh assistant, so the phase is [LiveTranscriptPhase.Reconnecting].
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED for the reason it is on [TelemetryConnection]: one small
 * function per rule, around one state.
 */
@Suppress("TooManyFunctions")
class TranscriptReducer(val callId: String) {

    var phase: LiveTranscriptPhase = LiveTranscriptPhase.Subscribing
        private set

    /** False when the server's memory did not reach back to the call's first line. */
    var complete: Boolean = true
        private set

    var finalTranscript: FinalTranscriptState = FinalTranscriptState.NotRequested
        private set

    private val segments = mutableMapOf<String, TranscriptSegment>()

    /** Segment ids retracted one by one, so a late copy cannot bring one back. */
    private val tombstones = mutableSetOf<String>()
    private var seqs = mutableMapOf<Long, SeqTrack>()

    /** The newest snapshot's epoch: every older epoch is closed. */
    private var closedBelow: Long? = null

    /** The newest epoch any frame or snapshot named. */
    private var newestEpoch: Long? = null
    private var gapOpenedAt: Long? = null

    /** ⛔ A SUBSCRIBE IS UNANSWERED: no other may be sent. True from the start. */
    private var awaitingSnapshot = true
    private var assembly: Assembly? = null
    private var endedEpoch: Long? = null

    /** The end is known to be final, as opposed to `agent_error` or a bare "not live" snapshot. */
    private var terminal = false
    private var resubscribedAfterNotLiveAt: Long? = null

    /** An `all: true` purge left no baseline: the first frame of an epoch sets one. */
    private var unbaselined = false

    /** The lines to show, in order. An interim one (`isFinal == false`) is drawn as provisional. */
    val lines: List<TranscriptSegment>
        get() = segments.values.sortedWith(compareBy({ it.epoch }, { it.index }, { it.segmentId }))

    // ── Inputs ──────────────────────────────────────────────────────────────────────────────────

    /** Apply one event received at [now]. ⚠️ Events for another call change nothing. */
    fun apply(event: TranscriptEvent, now: Long): List<TranscriptCommand> {
        if (event.callId != callId) return emptyList()
        return when (event) {
            is TranscriptEvent.Snapshot -> snapshotPart(event.data)
            is TranscriptEvent.Segment -> interruptAssembly() + segment(event.data.segment, now)
            is TranscriptEvent.Ended -> interruptAssembly() + ended(event.data, now)
            is TranscriptEvent.Retracted -> interruptAssembly() + retracted(event.data, now)
            // An error answers an op, not the transcript's flow: it leaves an arriving snapshot alone.
            is TranscriptEvent.Error -> error(event.data)
        }
    }

    /** ⚠️ A snapshot's parts arrive back to back, so anything between them means one was lost. */
    private fun interruptAssembly(): List<TranscriptCommand> {
        if (assembly == null) return emptyList()
        assembly = null
        return heal(0)
    }

    /**
     * The socket opened again after a gap, and the connection re-sent the subscribe: a snapshot is
     * on its way. ⚠️ A half-received snapshot from the old socket is dropped; the lines stay.
     */
    fun reconnected() {
        assembly = null
        awaitingSnapshot = true
    }

    /** The call row shows the call is over. ⚠️ Nothing for a call with no live transcript. */
    fun callEnded(): List<TranscriptCommand> =
        if (phase is LiveTranscriptPhase.Unavailable) emptyList() else end(TRANSCRIPT_ENDED_CALL_ENDED, terminal = true)

    /**
     * The call row shows the call in progress (§4.12 Q4, Q10). ⛔ THE ONLY WAY BACK FROM
     * `not_live`, at most once per [NOT_LIVE_RESUBSCRIBE_MILLIS]: never a timer.
     */
    fun callShownInProgress(now: Long): List<TranscriptCommand> {
        if (phase != LiveTranscriptPhase.Unavailable(TRANSCRIPT_ERROR_NOT_LIVE)) return emptyList()
        val last = resubscribedAfterNotLiveAt
        if (last != null && now - last < NOT_LIVE_RESUBSCRIBE_MILLIS) return emptyList()
        resubscribedAfterNotLiveAt = now
        phase = LiveTranscriptPhase.Subscribing
        awaitingSnapshot = true
        return listOf(TranscriptCommand.Subscribe)
    }

    /**
     * The [TranscriptCommand.CheckGap] delay ran out. ⚠️ RE-CHECKS THE FACT RATHER THAN TRUSTING
     * THE TIMER: a gap that closed asks for nothing, and while a subscribe is unanswered nothing
     * is sent, since its snapshot will settle the gap.
     */
    fun gapCheck(now: Long): List<TranscriptCommand> {
        val opened = gapOpenedAt
        if (opened == null || awaitingSnapshot) return emptyList()
        val waited = now - opened
        if (waited < GAP_HEAL_MILLIS) return listOf(TranscriptCommand.CheckGap(GAP_HEAL_MILLIS - waited))
        return heal(0)
    }

    /** The answer to [TranscriptCommand.FetchFinal]: the transcript text, or the failure. */
    fun finalFetched(result: ApiResult<String>): List<TranscriptCommand> {
        val fetching = finalTranscript as? FinalTranscriptState.Fetching ?: return emptyList()
        val retryable = when (result) {
            is ApiResult.Success -> {
                if (result.value.isNotBlank()) {
                    finalTranscript = FinalTranscriptState.Loaded(result.value)
                    return listOf(TranscriptCommand.Unsubscribe)
                }
                true
            }
            is ApiResult.Failure -> TelemetryConnection.isTransient(result)
        }
        if (!retryable || fetching.attempt >= FINAL_FETCH_ATTEMPTS) {
            finalTranscript = (result as? ApiResult.Failure)?.let(FinalTranscriptState::Failed)
                ?: FinalTranscriptState.Empty
            return listOf(TranscriptCommand.Unsubscribe)
        }
        val next = fetching.attempt + 1
        finalTranscript = FinalTranscriptState.Fetching(next)
        return listOf(TranscriptCommand.FetchFinal(finalFetchDelayMillis(next)))
    }

    // ── Snapshots ───────────────────────────────────────────────────────────────────────────────

    private fun snapshotPart(data: TranscriptSnapshotData): List<TranscriptCommand> {
        val current = assembly
        val whole = when {
            data.part == 0 -> Assembly(data, data.segments, nextPart = 1, broken = false)
            current != null && current.nextPart == data.part && sameHeader(current.header, data) ->
                current.copy(segments = current.segments + data.segments, nextPart = current.nextPart + 1)
            // ⚠️ A PART OUT OF ORDER, ONE WITH NO PART 0, OR A HEADER THAT CHANGED: the rest of it is
            // let through, then a whole snapshot is asked for, so two heals are never in flight.
            else -> Assembly(data, emptyList(), nextPart = data.part + 1, broken = true)
        }
        if (data.more) {
            assembly = whole
            return emptyList()
        }
        assembly = null
        awaitingSnapshot = false
        return if (whole.broken) heal(0) else replace(whole)
    }

    private fun replace(whole: Assembly): List<TranscriptCommand> {
        val header = whole.header
        segments.clear()
        whole.segments.filterNot { it.segmentId in tombstones }.forEach(::merge)
        complete = header.complete
        val epoch = header.epoch
        val lastSeq = header.lastSeq
        if (epoch != null && lastSeq != null) {
            // ⚠️ `lastSeq` COVERS THE SNAPSHOT'S EPOCH ONLY; older epochs' lines stay for display.
            seqs = mutableMapOf(epoch to SeqTrack(lastSeq))
            closedBelow = maxOf(closedBelow ?: epoch, epoch)
            newestEpoch = maxOf(newestEpoch ?: epoch, epoch)
            unbaselined = false
        } else {
            // ⚠️ PURGED (§4.12 Q12): no mark and no missing seq, until a frame sets one.
            seqs = mutableMapOf()
            unbaselined = true
        }
        gapOpenedAt = null
        // ⚠️ A FINAL END STAYS FINAL, whatever a later snapshot says.
        if (terminal) return emptyList()
        if (header.live) {
            revive()
            return emptyList()
        }
        endedEpoch = epoch
        return when (val reason = header.endedReason) {
            TRANSCRIPT_ENDED_AGENT_ERROR -> {
                phase = LiveTranscriptPhase.Reconnecting
                finalTranscript = FinalTranscriptState.NotRequested
                emptyList()
            }
            // ⚠️ AN OLDER SERVER SENDS NO REASON: an end a newer epoch may still undo.
            null -> end(TRANSCRIPT_ENDED_CALL_ENDED, terminal = false)
            else -> end(reason, terminal = true)
        }
    }

    // ── Live frames ─────────────────────────────────────────────────────────────────────────────

    private fun segment(segment: TranscriptSegment, now: Long): List<TranscriptCommand> {
        val (fresh, commands) = admit(segment.epoch, segment.seq, now)
        if (!fresh || segment.segmentId in tombstones) return commands
        merge(segment)
        // ⚠️ A NEWER EPOCH AFTER AN END THAT WAS NOT FINAL IS A FRESH ASSISTANT: live again.
        val ended = endedEpoch
        if (awaitingNewEpoch && (ended == null || segment.epoch > ended)) revive()
        return commands
    }

    /** After `agent_error`, or a snapshot that said only "not live". */
    private val awaitingNewEpoch: Boolean
        get() = !terminal && (phase == LiveTranscriptPhase.Reconnecting || phase is LiveTranscriptPhase.Ended)

    private fun revive() {
        phase = LiveTranscriptPhase.Live
        finalTranscript = FinalTranscriptState.NotRequested
    }

    /** Rule 2: the latest revision wins, and a final beats an interim at any revision. */
    private fun merge(incoming: TranscriptSegment) {
        val stored = segments[incoming.segmentId]
        val wins = stored == null ||
            if (stored.isFinal == incoming.isFinal) incoming.rev >= stored.rev else incoming.isFinal
        if (wins) segments[incoming.segmentId] = incoming
    }

    /** ⚠️ ONLY THE NEWEST EPOCH'S END CHANGES THE PHASE: a late end of an older one is counted only. */
    private fun ended(data: TranscriptEndedData, now: Long): List<TranscriptCommand> {
        val (fresh, commands) = admit(data.epoch, data.seq, now)
        if (!fresh || data.epoch != newestEpoch) return commands
        endedEpoch = data.epoch
        if (data.reason != TRANSCRIPT_ENDED_AGENT_ERROR) return commands + end(data.reason, terminal = true)
        if (!terminal) phase = LiveTranscriptPhase.Reconnecting
        return commands
    }

    private fun end(reason: String, terminal: Boolean): List<TranscriptCommand> {
        phase = LiveTranscriptPhase.Ended(reason)
        this.terminal = this.terminal || terminal
        if (finalTranscript != FinalTranscriptState.NotRequested) return emptyList()
        finalTranscript = FinalTranscriptState.Fetching(1)
        return listOf(TranscriptCommand.FetchFinal(finalFetchDelayMillis(1)))
    }

    /**
     * ⚠️ A RETRACTION IS APPLIED EVEN WHEN ITS `seq` WAS SEEN, OR ITS EPOCH IS CLOSED: dropping lines
     * twice is harmless, and leaving one on screen is not. The website's carries no seq at all.
     */
    private fun retracted(data: TranscriptRetractedData, now: Long): List<TranscriptCommand> {
        val epoch = data.epoch
        val seq = data.seq
        val commands = if (epoch != null && seq != null) admit(epoch, seq, now).second else emptyList()
        val gone = if (data.all) segments.keys.toList() else data.segmentIds
        gone.forEach {
            segments.remove(it)
            tombstones.add(it)
        }
        return commands
    }

    /**
     * ⚠️ ONLY AN ERROR ABOUT A SUBSCRIBE CHANGES ANYTHING: the server echoes the op exactly, so a
     * refused unsubscribe is not one, and re-subscribing after it would undo it.
     */
    private fun error(data: TranscriptErrorData): List<TranscriptCommand> {
        if (data.op != TranscriptClientOp.SUBSCRIBE_OP) return emptyList()
        return when (data.code) {
            // The refused subscribe will bring no snapshot; this one replaces it.
            TRANSCRIPT_ERROR_RATE_LIMITED -> heal(data.retryAfterMs ?: RATE_LIMIT_FALLBACK_MILLIS)
            TRANSCRIPT_ERROR_NOT_LIVE -> {
                awaitingSnapshot = false
                // ⚠️ AN ENDED TRANSCRIPT STAYS ENDED: `not_live` also answers a subscribe long after
                // the end. One still reconnecting gives up (§4.12 Q11).
                if (phase is LiveTranscriptPhase.Ended) return emptyList()
                phase = LiveTranscriptPhase.Unavailable(data.code)
                listOf(TranscriptCommand.Unsubscribe)
            }
            else -> {
                awaitingSnapshot = false
                phase = LiveTranscriptPhase.Unavailable(data.code)
                listOf(TranscriptCommand.Unsubscribe)
            }
        }
    }

    // ── Sequence tracking ───────────────────────────────────────────────────────────────────────

    /** Subscribe again for a fresh snapshot. ⛔ Every caller has settled no other is in flight. */
    private fun heal(after: Long): List<TranscriptCommand> {
        awaitingSnapshot = true
        return listOf(TranscriptCommand.Resubscribe(after))
    }

    /**
     * Whether the frame `(epoch, seq)` is new (rule 1), and the gap check it may start.
     *
     * ⚠️ A `seq` BELOW 1 IS NOT ONE THE SERVER SENDS, and an epoch older than the newest snapshot's
     * is closed: both are dropped.
     */
    private fun admit(epoch: Long, seq: Int, now: Long): Pair<Boolean, List<TranscriptCommand>> {
        if (seq < 1 || epoch < (closedBelow ?: epoch)) return false to emptyList()
        val track = seqs.getOrPut(epoch) { SeqTrack(if (unbaselined) seq - 1 else 0) }
        if (seq <= track.high) {
            if (!track.fill(seq)) return false to emptyList()
        } else {
            if (seq > track.high + 1) track.missing.add(track.high + 1..seq - 1)
            track.high = seq
        }
        newestEpoch = maxOf(newestEpoch ?: epoch, epoch)
        return true to updateGap(now)
    }

    private fun updateGap(now: Long): List<TranscriptCommand> {
        if (seqs.values.none { it.missing.isNotEmpty() }) {
            gapOpenedAt = null
            return emptyList()
        }
        if (gapOpenedAt != null) return emptyList()
        gapOpenedAt = now
        return listOf(TranscriptCommand.CheckGap(GAP_HEAL_MILLIS))
    }

    /** What has been seen of one epoch's `seq` counter. */
    private class SeqTrack(var high: Int) {
        /** The seqs below [high] not yet seen, as ranges: a jump of any size costs one. */
        val missing = mutableListOf<IntRange>()

        /** Take [seq] out of the missing set; whether it was there. */
        fun fill(seq: Int): Boolean {
            val index = missing.indexOfFirst { seq in it }
            if (index < 0) return false
            val range = missing.removeAt(index)
            if (seq < range.last) missing.add(index, seq + 1..range.last)
            if (range.first < seq) missing.add(index, range.first..seq - 1)
            return true
        }
    }

    /** A snapshot being put together from its parts. */
    private data class Assembly(
        val header: TranscriptSnapshotData,
        val segments: List<TranscriptSegment>,
        val nextPart: Int,
        val broken: Boolean,
    )

    companion object {
        /** How long a gap may stay open before the call is subscribed again. */
        const val GAP_HEAL_MILLIS: Long = 2_000

        /** The wait before re-subscribing after `rate_limited` that gave no `retryAfterMs`. */
        const val RATE_LIMIT_FALLBACK_MILLIS: Long = 2_000

        /** The least time between two subscribes after `not_live` (§4.12 Q10). */
        const val NOT_LIVE_RESUBSCRIBE_MILLIS: Long = 30_000

        /** How many times the full transcript is asked for before giving up. */
        const val FINAL_FETCH_ATTEMPTS: Int = 8

        private const val FINAL_FETCH_FIRST_MILLIS: Long = 2_000
        private const val FINAL_FETCH_CAP_MILLIS: Long = 30_000
        private const val FINAL_FETCH_MAX_DOUBLINGS = 8

        /** The wait before final-transcript attempt [attempt] (1 for the first): 2 s, doubling to 30 s. */
        fun finalFetchDelayMillis(attempt: Int): Long {
            val doublings = (attempt.coerceAtLeast(1) - 1).coerceAtMost(FINAL_FETCH_MAX_DOUBLINGS)
            return (FINAL_FETCH_FIRST_MILLIS shl doublings).coerceAtMost(FINAL_FETCH_CAP_MILLIS)
        }

        /** §4.12 Q6: `live`, `endedReason`, `complete`, `epoch` and `lastSeq` are the same on every part. */
        private fun sameHeader(lhs: TranscriptSnapshotData, rhs: TranscriptSnapshotData): Boolean =
            lhs.live == rhs.live && lhs.complete == rhs.complete && lhs.epoch == rhs.epoch &&
                lhs.lastSeq == rhs.lastSeq && lhs.endedReason == rhs.endedReason
    }
}

/** Where one call's live transcript stands. */
sealed interface LiveTranscriptPhase {
    /** Subscribed; the first snapshot has not arrived. ⚠️ This can last up to 30 seconds. */
    data object Subscribing : LiveTranscriptPhase

    /** Lines arrive as they are spoken. */
    data object Live : LiveTranscriptPhase

    /** The assistant failed (`agent_error`); a fresh one may continue as a new epoch. */
    data object Reconnecting : LiveTranscriptPhase

    /** The assistant stopped transcribing (or the call ended), for [reason]. The lines stay. */
    data class Ended(val reason: String) : LiveTranscriptPhase

    /**
     * No live transcript can be shown for this call, for [code] (a `transcript_error` code). The
     * client offers the transcript after the call instead.
     */
    data class Unavailable(val code: String) : LiveTranscriptPhase
}

/** The full transcript, fetched once the live one ended. */
sealed interface FinalTranscriptState {
    data object NotRequested : FinalTranscriptState

    /** Being fetched; [attempt] counts from 1. */
    data class Fetching(val attempt: Int) : FinalTranscriptState
    data class Loaded(val text: String) : FinalTranscriptState

    /** Still empty after every attempt. */
    data object Empty : FinalTranscriptState
    data class Failed(val failure: ApiResult.Failure) : FinalTranscriptState
}

/** What a [TranscriptReducer] asks its owner to do. ⚠️ A new one replaces any pending one of its kind. */
sealed interface TranscriptCommand {
    /** Subscribe again as a new subscription, after `not_live` and a call-status signal. */
    data object Subscribe : TranscriptCommand

    /** Send `transcript.subscribe` again after the delay (0: now), for a fresh snapshot. */
    data class Resubscribe(val afterMillis: Long) : TranscriptCommand

    /** Call [TranscriptReducer.gapCheck] after the delay. */
    data class CheckGap(val afterMillis: Long) : TranscriptCommand

    /** After the delay, fetch the call's transcript and hand it to [TranscriptReducer.finalFetched]. */
    data class FetchFinal(val afterMillis: Long) : TranscriptCommand

    /** Nothing more is wanted from the server for this call. */
    data object Unsubscribe : TranscriptCommand
}
