package com.distronode.districtai.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One contact, its four mutations, and the dossier poll.
 *
 * ⛔ THE POLL IS THE ONLY WAY A FINISHED DOSSIER EVER REACHES THIS SCREEN. `contacts/enrich`
 * answers immediately with `status: "pending"`; the crawl and the LLM synthesis happen on a
 * Pub/Sub subscriber and there is no push, no webhook and no completion endpoint. So a contact
 * whose enrichment is in flight is re-read on an interval until it settles.
 *
 * ⛔ AND IT IS SCOPED TO THIS ViewModel, WHICH IS WHAT MAKES "STOPS ON SCREEN EXIT" TRUE WITHOUT
 * ANY TEARDOWN CODE. The loop runs in `viewModelScope`, so leaving the destination cancels it.
 * The alternative — a poll owned by the repository or by a process-scoped holder — would keep
 * re-reading a contact nobody is looking at, on a route that fans out to a regional database.
 */
class ContactDetailViewModel(
    private val repository: ContactsRepository,
    private val workspaceId: String,
    private val contactId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    /** See the note on [ContactsViewModel.canMutate]: the membership role, which errs low. */
    val canMutate: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow<ContactDetailUiState>(ContactDetailUiState.Loading)
    val state: StateFlow<ContactDetailUiState> = _state.asStateFlow()

    /**
     * ⚠️ AT MOST ONE, EVER. Two concurrent polls would double the read rate on a route that fans
     * out to a regional database, for no extra information — both would be asking the same
     * question about the same row.
     */
    private var pollJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = ContactDetailUiState.Loading
        viewModelScope.launch {
            when (val result = repository.detail(workspaceId, contactId)) {
                is ApiResult.Success -> publish(result.value)
                is ApiResult.Failure ->
                    _state.value = ContactDetailUiState.Failed(result.toFailureText())
            }
        }
    }

    /**
     * Queue a Global Intelligence dossier.
     *
     * ⛔ ONE TAP IS ONE EXTERNAL CRAWL AND ONE LLM RUN, AND THE ENDPOINT IS NOT IDEMPOTENT. Three
     * separate guards stand in front of it and all three are deliberate: the role (the server
     * excludes `viewer`), `saving` (so a double tap cannot buy two), and [Contact.dgiOfferable]
     * (so a crawl already running cannot be started again). The server's own rate limit is
     * Redis-backed and FAIL-OPEN, so it is not a backstop for any of them.
     *
     * ⛔ AND THERE IS NO AUTOMATIC RETRY ON FAILURE. An enrichment that timed out may well have
     * been queued; re-sending it spends a second model run on the same contact. The failure is
     * surfaced and the operator decides.
     *
     * ⚠️ A 403 here is usually the WORKSPACE opt-in being off rather than the caller's role, and
     * the server's message names the settings page that turns it on. It is shown verbatim.
     */
    fun enrich() {
        val content = _state.value as? ContactDetailUiState.Content ?: return
        if (!canMutate || content.saving || !content.contact.dgiOfferable) return

        _state.value = content.copy(saving = true, mutationFailure = null)
        viewModelScope.launch {
            when (val result = repository.enrich(workspaceId, contactId)) {
                // ⚠️ Re-read rather than optimistically stamping "pending" locally. The server
                // has already written the status, and inventing it here would show a queued job
                // even in the cases where the write did not land.
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> reportMutationFailure(result)
            }
        }
    }

    /**
     * Clear the dossier, keeping the contact.
     *
     * ⛔ THE CONFIRMATION LIVES IN THE SCREEN, NOT HERE, and this method assumes it happened.
     * That split is deliberate: a ViewModel that owned the dialog state would make "was this
     * confirmed" a question about two objects.
     *
     * ⚠️ Afterwards `dgiStatus` is NULL and nothing is queued, so the re-read below both clears
     * the badge and re-offers enrichment. Polling stops on its own, because the refreshed contact
     * is not in flight.
     */
    fun clearIntel() {
        val content = _state.value as? ContactDetailUiState.Content ?: return
        if (!canMutate || content.saving) return

        _state.value = content.copy(saving = true, mutationFailure = null)
        viewModelScope.launch {
            when (val result = repository.clearIntel(workspaceId, contactId)) {
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> reportMutationFailure(result)
            }
        }
    }

    /**
     * Re-read WITHOUT blanking the screen.
     *
     * ⚠️ Unlike [load], a failure here does not replace the contact with a failure state: the
     * contact on screen is still perfectly good and the operator has just performed an action, so
     * the failure belongs beside it. Blanking would lose what they were reading in order to
     * report that a re-read failed.
     */
    private suspend fun refresh() {
        when (val result = repository.detail(workspaceId, contactId)) {
            is ApiResult.Success -> publish(result.value)
            is ApiResult.Failure -> reportMutationFailure(result)
        }
    }

    /**
     * Show a contact, and start or stop the poll to match its state.
     *
     * ⚠️ `saving` IS CLEARED HERE, which is why every mutation path ends in a read: the flag
     * means "a write is in flight", and a write is done exactly when its result has been read
     * back.
     */
    private fun publish(contact: Contact) {
        _state.value = ContactDetailUiState.Content(contact)
        syncPolling(contact)
    }

    /**
     * ⛔ THE TERMINAL CHECK IS [Contact.dgiInProgress], WHICH COVERS ALL THREE IN-FLIGHT
     * STATUSES. The pipeline advances pending -> crawling -> synthesizing, so a poll that stopped
     * on anything-but-"pending" would quit the moment the crawler started and leave the screen
     * showing a stale dossier for a job still running.
     *
     * ⚠️ AND NULL IS NOT IN FLIGHT. After `clear-intel` the status is null with nothing queued;
     * treating that as pending would poll forever against a job that does not exist.
     */
    private fun syncPolling(contact: Contact) {
        if (!contact.dgiInProgress) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        // Already watching this contact — a second collector would double the read rate and
        // learn nothing the first one does not.
        if (pollJob?.isActive == true) return

        pollJob = viewModelScope.launch {
            repository.dossierUpdates(workspaceId, contactId).collect { result ->
                // ⚠️ ONLY THE CONTACT IS REPLACED. A poll landing while a rename is in flight
                // must not clear `saving` or a pending failure message — it is a background
                // read, not the outcome of anything the operator did.
                val current = _state.value as? ContactDetailUiState.Content ?: return@collect
                if (result is ApiResult.Success) {
                    _state.value = current.copy(contact = result.value)
                }
            }
            // The flow completed, so the enrichment settled (or the read failed). Either way
            // nothing is watching any more.
            pollJob = null
        }
    }

    private fun reportMutationFailure(failure: ApiResult.Failure) {
        (_state.value as? ContactDetailUiState.Content)?.let {
            _state.value = it.copy(saving = false, mutationFailure = failure.toFailureText())
        }
    }

    /**
     * Rename the contact.
     *
     * ⛔ THE WHOLE LOADED CONTACT IS SENT BACK, WITH ONLY THE NAME CHANGED. `contacts/update` is a
     * wholesale replace: a key it does not receive is a column it clears, and a name-only body cleared
     * both addresses and got a 400 for it. The repository rebuilds the row from the contact on screen
     * (the last server read), which is why this passes the contact rather than its id.
     *
     * ⚠️ Refuses locally when the role does not permit it, so the app never fires a request it knows
     * will 403. That is an affordance, NOT a security control — the server still enforces.
     */
    fun rename(name: String) {
        val content = _state.value as? ContactDetailUiState.Content ?: return
        if (!canMutate || content.saving) return
        val trimmed = name.trim()
        // An empty name is not an edit, and the server would reject it as a validation error.
        if (trimmed.isBlank() || trimmed == content.contact.name) return

        _state.value = content.copy(saving = true, mutationFailure = null)
        viewModelScope.launch {
            when (val result = repository.update(workspaceId, content.contact, name = trimmed)) {
                is ApiResult.Success ->
                    // Re-read rather than patching the local copy: the server may normalise the value,
                    // and a stale local edit that disagrees with the list is worse than a round trip.
                    load()
                is ApiResult.Failure -> (_state.value as? ContactDetailUiState.Content)?.let {
                    _state.value = it.copy(saving = false, mutationFailure = result.toFailureText())
                }
            }
        }
    }

    /**
     * Delete the contact, calling [onDeleted] once it is gone so the caller can navigate away.
     *
     * ⚠️ A 404 counts as success — the repository maps it — because for a delete "it was already gone"
     * is the outcome the user asked for, not a failure they can act on.
     */
    fun delete(onDeleted: () -> Unit) {
        val content = _state.value as? ContactDetailUiState.Content ?: return
        if (!canMutate || content.saving) return

        _state.value = content.copy(saving = true, mutationFailure = null)
        viewModelScope.launch {
            when (val result = repository.delete(workspaceId, contactId)) {
                is ApiResult.Success -> onDeleted()
                is ApiResult.Failure -> (_state.value as? ContactDetailUiState.Content)?.let {
                    _state.value = it.copy(saving = false, mutationFailure = result.toFailureText())
                }
            }
        }
    }

    /** Dismiss a mutation error without reloading. */
    fun clearMutationFailure() {
        (_state.value as? ContactDetailUiState.Content)?.let {
            _state.value = it.copy(mutationFailure = null)
        }
    }

    companion object {
        fun factory(
            repository: ContactsRepository,
            workspaceId: String,
            contactId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactDetailViewModel(repository, workspaceId, contactId, role) as T
        }
    }
}

sealed interface ContactDetailUiState {
    data object Loading : ContactDetailUiState

    data class Content(
        val contact: Contact,
        /** A mutation is in flight; controls should be disabled rather than re-firing. */
        val saving: Boolean = false,
        /**
         * A failed mutation.
         *
         * ⚠️ Kept SEPARATE from the loaded contact so a failed rename does not blank the screen. The
         * contact is still perfectly good; only the edit failed.
         */
        val mutationFailure: FailureText? = null,
    ) : ContactDetailUiState

    data class Failed(val failure: FailureText) : ContactDetailUiState
}
