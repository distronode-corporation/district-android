package com.distronode.districtai.ui.desk

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.DeskRepository
import com.distronode.districtai.core.model.DeskSettings
import com.distronode.districtai.core.model.DeskSettingsPatch
import com.distronode.districtai.core.model.DeskTicketDraft
import com.distronode.districtai.core.model.DeskTicketStatus
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The desk queue's state machine.
 *
 * ⛔ TWO READS PER LOAD, AND THE SETTINGS READ IS NOT OPTIONAL. The queue alone cannot tell an
 * enabled-but-quiet desk from a disabled one, and those are the two screens an operator most needs
 * kept apart — see the ⛔ on [DeskUiState]. The settings read runs FIRST and short-circuits: a
 * disabled desk has no queue worth fetching, and asking for one would spend a request to be told
 * something already known.
 *
 * ⛔ EVERY ROUTE BEHIND THIS SCREEN EXCLUDES `viewer`, READS INCLUDED, WHICH IS UNUSUAL. [canUse] is
 * therefore a gate on the WHOLE destination rather than on its writes, and the entry point is
 * hidden for a viewer rather than captioned. A viewer that arrives another way is told why instead
 * of being walked into a 403 — and no request is sent, because there is no version of this screen
 * they can be shown.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED, DELIBERATELY AND NARROWLY, exactly as `MessagingViewModel`
 * does. detekt caps a class at 11 and fires AT the threshold; this one holds 14,
 * of which FIVE are the compose sheet's per-field editors — one responsibility spelled five times
 * because the alternative, a single `edit(block)` taking a lambda, would put the field names at the
 * call site where nothing checks them.
 *
 * ⛔ AND SPLITTING THE COMPOSER INTO ITS OWN ViewModel WAS CONSIDERED AND REJECTED, which is the
 * part worth recording. The submit has to refresh the QUEUE — a created ticket is not appended
 * locally, because a deduplicated submit returns no ticket at all — so two ViewModels would need a
 * shared holder to get the refresh across, and a shared mutable holder between two ViewModels of
 * one screen is a worse structure than a class one function over a lint ceiling. If this class ever
 * grows a second responsibility that is not the composer, the suppression should go rather than be
 * widened.
 */
