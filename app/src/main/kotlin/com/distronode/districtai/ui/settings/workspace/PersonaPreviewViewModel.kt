package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.PersonaOptionsRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.PersonaPreviewForm
import com.distronode.districtai.core.model.PersonaPreviewTokenResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One persona audition: a real, billed call to this workspace's own voice agent, answering as the
 * form on screen describes it.
 *
 * ⛔ NOTHING HERE IS A DRY RUN, AND EVERY RULE IN THIS TYPE FOLLOWS FROM THAT. The token invites the
 * agent into a `preview_<workspaceId>_<uuid>` room on the workspace's own pipeline and its own
 * media node, where it answers with speech recognition, a model and speech synthesis exactly as it
 * would on a telephone call. The route is capped at 10/min per WORKSPACE, it is not idempotent, and
 * the spend lands downstream in the agent rather than in the handler — so a failure is FINAL here
 * (a button, never a retry), a second tap while one is running is dropped, and the control stays
 * disabled for a few seconds after one ends.
 *
 * ⛔ IT NEVER TOUCHES `TelecomBridge` AND HOLDS NO REFERENCE TO IT, WHICH IS THE STRUCTURAL FORM OF
 * THE RULE RATHER THAN A COMMENT ABOUT IT. An audition is not a telephone call: a self-managed
 * `Connection` would put it in the system call list, make it answerable and resumable from there,
 * and count it against the one-call-at-a-time `DistrictConnectionService` declares to the OS —
 * for a session nobody can answer. The only collaborator here is [CallEngineFactory], which is the
 * same seam `ActiveRoomViewModel` uses and the one that knows nothing about Telecom.
 *
 * ⛔ THE ROOM IS END-TO-END ENCRYPTED AND THE PASSPHRASE GOES TO THE SDK VERBATIM. A `preview_*`
 * room is always encrypted, so an absent or blank key is the server failing to derive one rather
 * than "join in the clear" — [PersonaOptionsRepository] refuses such a body, because a client that
 * joined anyway would be the only unencrypted participant in the room, publishing and hearing noise
 * while every connection succeeded.
 *
 * ⚠️ THERE IS NO CROSS-SCREEN AUDIO ARBITRATION ON ANDROID TODAY, AND THAT IS A KNOWN DIFFERENCE
 * FROM iOS. iOS refuses a preview while a call or a meeting owns the `AVAudioSession` and ends the
 * preview when a call arrives, through its `CallStack`; this app has no such holder — the dialer,
 * the rooms screen and this one each build their own engine — so the refusal is bounded to this
 * screen's own [PersonaPreviewUiState.canStart]. Adding a process-wide holder is a separate change
 * and is not faked here.
 */
