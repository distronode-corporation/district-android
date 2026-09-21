package com.distronode.districtai.core.media

import android.content.Context
import android.view.View
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import com.twilio.audioswitch.AudioDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one place in the app that talks to the LiveKit SDK.
 *
 * ⛔ THIN BY POLICY, NOT BY ACCIDENT. Every line here is uncoverable by unit test (the
 * SDK needs a device and a live socket), and the aggregate coverage floor is a ratchet —
 * so logic added here is logic subtracted from the measured codebase. Anything that can
 * be a pure function belongs in [mapConnectionUpdate]/[toMediaParticipant]-style mappers
 * or in the consumer's ViewModel, where tests reach it. This class only forwards.
 *
 * ⚠️ The [scope] outlives composables but not the call: the owning ViewModel passes its
 * own scope and cancels it on clear, which tears down the event collection with it.
 */
class LiveKitCallEngine(
    context: Context,
    private val scope: CoroutineScope,
) : CallEngine {

    private val room: Room = LiveKit.create(context.applicationContext)

    private val _connectionState =
        MutableStateFlow<CallConnectionState>(CallConnectionState.Idle)
    override val connectionState: StateFlow<CallConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<MediaParticipant>>(emptyList())
    override val participants: StateFlow<List<MediaParticipant>> = _participants.asStateFlow()

    private val _isMicrophoneEnabled = MutableStateFlow(false)
    override val isMicrophoneEnabled: StateFlow<Boolean> = _isMicrophoneEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private var eventsJob: Job? = null

    @Suppress("TooGenericExceptionCaught")
    override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
        _connectionState.value = CallConnectionState.Connecting
        // ⛔ SET ON THE ROOM BEFORE `connect`, AND THAT ORDERING IS THE WHOLE MECHANISM. The SDK
        // builds its E2EEManager INSIDE `Room.connect`, from `getCurrentRoomOptions()`, which reads
        // this mutable field — verified in the 2.28.0 AAR (`Room$connect$2` offsets 454-561: read
        // current options, null-check `e2eeOptions`, then `E2EEManager.setup` and
        // `RTCEngine.setE2EEManager`). So the room can stay a `val` built once in the constructor
        // and still be encrypted per join, which is why this is two lines here rather than moving
        // `LiveKit.create` into this method and making `room` nullable for every other member.
        // ⛔ There is no Android equivalent of the web's `setE2EEEnabled(true)`: a non-null
        // `e2eeOptions` at connect time IS the enable, and setting it AFTER connect does nothing.
        // ⛔ The key is a passphrase and goes in unmodified — `E2EEOptions(sharedKey = …)` builds
        // the default BaseKeyProvider and calls `setSharedKey`, which UTF-8-encodes it and derives
        // via PBKDF2. Decoding it here would silently desync this client from the web and the agent.
        // ⛔ ASSIGNED UNCONDITIONALLY, INCLUDING THE `null` BRANCH, AND THAT ELSE IS NOT TIDINESS.
        // `room` is a long-lived `val`, so `e2eeOptions` SURVIVES a disconnect. An `if` with no
        // `else` would leave a previous room's key installed on the next join: an encrypted `meet_`
        // followed by answering a `call_` on the same engine would encrypt that call against a key
        // no other participant holds — the SIP bridge and the agent would receive undecryptable
        // media, which presents as one-way dead air rather than as an error. The engine's contract
        // is one instance per call, so this should be unreachable; it is written explicitly because
        // the failure it prevents is silent and the cost of the `else` is nothing.
        // ⚠️ `?.let` RATHER THAN AN `if`, SO THE null BRANCH IS AN ASSIGNMENT AND NOT AN OMISSION.
        // `sharedRoomKey` owns the decision (and is unit-tested); this line only installs it.
        room.e2eeOptions = sharedRoomKey(e2eeKeyBase64)?.let { E2EEOptions(sharedKey = it) }
        eventsJob?.cancel()
        eventsJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Reconnecting ->
                        _connectionState.value = CallConnectionState.Reconnecting
                    is RoomEvent.Reconnected ->
                        _connectionState.value = CallConnectionState.Connected
                    is RoomEvent.Disconnected ->
                        _connectionState.value =
                            CallConnectionState.Disconnected(event.reason?.name)
                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.ActiveSpeakersChanged,
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted,
                    -> refreshParticipants()
                    else -> Unit
                }
            }
        }
        try {
            room.connect(url, token)
            _connectionState.value = CallConnectionState.Connected
            refreshParticipants()
        } catch (t: Throwable) {
            // Deliberately Throwable: whatever the SDK throws (its own exception types,
            // IO, cancellation), the UI's single source of truth must read Failed before
            // the caller sees the error — a state flow left on Connecting renders a
            // spinner over a call that will never happen. Rethrown unchanged.
            _connectionState.value = CallConnectionState.Failed(t.message)
            throw t
        }
    }

    override suspend fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        room.disconnect()
        _connectionState.value = CallConnectionState.Disconnected()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        room.localParticipant.setMicrophoneEnabled(enabled)
        _isMicrophoneEnabled.value = enabled
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        room.localParticipant.setCameraEnabled(enabled)
        _isCameraEnabled.value = enabled
    }

    override suspend fun flipCamera() {
        val track = room.localParticipant
            .getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
        track?.switchCamera()
    }

    override fun setSpeakerphoneOn(on: Boolean) {
        // ⚠️ The known audio-routing contention point (AudioSwitch vs Telecom); see the
        // interface KDoc. This selects through the SDK's handler; the Telecom milestone
        // must confirm on-device that Telecom is not simultaneously routing.
        val handler = room.audioHandler as? AudioSwitchHandler ?: return
        val devices = handler.availableAudioDevices
        val target = if (on) {
            devices.firstOrNull { it is AudioDevice.Speakerphone }
        } else {
            devices.firstOrNull { it is AudioDevice.Earpiece }
                ?: devices.firstOrNull { it !is AudioDevice.Speakerphone }
        }
        if (target != null) handler.selectDevice(target)
    }

    private fun refreshParticipants() {
        _participants.value =
            room.remoteParticipants.values.map { it.toMediaParticipant(::initRenderer) }
    }

    /**
     * ⛔ THE ONE CAST TO AN SDK TYPE, AND IT LIVES HERE BY DESIGN. `VideoTrackHandle.initRenderer`
     * is declared over `android.view.View` so the model file stays SDK-free; the only view ever
     * passed to it is the `TextureViewRenderer` that [VideoTile] constructs, which is a
     * `TextureView`. A different view would be a programming error inside this module, and the
     * cast is where it would surface rather than being silently ignored.
     */
    private fun initRenderer(view: View) {
        (view as? TextureViewRenderer)?.let { room.initVideoRenderer(it) }
    }
}

