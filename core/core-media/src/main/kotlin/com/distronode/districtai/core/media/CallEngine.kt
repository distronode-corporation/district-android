package com.distronode.districtai.core.media

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the app and the realtime-media SDK.
 *
 * ⛔ NO LIVEKIT TYPE CROSSES THIS INTERFACE. Every consumer — the meet_ room grid, the
 * outbound softphone, and every one of their tests — programs against this, so the SDK
 * (and its WebRTC natives, its JitPack-hosted audioswitch fork, its ProGuard surface)
 * stays contained in this one module. A capability the UI needs is a member HERE, not a
 * reason to expose the SDK. Video tracks travel as [VideoTrackHandle], an opaque box the
 * rendering composable (which lives beside the SDK, in this module) knows how to open.
 *
 * ⚠️ ONE ENGINE INSTANCE PER CALL/ROOM, owned by the ViewModel that owns the session.
 * The engine holds a live socket and audio-device state; two instances would fight over
 * audio focus. Constructed via [CallEngineFactory] so ViewModels stay testable.
 *
 * ⚠️ AUDIO ROUTING IS THE KNOWN SLEEPER BUG for the softphone milestone: LiveKit's
 * AudioSwitch and Telecom's setAudioRoute both want to own the route, and the classic
 * symptom is audio out of the earpiece while Bluetooth is connected. This interface keeps
 * the route decision to one boolean deliberately; the Telecom integration must settle
 * on-device which side owns it, and that finding belongs in the M2 package's notes.
 */
interface CallEngine {

    /** The room connection's lifecycle, [CallConnectionState.Idle] before [connect]. */
    val connectionState: StateFlow<CallConnectionState>

    /**
     * Everyone in the room EXCEPT the local participant, in join order. The local
     * user's own controls read [isMicrophoneEnabled]/[isCameraEnabled] instead — a
     * self-tile that renders from the same list as remote tiles double-counts on
     * reconnect, which is the duplicate-tile bug the server's stable identities exist
     * to prevent.
     */
    val participants: StateFlow<List<MediaParticipant>>

    val isMicrophoneEnabled: StateFlow<Boolean>
    val isCameraEnabled: StateFlow<Boolean>

    /**
     * Join a room. The url/token pair comes from the server (calls/token for meet_
     * rooms, calls/dial for a direct call) and is used verbatim — ⛔ this client never
     * constructs a room name or picks a deployment; both are server decisions with
     * routing consequences (the room only exists on the bus that created it).
     *
     * [e2eeKeyBase64] is the room's shared encryption passphrase, or null to join
     * unencrypted. ⛔ NULL IS THE ORDINARY ANSWER FOR A PHONE CALL, not a degraded one:
     * a `call_`/`direct_` room has a SIP leg and the carrier delivers it unencrypted, so
     * there is nothing for a key to protect. It defaults to null so those two call sites
     * (`SoftphoneSession`, `InboundCallSession`) state that by saying nothing.
     * ⛔ PASSED TO THE SDK VERBATIM AND NEVER DECODED — see `E2eeInfo.key`, which owns
     * the full reasoning. The parameter is named for the ENCODING of the string it
     * carries, not for an instruction to reverse it.
     */
    suspend fun connect(url: String, token: String, e2eeKeyBase64: String? = null)

    /** Leave and release the audio device. Idempotent — hang-up paths race. */
    suspend fun disconnect()

    suspend fun setMicrophoneEnabled(enabled: Boolean)

    /** Meet rooms only; the softphone never publishes video. */
    suspend fun setCameraEnabled(enabled: Boolean)

    suspend fun flipCamera()

    /** Route audio to the loudspeaker (true) or the earpiece/default (false). */
    fun setSpeakerphoneOn(on: Boolean)
}

/**
 * The room key to install for one join, or null to join unencrypted.
 *
 * ⛔ IT EXISTS SO THE **RESET** IS A TESTABLE DECISION RATHER THAN AN `if` WITH NO `else`.
 * `LiveKitCallEngine` holds its `Room` as a long-lived `val`, and the SDK's `e2eeOptions`
 * survives a disconnect — so a join that carries no key must actively CLEAR the previous
 * one. Returning null here (rather than "leave it alone") is what makes the caller's
 * assignment total: an encrypted `meet_` followed by answering a `call_` on the same
 * engine would otherwise encrypt that call against a key no other participant holds, and
 * the SIP bridge would receive undecryptable media — one-way dead air, with no error
 * anywhere. The engine's contract is one instance per call so it should be unreachable;
 * this is written down because the failure it prevents is silent.
 *
 * ⚠️ THE FUNCTION IS HERE, IN THE SDK-FREE FILE, PRECISELY SO IT CAN BE UNIT-TESTED.
 * `E2EEOptions` cannot be constructed off a device — its constructor reaches through to
 * libwebrtc's native `FrameCryptorKeyProvider` — so the decision has to be separable from
 * the object it decides about. That is the same rule `LiveKitCallEngine`'s KDoc states for
 * everything else in that class.
 *
 * ⛔ A BLANK KEY IS NOT "NO ENCRYPTION" — it derives a real AES key that nobody else
 * derives — so it is normalised to null here as well as at the ViewModel. Two guards for
 * one rule, deliberately: this one covers `SoftphoneSession` and `InboundCallSession`,
 * which never go through the room ViewModel at all.
 */
internal fun sharedRoomKey(e2eeKeyBase64: String?): String? =
    e2eeKeyBase64?.takeIf { it.isNotBlank() }

