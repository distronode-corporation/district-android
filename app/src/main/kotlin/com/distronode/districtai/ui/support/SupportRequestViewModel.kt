package com.distronode.districtai.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.model.SupportStatusCategory
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One request, its conversation, and the two writes on it.
 *
 * ⛔ NEITHER WRITE IS EVER RETRIED AUTOMATICALLY, AND NEITHER IS IDEMPOTENT.
 * - A reply is posted as a PUBLIC Jira comment, so a repeat leaves a second copy in the customer's
 *   own thread and notifies the agent twice.
 * - A close posts a PUBLIC audit comment naming who asked BEFORE it applies the transition —
 *   deliberately, so the attribution survives a transition that fails — so a repeat that still
 *   finds a transition leaves a second "Closed at the requester's request by …" in that thread.
 * [retryOrNoop] therefore replays only the READ, and a failed write is left for the operator to
 * decide about.
 *
 * ⛔ NO POLLING. The detail read refreshes from Atlassian rather than serving the local mirror, so
 * it is slower than the list and a vendor outage degrades to the mirror instead of failing. A
 * refresh here is a deliberate act, never a timer.
 *
 * ⚠️ A 404 IS SHOWN AS THE SERVER SENT IT AND IS NOT INTERPRETED. "No such request", "not this
 * workspace's request" and "erased" answer identically so a sequential key cannot be probed with a
 * session and a loop; telling them apart here would rebuild the oracle the server declined to offer.
 */
class SupportRequestViewModel(
    private val repository: SupportRepository,
    val workspaceId: String,
    val key: String,
    role: WorkspaceRole?,
) : ViewModel() {

    val canUse: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<SupportRequestUiState>(SupportRequestUiState.Loading)
    val state: StateFlow<SupportRequestUiState> = _state.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    /** ⚠️ The desk's own word for the resolved state, adopted from the close response. */
    private val _closedAs = MutableStateFlow<String?>(null)
    val closedAs: StateFlow<String?> = _closedAs.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (!canUse) return
        _state.value = SupportRequestUiState.Loading
        viewModelScope.launch {
            when (val result = repository.request(workspaceId, key)) {
                is ApiResult.Success -> _state.value = SupportRequestUiState.Content(result.value)
                is ApiResult.Failure ->
                    _state.value = SupportRequestUiState.Failed(result.toFailureText())
            }
        }
    }

    fun editDraft(value: String) {
        _draft.value = value
    }

    /**
     * Answer on the request.
     *
     * ⚠️ THE ECHOED MESSAGE IS APPENDED RATHER THAN THE THREAD BEING RE-READ, because the read is a
     * live Atlassian fetch and spending one to show a sentence we already hold would be slower and
     * could fail. The `statusName` is left alone: a reply does not move the desk's workflow.
     *
     * ⚠️ A **409** IS A STATE, NOT A FAULT: the request is still being opened, has no Atlassian
     * thread yet, and accepting the reply would silently drop the one message the customer wanted
     * us to see. It arrives as a failure carrying the server's own sentence, and the draft is KEPT.
     */
    fun send() {
        if (!canUse) return
        val current = _state.value as? SupportRequestUiState.Content ?: return
        val body = _draft.value.trim()
        if (body.isEmpty() || current.sending) return

        _state.value = current.copy(sending = true, sendFailure = null)
        viewModelScope.launch {
            when (val result = repository.reply(workspaceId, key, body)) {
                is ApiResult.Success -> {
                    _draft.value = ""
                    _state.value = current.copy(
                        request = current.request.copy(
                            messages = current.request.messages + result.value,
                        ),
                        sending = false,
                    )
                }
                is ApiResult.Failure ->
                    _state.value =
                        current.copy(sending = false, sendFailure = result.toFailureText())
            }
        }
    }

    /**
     * Close the request.
     *
     * ⛔ OFFERED ONLY WHEN THE SERVER SAID IT COULD BE — see [SupportRequestUiState.Content.canClose].
     * A 409 `not-closeable` is an ANSWER rather than an error: the workflow offers no resolving
     * transition, or offers several and picking one would decide on the customer's behalf whether
     * their request was "done" or "won't do". Its sentence names the way forward (reply and we will
     * close it) and is shown verbatim.
     *
     * ⚠️ THE RESOLVED `statusName` IS ADOPTED FROM THE RESPONSE. The live workflow is localised, so
     * substituting "Closed" would print English over a status Atlassian spells in another language.
     */
    fun close() {
        if (!canUse) return
        val current = _state.value as? SupportRequestUiState.Content ?: return
        // A second tap during the first close would post a second audit comment; see the class.
        if (!current.canClose || current.closing) return

        _state.value = current.copy(closing = true, closeFailure = null)
        viewModelScope.launch {
            when (val result = repository.close(workspaceId, key)) {
                is ApiResult.Success -> {
                    _closedAs.value = result.value
                    _state.value = current.copy(
                        request = current.request.copy(
                            statusName = result.value,
                            // ⚠️ The category is what `isResolved` reads, and the close route does
                            // not echo it — so it is set here rather than inferred from the name,
                            // which is localised and cannot be compared.
                            statusCategory = SupportStatusCategory.RESOLVED,
                        ),
                        closing = false,
                    )
                }
                is ApiResult.Failure ->
                    _state.value =
                        current.copy(closing = false, closeFailure = result.toFailureText())
            }
        }
    }

    fun acknowledgeClosed() {
        _closedAs.value = null
    }

    /** ⛔ The READ only. See the ⛔ on this class: neither write may be replayed. */
    fun retryOrNoop() {
        if (_state.value is SupportRequestUiState.Failed) load()
    }

    companion object {
        fun factory(
            repository: SupportRepository,
            workspaceId: String,
            key: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SupportRequestViewModel(repository, workspaceId, key, role) as T
            }
        }
    }
}
