package com.distronode.districtai.ui.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.R
import com.distronode.districtai.core.data.CallControlRepository
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.DialOutcome
import com.distronode.districtai.core.data.DialRepository
import com.distronode.districtai.core.data.HangUpOutcome
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The outbound softphone's destination: the keypad, the call-back list, and one call at a time.
 *
 * ⛔ IT PLACES CALLS, WHICH MAKES IT THE ONLY ViewModel IN THIS APP WHOSE MISTAKES COST MONEY AND
 * RING A STRANGER'S TELEPHONE. Four properties follow from that and are each enforced here rather
 * than left to care:
 *
 *  1. **Nothing dials without an explicit tap.** There is no `init { }` dial, no effect that dials
 *     on entry and no retry anywhere. `POST /api/district/calls/dial` is not idempotent: the row is
 *     written and the carrier instructed BEFORE the response is minted, so a re-send is a second
 *     call to the same person, billed again.
 *  2. **One call at a time, and a second tap while one is live is dropped** — not queued, not
 *     allowed to replace. Two engines would fight over the device's audio focus, which is the
 *     failure `CallEngine` itself warns about, and the operator would be on two calls.
 *  3. **The microphone is required, unlike a meeting.** A room is joinable listen-only; a phone
 *     call is not. Placing one with no microphone bills the workspace for a call the callee
 *     experiences as silence, so a denied permission REFUSES the dial rather than degrading it.
 *  4. **The teardown must run even when the screen is gone.** See [SoftphoneSession].
 *
 * ⛔ THE LIVE CALL IS [SoftphoneSession]'S, NOT THIS CLASS'S, AND THE SPLIT IS ALONG A REAL SEAM.
 * This destination is long-lived and places a SEQUENCE of calls; each call owns a socket, the
 * device's audio focus and a duration, and `CallEngine`'s contract is one engine per call. Keeping
 * both here made "one engine per call" a matter of discipline rather than of structure.
 *
 * ⚠️ THIS CLIENT NEVER ENDS THE CALL SERVER-SIDE, AND THAT IS THE SERVER'S DESIGN RATHER THAN A GAP
 * HERE. There is no hang-up route: leaving the room is the whole of hanging up, and the `Call`
 * row's terminal status and billed duration belong to the SIP/webhook pipeline watching the
 * carrier leg. The duration on screen is this app's own measurement and is never written anywhere.
 */
