package com.distronode.districtai.ui.dialer

import com.distronode.districtai.R
import com.distronode.districtai.core.media.CallConnectionState
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.ui.FailureText

/**
 * The dialler, and the one call it may have in flight.
 *
 * ⛔ THE KEYPAD AND THE CALL ARE ONE DESTINATION'S STATE, NOT TWO DESTINATIONS, AND THAT IS A
 * SAFETY DECISION RATHER THAN A LAYOUT ONE. An in-call destination reached by navigation would be
 * restored from the back stack after process death, and the effect that starts a call would run
 * again — placing a SECOND billable call to someone whose first call died with the process, with
 * no user action. Every alternative guard (a flag in the ViewModel, a marker in the route) is
 * recreated or restored along with the destination and so cannot tell a fresh entry from a
 * restored one. Holding the call in [call] means a killed process comes back to an idle keypad,
 * which is exactly what the truth is: the socket died, the participant left, the call is over.
 *
 * ⛔ [callbacks] MUST SURVIVE A FAILED READ WITHOUT DISABLING THE KEYPAD, the same independence
 * the rooms lobby keeps between its history and its join form. They are unrelated server surfaces
 * — one is the call log, the other needs only a number — so an outage of the log must not become
 * an inability to place a call.
 */
data class DialerUiState(
    /**
     * ⛔ THE ROLE GATE, AND IT IS PRESENCE RATHER THAN WORDING. `POST /api/district/calls/dial`
     * excludes `viewer`, so a viewer who reached this screen would meet a 403 on the one thing it
     * exists to do. The entry into the screen is hidden for the same reason; this flag is the
     * second half, for a destination reached some other way (a restored back stack, a deep link).
     *
     * ⚠️ FAILS CLOSED. A role the route did not recognise is `null`, and `null != VIEWER` is TRUE
     * — so the check that sets this is `role != null && role.canMutate`, never `role != VIEWER`.
     */
    val canDial: Boolean = false,
    /**
     * What the field shows: the number in the local form of [country] (dialler design A).
     *
     * ⛔ THIS TEXT NEVER TRAVELS. What the dial API receives is [DialPlan.Reading.e164], read from it
     * in [country] and shown in full under the field ("Rings Canada · +1 416 555 0199") before Call.
     * The server normalises its own copy, and an E.164 number passes through that unchanged, so the
     * Do-Not-Call check and the dial run against the number the operator was shown.
     *
     * ⚠️ REWRITTEN AS THE OPERATOR TYPES, UNLIKE BEFORE DESIGN A, and safely: every form ends in a
     * digit and reads back to the same digits (`DialPlanTest` pins both), so no keystroke is lost.
     */
    val entry: String = "",
    /**
     * The country on the button, which the field is read in, or null until one is picked or a `+`
     * names one. A pasted `+44…` moves it to the United Kingdom.
     */
    val country: String? = null,
    /** The workspace's own country, from its numbers, or null. First in the Suggested group. */
    val homeCountry: String? = null,
    /**
     * The operator stopped typing: the field lost focus, Done was pressed, or a Call was refused.
     *
     * ⛔ A WRONG LENGTH IS SAID ONLY FROM THEN UNTIL THE NEXT EDIT, as the web, Mac and iOS dialers
     * do. Every ten-digit number passes through eight and nine digits on the way, so a sentence said
     * while typing would flash on every number; until then Call is simply off.
     */
    val lengthChecked: Boolean = false,
    val callbacks: CallbacksState = CallbacksState.Loading,
    /**
     * Why the last dial did not happen, or null.
     *
     * ⚠️ SHOWN ON THE KEYPAD RATHER THAN ON A CALL SCREEN, because a refused dial produced no
     * call — there is nothing to show it over. It is cleared the moment the entry changes: a
     * message about a number the operator has already moved on from is worse than none.
     */
    val refusal: FailureText? = null,
    /** The live call, or null when the dialler is idle. */
    val call: ActiveCallUiState? = null,
    /**
     * Bumped to ask the destination to launch the microphone permission request.
     *
     * ⛔ A COUNTER, NOT A BOOLEAN, AND THE DIFFERENCE IS WHETHER A SECOND DIAL WORKS. A boolean
     * that is already `true` produces no state change on the next dial, so `LaunchedEffect` keyed
     * on it would not re-run and the second call would silently never ask — which on a device
     * where the user denied the first request looks like a dial button that does nothing. It is
     * also why this is not simply requested when the screen opens: a permission dialog with no
     * visible reason is the one users deny permanently, and the reason here is a call they just
     * asked for.
     */
    val microphoneRequest: Int = 0,
) {
    /**
     * The field, read in the country on the button.
     *
     * ⚠️ DERIVED ON READ RATHER THAN STORED. It is a pure function of [entry] and [country], and a
     * cached copy would be one more thing to keep in step.
     */
    val reading: DialPlan.Reading
        get() = DialPlan.read(entry, country)

    /**
     * ⛔ ONE GATE: A COMPLETE NUMBER IN A KNOWN SHAPE. [DialPlan] sets an E.164 number only for a
     * length the country issues and at least [DialPlan.MINIMUM_DIGITS] digits, the route's own floor
     * after normalising, so the button cannot light for a number the server would answer with a 400,
     * nor for `+4165550123` (eight digits after Switzerland's `+41`, where every number has nine).
     * An emergency number never gets one.
     */
    val canPlaceCall: Boolean
        get() = canDial && call == null && reading.e164 != null

    /**
     * Why Call is off, in the operator's terms, or null when there is nothing to correct.
     *
     * ⚠️ AN EMPTY FIELD RETURNS null because the hint already occupies that moment, and a number still
     * being typed is not a mistake. See [lengthChecked] for when a wrong length is said.
     */
    val entryProblem: Int?
        get() {
            val read = reading
            return when (read.status) {
                DialPlan.Status.EMPTY, DialPlan.Status.READY -> null
                DialPlan.Status.NEEDS_COUNTRY -> R.string.dialer_entry_hint_none
                // ⚠️ AN INCOMPLETE NUMBER IS A WRONG LENGTH ONLY WITH A COUNTRY AND DIGITS TO MEASURE:
                // a lone `+`, a trunk `0` or an unknown code (said by the line above) is not.
                DialPlan.Status.TYPING ->
                    R.string.dialer_entry_wrong_length.takeIf {
                        lengthChecked && read.iso != null && read.nationalNumber.isNotEmpty()
                    }
                DialPlan.Status.TOO_LONG -> R.string.dialer_entry_wrong_length.takeIf { lengthChecked }
                // ⛔ THE ONLY REFUSAL HERE THAT IS NOT A CORRECTION: District cannot place an emergency
                // call, and the server's own sentence for it sends the operator to the phone's dialler.
                DialPlan.Status.EMERGENCY -> R.string.api_error_emergency_number
            }
        }

    /**
     * The field after an edit: read in the country on the button, written back in its local form,
     * with the button moved when the number names its own country.
     *
     * ⛔ A `+` (OR `011`, `00`) NUMBER SWITCHES THE FLAG and the field drops to the local form, so
     * the flag always says where the digits in the field ring. A +1 number flips between Canada, the
     * United States and the Caribbean by its area code.
     *
     * ⚠️ EVERY EDIT CLEARS THE PREVIOUS REFUSAL AND THE LENGTH CHECK: a message about a number the
     * operator has already changed is worse than none.
     */
    fun typed(value: String): DialerUiState {
        val read = DialPlan.read(value, country)
        return copy(entry = read.national, country = read.iso ?: country, refusal = null, lengthChecked = false)
    }

    /**
     * The operator chose a country in the list. ⚠️ THE DIGITS ALREADY TYPED ARE KEPT and read again
     * in the new country; a pick counts as an edit.
     *
     * ⚠️ A FIELD STILL SHOWING A `+` (a code with no digit after it yet, or a code no country uses)
     * gives up the plus and its code and keeps only the digits after a known one, so picking a
     * country means "this number is in that country"; a plus with no known code is cleared, or it
     * would ring nowhere beside the country picked. district-core-rust `entry_for_pick` (#63).
     */
    fun picked(iso: String): DialerUiState {
        val read = reading
        val kept = when {
            !entry.trimStart().startsWith("+") -> entry.filter(DialPlan::isDigit)
            read.iso != null -> read.nationalNumber
            else -> ""
        }
        return copy(country = iso).typed(kept)
    }

    /**
     * Record the workspace's country, and move the button to it only if nothing has moved it since
     * the keypad opened on [openedOn] and the field is still empty.
     */
    fun withHome(home: String, openedOn: String?): DialerUiState =
        copy(homeCountry = home, country = if (entry.isEmpty() && country == openedOn) home else country)
}

