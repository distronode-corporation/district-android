package com.distronode.districtai.ui.rooms

import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.media.MediaParticipant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [CallEngine] whose every input is drivable and whose every call is recorded.
 *
 * ⛔ A HAND-WRITTEN FAKE RATHER THAN A MOCKING FRAMEWORK, matching every other test double in this
 * module. The interface is nine members, and "what did the room actually ask the engine to do" is
 * assertable by reading [calls] as a list — which is also what makes "leave disconnects EXACTLY
 * once" a one-line assertion rather than an argument-captor configuration.
 *
 * ⛔ AND IT IS AN ENGINE, NOT A ROOM. It holds no notion of who is connected: the tests push
 * participants and connection states in directly, because the point of the seam is that the
 * ViewModel's behaviour is a function of what the engine REPORTS, not of what a real media server
 * would do. A fake that simulated LiveKit would be testing the simulation.
 *
 * ⚠️ `connect` CAN BE MADE TO THROW. The real engine sets its own state to `Failed` and RETHROWS —
 * that contract is stated on `LiveKitCallEngine` — so a fake that only returned would never
 * exercise the ViewModel's catch, and the crash it prevents is a process crash on a bad network.
 */
internal class FakeCallEngine : CallEngine {

    private val _connectionState =
        MutableStateFlow<CallConnectionState>(CallConnectionState.Idle)
    override val connectionState: StateFlow<CallConnectionState> = _connectionState.asStateFlow()

    private val _participants = MutableStateFlow<List<MediaParticipant>>(emptyList())
    override val participants: StateFlow<List<MediaParticipant>> = _participants.asStateFlow()

    private val _isMicrophoneEnabled = MutableStateFlow(false)
    override val isMicrophoneEnabled: StateFlow<Boolean> = _isMicrophoneEnabled.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    override val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    /**
     * Every call made on this engine, in order.
     *
     * ⚠️ STRINGS RATHER THAN A SEALED TYPE, deliberately: the assertions are about ORDER and COUNT
     * ("connect then disconnect", "disconnect exactly once"), and a list of strings makes a failed
     * assertion readable in the report without a custom toString.
     */
    val calls: MutableList<String> = mutableListOf()

    /** Set to make [connect] throw, as the real engine does on an unreachable media server. */
    var connectFailure: Throwable? = null

    /**
     * The room key handed to the last [connect], or null if that join was unencrypted.
     *
     * ⛔ A SEPARATE FIELD RATHER THAN A THIRD SEGMENT OF THE [calls] STRING, DELIBERATELY. Four
     * tests across three suites assert `"connect:<url>:<token>"` by exact equality, so widening
     * that string would red `IncomingCallControllerTest`, `DialerViewModelTest` and
     * `InboundCallSessionTest` for a change that has nothing to do with any of them — and the
     * resulting failures would name the softphone, which is the one caller that is CORRECTLY
     * unencrypted. The key is also the one input here worth asserting VERBATIM, and a field
     * compares verbatim without the escaping question a colon-delimited string raises.
     */
    var lastE2eeKey: String? = null

    override suspend fun connect(url: String, token: String, e2eeKeyBase64: String?) {
        calls += "connect:$url:$token"
        lastE2eeKey = e2eeKeyBase64
        connectFailure?.let { failure ->
            // ⚠️ MIRRORS THE REAL ENGINE: state goes to Failed BEFORE the throw, because a state
            // flow left on Connecting renders a spinner over a call that will never happen.
            _connectionState.value = CallConnectionState.Failed(failure.message)
            throw failure
        }
        _connectionState.value = CallConnectionState.Connected
    }

    override suspend fun disconnect() {
        calls += "disconnect"
        _connectionState.value = CallConnectionState.Disconnected()
    }

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        calls += "mic:$enabled"
        _isMicrophoneEnabled.value = enabled
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        calls += "camera:$enabled"
        _isCameraEnabled.value = enabled
    }

    override suspend fun flipCamera() {
        calls += "flip"
    }

    override fun setSpeakerphoneOn(on: Boolean) {
        calls += "speaker:$on"
    }

    /** Push a room event in, the way the SDK's event stream would. */
    fun emitConnection(state: CallConnectionState) {
        _connectionState.value = state
    }

    fun emitParticipants(people: List<MediaParticipant>) {
        _participants.value = people
    }
}

/**
 * A factory that hands out ONE fake and records the scope it was built with.
 *
 * ⛔ THE SCOPE IS RECORDED BECAUSE THE WHOLE TEARDOWN CONTRACT DEPENDS ON WHICH ONE IT IS. The
 * production factory is handed a scope that OUTLIVES `onCleared` — `viewModelScope` is already
 * cancelled by then, so a disconnect launched in it never reaches the socket and the room keeps
 * the participant until the server times them out. See [CallEngineFactory].
 */
internal class FakeCallEngineFactory(
    val engine: FakeCallEngine = FakeCallEngine(),
) : CallEngineFactory {

    var scopes: MutableList<CoroutineScope> = mutableListOf()

    override fun create(scope: CoroutineScope): CallEngine {
        scopes += scope
        return engine
    }
}
