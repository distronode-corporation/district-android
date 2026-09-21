package com.distronode.districtai.ui.support

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.SupportRepository
import com.distronode.districtai.core.model.SupportRequestDraft
import com.distronode.districtai.core.model.SupportRequestFiling
import com.distronode.districtai.core.model.SupportRequestKind
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The list of this workspace's requests with Distronode, and the composer that raises one.
 *
 * ⛔ ALL FIVE SUPPORT ROUTES EXCLUDE `viewer`, READS INCLUDED — these payloads are support
 * CORRESPONDENCE rather than operational status. [canUse] therefore gates the whole destination and
 * not merely its writes, the entry is hidden rather than captioned, and a viewer that arrives
 * another way spends no request.
 *
 * ⛔ NOTHING THIS CLASS SENDS IDENTIFIES THE REQUESTER. The workspace is the only scope on the wire
 * and the server verifies it against the caller's own membership; the requester is derived from the
 * session and hashed there. No email address, phone number or hash is computed, held or sent by the
 * app, and adding one would turn an authenticated read into a lookup that takes its answer from its
 * input — the exact shape the voice path refuses for the exact reason that caller ID is spoofable.
 *
 * ⚠️ A MEMBER SEES THE WHOLE WORKSPACE'S REQUESTS, not only the ones they raised. That is a
 * deliberate server decision (a colleague's open ticket must not become unreachable when they
 * leave) and is why the role list is the administrative pair rather than every member.
 */
class SupportViewModel(
    private val repository: SupportRepository,
    val workspaceId: String,
    role: WorkspaceRole?,
    /** ⚠️ Injected only so a test can assert the key survived a retry. */
    private val keys: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {

    val canUse: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<SupportUiState>(SupportUiState.Loading)
    val state: StateFlow<SupportUiState> = _state.asStateFlow()

    private val _compose = MutableStateFlow(SupportComposeState(idempotencyKey = keys()))
    val compose: StateFlow<SupportComposeState> = _compose.asStateFlow()

    private val _submitted = MutableStateFlow<SupportSubmitted?>(null)
    val submitted: StateFlow<SupportSubmitted?> = _submitted.asStateFlow()

    init {
        load()
    }

    fun load(refreshing: Boolean = false) {
        if (!canUse) return

        val current = _state.value
        if (refreshing && current is SupportUiState.Content) {
            _state.value = current.copy(refreshing = true)
        } else if (!refreshing) {
            _state.value = SupportUiState.Loading
        }

        viewModelScope.launch {
            when (val result = repository.requests(workspaceId)) {
                // ⛔ AN EMPTY LIST IS A REAL ANSWER AND A FAILURE IS NOT ONE. See SupportUiState.
                is ApiResult.Success -> _state.value = SupportUiState.Content(result.value)
                is ApiResult.Failure -> _state.value = SupportUiState.Failed(result.toFailureText())
            }
        }
    }

    fun setKind(kind: SupportRequestKind) = editCompose { it.copy(kind = kind) }

    fun editSubject(value: String) = editCompose { it.copy(subject = value) }

    fun editMessage(value: String) = editCompose { it.copy(message = value) }

    /**
     * Raise the request.
     *
     * ⛔ THE KEY ON THE DRAFT IS REUSED ON EVERY ATTEMPT AND IS **NOT** REGENERATED ON FAILURE. That
     * is what makes this the one write on this surface a caller may repeat: the same key collapses
     * onto the first request, a fresh one puts a second ticket in a human's queue. The key is
     * replaced only by [discardDraft], i.e. when the operator abandons this draft for good.
     *
     * ⚠️ A 429 (10/hour per workspace, plus a durable 5/day per requester) and a 503 (the desk is
     * not configured) both carry the server's own sentence naming the remedy, and it reaches the
     * screen verbatim through `FailureText`.
     */
    fun submit() {
        if (!canUse) return
        val draft = _compose.value
        if (!draft.submittable) return

        _compose.value = draft.copy(submitting = true, failure = null)
        viewModelScope.launch {
            val result = repository.createRequest(
                workspaceId = workspaceId,
                draft = SupportRequestDraft(
                    kind = draft.kind,
                    subject = draft.subject,
                    message = draft.message,
                ),
                idempotencyKey = draft.idempotencyKey,
            )
            when (result) {
                is ApiResult.Success -> {
                    _submitted.value = outcomeOf(result.value)
                    // ⚠️ A fresh draft, and therefore a fresh key: this one is spent.
                    _compose.value = SupportComposeState(idempotencyKey = keys())
                    load(refreshing = true)
                }
                is ApiResult.Failure ->
                    // ⛔ THE KEY SURVIVES. `draft` still holds it, so the retry is a repeat rather
                    // than a second request.
                    _compose.value = draft.copy(submitting = false, failure = result.toFailureText())
            }
        }
    }

    fun acknowledge() {
        _submitted.value = null
    }

    /** ⚠️ Mints a new key, because abandoning a draft means the next one is genuinely different. */
    fun discardDraft() {
        _compose.value = SupportComposeState(idempotencyKey = keys())
    }

    fun retryOrNoop() {
        if (_state.value is SupportUiState.Failed) load()
    }

    private fun outcomeOf(filing: SupportRequestFiling): SupportSubmitted = when (filing) {
        is SupportRequestFiling.Filed ->
            SupportSubmitted(SupportSubmitOutcome.FILED, filing.issueKey)
        SupportRequestFiling.Deduplicated -> SupportSubmitted(SupportSubmitOutcome.DEDUPLICATED)
        // ⚠️ A SUCCESS. The claim row is held; only the key to quote is missing.
        SupportRequestFiling.Pending -> SupportSubmitted(SupportSubmitOutcome.PENDING)
    }

    private fun editCompose(block: (SupportComposeState) -> SupportComposeState) {
        _compose.value = block(_compose.value).copy(failure = null)
    }

    companion object {
        fun factory(
            repository: SupportRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return SupportViewModel(repository, workspaceId, role) as T
            }
        }
    }
}
