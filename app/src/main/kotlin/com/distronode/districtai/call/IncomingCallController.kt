package com.distronode.districtai.call

import com.distronode.districtai.R
import com.distronode.districtai.core.data.AnswerOutcome
import com.distronode.districtai.core.data.InboundCallRepository
import com.distronode.districtai.push.PushNotifier
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.dialer.ActiveCallUiState
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Where the app is in an inbound call.
 *
 * ⛔ [ANSWERING] IS A PHASE OF ITS OWN AND NOT A SPINNER OVER [RINGING], BECAUSE THE TWO ACCEPT
 * DIFFERENT INPUT. Answer and Decline are both live while ringing; once the answer round trip is out
 * neither may be pressed again — a second answer would write the rendezvous twice and a decline
 * would tear down a call that is mid-join. It is also visible to the user: the button has to stop
 * looking pressable, and there is a real gap to fill (one authenticated request plus a LiveKit join)
 * that a ringing screen would otherwise spend looking frozen.
 */
enum class IncomingCallPhase {
    /** The phone is ringing. ⚠️ Bounded — see [IncomingCallController]'s ring timeout. */
    RINGING,

    /** Answer was pressed; the credential is being fetched and the room joined. */
    ANSWERING,

    /** Media is up. ⚠️ The duration in [IncomingCallUiState.call] runs from this moment. */
    IN_CALL,

    /** Over, for any reason. ⚠️ [IncomingCallUiState.message] says which, when there is anything to say. */
    ENDED,
}

/**
 * One inbound call, as the screen sees it.
 *
 * ⚠️ [call] IS NULL UNTIL MEDIA IS UP, which is what stops a ringing screen drawing a duration.
 * ⚠️ [message] IS SET ONLY WHEN THERE IS SOMETHING WORTH SAYING: a call the user declined, or one
 * that simply ended, says nothing — the notification going away is the whole feedback. It carries
 * text for the two cases the user could otherwise misread as the app being broken: the caller hung
 * up first, and the answer was refused.
 */
data class IncomingCallUiState(
    val workspaceId: String,
    val callId: String,
    val phase: IncomingCallPhase,
    val call: ActiveCallUiState? = null,
    val message: FailureText? = null,
)

/**
 * The inbound call state machine: ring, answer, decline, time out.
 *
 * ⛔ **NOTHING IS TOLD TO THE SERVER ON A DECLINE OR A TIMEOUT, AND THAT IS A PRIVACY PROPERTY
 * RATHER THAN AN OMISSION.** `POST /api/district/actions/ring-app` blocks on a Redis rendezvous for
 * ~25 seconds and falls back to PSTN when it expires, so the server already has everything it needs
 * from the ABSENCE of an answer. Reporting a decline would make a deliberate refusal
 * distinguishable from a phone in a pocket — to the agent, and through it to the caller — and there
 * is no version of that distinction the product wants. An unanswered ring and a declined one must
 * look identical from outside, and the only way to guarantee that is for neither to send anything.
 *
 * ⛔ **THE RING IS BOUNDED HERE, LOCALLY, AND THE BOUND IS LONGER THAN THE SERVER'S ON PURPOSE.**
 * The rendezvous expires at 25s; this waits ~30. Timing out FIRST would take the Answer button away
 * while the server was still willing to accept one, which is the one ordering that turns a slow
 * thumb into a missed call. Timing out at all is what keeps `onCreateIncomingConnection` honest —
 * it used to be absent precisely so nothing could leave a RINGING connection nobody would resolve,
 * and this timeout is what replaced that guarantee.
 *
 * ⛔ **ONE CALL AT A TIME, AND A SECOND PUSH WHILE ONE IS LIVE IS DROPPED** — not queued, not
 * allowed to replace. `DistrictCallRegistry` holds at most one connection, one engine owns the
 * device's audio focus, and a replacement would tear down a conversation the user is having in order
 * to ring them about another. The dropped call still reaches the server's timeout and falls back to
 * PSTN, which is the correct outcome for a person who is already on the phone.
 *
 * ⛔ **`setActive()` IS NOT CALLED WHEN ANSWER IS PRESSED.** It is called by [InboundCallSession]
 * after media connects, mirroring the outbound answer-latch. Telling the OS a call is ACTIVE while
 * the credential is still being fetched starts its duration counter early and, on a failed join,
 * leaves the user looking at a connected call with no audio.
 *
 * ⚠️ **PROCESS-SCOPED, LIKE THE TELECOM REGISTRY, AND FOR THE SAME REASON.** A ring arrives at a
 * `FirebaseMessagingService` — possibly into a cold process with no Activity at all — and has to
 * survive until an Activity exists to draw it. A ViewModel-scoped owner would be constructed after
 * the event it needs to have received.
 *
 * ⚠️ **NONE OF THIS IS VERIFIABLE ON THIS MACHINE.** Waydroid is API 33 with no Play Services, so
 * the push cannot be delivered, the foreground-service types are API 34 concepts, and the audio
 * route is an on-device question. What IS tested is every transition below, driven through injected
 * seams.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED, DELIBERATELY AND NARROWLY, the same call
 * [DeskViewModel] and [MessagingViewModel] make. Each function here is one
 * call transition; the notification-answer bridge ([requestAnswerFromNotification] and
 * [consumeAnswerRequest]) is the twelfth, one past detekt's ceiling of 11. Splitting it into
 * a second class to save one function would scatter the state machine this class exists to
 * hold in one place.
 */
