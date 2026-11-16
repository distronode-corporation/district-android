package com.distronode.districtai.ui.hq.voice

import com.distronode.districtai.core.data.HqVoiceRepository
import com.distronode.districtai.core.media.HqVoiceEngine
import com.distronode.districtai.core.media.HqVoiceEngineEvent
import com.distronode.districtai.core.media.HqVoiceEngineFactory
import com.distronode.districtai.core.model.HqAgentState
import com.distronode.districtai.core.model.HqMicMode
import com.distronode.districtai.core.model.HqResolutionStatus
import com.distronode.districtai.core.model.HqTurn
import com.distronode.districtai.core.model.HqVoice
import com.distronode.districtai.core.model.HqVoicePacket
import com.distronode.districtai.core.model.HqVoiceTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One District HQ voice session, from the member's tap to its end. Ported from iOS
 * (`HQVoiceSession.swift`); the rules are the same unless a ⚠️ says otherwise.
 *
 * ⛔ A TOKEN IS MINTED ONLY ON THE MEMBER'S TAP ([start]), never as a prefetch: each one starts a
 * billed session of at most [HqVoice.SESSION_SECONDS].
 *
 * ⛔ THE CONSENT NOTICE COMES BEFORE THE MICROPHONE. The credential's `processingRegion` is shown
 * ([HqVoicePhase.Notice]) and nothing is joined until the member says Start ([begin]). ⚠️ ON
 * ANDROID THE MICROPHONE PERMISSION IS ASKED FOR ONLY THEN, after that consent
 * ([HqVoicePhase.Permission]), never at the Talk tap; a refusal discards the session.
 *
 * ⛔ A SESSION THAT NEVER WENT LIVE IS DISCARDED, ONE THE APP ENDS IS ENDED. A declined notice, a
 * refused microphone, a failed join and an agent that never joined call `hq/voice-discard`, and a
 * discard the service refuses falls back to `hq/voice-end` ([discard]); Stop, the 30 minutes, the
 * audio being taken, the agent leaving and the screen going away call `hq/voice-end`, so only the
 * minutes used are counted. Both by `sessionId`.
 *
 * ⛔ PUSH-TO-TALK STARTS MUTED. With [HqMicMode.PUSH] the microphone opens only while Hold to talk
 * is held ([holdToTalk]); with an open mode it opens on the join.
 *
 * ⛔ ONE ROOM AT A TIME WITH CALLS AND MEETINGS. Refused while the audio is held by a call or a
 * meeting ([HqVoiceAudio.isBusy]); a call ringing or answered while live takes the audio focus and
 * ends it ([HqVoiceEndReason.Interrupted]).
 *
 * ⛔ EVERY COROUTINE RUNS ON [scope], WHICH IS THE APPLICATION'S, NOT A SCREEN'S, so the end or the
 * discard still reaches the service after the screen and its ViewModel are gone. The scope must
 * be single-threaded (the application scope is `Main.immediate`): the state below is not locked.
 */
