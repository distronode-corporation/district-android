package com.distronode.districtai.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SuperviseRepository
import com.distronode.districtai.core.media.CallEngineFactory
import com.distronode.districtai.core.model.ActiveCall
import com.distronode.districtai.core.network.ApiResult
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

/** The Live calls list. */
sealed interface LiveCallList {
    data object Loading : LiveCallList

    data class Loaded(val calls: List<ActiveCall>) : LiveCallList

    /** ⛔ Never drawn as "no calls": we could not look. */
    data class Failed(val failure: FailureText) : LiveCallList
}

/**
 * The take-over confirmation and the microphone request.
 *
 * @property microphoneRequest ⚠️ A COUNTER, like the dialer's: a permission launcher cannot be
 *   called from a ViewModel, and a flag would not fire again after a denial.
 */
data class LiveCallsPrompt(
    val confirmingTakeover: String? = null,
    val microphoneRequest: Int = 0,
)

/**
 * The Live calls screen: the calls in progress, and listening to, whispering into and taking over
 * one of them (the web's Live page, `useLiveDashboard`).
 *
 * ⛔ WHISPER AND TAKE OVER ARE NEVER OFFERED ON A TEAM MEMBER'S OWN CALL, OR TO A VIEWER, and are
 * refused here as well as hidden. A `direct_` room has no receptionist (the server refuses both
 * with `call_has_no_receptionist`), and the server refuses a viewer. Listening is open to all three
 * roles, as `calls/token` is.
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
    private val repository: SuperviseRepository,
    private val workspaceId: String,
    private val canWrite: Boolean,
    engineFactory: CallEngineFactory,
    feed: ((CoroutineScope, (LiveFeedEvent) -> Unit) -> LiveCallsFeed)?,
    engineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {

    private val _list = MutableStateFlow<LiveCallList>(LiveCallList.Loading)
    val list: StateFlow<LiveCallList> = _list.asStateFlow()

    private val _prompt = MutableStateFlow(LiveCallsPrompt())
    val prompt: StateFlow<LiveCallsPrompt> = _prompt.asStateFlow()

    private val supervisor = LiveSupervisor(repository, workspaceId, engineFactory, viewModelScope, engineScope)
    val supervision: StateFlow<LiveSupervisor.State> = supervisor.state

    private val whisperDesk = WhisperDesk(repository, workspaceId, viewModelScope)
    val whispers = whisperDesk.boxes

    private val socket: LiveCallsFeed? = feed?.invoke(viewModelScope, ::onFeed)
    private var loading: Job? = null
    private var reloadAfter = false
    private var pendingTakeover: String? = null

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
                _list.value = when (val result = repository.activeCalls(workspaceId)) {
                    is ApiResult.Success -> LiveCallList.Loaded(result.value)
                    is ApiResult.Failure -> LiveCallList.Failed(result.toFailureText())
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
        _prompt.value = _prompt.value.copy(confirmingTakeover = null)
        pendingTakeover = null
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

    /** Open the in-page confirmation. ⛔ Never a takeover on its own: the microphone goes live. */
    fun askTakeOver(callId: String) {
        if (writable(callId)) _prompt.value = _prompt.value.copy(confirmingTakeover = callId)
    }

    fun cancelTakeOver() {
        _prompt.value = _prompt.value.copy(confirmingTakeover = null)
    }

    /** Confirmed: ask for the microphone, and take over once it is granted. */
    fun confirmTakeOver() {
        val callId = _prompt.value.confirmingTakeover ?: return
        pendingTakeover = callId
        _prompt.value = LiveCallsPrompt(microphoneRequest = _prompt.value.microphoneRequest + 1)
    }

    /** ⚠️ A result with nothing pending (the screen was left meanwhile) is dropped. */
    fun onMicrophonePermissionResult(granted: Boolean) {
        val callId = pendingTakeover ?: return
        pendingTakeover = null
        if (granted) {
            supervisor.takeOver(callId)
        } else {
            supervisor.fail(callId, UiText.Resource(R.string.live_calls_microphone_denied))
        }
    }

    override fun onCleared() {
        socket?.stop()
        supervisor.release()
    }

    private fun onFeed(event: LiveFeedEvent) {
        when (event) {
            LiveFeedEvent.Opened, LiveFeedEvent.CallsChanged -> refresh()
            is LiveFeedEvent.Receipt -> whisperDesk.receipt(event.callId, event.data)
        }
    }

    /** A writer, and a call the receptionist is on. */
    private fun writable(callId: String): Boolean {
        val row = (_list.value as? LiveCallList.Loaded)?.calls?.firstOrNull { it.id == callId }
        return canWrite && row != null && !row.isTeamMemberCall
    }

    companion object {
        fun factory(
            repository: SuperviseRepository,
            workspaceId: String,
            canWrite: Boolean,
            engineFactory: CallEngineFactory,
            feed: ((CoroutineScope, (LiveFeedEvent) -> Unit) -> LiveCallsFeed)?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { LiveCallsViewModel(repository, workspaceId, canWrite, engineFactory, feed) }
        }
    }
}
