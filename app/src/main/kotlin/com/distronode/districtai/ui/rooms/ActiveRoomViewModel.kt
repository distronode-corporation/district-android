package com.distronode.districtai.ui.rooms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.MeetingsRepository
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.media.MediaParticipant
import com.distronode.districtai.core.model.RoomTokenResponse
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One live `meet_` room, for as long as its destination exists.
 *
 * ⛔ IT OWNS EXACTLY ONE ENGINE, CREATED IN THE CONSTRUCTOR AND RELEASED ONCE. The engine holds a
 * live socket and the device's audio focus; a second instance would fight the first for the route,
 * which is the failure mode [CallEngine] itself warns about. The destination is keyed on the room
 * name so navigating to a different room builds a different ViewModel with a different engine
 * rather than re-pointing this one.
 *
 * ⛔ PERMISSIONS GATE THE CONNECT, AND NEITHER DENIAL IS FATAL. The screen requests
 * RECORD_AUDIO and CAMERA before this connects, and reports the outcome through
 * [onPermissionsResult]; the join then happens either way, because BOTH refusals still leave a
 * usable meeting. A denied camera is audio-only attendance, which is how most people attend
 * anyway. A denied microphone is listen-only attendance, which is exactly what a `viewer` gets
 * from the server regardless — so refusing to join over it would deny a seat the product already
 * offers. What changes is which controls are enabled and what the screen says about why.
 *
 * ⛔ THE TEARDOWN CANNOT RUN IN `viewModelScope`. `onCleared` fires AFTER that scope is cancelled,
 * so a disconnect launched there is cancelled before it reaches the socket and the room keeps the
 * participant until the server times them out — a ghost in the grid for everyone else, and a
 * meeting the Companion records as still running. [engineScope] exists to outlive `onCleared` by
 * exactly the length of the disconnect and is cancelled by the disconnect itself.
 */
