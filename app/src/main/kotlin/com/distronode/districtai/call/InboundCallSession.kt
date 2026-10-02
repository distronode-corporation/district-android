package com.distronode.districtai.call

import com.distronode.districtai.core.media.CallEngine
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.dialer.ActiveCallUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * ONE answered inbound call: its engine, its scope, its clock.
 *
 * ⛔ ITS OWN CLASS RATHER THAN A REUSE OF `SoftphoneSession`, AND THE DIFFERENCE IS THE ANSWER
 * SIGNAL RATHER THAN STYLE. An outbound call is answered when a PARTICIPANT APPEARS — the SIP bridge
 * adds the callee to the room at pickup, and that is the only ring-versus-answer signal that path
 * has. An inbound call joins a room that ALREADY contains the caller and the AI, so participants are
 * present from the first frame and a session that latched on them would report "answered" before it
 * had connected to anything. Here the answer moment is OUR MEDIA COMING UP, which is a different
 * event with a different failure mode, and folding both into one class would mean a flag deciding
 * which of two state machines is running.
 *
 * ⛔ TELECOM IS TOLD ACTIVE ONLY AFTER [connect] SUCCEEDS, WHICH IS THE INBOUND MIRROR OF THE
 * OUTBOUND ANSWER-LATCH. Marking the connection ACTIVE when the user pressed Answer would tell the
 * OS a conversation is under way while this app was still fetching a credential and opening a
 * socket — the OS's own duration counter would start early, and on a failed join the user would be
 * looking at a connected call that has no audio.
 *
 * ⚠️ THE LIFECYCLE ITSELF (the release latch, the toggles, the teardown, the in-flight hang-up guard
 * and the clock) IS [CallSessionCore], SHARED WITH `SoftphoneSession`. Only the answer signal lives
 * here. The scope is supplied and is not `viewModelScope`, for the reason the core documents.
 *
 * ⚠️ IT HOLDS NO CALLER IDENTITY, AND IT CANNOT. The push carries identifiers only and the answer
 * route returns a join credential rather than a caller, so [ActiveCallUiState.number] is EMPTY here
 * and the screen substitutes a label. That is a real product limitation of the ids-only payload,
 * written down rather than papered over with the call id.
 */
internal class InboundCallSession(
    engine: CallEngine,
    /** ⛔ Outlives the ViewModel/controller. See [CallSessionCore]. */
    scope: CoroutineScope,
    telecom: TelecomBridge,
    tickMillis: Long,
) {

    private val core = CallSessionCore(engine, scope, telecom, tickMillis, number = "")

    val state: StateFlow<ActiveCallUiState> get() = core.state

    /**
     * Start watching the engine.
     *
     * ⛔ IT DOES **NOT** TOUCH TELECOM, UNLIKE THE OUTBOUND SESSION'S `begin`. The connection
     * already exists and is RINGING: `TelecomBridge.startIncoming` created it when the push arrived,
     * which is minutes of user-visible ringing before this object is constructed. Announcing the
     * call again here would ask Telecom for a SECOND connection for the same call.
     *
     * @param observeScope the owner's scope. ⚠️ The collectors feed the UI, so they should stop when
     *   the screen does; the disconnect is the one thing that must not, and it runs in the core's.
     */
    fun begin(observeScope: CoroutineScope) {
        core.observe(observeScope)
    }

    /**
     * Join the room the answer route named.
     *
     * ⛔ THE url/token PAIR IS USED VERBATIM. The room lives on the deployment that CREATED it —
     * for an inbound call, the US SIP bridge, regardless of the workspace's own region — and the
     * server resolved that by room rather than by workspace precisely because an EU tenant has rooms
     * on both buses. A client that derived a URL would create an empty room of the same name on the
     * wrong bus and sit in it alone while the caller waited.
     *
     * ⛔ MEDIA COMING UP IS THE ANSWER SIGNAL HERE (see the class doc), so the latch is set on a
     * successful join and nowhere else. The failure, cancellation and hang-up-in-flight handling is
     * [CallSessionCore.connect]'s.
     *
     * @return true when media is up. ⛔ False means the call is over and Telecom has been told.
     */
    suspend fun connect(url: String, token: String, observeScope: CoroutineScope): Boolean {
        if (!core.connect(url, token)) return false
        core.markAnswered(observeScope)
        return true
    }

    fun toggleMicrophone(observeScope: CoroutineScope) = core.toggleMicrophone(observeScope)

    fun toggleSpeaker() = core.toggleSpeaker()

    /** End the call, then tell the caller. See [CallSessionCore.end] for the ordering. */
    fun end(onEnded: () -> Unit) = core.end(onEnded)
}

/**
 * Builds one [InboundCallSession] per answered call.
 *
 * ⛔ THE SCOPE AND THE ENGINE ARE MINTED TOGETHER, WHICH IS THE PART THAT MUST NOT DRIFT.
 * [CallEngineFactory] takes the scope its event collection lives in, and [InboundCallSession] runs
 * its disconnect in the same one — so building them from two separate calls would leave the engine's
 * collector alive after the session that owned it had gone. Same contract as
 * `SoftphoneSessionFactory`, which exists for the outbound half.
 *
 * ⚠️ ONE ENGINE PER CALL, NEVER A REUSED ONE. `LiveKitCallEngine` wraps a single `Room` and a second
 * `connect` on a disconnected one is not a supported operation.
 */
internal class InboundCallSessionFactory(
    private val engineFactory: CallEngineFactory,
    private val telecom: TelecomBridge,
    /** ⛔ NOT `viewModelScope` (see [CallSessionCore]). Injectable so a test owns the lifetime. */
    private val engineScopeFactory: () -> CoroutineScope = ::newCallScope,
    /** ⚠️ The timer's granularity, not a poll interval — nothing is fetched. Injectable for tests. */
    private val tickMillis: Long = CALL_TICK_MILLIS,
) {

    fun create(): InboundCallSession {
        val scope = engineScopeFactory()
        return InboundCallSession(
            engine = engineFactory.create(scope),
            scope = scope,
            telecom = telecom,
            tickMillis = tickMillis,
        )
    }
}