@Suppress("TooManyFunctions")
internal class IncomingCallController(
    private val repository: InboundCallRepository,
    /**
     * ⚠️ A BUNDLE RATHER THAN FOUR PARAMETERS, AND THE detekt CEILING THAT FORCED IT IS WORTH
     * RESPECTING RATHER THAN SUPPRESSING — the same call [InboundCallSessionFactory] and
     * `SoftphoneSessionFactory` make. The four things it holds are one concept: everything that has
     * to be told about a call ON THE DEVICE, as opposed to [repository], which is the one thing that
     * talks to the server. See [IncomingCallSurfaces].
     */
    private val surfaces: IncomingCallSurfaces,
    /** ⛔ The application graph's scope. See the ⚠️ on the class. */
    private val scope: CoroutineScope,
    /** ⚠️ Injectable so a test does not wait thirty seconds. See the ⛔ on the class for the value. */
    private val ringTimeoutMillis: Long = RING_TIMEOUT_MILLIS,
) {

    private val telecom: TelecomBridge get() = surfaces.telecom
    private val notifier: PushNotifier get() = surfaces.notifier
    private val sessions: InboundCallSessionFactory get() = surfaces.sessions
    private val foreground: ForegroundCallHost get() = surfaces.foreground

    private val _state = MutableStateFlow<IncomingCallUiState?>(null)

    private val _answerRequested = MutableStateFlow(false)

    /**
     * True between a notification's Answer press and the ringing screen acting on it.
     *
     * ⛔ THE NOTIFICATION CANNOT ANSWER DIRECTLY ANY MORE, BECAUSE ANSWERING NOW ASKS FOR THE
     * MICROPHONE FIRST, AND THE ONLY PLACE THAT CAN ASK IS THE COMPOSE TREE. The obvious host for
     * that request, `registerForActivityResult` on the Activity, is refused by lint
     * (`InvalidFragmentVersionForActivityResult`: `androidx.fragment` 1.1.0 rides in transitively
     * and the check does not know this Activity has no fragments), and raising the dependency is a
     * lockfile change across every configuration for a check that does not apply. So the Activity
     * records the press here and `IncomingCallHost`, which already owns a permission launcher for
     * the on-screen button, fulfils it: one answer path, one dialog, one place that knows about
     * permissions.
     */
    val answerRequested: StateFlow<Boolean> = _answerRequested.asStateFlow()

    /** ⚠️ Null when nothing is ringing and nothing is live. The activity draws nothing for null. */
    val state: StateFlow<IncomingCallUiState?> = _state.asStateFlow()

    private var session: InboundCallSession? = null
    private var ringTimeout: Job? = null
    private var mirror: Job? = null

    /**
     * A push says a call is ringing for this workspace.
     *
     * ⚠️ THE NOTIFICATION IS POSTED EVEN IF TELECOM REFUSED THE CALL. `startIncoming` swallows its
     * own failures — an unregistered self-managed account, an emergency call in progress — and the
     * heads-up notification is drawn independently, so the user can still answer. That is the
     * degraded-first design the plan asks for: Telecom buys politeness (audio focus, ringer
     * suppression), and losing it must not lose the call.
     */
    fun onIncomingCall(workspaceId: String, callId: String) {
        // ⛔ See the ⛔ on the class: dropped, not queued and not a replacement.
        if (_state.value != null) return
        _state.value = IncomingCallUiState(
            workspaceId = workspaceId,
            callId = callId,
            phase = IncomingCallPhase.RINGING,
        )
        telecom.startIncoming(onSystemAnswer = ::answer, onSystemHangUp = ::decline)
        notifier.showIncomingCall(workspaceId, callId)
        ringTimeout = scope.launch {
            delay(ringTimeoutMillis)
            // ⛔ IDENTICAL TO A DECLINE, DELIBERATELY AND VISIBLY. See the ⛔ on the class: the two
            // must be indistinguishable from outside, so they share the code path rather than
            // merely agreeing today.
            decline()
        }
    }

    /**
     * The user answered — from the notification, the ringing screen, or a system surface.
     *
     * ⚠️ IDEMPOTENT BY PHASE. The notification's Answer button, the on-screen button and a car head
     * unit can all fire, and the OS can deliver one twice; anything that is not still RINGING is
     * dropped rather than starting a second round trip.
     */
    fun answer() {
        val current = _state.value ?: return
        if (current.phase != IncomingCallPhase.RINGING) return
        clearRing()
        // ⛔ THE RING NOTIFICATION GOES AT THE PRESS, NOT AT THE CONNECT, AND THE GAP IS WHERE THE BUG
        // WAS. Leaving it up during the answer round trip leaves an Answer/Decline pair on screen for
        // a call that is already being joined: Decline would tear down a call whose credential has
        // been spent and whose rendezvous the server has already read, and Answer would be a second
        // answer. Worse, the notification is `setOngoing(true)` — deliberately, so a swipe cannot
        // dismiss a ringing call — so the user cannot get rid of it themselves. `finish` cancels it
        // too, which covers the answer that never lands; this covers the one that does.
        notifier.cancelIncomingCall()
        _state.value = current.copy(phase = IncomingCallPhase.ANSWERING)
        scope.launch {
            when (val outcome = repository.answer(current.workspaceId, current.callId)) {
                is AnswerOutcome.Joinable ->
                    join(outcome.session.url, outcome.session.token)
                // ⚠️ NOT AN ERROR, AND NOT WORDED AS ONE. The overwhelmingly common way to reach
                // this is that the caller hung up between the phone ringing and a thumb arriving.
                AnswerOutcome.AlreadyEnded ->
                    finish(FailureText(UiText.Resource(R.string.incoming_call_gone), retryable = false))
                is AnswerOutcome.NotAnswered -> finish(outcome.failure.toFailureText())
            }
        }
    }

    /**
     * The notification's Answer button.
     *
     * ⚠️ RECORDED, NOT ACTED ON, for the reason on [answerRequested]. Dropped unless the call is
     * still RINGING, so a stale pending intent for a call that already ended raises nothing and no
     * permission dialog appears over an idle screen.
     */
    fun requestAnswerFromNotification() {
        if (_state.value?.phase != IncomingCallPhase.RINGING) return
        _answerRequested.value = true
    }

    /** The ringing screen has taken the request; see [answerRequested]. */
    fun consumeAnswerRequest() {
        _answerRequested.value = false
    }

    /**
     * The user refused, the ring timed out, or the OS ended it for us.
     *
     * ⛔ NO SERVER CALL. See the ⛔ on the class — this is the whole reason the class doc leads with
     * it. ⚠️ Reachable from a RINGING phase and from an OS callback at any phase, so it tears the
     * live session down too rather than assuming there is not one.
     */
    fun decline() {
        if (_state.value == null) return
        hangUp()
    }

    /**
     * End an answered call, or abandon a ringing one.
     *
     * ⚠️ THE SAME EXIT FOR BOTH, BECAUSE THE TEARDOWN IS THE SAME: stop the ring timer, take the
     * notification away, tell Telecom, drop the foreground service and disconnect any media. A
     * ringing call simply has no media to disconnect.
     */
    fun hangUp() {
        finish(null)
    }

    /**
     * Dismiss an ended call and return the screen to whatever was underneath.
     *
     * ⛔ ENDED IS A STATE THE USER LEAVES, NOT ONE THAT EXPIRES. A call that vanished on hang-up
     * would answer "what happened / how long was that" with nothing — the same call `DialerViewModel`
     * makes about its own ended-call summary.
     */
    fun dismiss() {
        if (_state.value?.phase != IncomingCallPhase.ENDED) return
        // ⛔ THE MIRROR IS CANCELLED HERE AND NOT IN [finish], WHICH LOOKS LIKE THE OBVIOUS PLACE AND
        // IS NOT. `InboundCallSession.end` sets `ended` on its own state, and the screen renders the
        // ended summary — the duration, and the note that the call log is the record — from THAT
        // flag. Cutting the collector at `finish` would leave the last emission unread, so a call
        // that had just ended would keep drawing live mute/speaker/hang-up controls. It stays until
        // the user has dismissed what it was feeding.
        mirror?.cancel()
        mirror = null
        _state.value = null
    }

    fun toggleMicrophone() {
        session?.toggleMicrophone(scope)
    }

    fun toggleSpeaker() {
        session?.toggleSpeaker()
    }

    /**
     * Media is authorised: join the room, then let the OS and the user know.
     *
     * ⛔ THE FOREGROUND SERVICE STARTS ONLY AFTER THE JOIN SUCCEEDS. An FGS started on the press and
     * then abandoned is a `phoneCall`-typed service with no call, which on API 34+ is exactly the
     * shape the platform kills the process for.
     */
    private suspend fun join(url: String, token: String) {
        val live = sessions.create()
        session = live
        live.begin(scope)
        // ⚠️ No earlier mirror to cancel: a join follows a RINGING phase, which only a call arriving
        // on a cleared screen starts, and [dismiss] cancels and clears the mirror as it clears it.
        mirror = scope.launch {
            live.state.collect { call -> update { copy(call = call) } }
        }
        val joined = live.connect(url, token, scope)
        // ⛔ A HANG-UP CAN LAND WHILE THE JOIN IS IN FLIGHT: a headset button or the OS's own call
        // surface reaches [decline] at any phase, and [finish] has then already torn this call down
        // and cleared [session]. Acting on the join's outcome after that restarted the foreground
        // service for a call that had ended and painted it IN_CALL (or, on a failed join, replaced
        // the ended call's message with a media failure the user never had).
        if (session !== live) return
        if (!joined) {
            finish(FailureText(UiText.Resource(R.string.incoming_call_media_failed), retryable = false))
            return
        }
        foreground.setCallActive(true)
        update { copy(phase = IncomingCallPhase.IN_CALL) }
    }

    /**
     * Rewrite the call on screen, if there still is one.
     *
     * ⚠️ ONE PLACE FOR THE `?.`: [hangUp] is public and reaches [finish] with no call at all, and a
     * write then must not conjure one.
     */
    private fun update(transform: IncomingCallUiState.() -> IncomingCallUiState) {
        _state.value = _state.value?.transform()
    }

    /**
     * The one exit. ⚠️ Safe to call twice: [InboundCallSession.end] is itself one-way, and the
     * notification cancel and the Telecom disconnect are both idempotent.
     */
    private fun finish(message: FailureText?) {
        clearRing()
        notifier.cancelIncomingCall()
        foreground.setCallActive(false)
        val live = session
        session = null
        if (live == null) {
            // ⚠️ NOTHING JOINED, SO TELECOM IS STILL HOLDING A RINGING CONNECTION AND HAS TO BE TOLD
            // DIRECTLY. When a session exists it owns that call — `end` disconnects Telecom itself —
            // and telling it twice would race the registry's disarm against its own teardown.
            telecom.setDisconnected()
        } else {
            live.end {}
        }
        update { copy(phase = IncomingCallPhase.ENDED, message = message) }
    }

    private fun clearRing() {
        ringTimeout?.cancel()
        ringTimeout = null
    }

    internal companion object {
        /**
         * ⛔ LONGER THAN THE SERVER'S 25s RENDEZVOUS, ON PURPOSE. See the ⛔ on the class: timing out
         * first would withdraw the Answer button while the server would still have accepted one.
         */
        const val RING_TIMEOUT_MILLIS: Long = 30_000L
    }
}