class ActiveRoomViewModel(
    engineFactory: CallEngineFactory,
    private val repository: MeetingsRepository,
    /**
     * The FULL `meet_<workspaceId>_<suffix>` name, minted by
     * [com.distronode.districtai.core.model.MeetRoomName] before this destination was navigated to.
     *
     * ⛔ NO SEPARATE `workspaceId` PARAMETER, AND THAT ABSENCE IS DELIBERATE RATHER THAN AN
     * OVERSIGHT. The workspace is already inside this string, and it is the copy the SERVER parses
     * back out to decide who may join — so a second parameter would be a value that either agrees
     * with the name (and is redundant) or disagrees with it (and is ignored, while looking
     * authoritative to the next reader). Nothing this ViewModel calls takes a workspace id.
     */
    private val roomName: String,
    /**
     * ⚠️ THE ROLE DECIDES WHAT THE UI OFFERS; THE SERVER DECIDES WHAT IS ALLOWED. A viewer's token
     * carries `canPublish:false`, so its microphone would be refused by the media server whatever
     * this client thinks — disabling the controls avoids offering an action that cannot work,
     * which is not the same as enforcing anything. A null role (an unrecognised value in the route)
     * fails CLOSED to no publishing, the same direction every sibling destination fails.
     */
    private val role: WorkspaceRole?,
    /** The origin a `guestPath` is joined onto. Injected so the link is assertable in a test. */
    private val webOrigin: String,
    /**
     * ⛔ NOT `viewModelScope`. See the class doc — this one has to survive `onCleared`.
     *
     * ⚠️ Injectable so a test can drive the engine's lifetime deterministically; the production
     * default is `Main.immediate` because the state it writes is read by Compose.
     */
    private val engineScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    private val engine: CallEngine = engineFactory.create(engineScope)

    private val _state = MutableStateFlow(
        // ⛔ `role != null &&`, NOT JUST `role != VIEWER`. A null role is what `WorkspaceRole.fromWire`
        // returns for a corrupted or renamed value in the route, and `null != VIEWER` is TRUE — so
        // the shorter expression would hand an UNKNOWN role publish rights, which is the permissive
        // direction and the opposite of what every sibling destination does. Caught by
        // `an unrecognised role fails closed to no publishing`.
        ActiveRoomUiState(canPublish = role != null && role != WorkspaceRole.VIEWER),
    )
    val state: StateFlow<ActiveRoomUiState> = _state.asStateFlow()

    /**
     * ⛔ ONE-WAY, AND IT IS WHAT MAKES "LEAVE DISCONNECTS EXACTLY ONCE" TRUE. `leave` and
     * `onCleared` both run on a normal exit — the screen pops itself after leaving — so without
     * this the room would be disconnected twice, and the second call would race the first's
     * cancellation of [engineScope].
     */
    private var released = false

    init {
        // ⚠️ Collected in `viewModelScope`, not `engineScope`: these only feed the UI, so they
        // should stop the moment the screen is gone. The disconnect is the one thing that must not.
        viewModelScope.launch {
            engine.connectionState.collect { connection ->
                _state.value = _state.value.copy(connection = connection)
            }
        }
        viewModelScope.launch {
            engine.participants.collect { people ->
                // ⚠️ Both derived from ONE list in one write, so the grid and the chip can never
                // disagree about whether the Companion is in the room.
                _state.value = _state.value.copy(
                    participants = people.filterNot(::isCompanion),
                    companionPresent = people.any(::isCompanion),
                )
            }
        }
        viewModelScope.launch {
            engine.isMicrophoneEnabled.collect { on ->
                _state.value = _state.value.copy(micEnabled = on)
            }
        }
        viewModelScope.launch {
            engine.isCameraEnabled.collect { on ->
                _state.value = _state.value.copy(cameraEnabled = on)
            }
        }
    }

    /**
     * Record what the OS granted, then join.
     *
     * ⛔ THE ONLY ENTRY POINT TO THE CONNECT, DELIBERATELY. Connecting in `init` and asking for
     * permissions alongside it would race: the engine would publish a microphone track before the
     * user had answered the dialog, which on Android produces a silent track rather than an error.
     * `RequestMultiplePermissions` invokes its callback immediately when everything is already
     * granted, so this is one path rather than two.
     *
     * ⚠️ IDEMPOTENT. A configuration change re-runs the launcher's effect, and re-joining a room
     * this engine is already in would tear down the media it just established.
     */
    fun onPermissionsResult(microphoneGranted: Boolean, cameraGranted: Boolean) {
        _state.value = _state.value.copy(
            permissions = RoomPermissions(
                requested = true,
                microphoneGranted = microphoneGranted,
                cameraGranted = cameraGranted,
            ),
        )
        if (_state.value.connection == CallConnectionState.Idle && !released) join()
    }

    private fun join() {
        _state.value = _state.value.copy(
            connection = CallConnectionState.Connecting,
            joinFailure = null,
        )
        viewModelScope.launch {
            when (val result = repository.roomToken(roomName)) {
                is ApiResult.Success -> connectWith(result.value)
                is ApiResult.Failure -> _state.value = _state.value.copy(
                    // ⛔ BACK TO Idle, NOT Failed. The engine never connected, so its own state
                    // flow still reads Idle and would immediately overwrite anything set here —
                    // and the reason the user needs is the API failure, which lives in
                    // `joinFailure`. Leaving `connection` on `Connecting` would render a spinner
                    // over a join that will never happen.
                    connection = CallConnectionState.Idle,
                    joinFailure = result.toFailureText(),
                )
            }
        }
    }

    private suspend fun connectWith(token: RoomTokenResponse) {
        _state.value = _state.value.copy(
            // ⚠️ NULL FOR A VIEWER, BY SERVER DECISION. Never synthesised — see
            // [ActiveRoomUiState.guestLink].
            guestLink = token.guestPath?.let { webOrigin.trimEnd('/') + it },
        )
        // ⛔ `takeIf { isNotBlank() }` IS LOAD-BEARING, NOT DEFENSIVE TIDYING. `E2eeInfo.key`
        // defaults to blank so a `{"e2ee":{}}` body decodes rather than throwing, and an empty
        // passphrase is not "no encryption" — it derives a real AES key that nobody else on the
        // call derives. Forwarding it would join the room and then hear nothing, which reads as a
        // media fault rather than a key fault. A null here joins unencrypted, which is correct for
        // every `call_` room and is the honest answer when the server sent us no usable key.
        val failure = runCatching {
            engine.connect(token.url, token.token, token.e2ee?.key?.takeIf { it.isNotBlank() })
        }.exceptionOrNull()
        // ⛔ LEFT WHILE THE JOIN WAS IN FLIGHT: [release] already launched its disconnect, but that
        // disconnect may have reached the engine BEFORE this connect resolved, and nothing orders
        // the two inside the SDK. So the room could be left joined, and the code below would then
        // publish the microphone, after the user had left. The same guard `CallSessionCore.connect`
        // carries for a hang-up: never the microphone, and a disconnect issued now, after the
        // connect has resolved, whatever it resolved to (a cancellation included). ⚠️ In THIS
        // coroutine and `NonCancellable`, not in [engineScope]: [release] cancels that scope once
        // its own disconnect finishes, and a disconnect launched into a cancelled scope never runs.
        if (released) withContext(NonCancellable) { engine.disconnect() }
        // ⛔ CANCELLATION IS NOT A FAILED JOIN, AND IT IS RETHROWN. Caught, it painted `Failed` with
        // a coroutine-internals message over a teardown and let the cancelled coroutine carry on.
        if (failure is CancellationException) throw failure
        if (released) return
        if (failure != null) {
            // ⚠️ The engine has already set its own state to Failed and rethrown; this only records
            // that the throw was seen. Swallowing it here is correct: the connection state IS the
            // user-facing outcome, and letting it escape would crash the process for a network
            // condition.
            _state.value = _state.value.copy(connection = CallConnectionState.Failed(failure.message))
            return
        }

        // ⛔ MICROPHONE ON, CAMERA OFF, AND THE ASYMMETRY IS DELIBERATE. Joining muted is a
        // well-known way to have a meeting where nobody realises they are inaudible; joining with
        // the camera live is a well-known way to be seen before you meant to be. Granting the
        // camera permission is consent to USE it, not consent to be on it. Both respect
        // canPublish, because a viewer's token would have the server refuse either.
        if (_state.value.canPublish && _state.value.permissions.microphoneGranted) {
            engine.setMicrophoneEnabled(true)
        }
    }

    /**
     * ⚠️ NO-OP WITHOUT PUBLISH RIGHTS OR THE PERMISSION, rather than attempting and failing. The
     * media server would refuse a viewer outright, and the OS would hand an unpermitted engine a
     * silent track — which looks like a working microphone to everyone except the people who
     * cannot hear it.
     */
    fun toggleMicrophone() {
        if (!_state.value.canPublish || !_state.value.permissions.microphoneGranted) return
        viewModelScope.launch { engine.setMicrophoneEnabled(!_state.value.micEnabled) }
    }

    /** ⚠️ Same gating as [toggleMicrophone], against the camera permission. */
    fun toggleCamera() {
        if (!_state.value.canPublish || !_state.value.permissions.cameraGranted) return
        viewModelScope.launch { engine.setCameraEnabled(!_state.value.cameraEnabled) }
    }

    /**
     * ⚠️ ONLY WHILE THE CAMERA IS ON. Flipping a camera that is not publishing does nothing
     * visible, so the control would report success for an action with no effect.
     */
    fun flipCamera() {
        if (!_state.value.cameraEnabled) return
        viewModelScope.launch { engine.flipCamera() }
    }

    /**
     * ⚠️ THE ROUTE IS SET SYNCHRONOUSLY AND THE FLAG IS THIS SCREEN'S OWN. The engine has no
     * "current route" flow to read back — see the audio-routing note on [CallEngine] — so this is
     * what the app believes it asked for rather than what the device is doing. That is honest for
     * a toggle and would not be honest for a status readout.
     */
    fun toggleSpeaker() {
        val next = !_state.value.speakerOn
        engine.setSpeakerphoneOn(next)
        _state.value = _state.value.copy(speakerOn = next)
    }

    /**
     * Leave the room, then tell the caller so it can pop the destination.
     *
     * ⛔ THE CALLBACK RUNS AFTER THE DISCONNECT RESOLVES, NOT BEFORE. Popping first would clear
     * this ViewModel and route the teardown through `onCleared`, which is the path that exists for
     * the case where the user never pressed anything — using it for a deliberate leave would make
     * the ordinary exit depend on the emergency one.
     */
    fun leave(onLeft: () -> Unit) = release(onLeft)

    override fun onCleared() {
        // ⚠️ A NO-OP AFTER `leave`, which is the normal case: the screen pops itself once the
        // disconnect resolves, and that clears this ViewModel. See [released].
        release {}
    }

    private fun release(after: () -> Unit) {
        if (released) return
        released = true
        engineScope.launch {
            try {
                engine.disconnect()
            } finally {
                after()
                // ⚠️ LAST, AND FROM INSIDE THE SCOPE IT CANCELS. The disconnect is the only reason
                // this scope outlives the ViewModel, so once it has run there is nothing left for
                // the scope to do and leaving it alive would leak the engine's event collector.
                engineScope.cancel()
            }
        }
    }

    companion object {
        fun factory(
            engineFactory: CallEngineFactory,
            repository: MeetingsRepository,
            roomName: String,
            role: WorkspaceRole?,
            webOrigin: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ActiveRoomViewModel(
                    engineFactory = engineFactory,
                    repository = repository,
                    roomName = roomName,
                    role = role,
                    webOrigin = webOrigin,
                )
            }
        }
    }
}

/**
 * Is this participant the note-taking Companion rather than a person?
 *
 * ⛔ THE SAME TWO TESTS THE WEB APPLIES, IN THE SAME ORDER, AND THE DUPLICATION IS THE POINT. The
 * web's `isBotParticipant` reads `participant.isAgent || identity.startsWith("ai-companion-")`;
 * getting this wrong on one platform and not the other produces a blank muted tile that only
 * phone users see. The kind is the real test — the voice agent joins through the LiveKit Agents
 * framework with `kind = AGENT` — and the prefix is a retired browser-side signalling participant
 * that summon no longer mints. The web keeps its check defensively and so does this; a room built
 * before the metadata cutover could still contain one.
 */
internal fun isCompanion(participant: MediaParticipant): Boolean =
    participant.isAgent || participant.identity.startsWith(RETIRED_COMPANION_IDENTITY_PREFIX)

private const val RETIRED_COMPANION_IDENTITY_PREFIX = "ai-companion-"