/** The call-back list: the workspace's recent INBOUND calls. See `CallsRepository.recentCallbacks`. */
sealed interface CallbacksState {

    data object Loading : CallbacksState

    /**
     * @param calls ⚠️ MAY BE EMPTY ON A HEALTHY WORKSPACE — nobody has called in, or the last page
     *   of the log was all outbound. It renders as an explanatory empty state, never as a failure.
     */
    data class Ready(val calls: List<CallSummary>) : CallbacksState

    data class Failed(val failure: FailureText) : CallbacksState
}

/**
 * One outbound call in progress.
 *
 * ⛔ [answered] IS DERIVED FROM PARTICIPANTS AND FROM NOTHING ELSE, WHICH IS THE ONLY SIGNAL THIS
 * CLIENT HAS. There is no signalling channel for ring-versus-answer: `POST calls/dial` returns as
 * soon as the carrier accepts the dial, deliberately, so the app can be in the room hearing call
 * progress while the far end is still ringing. What changes at answer time is that the SIP
 * participant JOINS THE ROOM — LiveKit's SIP bridge adds the callee as a participant when the call
 * is picked up — so `participants.isNotEmpty()` is the answer event. Until then, `Connected` means
 * "we are in the room", not "somebody is on the line", and a UI that started a duration timer on
 * `Connected` would count the ringing as conversation.
 *
 * ⚠️ AND IT IS ONE-WAY. Once answered, a participant list that empties means the callee HUNG UP,
 * not that they were never there — so [answered] is latched rather than recomputed, and the
 * emptying is handled as the call ending.
 *
 * ⛔ [elapsedSeconds] COUNTS FROM THE ANSWER, NOT FROM THE DIAL, and it is what the operator will
 * compare against an invoice. The platform bills answered time; a timer started at dial would
 * overstate every call by its ring duration and make the app the thing that looks wrong.
 */