@Suppress("TooManyFunctions")
class DeskViewModel(
    private val repository: DeskRepository,
    val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    val canUse: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<DeskUiState>(DeskUiState.Loading)
    val state: StateFlow<DeskUiState> = _state.asStateFlow()

    private val _compose = MutableStateFlow(DeskComposeState())
    val compose: StateFlow<DeskComposeState> = _compose.asStateFlow()

    /**
     * ⚠️ A SUBMITTED TICKET'S REFERENCE, FOR THE CONFIRMATION. Cleared by [acknowledge] so it cannot
     * be shown twice; null for a deduplicated submit, which the screen words differently.
     */
    private val _submitted = MutableStateFlow<String?>(null)
    val submitted: StateFlow<String?> = _submitted.asStateFlow()

    init {
        load()
    }

    fun load(refreshing: Boolean = false) {
        if (!canUse) return

        val current = _state.value
        if (refreshing && current is DeskUiState.Content) {
            _state.value = current.copy(refreshing = true)
        } else if (!refreshing) {
            _state.value = DeskUiState.Loading
        }

        viewModelScope.launch {
            when (val settings = repository.settings(workspaceId)) {
                // ⛔ A FAILED SETTINGS READ IS A FAILURE, NOT A DISABLED DESK. Rendering it as
                // "turn the desk on" sends an operator to switch on something already on.
                is ApiResult.Failure -> _state.value = DeskUiState.Failed(settings.toFailureText())
                is ApiResult.Success ->
                    if (settings.value.enabled) {
                        loadQueue(settings.value)
                    } else {
                        _state.value = DeskUiState.Disabled(settings.value)
                    }
            }
        }
    }

    private suspend fun loadQueue(settings: DeskSettings) {
        // ⚠️ NO `status` FILTER ON THE WIRE. The chips carry counts over the whole queue; see the
        // ⛔ on DeskUiState.Content.tickets.
        when (val tickets = repository.tickets(workspaceId)) {
            is ApiResult.Success -> {
                val keptFilter = (_state.value as? DeskUiState.Content)?.filter
                _state.value = DeskUiState.Content(
                    tickets = tickets.value,
                    settings = settings,
                    filter = keptFilter,
                )
            }
            is ApiResult.Failure -> _state.value = DeskUiState.Failed(tickets.toFailureText())
        }
    }

    /** ⚠️ Local only. Nothing is re-fetched, so a chip cannot disagree with the counts beside it. */
    fun filterBy(status: DeskTicketStatus?) {
        val current = _state.value as? DeskUiState.Content ?: return
        _state.value = current.copy(filter = if (current.filter == status) null else status)
    }

    /**
     * Turn the desk on from the empty state.
     *
     * ⚠️ SENDS ONLY `enabled`, never the rest of the form. This screen never read the other two
     * fields, so including them would write values it does not know — the
     * `blank_form_overwrites_config` shape.
     */
    fun enableDesk() {
        if (!canUse) return
        val current = _state.value as? DeskUiState.Disabled ?: return
        _state.value = DeskUiState.Loading
        viewModelScope.launch {
            when (
                val result = repository.saveSettings(
                    workspaceId,
                    DeskSettingsPatch(enabled = true),
                )
            ) {
                is ApiResult.Success -> loadQueue(result.value)
                is ApiResult.Failure -> {
                    _state.value = DeskUiState.Disabled(current.settings)
                    // ⚠️ The failure is surfaced through the compose channel rather than replacing
                    // the screen: the desk is still off, which is the more important fact.
                    _compose.value = _compose.value.copy(failure = result.toFailureText())
                }
            }
        }
    }

    // ── The compose sheet ────────────────────────────────────────────────────

    fun editSubject(value: String) = editCompose { it.copy(subject = value) }

    fun editMessage(value: String) = editCompose { it.copy(message = value) }

    fun editRequesterName(value: String) = editCompose { it.copy(requesterName = value) }

    fun editRequesterEmail(value: String) = editCompose { it.copy(requesterEmail = value) }

    fun editRequesterPhone(value: String) = editCompose { it.copy(requesterPhone = value) }

    /**
     * ⛔ THE BOXES ARE HANDED OVER RAW AND `DeskRepository` TRIMS THEM TO NULL. A blank optional must
     * not reach the wire as `""` — the route's `.email()` fails on it and takes the whole create
     * down with a message naming the subject and description, both of which were filled in.
     *
     * ⚠️ THE IDEMPOTENCY KEY IS THE REPOSITORY'S AND IS MINTED PER CALL, which is per submit. That
     * is what makes a double tap collapse onto one ticket, and it is the opposite of Support's rule
     * — see `SupportRepository.createRequest`.
     */
    fun submit() {
        if (!canUse) return
        val draft = _compose.value
        if (!draft.submittable) return

        _compose.value = draft.copy(submitting = true, failure = null)
        viewModelScope.launch {
            val result = repository.createTicket(
                workspaceId = workspaceId,
                draft = DeskTicketDraft(
                    subject = draft.subject,
                    message = draft.message,
                    requesterName = draft.requesterName,
                    requesterEmail = draft.requesterEmail,
                    requesterPhone = draft.requesterPhone,
                ),
            )
            when (result) {
                is ApiResult.Success -> {
                    // ⚠️ A DEDUPLICATED SUBMIT CARRIES NO TICKET AND IS STILL A SUCCESS. The ticket
                    // exists; we have no row to show, so the queue is re-read rather than appended
                    // to.
                    _submitted.value = result.value?.displayReference.orEmpty()
                    _compose.value = DeskComposeState()
                    load(refreshing = true)
                }
                is ApiResult.Failure ->
                    _compose.value = draft.copy(submitting = false, failure = result.toFailureText())
            }
        }
    }

    fun acknowledge() {
        _submitted.value = null
    }

    fun discardDraft() {
        _compose.value = DeskComposeState()
    }

    private fun editCompose(block: (DeskComposeState) -> DeskComposeState) {
        _compose.value = block(_compose.value).copy(failure = null)
    }

    /** ⚠️ Replays the load only when it failed; a loaded queue is left alone. */
    fun retryOrNoop() {
        if (_state.value is DeskUiState.Failed) load()
    }

    companion object {
        fun factory(
            repository: DeskRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { DeskViewModel(repository, workspaceId, role) }
        }
    }
}
