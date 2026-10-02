package com.distronode.districtai.ui.desk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import com.distronode.districtai.ui.updateLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One ticket, its thread, and the two writes on it.
 *
 * ⛔ EVERY WRITE ADOPTS THE SERVER'S ECHO RATHER THAN THE VALUE IT ASKED FOR, AND BOTH WRITES HAVE
 * A SPECIFIC REASON:
 * - A reply auto-sets the ticket to `waiting` **unless it is resolved**, where the server
 *   deliberately leaves the state alone. A screen that assumed `waiting` would be wrong for exactly
 *   the ticket an operator is most likely to be answering as a courtesy.
 * - Resolving stamps `resolvedAt` and any other status CLEARS it. A screen holding its own copy
 *   would show a resolution time for a ticket that has since been reopened, and every figure
 *   computed from it is wrong in a way that looks plausible.
 *
 * ⛔ EACH COMPLETION LANDS ON THE LATEST STATE, NOT THE ONE CAPTURED AT THE TAP. A status chip
 * stays tappable while a reply is sending, so the two writes overlap; restoring a tap-time snapshot
 * would leave the reply's spinner running forever and drop a reply that was delivered. See
 * [updateLatest].
 *
 * ⛔ A FAILED WRITE NEVER DESTROYS THE THREAD. Both failures land in a field on `Content` rather
 * than replacing the state, because the correspondence already on screen is still true and is the
 * thing the operator needs in order to try again.
 */
class DeskTicketViewModel(
    private val repository: DeskRepository,
    val workspaceId: String,
    val ticketId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    /** ⛔ Gates the whole destination, not just the writes — see [DeskViewModel.canUse]. */
    val canUse: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<DeskTicketUiState>(DeskTicketUiState.Loading)
    val state: StateFlow<DeskTicketUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (!canUse) return
        _state.value = DeskTicketUiState.Loading
        viewModelScope.launch {
            when (val result = repository.ticket(workspaceId, ticketId)) {
                is ApiResult.Success -> _state.value = DeskTicketUiState.Content(result.value)
                is ApiResult.Failure ->
                    _state.value = DeskTicketUiState.Failed(result.toFailureText())
            }
        }
    }

    fun editDraft(value: String) {
        _draft.value = value
    }

    /**
     * Answer the customer.
     *
     * ⚠️ THE DRAFT IS CLEARED ONLY ON SUCCESS. A failed send that emptied the box would lose the
     * sentence the operator wrote, which is the whole content of the action.
     */
    fun send() {
        if (!canUse) return
        val current = _state.value as? DeskTicketUiState.Content ?: return
        val body = _draft.value.trim()
        if (body.isEmpty() || current.sending) return

        _state.value = current.copy(sending = true, sendFailure = null)
        viewModelScope.launch {
            when (val result = repository.reply(workspaceId, ticketId, body)) {
                is ApiResult.Success -> {
                    _draft.value = ""
                    val reply = result.value
                    val echoed = reply.ticket
                    if (echoed == null) {
                        // ⛔ THE DEGRADED REPLAY: the reply landed and the server can no longer say
                        // which one it was. Re-reading is the only way to show the truth, and
                        // guessing a `waiting` status here is exactly the mistake this class exists
                        // to avoid.
                        load()
                    } else {
                        updateContent { latest ->
                            latest.copy(
                                ticket = latest.ticket.adopting(echoed, appending = reply.message),
                                sending = false,
                                lastNotified = reply.notified,
                            )
                        }
                    }
                }
                is ApiResult.Failure ->
                    updateContent { it.copy(sending = false, sendFailure = result.toFailureText()) }
            }
        }
    }

    /**
     * ⚠️ NOT CONFIRM-GATED, DELIBERATELY, AND THE REASON IS THAT NOTHING HERE IS IRREVERSIBLE. HQ's
     * confirm exists because its writes delete CRM rows and spend money; a ticket status is three
     * values an operator can move between freely, and a confirmation on each would be friction
     * charged for no risk. The state is echoed back so a mistaken tap is visible immediately.
     */
    fun setStatus(status: DeskTicketStatus) {
        if (!canUse) return
        val current = _state.value as? DeskTicketUiState.Content ?: return
        if (current.statusChanging || current.ticket.knownStatus == status) return

        _state.value = current.copy(statusChanging = true, statusFailure = null)
        viewModelScope.launch {
            when (val result = repository.setStatus(workspaceId, ticketId, status)) {
                is ApiResult.Success -> updateContent { latest ->
                    latest.copy(
                        // ⛔ The echo, never `status`. See the ⛔ on this class.
                        ticket = latest.ticket.adopting(result.value),
                        statusChanging = false,
                    )
                }
                is ApiResult.Failure -> updateContent {
                    it.copy(statusChanging = false, statusFailure = result.toFailureText())
                }
            }
        }
    }

    /**
     * ⛔ REPLAYS THE READ AND NEVER A WRITE. A reply may already have been posted when the session
     * expired, and re-sending it would put a second message in the customer's thread.
     */
    fun retryOrNoop() {
        if (_state.value is DeskTicketUiState.Failed) load()
    }

    private fun updateContent(block: (DeskTicketUiState.Content) -> DeskTicketUiState) =
        _state.updateLatest(DeskTicketUiState.Content::class.java, block)

    companion object {
        fun factory(
            repository: DeskRepository,
            workspaceId: String,
            ticketId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return DeskTicketViewModel(repository, workspaceId, ticketId, role) as T
            }
        }
    }
}
