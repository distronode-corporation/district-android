package com.distronode.districtai.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.data.SuperviseRepository
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
 * customer's call with nothing on screen to end it.
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

    /** Read the list. ⚠️ A refresh asked for while one runs runs once more after it, never alongside. */
    fun refresh() {
        if (loading?.isActive == true) {
            reloadAfter = true
            return
        }
        loading = viewModelScope.launch {
            do {
                reloadAfter = false
                when (val result = calls.liveCalls(workspaceId)) {
                    is ApiResult.Success -> show(result.value.filterNot { it.id in ended })
                    is ApiResult.Failure -> _list.value = (_list.value as? LiveCallList.Loaded)
                        ?.copy(refreshFailure = result.toFailureText())
                        ?: LiveCallList.Failed(result.toFailureText())
                }
            } while (reloadAfter)
        }
    }

    fun onScreenStarted(started: Boolean) {
        if (started) {
            socket?.start()
            return
        }
        socket?.stop()
        takeOver.reset()
        supervisor.leave()
    }

    fun listen(callId: String) = supervisor.listen(callId)

    /** Stop listening, or hand a taken-over call back. */
    fun leave() = supervisor.leave()

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
        if (gone) supervisor.leave()
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