/**
 * ⚠️ `TooManyFunctions` AND `LongParameterList` ARE SUPPRESSED TOGETHER, for one reason: the
 * server-side hang-up. Ending a call now means telling the carrier as well as tearing down the
 * local session, which added a repository to the constructor and the hang-up pair to the surface,
 * taking both counts one over their thresholds. Splitting the carrier leg from the local leg is
 * exactly the seam that must NOT exist: the whole point of `requestServerHangUp` is that the two
 * happen from one place so neither can be forgotten.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class DialerViewModel(
    private val dialRepository: DialRepository,
    private val callsRepository: CallsRepository,
    /**
     * ⚠️ A FACTORY RATHER THAN AN ENGINE FACTORY, A TELECOM BRIDGE, A SCOPE FACTORY AND A TICK —
     * four parameters that are one concept, and that together pushed this constructor past
     * detekt's ceiling. See [SoftphoneSessionFactory], which is also where "one engine per call,
     * built with the scope its teardown runs in" is enforced.
     */
    private val sessions: SoftphoneSessionFactory,
    /**
     * The server-side hang-up.
     *
     * ⛔ WITHOUT IT THIS SCREEN CANNOT END A CALL, AND THE FAILURE IS A BILL RATHER THAN AN ERROR.
     * [SoftphoneSession.end] disconnects THIS device from the LiveKit room and says nothing to the
     * SIP participant; the carrier leg goes on ringing or talking to an empty room and goes on
     * being billed. A call ended on the device in under a second can bill roughly 90 seconds at the
     * carrier, with nothing failing and nothing logged.
     */
    private val callControl: CallControlRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
    /**
     * ⛔ A SCOPE THAT OUTLIVES THIS ViewModel, DELIBERATELY, AND IT IS NOT `viewModelScope`.
     * [onCleared] ends the call and `viewModelScope` is cancelled at exactly that moment, so a
     * hang-up launched there on the teardown path would be cancelled before it left the device —
     * on precisely the path it exists for. Nothing cancels this one, which is the correct lifetime
     * for a request whose whole job is to survive the teardown that issued it.
     */
    private val serverHangUpScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    private val _state = MutableStateFlow(
        // ⛔ `allowsMutation()` ON THE NULLABLE TYPE, NOT `role != VIEWER`. `WorkspaceRole.fromWire`
        // answers null for a value it does not recognise, and `null != VIEWER` is TRUE — so the
        // shorter expression would hand an UNKNOWN role the ability to spend the workspace's
        // minutes. The extension exists precisely so the null case cannot be defaulted permissive
        // by accident; same direction every sibling destination fails.
        DialerUiState(canDial = role.allowsMutation()),
    )
    val state: StateFlow<DialerUiState> = _state.asStateFlow()

    private var session: SoftphoneSession? = null

    /**
     * ⚠️ CANCELLED WHEN A NEW CALL STARTS. Without it, a finished session could still write into
     * the screen a later call is using — the two would take turns describing the same field.
     */
    private var mirror: Job? = null

    /** ⚠️ Set while a dial request is in flight, so a second tap cannot start a second one. */
    private var dialing = false

    /**
     * The `Call.callSid` the dial answered with.
     *
     * ⛔ THE ONLY IDENTIFIER THAT CAN END THE CARRIER LEG, and it exists for exactly as long as this
     * object does. It is not a room name and is not derivable from one.
     */
    private var placedCallId: String? = null

    /**
     * ⚠️ EXACTLY-ONCE, NOT AT-MOST-ONCE-PER-PATH. The route is idempotent, so a second send would be
     * safe; this latch exists so that the two paths that can reach it — the ordinary hang-up and the
     * dial that comes back after one — do not both spend a request on a call nobody is on.
     */
    private var serverHangUpSent = false

    /**
     * ⛔ THE CASE THIS WHOLE DESIGN IS SHAPED AROUND, AND IT IS THE COMMON ONE. `calls/dial` writes
     * the `Call` row and instructs the carrier BEFORE it answers, so a response that has not arrived
     * may already be ringing somebody — and an operator who misdials hangs up in well under a
     * second. Discarding that response would throw away the only `callId` this client will ever
     * hold, and the phone at the far end would go on ringing.
     */
    private var dialAbandoned = false

    /**
     * ⚠️ RECORDED, NEVER SHOWN, AND READ ONLY BY A TEST. A failed carrier hang-up is a diagnostic:
     * the call is already over locally, so rendering one would put an error over a call that ended
     * correctly from the operator's point of view.
     */
    var lastServerHangUp: HangUpOutcome? = null
        private set

    init {
        load()
    }

    /**
     * Read the recent inbound calls the operator might call back.
     *
     * ⚠️ IDEMPOTENT AND SAFE TO REPLAY on a session change, unlike anything else this ViewModel
     * does. It is a GET.
     */
    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                callbacks = when (val result = callsRepository.recentCallbacks(workspaceId)) {
                    is ApiResult.Success -> CallbacksState.Ready(result.value)
                    is ApiResult.Failure -> CallbacksState.Failed(result.toFailureText())
                },
            )
        }
    }

    /**
     * ⚠️ Clears the previous refusal: a message about a number already replaced is noise.
     *
     * ⚠️ ALSO THE CALL-BACK HANDLER. Tapping a row FILLS this field and does not dial — one tap
     * must never place a call, least of all from a scrolling list where a mis-scroll lands on a
     * row.
     */
    fun onEntryChange(entry: String) {
        _state.value = _state.value.copy(entry = entry, refusal = null)
    }

    /**
     * The operator asked to place the call.
     *
     * ⛔ THIS ASKS FOR THE MICROPHONE AND STOPS. The dial happens in [onMicrophonePermissionResult]
     * and nowhere else, because a `connect` that publishes a microphone track before the OS dialog
     * is answered gets a SILENT track rather than an error — which looks like a working microphone
     * to everyone except the person on the other end. `RequestPermission` invokes its callback
     * immediately when the permission is already held, so this is one path rather than two.
     *
     * ⚠️ DROPPED WHILE A CALL IS LIVE OR A DIAL IS IN FLIGHT, rather than queued. See property 2 on
     * the class.
     */
    fun onDial() {
        if (!_state.value.canPlaceCall || dialing) return
        _state.value = _state.value.copy(
            refusal = null,
            microphoneRequest = _state.value.microphoneRequest + 1,
        )
    }

    /**
     * ⛔ A DENIAL REFUSES THE CALL, WHICH IS THE OPPOSITE OF WHAT THE ROOM SCREEN DOES AND IS
     * DELIBERATE. A meeting with no microphone is listen-only attendance, a seat the product
     * already sells. A phone call with no microphone is a billed call the callee experiences as an
     * empty line — so it is refused before any money is spent, with a message that says why.
     */
    fun onMicrophonePermissionResult(granted: Boolean) {
        if (!granted) {
            _state.refuse(FailureText(UiText.Resource(R.string.dialer_needs_microphone), retryable = false))
            return
        }
        if (!_state.value.canPlaceCall || dialing) return
        placeCall(_state.value.entry)
    }

    fun toggleMicrophone() {
        session?.toggleMicrophone(viewModelScope)
    }

    fun toggleSpeaker() {
        session?.toggleSpeaker()
    }

    /**
     * End the call, then tell the caller.
     *
     * ⚠️ NO NAVIGATION FOLLOWS, unlike the room screen's `leave`. The ended call keeps the screen so
     * the operator can read its duration and be told the record is the call log; the dialler is
     * also where the next call starts from, so popping would put them a navigation away from it.
     */
    fun hangUp(onEnded: () -> Unit) {
        // ⛔ THE CARRIER REQUEST GOES FIRST AND IS NOT AWAITED. Nothing local may wait on a network
        // round trip: the local teardown below has to run at the speed of a button press, and
        // awaiting a timeout here would hold the call screen open on a call the operator has
        // already finished with.
        requestServerHangUp()
        // ⚠️ A dial still in flight is marked abandoned so its response tears itself down rather
        // than building a session for a call nobody is on.
        // ⛔ AND THE OPTIMISTIC CALL STATE GOES WITH IT, HERE RATHER THAN WHEN THE RESPONSE LANDS.
        // `placeCall` puts an `ActiveCallUiState` up the instant it is called so the screen reacts
        // to the button rather than to the network; leaving it up until the dial answers would keep
        // a call screen on top of a call the operator has already ended, for the whole round trip.
        if (dialing) {
            dialAbandoned = true
            _state.value = _state.value.copy(call = null)
        }

        val live = session
        if (live == null) {
            onEnded()
            return
        }
        session = null
        live.end(onEnded)
    }

    /**
     * Ask the server to end the carrier leg. ⛔ Fire and forget.
     *
     * ⛔ NO RETRY. The route is idempotent, so a retry would be SAFE — and it would still be wrong:
     * it would spend requests on behalf of a call nobody is on, and the failures that reach here are
     * offline and signed-out, neither of which a second immediate attempt fixes.
     *
     * ⚠️ THE OUTCOME IS RECORDED, NEVER SHOWN. `AlreadyEnded` and `NotDirectCall` are answers rather
     * than faults, and even `NotEnded` must not reach the screen: by the time this is sent the call
     * is over locally and the operator is looking at a summary, so an error over it would report a
     * failure for a call that ended correctly from their point of view.
     */
    private fun requestServerHangUp() {
        val callId = placedCallId ?: return
        if (serverHangUpSent) return
        serverHangUpSent = true
        // ⛔ The values are read out BEFORE the launch. The request has to outlive this object.
        val workspace = workspaceId
        val repository = callControl
        serverHangUpScope.launch {
            lastServerHangUp = repository.hangUp(workspace, callId)
        }
    }

    /** Dismiss the ended-call summary and return to the keypad. */
    fun clearEndedCall() {
        if (_state.value.call?.ended == true) _state.value = _state.value.copy(call = null)
    }

    /**
     * ⚠️ A NO-OP AFTER an explicit hang-up, which is the normal case. This exists for the exit where
     * the operator never pressed anything — a back gesture out of the destination, or the process
     * tearing the graph down. It works only because the session's scope is NOT `viewModelScope`,
     * which is already cancelled by the time this runs.
     */
    override fun onCleared() {
        hangUp {}
    }

    private fun placeCall(number: String) {
        dialing = true
        _state.value = _state.value.copy(call = ActiveCallUiState(number = number), refusal = null)
        viewModelScope.launch {
            when (val outcome = dialRepository.dial(workspaceId, number)) {
                is DialOutcome.Placed -> {
                    // ⛔ KEPT BEFORE ANYTHING ELSE. This is the only `callId` this client will ever
                    // hold, and the branch below depends on having it.
                    placedCallId = outcome.session.callId
                    if (dialAbandoned) {
                        // ⛔ THE OPERATOR HUNG UP WHILE THE DIAL WAS IN FLIGHT, AND THE CARRIER LEG
                        // IS LIVE. Building a session here would put a call screen back on top of a
                        // call they have already ended; sending the carrier hang-up is the only
                        // thing that stops the phone at the far end ringing.
                        dialAbandoned = false
                        requestServerHangUp()
                        // ⚠️ Belt and braces with the clear in `hangUp`: whichever ran first, the
                        // screen must not end up showing a call that was hung up before it existed.
                        _state.value = _state.value.copy(call = null)
                    } else {
                        val live = sessions.create(number)
                        session = live
                        // ⚠️ The hang-up handler travels with the call: it is what a headset button
                        // or the OS's own call notification reaches, and without it the media
                        // outlives the user's belief that they hung up — a live microphone they
                        // think is off.
                        live.begin(viewModelScope) { hangUp {} }
                        mirror?.cancel()
                        mirror = viewModelScope.launch {
                            live.state.collect { call ->
                                _state.value = _state.value.copy(call = call)
                            }
                        }
                        live.connect(outcome.session.url, outcome.session.token)
                    }
                }
                // ⚠️ EVERY REFUSAL CLEARS THE CALL AND RETURNS TO THE KEYPAD. Nothing was placed, so
                // leaving a call screen up would be showing a call that does not exist.
                DialOutcome.SubscriptionInactive -> _state.refuse(
                    FailureText(UiText.Resource(R.string.dialer_subscription_inactive), retryable = false),
                )
                DialOutcome.OverageCapReached -> _state.refuse(
                    FailureText(UiText.Resource(R.string.dialer_overage_cap), retryable = false),
                )
                is DialOutcome.NotPlaced -> _state.refuse(outcome.failure.toFailureText())
            }
            dialing = false
        }
    }

    companion object {
        @Suppress("LongParameterList")
        fun factory(
            dialRepository: DialRepository,
            callsRepository: CallsRepository,
            callControl: CallControlRepository,
            engineFactory: CallEngineFactory,
            telecom: TelecomBridge,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = DialerViewModel(
                dialRepository = dialRepository,
                callsRepository = callsRepository,
                callControl = callControl,
                sessions = SoftphoneSessionFactory(engineFactory, telecom),
                workspaceId = workspaceId,
                role = role,
            ) as T
        }
    }
}

/**
 * Report that the dial did not happen, and clear any call it had provisionally drawn.
 *
 * ⛔ A TOP-LEVEL EXTENSION RATHER THAN A ViewModel METHOD, AND THE CEILING THAT FORCED IT IS WORTH
 * RESPECTING. detekt caps a class's function count, and the rule is there to catch a class that
 * has grown several responsibilities — which this one nearly did before the call session moved
 * out. This is a pure state transition with no dependency on the ViewModel at all, so it belongs
 * outside it.
 *
 * ⛔ IT CLEARS `call`, WHICH IS THE HALF THAT MATTERS. A refusal means NOTHING WAS PLACED — no row,
 * no carrier instruction, no money — so leaving a call screen up would be drawing a call that does
 * not exist, on the one surface where "is there a call happening" has to be answerable at a glance.
 */
private fun MutableStateFlow<DialerUiState>.refuse(failure: FailureText) {
    value = value.copy(call = null, refusal = failure)
}
