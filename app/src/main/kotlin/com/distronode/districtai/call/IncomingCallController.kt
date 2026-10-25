package com.distronode.districtai.call

import com.distronode.districtai.R
import com.distronode.districtai.core.data.AnswerOutcome
import com.distronode.districtai.core.data.InboundCallRepository
import android.telecom.DisconnectCause
import com.distronode.districtai.push.PushNotifier
import com.distronode.districtai.push.RingCancelReason
import com.distronode.districtai.telecom.TelecomBridge
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.dialer.ActiveCallUiState
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 * ⛔ **A PERSON'S DECLINE IS TOLD TO THE SERVER, ONCE; A TIMEOUT AND A WITHDRAWN RING ARE NOT.**
 * (Sean, 2026-10-24, decisions log GitLab #2 note 4083623386, reversing the rule that neither was
 * sent.) `POST /api/district/actions/ring-app` blocks on a Redis rendezvous for ~25 seconds; when
 * every member it rang has declined, the server now ends the ring at once and the agent takes a
 * message instead of holding the caller in silence for the rest of the window. So the two exits are
 * kept apart on purpose:
 *
 * - [decline] is a PERSON: the ringing screen's Decline, the notification's Decline, or a hang-up
 *   from a system surface (lock screen, headset, car, watch) while the call is still RINGING. It
 *   sends `POST calls/{id}/decline` once, never retried; the route is idempotent and fails open.
 * - The ring timeout and [onRingCancelled] are NOT a person and send nothing. A timeout reported as
 *   a decline would end a ring another member was reaching for, and a withdrawn ring is the
 *   server's own news.
 *
 * ⚠️ THE TRADE THE OLD RULE PROTECTED AGAINST IS NOW ACCEPTED: a deliberate refusal is
 * distinguishable from a phone in a pocket, to the server and through the agent's wording to the
 * caller ("they can't take it right now"). The decision log records it.
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
 * PSTN, which is the correct outcome for a person who is already on the phone. ⛔ "LIVE" MEANS
 * RINGING, ANSWERING OR IN_CALL, NOT ENDED: an ENDED summary waits for a Dismiss the user may never
 * give (a missed ring in a pocket, a Decline from the notification), and treating it as live dropped
 * every later call until they opened the app.
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
     * `SoftphoneSessionFactory` make. The things it holds are one concept: everything that has
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
    private val ringer: IncomingRinger get() = surfaces.ringer

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

    /**
     * The calls whose rings the server has withdrawn, most recent last, at most [CANCELLED_MEMORY].
     *
     * ⚠️ FCM DOES NOT ORDER DELIVERIES, so a cancellation can arrive BEFORE the ring it cancels (the
     * ring push delayed in Doze, the cancel not). Without this a phone would then ring for thirty
     * seconds for a call another device answered a moment ago. Bounded because it only has to
     * outlive a delivery race, not the process.
     */
    private val cancelledCalls = ArrayDeque<String>()

    /**
     * Everything that watches one answered call: the session's engine collectors, its duration
     * ticker and the mirror onto [state].
     *
     * ⛔ A CHILD OF [scope], CANCELLED BY [dismiss], RATHER THAN [scope] ITSELF. [scope] is the
     * application's and never ends, so collectors started on it outlived their call: every answered
     * call left its session's two engine collectors running for the life of the process, holding the
     * engine they read.
     */
    private var callScope: CoroutineScope? = null

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
        // ⛔ See the ⛔ on the class: dropped, not queued and not a replacement, while a call is live.
        val current = _state.value
        if (current != null && current.phase != IncomingCallPhase.ENDED) return
        // ⚠️ A ring that arrives after its own cancellation. See [cancelledCalls].
        if (callId in cancelledCalls) return
        // ⚠️ AN ENDED CALL IS REPLACED, AND ITS MIRROR GOES WITH IT, exactly as [dismiss] would: the
        // old session emitting afterwards must not paint its finished call onto this ring.
        callScope?.cancel()
        callScope = null
        _state.value = IncomingCallUiState(
            workspaceId = workspaceId,
            callId = callId,
            phase = IncomingCallPhase.RINGING,
        )
        // ⚠️ A SYSTEM HANG-UP IS [decline], which sends only while the call is still RINGING: the
        // lock screen's Decline, a headset button and a car all reach this, and each is a person.
        // Once answered, the same callback is an ordinary hang-up and sends nothing.
        telecom.startIncoming(onSystemAnswer = ::answer, onSystemHangUp = ::decline)
        notifier.showIncomingCall(workspaceId, callId)
        // ⛔ THE APP RINGS ITS OWN SELF-MANAGED CALL; TELECOM DOES NOT. See [IncomingRinger]: 3.0.0
        // left this out and every inbound call rang silently. ⚠️ Started even when Telecom refused
        // the call, for the degraded-first reason above: the notification is still answerable. The
        // ringer itself declines to sound during a phone call (it is refused audio focus).
        ringer.start()
        ringTimeout = scope.launch {
            delay(ringTimeoutMillis)
            // ⛔ NOT [decline], DELIBERATELY AND VISIBLY. Nobody refused this call; nobody was there.
            // Reporting it as a decline would let the server end a ring another member was about to
            // answer. See the ⛔ on the class.
            hangUp()
        }
    }

    /**
     * The server says a ring is over: answered on another device, timed out, or the caller gone.
     *
     * ⛔ ONLY A CALL THAT IS STILL **RINGING HERE** IS ENDED. The cancellation goes to every device
     * the ring reached, the one that answered included, so on that device (ANSWERING or IN_CALL) it
     * must do nothing, and a cancellation for a call this device is not ringing (never rang, already
     * ended, or another call) is a no-op too. ⛔ AND NOTHING IS SENT BACK: a withdrawn ring is not a
     * person declining, whatever the reason says.
     *
     * ⚠️ The Telecom connection ends as ANSWERED_ELSEWHERE when another device answered, as
     * REJECTED when this person declined on another of their devices ([RingCancelReason.DECLINED],
     * which is not a missed call), and as MISSED otherwise (an unknown reason reads as WITHDRAWN;
     * see [RingCancelReason]), so the OS's own surfaces say what happened rather than that this user
     * hung up.
     *
     * ⛔ AND THE RINGING SCREEN IS WITHDRAWN, NOT TURNED INTO AN ENDED SUMMARY. The contract says
     * withdraw the incoming-call screen: there is nothing to summarise about a call this device never
     * took, and an ENDED screen would wait for a Dismiss the user owes nothing for.
     */
    fun onRingCancelled(callId: String, reason: RingCancelReason) {
        cancelledCalls.remove(callId)
        cancelledCalls.addLast(callId)
        if (cancelledCalls.size > CANCELLED_MEMORY) cancelledCalls.removeFirst()
        val current = _state.value ?: return
        if (current.callId != callId || current.phase != IncomingCallPhase.RINGING) return
        val cause = when (reason) {
            RingCancelReason.ANSWERED -> DisconnectCause.ANSWERED_ELSEWHERE
            RingCancelReason.DECLINED -> DisconnectCause.REJECTED
            RingCancelReason.TIMEOUT, RingCancelReason.CALL_ENDED, RingCancelReason.WITHDRAWN ->
                DisconnectCause.MISSED
        }
        finish(null, cause)
        dismiss()
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
            val outcome = repository.answer(current.workspaceId, current.callId)
            // ⛔ A HANG-UP CAN LAND DURING THE ANSWER ROUND TRIP, before [join] has a session to
            // compare: a headset or the OS's own call surface reaches [decline] at any phase. Acting
            // on the outcome after that joined the room with the microphone on, started the
            // `phoneCall` service and repainted IN_CALL for a call the user had ended; a late refusal
            // would end whatever call rang next. Nothing is owed to the server: its rendezvous has
            // already been read, and a hang-up after the press is not a decline (see [decline]).
            val now = _state.value
            if (now == null || now.callId != current.callId || now.phase != IncomingCallPhase.ANSWERING) {
                return@launch
            }
            when (outcome) {
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
     * A person refused the call: the ringing screen, the notification's Decline, or a system surface.
     *
     * ⛔ SENDS `calls/{id}/decline` ONLY WHILE THE CALL IS RINGING, ONCE, AND NEVER RETRIED. See the
     * ⛔ on the class. The request goes out on the application scope before the teardown, so it
     * outlives the screen or the receiver that pressed it, and it is bounded by
     * [DECLINE_BUDGET_MILLIS] so a dead network cannot hold the notification receiver open.
     *
     * ⚠️ Reachable from a RINGING phase and from an OS callback at any phase, so it tears the live
     * session down too rather than assuming there is not one. Past RINGING it is a hang-up and sends
     * nothing: the call was answered, so there is no ring left to decline.
     *
     * @return the decline request in flight, or null when nothing was sent. ⚠️ Read only by
     *   [IncomingCallActionReceiver], which holds its broadcast open until the request is done.
     */
    fun decline(): Job? {
        val current = _state.value ?: return null
        val sent = if (current.phase == IncomingCallPhase.RINGING) {
            scope.launch {
                withTimeoutOrNull(DECLINE_BUDGET_MILLIS) {
                    repository.decline(current.workspaceId, current.callId)
                }
            }
        } else {
            null
        }
        hangUp()
        return sent
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
        // ⛔ THE MIRROR (WITH THE REST OF [callScope]) IS CANCELLED HERE AND NOT IN [finish], WHICH
        // LOOKS LIKE THE OBVIOUS PLACE AND IS NOT. `InboundCallSession.end` sets `ended` on its own
        // state, and the screen renders the ended summary (the duration, and the note that the call
        // log is the record) from THAT flag. Cutting the collector at `finish` would leave the last emission unread, so a call
        // that had just ended would keep drawing live mute/speaker/hang-up controls. It stays until
        // the user has dismissed what it was feeding.
        callScope?.cancel()
        callScope = null
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
        // ⚠️ No earlier call scope to cancel: a join follows a RINGING phase, which only a call
        // arriving on a cleared or ENDED screen starts, and both [dismiss] and [onIncomingCall]
        // cancel and clear it on the way.
        val watch = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        callScope = watch
        live.begin(watch)
        watch.launch {
            live.state.collect { call -> update { copy(call = call) } }
        }
        val joined = live.connect(url, token, watch)
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
    private fun finish(message: FailureText?, cause: Int = DisconnectCause.LOCAL) {
        clearRing()
        notifier.cancelIncomingCall()
        foreground.setCallActive(false)
        val live = session
        session = null
        if (live == null) {
            // ⚠️ NOTHING JOINED, SO TELECOM IS STILL HOLDING A RINGING CONNECTION AND HAS TO BE TOLD
            // DIRECTLY. When a session exists it owns that call — `end` disconnects Telecom itself —
            // and telling it twice would race the registry's disarm against its own teardown.
            telecom.setDisconnected(cause)
        } else {
            live.end {}
        }
        update { copy(phase = IncomingCallPhase.ENDED, message = message) }
    }

    /**
     * The phone stops ringing: the timeout and the ringer.
     *
     * ⛔ THE RINGER STOPS HERE AND ONLY HERE, AND THIS IS REACHED FROM EVERY PATH THAT ENDS A RING.
     * [answer] calls it at the press; [finish] (decline, the timeout, a hang-up from a system surface
     * or a headset, a refused answer, a caller who hung up first, a failed join) calls it on the way
     * out. One place means a new exit cannot forget the sound. ⚠️ There is no push for "the caller
     * hung up" or "answered on another device"; the server sends only `incoming_call`, so those two
     * end the ring at the timeout, or at the Answer press when the answer route says the call is gone.
     */
    private fun clearRing() {
        ringTimeout?.cancel()
        ringTimeout = null
        ringer.stop()
    }

    internal companion object {
        /**
         * ⛔ LONGER THAN THE SERVER'S 25s RENDEZVOUS, ON PURPOSE. See the ⛔ on the class: timing out
         * first would withdraw the Answer button while the server would still have accepted one.
         */
        const val RING_TIMEOUT_MILLIS: Long = 30_000L

        /** ⚠️ How many withdrawn rings [cancelledCalls] remembers; a delivery race, not a history. */
        const val CANCELLED_MEMORY: Int = 16

        /**
         * ⚠️ HOW LONG A DECLINE MAY TAKE BEFORE IT IS ABANDONED. Inside the ten seconds a foreground
         * broadcast gets (a background one gets more), because the notification's Decline holds its
         * broadcast open for the request. An abandoned decline costs the caller the rest of the ring, which is what every
         * decline cost before the route existed.
         */
        const val DECLINE_BUDGET_MILLIS: Long = 8_000L
    }
}

/**
 * Everything an inbound call has to tell the DEVICE, as one parameter.
 *
 * ⛔ IT EXISTS BECAUSE FOUR OF THEM TOGETHER CROSSED detekt's CONSTRUCTOR CEILING, AND THE GROUPING
 * THAT RESULTED IS A REAL ONE RATHER THAN AN ARBITRARY BAG. [IncomingCallController] talks to
 * exactly two worlds: the SERVER (one repository, one route, one authenticated round trip) and the
 * DEVICE (the OS's call registry, the notification shade, the media stack, the process's foreground
 * status, the ringer). Every member here is the second world, and each is an interface or a
 * factory precisely so the controller's transitions can be driven without any of it.
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
    /** ⛔ The ringtone and the vibration: started with the ring, stopped with it. See [IncomingRinger]. */
    val ringer: IncomingRinger,
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