data class ActiveCallUiState(
    /** ⚠️ As typed. The server normalised its own copy; this is what the operator will recognise. */
    val number: String,
    val connection: CallConnectionState = CallConnectionState.Connecting,
    /** ⛔ Latched at the first participant. See the class doc. */
    val answered: Boolean = false,
    val elapsedSeconds: Int = 0,
    /**
     * ⚠️ TRUE FROM THE START, unlike a meeting. A softphone that joined muted would put the
     * operator on a call the callee cannot hear, having just chosen to make it — and unlike a
     * meeting there is no room full of people to notice. The camera is not the mirror image of
     * this: it is never touched at all, because the softphone publishes no video.
     */
    val micEnabled: Boolean = true,
    /**
     * ⚠️ WHAT THE APP ASKED FOR, NOT WHAT THE DEVICE IS DOING. The engine exposes no route to read
     * back — see the audio-routing note on `CallEngine` — so this is honest for a toggle and would
     * not be honest as a status readout.
     */
    val speakerOn: Boolean = false,
    /** ⚠️ Set once, at hang-up. [elapsedSeconds] freezes with it and is the call's billed length. */
    val ended: Boolean = false,
) {
    /**
     * What the screen draws.
     *
     * ⛔ COMPUTED IN ONE PLACE SO THE LABEL AND THE TIMER CANNOT DISAGREE. Two of these phases look
     * identical in the connection state alone — [CallPhase.RINGING] and [CallPhase.IN_CALL] are
     * both `Connected` — and deriving them separately in the screen is how a timer starts over
     * "Calling…".
     */
    val phase: CallPhase
        get() = when {
            ended -> CallPhase.ENDED
            connection is CallConnectionState.Failed -> CallPhase.FAILED
            // ⚠️ A remote Disconnected BEFORE the answer is the callee declining or the dial
            // failing at the carrier; after it, it is the far end hanging up. Both end the call,
            // and the duration distinguishes them for the operator without a second phase.
            connection is CallConnectionState.Disconnected -> CallPhase.ENDED
            answered -> CallPhase.IN_CALL
            connection is CallConnectionState.Connected -> CallPhase.RINGING
            else -> CallPhase.DIALING
        }

    /**
     * ⚠️ A BANNER OVER A LIVE CALL, NEVER A PHASE. A phone handing over between wifi and its radio
     * reconnects routinely and the SDK recovers by itself; treating it as a failure would hang up
     * calls that were about to survive the ordinary event this state exists to describe.
     */
    val reconnecting: Boolean get() = connection is CallConnectionState.Reconnecting
}

/**
 * ⛔ [RINGING] EXISTS BECAUSE "IN THE ROOM" AND "ON THE PHONE" ARE DIFFERENT FACTS. Collapsing it
 * into [IN_CALL] would show a running duration while the callee's handset is still ringing, and
 * the number it showed would not be the number they are billed for.
 */
enum class CallPhase {
    /** Placing the call: the request is out, or the room is being joined. */
    DIALING,

    /** In the room, waiting for the callee to pick up. ⚠️ No timer runs here. */
    RINGING,

    /** The callee is on the line. */
    IN_CALL,

    /** Over, with a final duration. */
    ENDED,

    /** The media session could not be established. ⚠️ Distinct from a refused dial, which never got here. */
    FAILED,
}