/**
 * Everything an inbound call has to tell the DEVICE, as one parameter.
 *
 * ⛔ IT EXISTS BECAUSE FOUR OF THEM TOGETHER CROSSED detekt's CONSTRUCTOR CEILING, AND THE GROUPING
 * THAT RESULTED IS A REAL ONE RATHER THAN AN ARBITRARY BAG. [IncomingCallController] talks to
 * exactly two worlds: the SERVER (one repository, one route, one authenticated round trip) and the
 * DEVICE (the OS's call registry, the notification shade, the media stack, the process's foreground
 * status). Every member here is the second world, and each is an interface or a factory precisely so
 * the controller's transitions can be driven without any of it.
 *
 * ⚠️ NOT A "DEPENDENCIES" BAG TO GROW. If something arrives that is neither of those two worlds, it
 * belongs as its own parameter — the point of the bundle is that its members share a reason, not
 * that they share a constructor.
 */
internal data class IncomingCallSurfaces(
    /** The OS's view of the call: the ring, the ACTIVE latch, the teardown. */
    val telecom: TelecomBridge,
    /** The heads-up notification with Answer and Decline, and taking it away. */
    val notifier: PushNotifier,
    /** ⚠️ ONE ENGINE PER CALL. See [InboundCallSessionFactory]. */
    val sessions: InboundCallSessionFactory,
    /** ⛔ Started only after media is up, stopped on every terminal path. See [ForegroundCallHost]. */
    val foreground: ForegroundCallHost,
)

/**
 * Keeping the process alive for the duration of a call.
 *
 * ⛔ AN INTERFACE BECAUSE A FOREGROUND SERVICE IS UNTESTABLE HERE AND ITS ORDERING IS NOT. What
 * matters is that it starts only after media is up and stops on EVERY terminal path — a
 * `phoneCall`-typed foreground service outliving its call is what Android 14 kills a process for,
 * and one that never starts is a call the system may freeze mid-conversation. Both are assertions a
 * recorder can make; neither needs a real service.
 *
 * ⚠️ IT TAKES A BOOLEAN RATHER THAN start/stop, so "stop when it was never started" is expressible
 * and idempotent, which is what [IncomingCallController.finish] needs from every path.
 */
internal fun interface ForegroundCallHost {
    fun setCallActive(active: Boolean)
}