/**
 * Pure mapping from an SDK participant to the UI model — separated so the shape rules
 * (name blank→null, first subscribed video wins) stay testable in consumers via the
 * data types even though this function itself needs SDK instances.
 */
private fun RemoteParticipant.toMediaParticipant(
    initRenderer: (View) -> Unit,
): MediaParticipant {
    val video = videoTrackPublications
        .firstOrNull { (pub, _) -> pub.subscribed }
        ?.second as? RemoteVideoTrack
    return MediaParticipant(
        identity = identity?.value ?: "",
        name = name?.takeIf { it.isNotBlank() },
        isSpeaking = isSpeaking,
        isMicrophoneEnabled = isMicrophoneEnabled,
        // ⚠️ The room's renderer initialiser rides along with the track — see the ⛔ on
        // `VideoTrackHandle.initRenderer`. It is deliberately NOT part of the handle's equality:
        // two handles for the same track sid are the same tile to Compose regardless of which
        // refresh produced them, and including a lambda would make every refresh unequal.
        videoTrack = video?.let { VideoTrackHandle(it, it.sid ?: it.name, initRenderer) },
        // ⚠️ The SDK's own classification, not an identity-prefix guess — see the ⛔ on
        // `MediaParticipant.isAgent`. The transcription Companion is the only AGENT this product
        // puts in a meet_ room today, but the kind covers every service participant.
        isAgent = kind == Participant.Kind.AGENT,
    )
}