class PersonaPreviewViewModel(
    private val repository: PersonaOptionsRepository,
    private val workspaceId: String,
    engineFactory: CallEngineFactory,
    private val engineScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val cooldownMillis: Long = COOLDOWN_MILLIS,
) : ViewModel() {

    private val engine: CallEngine = engineFactory.create(engineScope)

    private val _state = MutableStateFlow(PersonaPreviewUiState())
    val state: StateFlow<PersonaPreviewUiState> = _state.asStateFlow()

    /** ⚠️ Held rather than detached so the cooldown cannot outlive the object, and a test can await it. */
    private var cooldownJob: Job? = null

    private var released = false

    /**
     * The mint and the join that follows it.
     *
     * ⛔ HELD SO [release] CAN CANCEL IT. Left running, a sheet stopped or dismissed during the mint
     * round trip joined a billed agent session afterwards, speaker on and microphone published,
     * with no UI left to stop it and `onCleared` a no-op on an already-released object.
     */
    private var mintJob: Job? = null

    /**
     * ⛔ CAPTURED WHEN THE BUTTON IS PRESSED, NOT WHEN THE SCREEN IS BUILT, and the difference is
     * the whole promise of the feature. The form is edited continuously; an audition is of what was
     * on screen at the tap, and a form supplied at construction would go stale the moment the next
     * character was typed.
     */
    private var pendingForm: PersonaPreviewForm? = null

    init {
        viewModelScope.launch {
            engine.connectionState.collect(::applyConnection)
        }
        viewModelScope.launch {
            engine.participants.collect(::applyRoster)
        }
        viewModelScope.launch {
            engine.isMicrophoneEnabled.collect { on ->
                _state.value = _state.value.copy(micEnabled = on)
            }
        }
    }

    /**
     * Ask for the microphone, then start.
     *
     * ⛔ THE ONLY ENTRY POINT, AND IT IS REACHED FROM A BUTTON. Minting on appear would charge for a
     * screen somebody opened to read.
     */
    fun start(form: PersonaPreviewForm) {
        if (!_state.value.canStart) return
        pendingForm = form
        _state.value = _state.value.copy(
            failure = null,
            microphoneDenied = false,
            microphoneRequest = _state.value.microphoneRequest + 1,
        )
    }

    /**
     * ⚠️ A DENIAL IS NOT FATAL. The agent still greets and can be heard, which is most of an
     * audition; the session goes ahead and the screen says why nothing is being heard back.
     */
    fun onMicrophonePermissionResult(granted: Boolean) {
        // ⚠️ A RESULT WITH NO PENDING FORM IS DROPPED. The permission callback can arrive after the
        // sheet was dismissed, and minting then would start a billed session for a screen nobody is
        // looking at.
        val form = pendingForm ?: return
        if (!_state.value.canStart) return
        _state.value = _state.value.copy(
            microphoneDenied = !granted,
            phase = PersonaPreviewPhase.Minting,
        )
        mintJob = viewModelScope.launch { mintAndConnect(form) }
    }

    /**
     * ⛔ A FAILED MINT IS FINAL. The route is not idempotent and each token starts a billed session,
     * so nothing here retries; the operator is left on a button.
     */
    private suspend fun mintAndConnect(form: PersonaPreviewForm) {
        when (val result = repository.previewToken(workspaceId, form)) {
            is ApiResult.Success -> connect(result.value)
            is ApiResult.Failure -> {
                _state.value = _state.value.copy(
                    phase = PersonaPreviewPhase.Failed,
                    failure = result.toFailureText(),
                )
                startCooldown()
            }
        }
    }

    private suspend fun connect(credential: PersonaPreviewTokenResponse) {
        _state.value = _state.value.copy(phase = PersonaPreviewPhase.Connecting)
        val failure = runCatching {
            // ⛔ THE KEY IS PASSED VERBATIM AND IS NEVER BASE64-DECODED. Every LiveKit SDK
            // UTF-8-encodes this string and runs PBKDF2 over those ASCII bytes; decoding it to 32
            // raw bytes selects a different derivation, and the failure is not an error — both
            // sides join and every track is undecryptable noise.
            engine.connect(credential.url, credential.token, credential.e2ee?.key)
        }.exceptionOrNull()
        // ⛔ STOPPED WHILE THE JOIN WAS IN FLIGHT: [release] already launched its disconnect, and
        // nothing orders it against a join the SDK finishes afterwards, so the room is left again
        // now that the join has resolved, whatever it resolved to. ⚠️ `NonCancellable` and in this
        // coroutine, because [release] cancelled both it and the engine scope.
        if (released) withContext(NonCancellable) { engine.disconnect() }
        // ⛔ CANCELLATION IS NOT A FAILED JOIN, AND IT IS RETHROWN rather than swallowed with it.
        if (failure is CancellationException) throw failure
        // ⚠️ On a failure the engine has already published a Failed connection state; returning
        // records that the throw was seen rather than letting it escape a coroutine with no catch
        // above it.
        if (released || failure != null) return
        // ⛔ SPEAKER ON AND THE MICROPHONE PUBLISHED, WHICH IS THE OPPOSITE OF A MEETING AND RIGHT
        // HERE. An audition is held at arm's length while somebody watches the form, and one joined
        // muted is an audition of nothing.
        engine.setSpeakerphoneOn(true)
        if (!_state.value.microphoneDenied) engine.setMicrophoneEnabled(true)
    }

    /**
     * Stop the session deliberately.
     *
     * ⛔ THE TEARDOWN RUNS FROM EVERY STATE AND ONLY THE PHASE IS CONDITIONAL. A session that FAILED
     * mid-call, or was dropped by the server, still holds an engine and a socket, so guarding the
     * whole method on `isRunning` would leave a dismissed sheet with a live room publishing a
     * microphone and nothing able to reach it.
     */
    fun stop() {
        val wasRunning = _state.value.phase.isRunning
        pendingForm = null
        release()
        if (!wasRunning) return
        _state.value = _state.value.copy(
            phase = PersonaPreviewPhase.Ended(PersonaPreviewEnding.Stopped),
            agentPresent = false,
            agentSpeaking = false,
        )
        startCooldown()
    }

    override fun onCleared() {
        release()
    }

    /**
     * ⛔ NEVER REPORTS `Stopped`. [stop] sets its own phase and marks the object released before the
     * disconnect lands, so this branch is reachable only for a disconnect the operator did not
     * cause — a token expiring, the agent's room being torn down, a duplicate identity evicting
     * this session.
     */
    private fun applyConnection(connection: CallConnectionState) {
        if (released) return
        _state.value = when (connection) {
            // ⚠️ The engine's resting state before a join and after a teardown: nothing to show.
            CallConnectionState.Idle -> return
            CallConnectionState.Connecting -> _state.value.copy(phase = PersonaPreviewPhase.Connecting)
            CallConnectionState.Connected -> _state.value.copy(
                phase = if (_state.value.agentPresent) {
                    PersonaPreviewPhase.Live
                } else {
                    PersonaPreviewPhase.Waiting
                },
            )
            CallConnectionState.Reconnecting ->
                _state.value.copy(phase = PersonaPreviewPhase.Reconnecting)
            is CallConnectionState.Disconnected -> {
                startCooldown()
                _state.value.copy(
                    phase = PersonaPreviewPhase.Ended(
                        PersonaPreviewEnding.DroppedRemotely(connection.reason),
                    ),
                    agentPresent = false,
                    agentSpeaking = false,
                )
            }
            is CallConnectionState.Failed -> {
                startCooldown()
                _state.value.copy(phase = PersonaPreviewPhase.Failed)
            }
        }
    }

    /**
     * ⛔ THE AGENT IS IDENTIFIED BY THE MEDIA LAYER'S OWN `isAgent`, never by an identity prefix —
     * the same test `ActiveRoomViewModel` applies.
     */
    private fun applyRoster(people: List<MediaParticipant>) {
        if (released) return
        val agents = people.filter(MediaParticipant::isAgent)
        val present = agents.isNotEmpty()
        _state.value = _state.value.copy(
            agentPresent = present,
            agentSpeaking = agents.any(MediaParticipant::isSpeaking),
            phase = if (isJoinedPhase(_state.value.phase)) {
                if (present) PersonaPreviewPhase.Live else PersonaPreviewPhase.Waiting
            } else {
                _state.value.phase
            },
        )
    }

    private fun release() {
        if (released) return
        released = true
        mintJob?.cancel()
        engineScope.launch {
            try {
                engine.disconnect()
            } finally {
                engineScope.cancel()
            }
        }
        _state.value = _state.value.copy(micEnabled = false)
    }

    private fun startCooldown() {
        cooldownJob?.cancel()
        _state.value = _state.value.copy(cooling = true)
        cooldownJob = viewModelScope.launch {
            delay(cooldownMillis)
            _state.value = _state.value.copy(cooling = false)
        }
    }

    companion object {
        /** ⚠️ A cheaper guard than the route's own 10/min ceiling. See [PersonaPreviewUiState]. */
        const val COOLDOWN_MILLIS: Long = 5_000L

        fun factory(
            repository: PersonaOptionsRepository,
            workspaceId: String,
            engineFactory: CallEngineFactory,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = PersonaPreviewViewModel(
                repository = repository,
                workspaceId = workspaceId,
                engineFactory = engineFactory,
            ) as T
        }
    }
}

/**
 * Whether a roster change may move the phase.
 *
 * ⛔ IT MUST NOT MOVE ONE OUT OF `Ended` OR `Failed`. A roster update can arrive after the SDK has
 * reported a disconnect, and a phase machine that let it would put a finished audition back on
 * screen as live — with the Start button still disabled and nothing to stop.
 */
private fun isJoinedPhase(phase: PersonaPreviewPhase): Boolean =
    phase == PersonaPreviewPhase.Waiting ||
        phase == PersonaPreviewPhase.Live ||
        phase == PersonaPreviewPhase.Reconnecting