/**
 * Constructs one engine per call session. The production factory wires the SDK.
 *
 * ⛔ THE CALLER SUPPLIES THE SCOPE, AND THAT IS NOT A CONVENIENCE. [LiveKitCallEngine] collects the
 * room's event stream for the life of the session, so the collection has to die when the session's
 * OWNER dies — which is the ViewModel, not the process and not the application graph. A factory
 * that captured its own scope would leave one engine's event collector running after its screen was
 * gone, quietly updating state flows nobody reads, and a second session would then have two
 * collectors fighting over audio focus. The application container supplies the Context (which is
 * process-scoped and cannot go stale) and the ViewModel supplies the lifetime.
 *
 * ⚠️ IT IS NOT `viewModelScope`. The scope handed in has to outlive `onCleared` by exactly long
 * enough to run the disconnect — `viewModelScope` is already cancelled by then, so a teardown
 * launched in it never runs and the room is left holding the participant. See
 * `ActiveRoomViewModel.release`.
 */
fun interface CallEngineFactory {
    fun create(scope: CoroutineScope): CallEngine
}

/**
 * The room connection's lifecycle.
 *
 * ⚠️ [Reconnecting] is a real state the UI must render (mid-call radio handover is
 * normal on a phone), not an error: the SDK resumes the session itself, and a UI that
 * treats it as [Failed] hangs up calls that would have survived.
 */
sealed interface CallConnectionState {
    data object Idle : CallConnectionState
    data object Connecting : CallConnectionState
    data object Connected : CallConnectionState
    data object Reconnecting : CallConnectionState

    /** A deliberate or completed end. [reason] is display-safe or null. */
    data class Disconnected(val reason: String? = null) : CallConnectionState

    /** Could not connect or the session was lost for good. */
    data class Failed(val message: String? = null) : CallConnectionState
}

/**
 * One remote participant, as much of them as the UI renders.
 *
 * [identity] is the server-derived stable identity (`user-<hash>` from the token
 * routes) — it is the EVICTION key, so the same human rejoining replaces themselves
 * rather than appearing twice. [videoTrack] is null when the participant publishes no
 * video (every softphone participant, and camera-off meet participants).
 */
data class MediaParticipant(
    val identity: String,
    val name: String?,
    val isSpeaking: Boolean = false,
    val isMicrophoneEnabled: Boolean = true,
    val videoTrack: VideoTrackHandle? = null,
    /**
     * True for a participant the server joined as a service rather than a person.
     *
     * ⛔ ON THE MODEL RATHER THAN INFERRED FROM [identity], BECAUSE THE IDENTITY IS NOT A RELIABLE
     * SIGNAL AND THE CONSEQUENCE OF GUESSING IS VISIBLE. The transcription Companion joins every
     * `meet_` room with the LiveKit Agents framework's `kind = AGENT` and a default `agent-<jobId>`
     * identity, and it publishes no media — so a grid that did not know to exclude it renders a
     * blank muted tile in the middle of a meeting. The kind is what the SDK actually reports and
     * what the web filters on too (`isBotParticipant`); the identity prefix is a fallback the web
     * keeps for a retired browser-side participant, not the primary test.
     *
     * ⚠️ THIS IS A CAPABILITY THE UI GENUINELY NEEDS, WHICH IS WHY IT IS HERE AND NOT A REASON TO
     * EXPOSE THE SDK PARTICIPANT. It is a boolean, and it is the whole of what the grid has to know.
     */
    val isAgent: Boolean = false,
)

/**
 * An opaque handle to a remote video track.
 *
 * ⛔ OPAQUE ON PURPOSE: the boxed value is an SDK type, and unwrapping it anywhere but
 * this module's own renderer composable re-couples the app to LiveKit. Equality is by
 * track identity so Compose can skip recomposition when the track has not changed.
 */
class VideoTrackHandle internal constructor(
    internal val sdkTrack: Any,
    private val trackSid: String,
    /**
     * Binds a freshly created renderer view to the room that owns this track.
     *
     * ⛔ IT HAS TO TRAVEL WITH THE TRACK BECAUSE A RENDERER CANNOT BE INITIALISED WITHOUT THE
     * ROOM'S OWN EGL CONTEXT, AND THE SDK EXPOSES NO STATIC WAY TO REACH IT. `Room.initVideoRenderer`
     * is the only public entry point, so either the room crosses into the tile (which would put an
     * SDK object in a composable's parameter list, exactly what [CallEngine] exists to prevent) or
     * the one call the tile needs comes along with the handle. This is the smaller hole: it is one
     * function, it captures the room in the module that already owns it, and it cannot be used to
     * reach anything else on the room.
     *
     * ⚠️ TYPED AS `android.view.View`, NOT AS THE SDK'S RENDERER, AND THAT IS WHAT KEEPS THIS FILE
     * SDK-FREE. `TextureViewRenderer` is a `TextureView`, so the platform supertype is a real
     * description rather than a dodge; the one cast back lives in [LiveKitCallEngine], which is
     * where casts to SDK types belong. ⛔ Defaults to a no-op so the model types stay constructible
     * in a test without an SDK — `CallEngineModelsTest` builds handles from `Any()`.
     */
    internal val initRenderer: (View) -> Unit = {},
) {
    override fun equals(other: Any?): Boolean = other is VideoTrackHandle && other.trackSid == trackSid
    override fun hashCode(): Int = trackSid.hashCode()
}
