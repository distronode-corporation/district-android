package com.distronode.districtai.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.data.isLive
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.CallSummary
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the Live calls screen reads from: the call log for the list, supervise for the actions. */
data class LiveCallsSources(val supervise: SuperviseRepository, val calls: CallsRepository)

/** The Live calls list. */
sealed interface LiveCallList {
    data object Loading : LiveCallList

    /**
     * @property refreshFailure the last read failed after this list loaded. ⛔ THE LIST STAYS: the
     *   card of a call being listened to or taken over carries the only control that ends that
     *   session, so a failed refresh is a notice above the cards, cleared by the next good read.
     */
    data class Loaded(val calls: List<CallSummary>, val refreshFailure: FailureText? = null) : LiveCallList

    /** Nothing has loaded yet and the read failed. ⛔ Never drawn as "no calls": we could not look. */
    data class Failed(val failure: FailureText) : LiveCallList
}

/**
 * The Live calls screen: the calls in progress, and listening to, whispering into and taking over
 * one of them (the web's Live page, `useLiveDashboard`).
 *
 * ⛔ THE LIST IS THE CALL LOG, KEPT TO THE LIVE CALLS ([CallsRepository.liveCalls]), and the
 * workspace socket keeps it current the way the web's Live page does: a call starting or changing
 * reads the list again, a call ending drops its row at once. `calls/active` is never read (no
 * contract, whole database rows).
 *
 * ⛔ WHISPER AND TAKE OVER ARE OFFERED ONLY ON A CALL WHOSE `receptionistOnCall` IS TRUE, AND ONLY
 * TO A WRITER, and are refused here as well as hidden. A team member's own call has no
 * receptionist (the server refuses both with `call_has_no_receptionist`, which stays the
 * fallback), and the server refuses a viewer. Listening is open to all three roles, as
 * `calls/token` is.
 *
 * ⛔ LEAVING THE SCREEN LEAVES THE CALL. [onScreenStarted] false stops listening or hands the call
 * back, as closing the web page does: no foreground service holds the room, so a call left running
 * behind a stopped screen would be a supervisor (and on a takeover, a live microphone) in a
 * customer's call with nothing on screen to end it. ⚠️ EXCEPT A ROTATION, which is a stop too
 * (MainActivity is recreated): the session is held here, above the activity, and survives it.
 * The screen stays on while a session is active, so a screen timeout never stops it either
 * ([SupervisionLifecycle]).
 *
 * @param feed makes the telemetry socket in the scope it is given; null where there is none (a test
 *   about the rest of the screen).
 */