@Suppress("TooManyFunctions")
class HqVoiceSession(
    private val workspaceId: String,
    val canConfirm: Boolean,
    private val repository: HqVoiceRepository,
    private val engineFactory: HqVoiceEngineFactory,
    private val audio: HqVoiceAudio,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(HqVoiceUiState())
    val state: StateFlow<HqVoiceUiState> = _state.asStateFlow()

    private val phase: HqVoicePhase get() = _state.value.phase
    private var engine: HqVoiceEngine? = null
    private var credential: HqVoiceTokenResponse? = null

    /** ⚠️ A finished placeholder until the join, so ending never has to ask whether one exists. */
    private var listening: Job = Job().apply { cancel() }

    private var timer: Job? = null
    private var agentWindow: Job? = null
    private val microphone = Mutex()
    private var micWanted = false

    /** Each mint's number, so an answer overtaken by a Stop and a new Talk is given back, not shown. */
    private var mint = 0

    /** The member tapped Talk: check the audio, then mint. */
    fun start(history: List<HqTurn>, locale: String): Job = scope.launch {
        if (phase.isActive) return@launch
        if (audio.isBusy()) {
            setPhase(HqVoicePhase.Ended(HqVoiceEndReason.Busy))
            return@launch
        }
        _state.value = HqVoiceUiState(phase = HqVoicePhase.Minting)
        val mine = ++mint
        val answer = repository.voiceToken(workspaceId, locale, history)
        // ⚠️ STOPPED, OR REPLACED, WHILE THE TOKEN WAS IN FLIGHT: nothing is shown, and a session the
        // service did open is discarded.
        if (phase != HqVoicePhase.Minting || mine != mint) {
            if (answer is ApiResult.Success) discard(answer.value)
            return@launch
        }
        when (answer) {
            is ApiResult.Success -> {
                credential = answer.value
                _state.update {
                    it.copy(phase = HqVoicePhase.Notice(answer.value), micMode = answer.value.micModeValue)
                }
            }
            // ⚠️ THE ALLOWANCE AND CAPACITY REFUSALS ARE THE SERVICE'S OWN SENTENCES (translated by
            // their code); the text console keeps working.
            is ApiResult.Failure -> setPhase(
                HqVoicePhase.Ended(
                    if (answer.code() in REFUSAL_CODES) {
                        HqVoiceEndReason.Refused(answer.toFailureText())
                    } else {
                        HqVoiceEndReason.Failed(answer.toFailureText())
                    },
                ),
            )
        }
    }

    /** The member declined the notice (or dismissed it): nothing is joined, and the session is discarded. */
    fun cancelNotice(): Job = scope.launch { decline() }

    /**
     * The member said Start on the notice. ⚠️ Asks for the microphone permission now if it is not
     * held, and joins only once it is ([onMicrophonePermission]).
     */
    fun begin(): Job = scope.launch {
        val notice = phase as? HqVoicePhase.Notice ?: return@launch
        if (audio.isBusy()) {
            setPhase(HqVoicePhase.Ended(HqVoiceEndReason.Busy))
            credential = null
            discard(notice.credential)
            return@launch
        }
        if (audio.hasMicrophonePermission()) {
            join(notice.credential)
        } else {
            _state.update {
                it.copy(
                    phase = HqVoicePhase.Permission(notice.credential),
                    microphoneRequest = it.microphoneRequest + 1,
                )
            }
        }
    }

    /** The screen launched the permission request: it is not launched again (a rotation, a return). */
    fun onMicrophoneRequested() {
        _state.update { it.copy(microphoneRequest = 0) }
    }

    /** The system's answer to the permission request. */
    fun onMicrophonePermission(granted: Boolean): Job = scope.launch {
        val waiting = phase as? HqVoicePhase.Permission ?: return@launch
        if (granted) {
            join(waiting.credential)
        } else {
            credential = null
            setPhase(HqVoicePhase.Ended(HqVoiceEndReason.MicrophoneDenied))
            discard(waiting.credential)
        }
    }

    /** Push-to-talk: the microphone is open only while held. */
    fun holdToTalk(held: Boolean): Job = scope.launch {
        if (phase == HqVoicePhase.Live && _state.value.micMode == HqMicMode.PUSH) setMicrophone(held)
    }

    /** An open microphone's mute switch. */
    fun toggleMute(): Job = scope.launch {
        if (phase == HqVoicePhase.Live && _state.value.micMode == HqMicMode.OPEN) setMicrophone(!micWanted)
    }

    /** The member stopped the session. */
    fun stop(): Job = scope.launch {
        when (phase) {
            is HqVoicePhase.Notice, is HqVoicePhase.Permission -> decline()
            HqVoicePhase.Minting -> setPhase(HqVoicePhase.Idle)
            else -> finish(HqVoiceEndReason.ByMember)
        }
    }

    /**
     * ⛔ THE SCREEN WENT AWAY: the app went to the background, the member left HQ, or the ViewModel
     * was cleared. IN ANY PHASE. Before the join it is abandoned (a notice declined, a mint given
     * back when it lands); from the join on it is finished, so the room disconnects, the
     * microphone stops and the service is told. ⚠️ Not on a rotation: the screen's lifecycle effect
     * skips a configuration change.
     */
    fun end(): Job = scope.launch {
        when (phase) {
            HqVoicePhase.Connecting, HqVoicePhase.Live -> finish(HqVoiceEndReason.ByMember)
            else -> stopBeforeJoin()
        }
    }

    /** Applies a voice proposal by a tap. ⛔ Single-flight per card. */
    fun confirm(proposalId: String): Job = scope.launch {
        val card = cardFor(proposalId) ?: return@launch
        if (!canConfirm || card.isWorking || card.outcome != null) return@launch
        updateCard(proposalId) { it.copy(isWorking = true) }
        val result = repository.confirm(workspaceId, card.proposal)
        val applied = result is ApiResult.Success && result.value.executed
        updateCard(proposalId) {
            it.copy(isWorking = false, outcome = if (applied) HqResolutionStatus.APPLIED else HqResolutionStatus.FAILED)
        }
    }

    /** Cancels a voice proposal by a tap. ⛔ Single-flight per card. */
    fun cancel(proposalId: String): Job = scope.launch {
        val card = cardFor(proposalId) ?: return@launch
        if (card.isWorking || card.outcome != null) return@launch
        updateCard(proposalId) { it.copy(isWorking = true) }
        val cancelled = repository.cancel(workspaceId, proposalId) is ApiResult.Success
        updateCard(proposalId) {
            it.copy(isWorking = false, outcome = if (cancelled) HqResolutionStatus.CANCELLED else it.outcome)
        }
    }

    // ── Joining ──────────────────────────────────────────────────────────────────────────────────

    @Suppress("TooGenericExceptionCaught")
    private suspend fun join(credential: HqVoiceTokenResponse) {
        // ⚠️ `isJoinable` was checked by the repository, so the key is there.
        val key = credential.joinKey
        setPhase(HqVoicePhase.Connecting)
        val engine = engineFactory.create(scope, key)
        this.engine = engine
        listen(engine)
        try {
            engine.connect(credential.url, credential.token)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // ⚠️ Whatever the SDK throws: the member reads one sentence, the session is discarded.
            // A join that was already ended (and maybe replaced) finishes nothing.
            if (this.engine === engine) finish(HqVoiceEndReason.JoinFailed)
            return
        }
        // ⛔ ENDED WHILE THE JOIN WAS IN FLIGHT (a Stop, the audio taken, the screen gone): `finish`
        // has run already, and a connect that completes after it would leave the room joined.
        // ⛔ BY THE ENGINE, NOT THE PHASE: after a Stop and a quick new Talk, the NEW session's
        // join is `Connecting` too, and this stale one must not take it over.
        if (this.engine !== engine) {
            engine.disconnect()
            return
        }
        setPhase(HqVoicePhase.Live)
        startAgentWindow()
        // ⛔ THE FIRST OPEN GOES THROUGH [setMicrophone] like every other change, so a mute tapped
        // while it publishes is applied after it, not overwritten by it.
        if (_state.value.micMode == HqMicMode.OPEN) setMicrophone(true)
        if (this.engine !== engine) return
        startTimer()
        // ⚠️ TALK IS ONLY ON THE HQ SCREEN, AND ON ANDROID THE SESSION ENDS WHEN HQ IS LEFT, so the
        // screen is reported once, as HQ.
        scope.launch { repository.reportScreen(workspaceId, credential.roomName, 1, HQ_PAGE) }
    }

    /**
     * ⛔ SERIALIZED, AND A REQUEST OVERTAKEN BY A NEWER ONE IS SKIPPED. Opening the microphone
     * publishes a track over the network, so a quick press and release could otherwise finish out
     * of order and leave the microphone open after the button was let go. The mutex is fair, so the
     * changes apply in the order they were asked for.
     */
    private suspend fun setMicrophone(wanted: Boolean) {
        micWanted = wanted
        microphone.withLock {
            val engine = engine
            // ⚠️ NO ENGINE MEANS THE SESSION ENDED while this change waited its turn.
            if (micWanted != wanted || engine == null) return
            val applied = engine.setMicrophone(wanted)
            if (micWanted == wanted && phase == HqVoicePhase.Live) {
                _state.update { it.copy(isMicrophoneOn = applied) }
                // ⚠️ A REFUSED CHANGE IS NOT WANTED ANY MORE: the next tap retries the state the
                // screen shows, not the one that failed.
                micWanted = applied
            }
        }
    }

    // ── Ending ───────────────────────────────────────────────────────────────────────────────────

    private suspend fun decline() {
        val waiting = when (val current = phase) {
            is HqVoicePhase.Notice -> current.credential
            is HqVoicePhase.Permission -> current.credential
            else -> return
        }
        credential = null
        setPhase(HqVoicePhase.Idle)
        discard(waiting)
    }

    /** Before the join: a notice is declined, and a token still minting is given back when it lands. */
    private suspend fun stopBeforeJoin() {
        if (phase == HqVoicePhase.Minting) setPhase(HqVoicePhase.Idle) else decline()
    }

    /**
     * ⚠️ THE ROOM CLOSING ON ITS OWN ([HqVoiceEndReason.Disconnected]) CALLS NOTHING: the service
     * ended it.
     *
     * ⛔ THE SERVICE IS TOLD WHILE THE ROOM DISCONNECTS, NOT AFTER IT: the open row holds one of the
     * fleet's voice slots until its 30-minute TTL if the call never leaves.
     *
     * @param discarding discard even a live session (an agent that never joined).
     */
    private suspend fun finish(reason: HqVoiceEndReason, discarding: Boolean = false) {
        if (!phase.isActive) return
        val wasLive = phase == HqVoicePhase.Live && !discarding
        val credential = credential
        this.credential = null
        timer?.cancel()
        timer = null
        agentWindow?.cancel()
        agentWindow = null
        listening.cancel()
        val engine = engine
        this.engine = null
        micWanted = false
        _state.update { it.copy(phase = HqVoicePhase.Ended(reason), isMicrophoneOn = false, agentState = null) }
        val disconnecting = scope.launch { engine?.disconnect() }
        if (credential != null && reason != HqVoiceEndReason.Disconnected) {
            if (wasLive) repository.end(workspaceId, credential.sessionId) else discard(credential)
        }
        disconnecting.join()
    }

    /**
     * A session that never went live.
     *
     * ⛔ ANYTHING BUT A DISCARD IS FOLLOWED BY AN END. The service discards only a row under 90
     * seconds old and otherwise answers `discarded: false`, leaving it open for its 30-minute TTL;
     * `hq/voice-end` closes the member's open rows at any age. A failed discard is ended too.
     */
    private suspend fun discard(credential: HqVoiceTokenResponse) {
        val discarded = repository.discard(workspaceId, credential.sessionId)
        if (discarded is ApiResult.Success && discarded.value) return
        repository.end(workspaceId, credential.sessionId)
    }

    // ── The clocks and the room ──────────────────────────────────────────────────────────────────

    /**
     * ⛔ EACH CLOCK LETS GO OF ITSELF BEFORE IT FINISHES THE SESSION: `finish` cancels it, and a
     * cancelled coroutine's `hq/voice-end` would fail before it was sent.
     */
    private fun startTimer() {
        timer = scope.launch {
            delay(HqVoice.SESSION_SECONDS * MILLIS)
            timer = null
            finish(HqVoiceEndReason.TimedOut)
        }
    }

    /** ⚠️ NOT STARTED WHEN THE AGENT'S STATE ARRIVED DURING THE JOIN: it has joined already. */
    private fun startAgentWindow() {
        if (_state.value.agentState != null) return
        agentWindow = scope.launch {
            delay(HqVoice.AGENT_JOIN_SECONDS * MILLIS)
            agentWindow = null
            finish(HqVoiceEndReason.NoAgent, discarding = true)
        }
    }

    private fun listen(engine: HqVoiceEngine) {
        listening = scope.launch { engine.events.collect(::handle) }
    }

    /**
     * ⛔ AN EVENT THAT ENDS THE SESSION FINISHES IT IN A COROUTINE OF ITS OWN, for the clocks'
     * reason: this runs inside [listening], which `finish` cancels.
     */
    private fun handle(event: HqVoiceEngineEvent) {
        when (event) {
            // ⛔ IN `Connecting` TOO: a room closed during the join must not go live, and the
            // session that never went live is discarded ([join] sees the phase has moved).
            HqVoiceEngineEvent.Disconnected -> when (phase) {
                HqVoicePhase.Live -> finishSeparately(HqVoiceEndReason.Disconnected)
                HqVoicePhase.Connecting -> finishSeparately(HqVoiceEndReason.JoinFailed)
                else -> Unit
            }
            HqVoiceEngineEvent.AgentLeft ->
                if (phase == HqVoicePhase.Live) finishSeparately(HqVoiceEndReason.AgentLeft)
            HqVoiceEngineEvent.AudioInterrupted -> finishSeparately(HqVoiceEndReason.Interrupted)
            is HqVoiceEngineEvent.Transcript -> transcript(event)
            is HqVoiceEngineEvent.AgentState -> HqAgentState.fromWire(event.wire)?.let { state ->
                _state.update { it.copy(agentState = state) }
                // ⛔ THE AGENT JOINED: the window closes.
                agentWindow?.cancel()
                agentWindow = null
            }
            is HqVoiceEngineEvent.Packet -> HqVoicePacket.read(event.payload)?.let(::packet)
        }
    }

    private fun finishSeparately(reason: HqVoiceEndReason) {
        scope.launch { finish(reason) }
    }

    private fun transcript(event: HqVoiceEngineEvent.Transcript) {
        val segment = HqVoiceSegment(event.id, event.text, event.fromMember)
        _state.update { current ->
            val index = current.segments.indexOfFirst { it.id == segment.id }
            val segments = if (index >= 0) {
                current.segments.toMutableList().also { it[index] = segment }
            } else {
                current.segments + segment
            }
            current.copy(segments = segments)
        }
    }

    private fun packet(packet: HqVoicePacket) {
        when (packet) {
            is HqVoicePacket.Proposal -> _state.update { current ->
                if (current.proposals.any { it.id == packet.proposal.proposalId }) {
                    current
                } else {
                    current.copy(proposals = current.proposals + HqVoiceProposalCard(packet.proposal))
                }
            }
            is HqVoicePacket.Resolved -> updateCard(packet.resolution.proposalId) {
                it.copy(outcome = packet.resolution.status, isWorking = false)
            }
        }
    }

    private fun cardFor(proposalId: String): HqVoiceProposalCard? =
        _state.value.proposals.firstOrNull { it.id == proposalId }

    private fun updateCard(proposalId: String, change: (HqVoiceProposalCard) -> HqVoiceProposalCard) {
        _state.update { current ->
            current.copy(proposals = current.proposals.map { if (it.id == proposalId) change(it) else it })
        }
    }

    private fun setPhase(next: HqVoicePhase) {
        _state.update { it.copy(phase = next) }
    }

    private companion object {
        const val MILLIS = 1_000L
        const val HQ_PAGE = "hq"
        val REFUSAL_CODES = setOf(HqVoice.ALLOWANCE_REACHED_CODE, HqVoice.BUSY_CODE)
    }
}

/** The `code` a coded refusal carries, or null. */
internal fun ApiResult.Failure.code(): String? = when (this) {
    is ApiResult.HttpFailure -> code
    is ApiResult.Forbidden -> code
    is ApiResult.NotFound -> code
    is ApiResult.RateLimited -> code
    is ApiResult.Unauthorized, is ApiResult.RegionsDegraded, is ApiResult.NetworkFailure,
    is ApiResult.DecodeFailure,
    -> null
}