class LiveCallsViewModel(
    sources: LiveCallsSources,
    private val workspaceId: String,
    private val canWrite: Boolean,
    engineFactory: CallEngineFactory,
    feed: ((CoroutineScope, (LiveFeedEvent) -> Unit) -> LiveCallsFeed)?,
    engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    private val repository = sources.supervise
    private val calls = sources.calls

    private val _list = MutableStateFlow<LiveCallList>(LiveCallList.Loading)
    val list: StateFlow<LiveCallList> = _list.asStateFlow()

    private val supervisor = LiveSupervisor(repository, workspaceId, engineFactory, viewModelScope, engineScope)
    val supervision: StateFlow<LiveSupervisor.State> = supervisor.state

    private val whisperDesk = WhisperDesk(repository, workspaceId, viewModelScope)
    val whispers = whisperDesk.boxes

    /** Ask, cancel and confirm a takeover, and the microphone's answer. */
    val takeOver = TakeOverFlow(supervisor, ::writable)
    val prompt: StateFlow<LiveCallsPrompt> = takeOver.prompt

    private val socket: LiveCallsFeed? = feed?.invoke(viewModelScope, ::onFeed)
    private var loading: Job? = null
    private var reloadAfter = false

    /**
     * Calls the socket said ended. ⚠️ A read that was already in flight is older than that event, so
     * its answer may still list the call; these stay off the list whatever a read says.
     */
    private val ended = mutableSetOf<String>()

    init {
        refresh()
    }

    /**
     * Read the list. ⚠️ A refresh asked for while one runs runs once more after it, never alongside.
     *
     * ⛔ A SESSION THAT ENDED TAKES THE CALL WITH IT. [sessionChanged] (a sign-in or a sign-out
     * while this screen is up) leaves first, and a read answered `Unauthorized` (the session was
     * revoked) leaves too: no supervisor, and no live microphone, stays in a customer's call under
     * credentials that no longer hold.
     */
    fun refresh(sessionChanged: Boolean = false) {
        if (sessionChanged) leave()
        if (loading?.isActive == true) {
            reloadAfter = true
            return
        }
        loading = viewModelScope.launch {
            do {
                reloadAfter = false
                when (val result = calls.liveCalls(workspaceId)) {
                    is ApiResult.Success -> show(withSupervised(result.value).filterNot { it.id in ended })
                    is ApiResult.Failure -> {
                        if (result is ApiResult.Unauthorized) leave()
                        val failure = result.toFailureText()
                        val current = _list.value
                        _list.value = if (current is LiveCallList.Loaded) {
                            current.copy(refreshFailure = failure)
                        } else {
                            LiveCallList.Failed(failure)
                        }
                    }
                }
            } while (reloadAfter)
        }
    }

    /**
     * The screen started or stopped.
     *
     * @param recreating the stop is a configuration change (a rotation): the activity comes straight
     *   back and this ViewModel, the engine and the room survive it, so the session and a pending
     *   microphone answer are kept. ⛔ Any other stop (the app backgrounded, the screen left) leaves.
     */
    fun onScreenStarted(started: Boolean, recreating: Boolean = false) {
        if (started) {
            socket?.start()
            return
        }
        socket?.stop()
        if (recreating) return
        leave()
    }

    fun listen(callId: String) = supervisor.listen(callId)

    /**
     * Stop listening, or hand a taken-over call back.
     *
     * ⚠️ A takeover being confirmed, or waiting on the microphone's answer, goes with it: a grant
     * arriving after Stop must not take the call over.
     */
    fun leave() {
        takeOver.reset()
        supervisor.leave()
    }

    fun setWhisperDraft(callId: String, text: String) {
        if (writable(callId)) whisperDesk.setDraft(callId, text)
    }

    fun sendWhisper(callId: String) {
        if (writable(callId)) whisperDesk.send(callId)
    }

    override fun onCleared() {
        socket?.stop()
        supervisor.release()
    }

    private fun onFeed(event: LiveFeedEvent) {
        when (event) {
            LiveFeedEvent.Opened, LiveFeedEvent.CallsChanged -> refresh()
            // ⚠️ Dropped at once, from the envelope alone: the row is gone without a read.
            is LiveFeedEvent.CallEnded -> {
                ended += event.callId
                (_list.value as? LiveCallList.Loaded)?.let { loaded ->
                    show(loaded.calls.filterNot { it.id == event.callId }, loaded.refreshFailure)
                }
            }
            is LiveFeedEvent.Receipt -> whisperDesk.receipt(event.callId, event.data)
        }
    }

    /**
     * Put [rows] on screen, and leave the session of a call that is no longer among them.
     *
     * ⛔ A CARD IS THE ONLY CONTROL THAT ENDS ITS SESSION ("Stop listening", "Hand back"), so a call
     * that leaves the list (it ended, or a read no longer counts it as live) takes its session with
     * it, request in flight included: no supervisor and no microphone stays in a call nothing on
     * screen can reach. A call that really ended closes its room anyway; this makes it immediate.
     */
    private fun show(rows: List<CallSummary>, refreshFailure: FailureText? = null) {
        _list.value = LiveCallList.Loaded(rows, refreshFailure)
        val listed = rows.map { it.id }.toSet()
        val state = supervisor.state.value
        val gone = listOfNotNull(state.callId, state.requestingCallId).any { it !in listed }
        if (gone) leave()
    }

    /**
     * [rows], plus the supervised call when the read did not list it but the call is still live.
     *
     * ⛔ A CALL MISSING FROM A READ IS NOT A CALL THAT ENDED. The list stops reading at a bound
     * ([CallsRepository.liveCalls]), so a call can be live and unlisted; leaving on its absence alone
     * ended a listen or a takeover in a busy workspace. So the call being listened to, taken over or
     * requested is read by id first (the contracted call detail, the same row the list carries):
     * - still live: its row stays, after the listed ones (it is older than all of them);
     * - over, or gone (`NotFound`): it is left out, and [show] leaves its session;
     * - the read failed: the card it had stays, as a failed list read keeps its cards, because the
     *   card holds the only control that ends the session. A call that really ended closes its room,
     *   which ends the session anyway. ⚠️ Except `Unauthorized`: the session no longer holds.
     */
    private suspend fun withSupervised(rows: List<CallSummary>): List<CallSummary> {
        val state = supervisor.state.value
        val missing = listOfNotNull(state.callId, state.requestingCallId)
            .distinct()
            .filter { id -> rows.none { it.id == id } }
        if (missing.isEmpty()) return rows
        val shown = (_list.value as? LiveCallList.Loaded)?.calls.orEmpty()
        val kept = missing.mapNotNull { id ->
            when (val result = calls.detail(workspaceId, id)) {
                is ApiResult.Success -> result.value.takeIf { it.isLive }
                is ApiResult.NotFound, is ApiResult.Unauthorized -> null
                is ApiResult.Failure -> shown.firstOrNull { it.id == id }
            }
        }
        return rows + kept
    }

    /** A writer, and a call the receptionist is on. */
    private fun writable(callId: String): Boolean {
        val row = (_list.value as? LiveCallList.Loaded)?.calls?.firstOrNull { it.id == callId }
        return canWrite && row != null && row.receptionistOnCall
    }

    companion object {
        fun factory(
            repository: SuperviseRepository,
            calls: CallsRepository,
            workspaceId: String,
            canWrite: Boolean,
            engineFactory: CallEngineFactory,
            feed: ((CoroutineScope, (LiveFeedEvent) -> Unit) -> LiveCallsFeed)?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LiveCallsViewModel(LiveCallsSources(repository, calls), workspaceId, canWrite, engineFactory, feed)
            }
        }
    }
}
