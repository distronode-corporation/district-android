package com.distronode.districtai.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
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
 *
 * ⛔ AND IT PAUSES WHILE THE SCREEN IS STOPPED, which the scope alone does not give. A pop destroys
 * the entry, but the app going to the background or another destination being pushed on top only
 * STOPS it, and the loop went on reading every 2.5 seconds for a screen nobody could see. The
 * destination reports its own start and stop through [onScreenStarted] (a `LifecycleStartEffect`
 * in `DistrictNavHost`), so no lifecycle type reaches this class; see [DossierPoll].
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

    private val poll = DossierPoll(
        scope = viewModelScope,
        updates = { repository.dossierUpdates(workspaceId, contactId) },
        onResult = { result -> _state.applyPollResult(result) },
    )

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
                // ⚠️ RE-READ FIRST, because the server stamps the row before answering and the
                // read is the truth (and may already say "crawling").
                is ApiResult.Success -> when (val read = repository.detail(workspaceId, contactId)) {
                    is ApiResult.Success -> publish(read.value)
                    // ⛔ BUT A FAILED RE-READ IS NOT AN ENRICH FAILURE, AND MUST NOT RE-ARM THE
                    // BUTTON. The write is confirmed here, so stamping "pending" is not a guess.
                    // Reporting it as a failure kept the pre-enrich contact, whose null status
                    // makes [Contact.dgiOfferable] true: one more tap on a flaky connection bought
                    // a second crawl and a second LLM run. The stamp shows the badge and starts the
                    // poll, which re-reads on its own; a failed poll then says so (see
                    // [applyPollResult]). Dropped if a reload has already put the screen back to
                    // Loading: that read answers.
                    is ApiResult.Failure -> (_state.value as? ContactDetailUiState.Content)?.let {
                        publish(it.contact.copy(dgiStatus = DGI_PENDING))
                    }
                }
                is ApiResult.Failure -> _state.reportMutationFailure(result)
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
                is ApiResult.Failure -> _state.reportMutationFailure(result)
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
            is ApiResult.Failure -> _state.reportMutationFailure(result)
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
        poll.sync(contact)
    }

    /**
     * The screen was started (true) or stopped (false): resume or pause the dossier poll.
     *
     * ⚠️ RESUMED ONLY WHERE IT WOULD HAVE BEEN RUNNING. A contact still loading is picked up by its
     * own load, which syncs the poll when it lands; and a poll that stopped on a failure stays
     * stopped behind its retry ([checkDossierAgain]), so returning to the screen does not quietly
     * restart it under a card that says it failed.
     */
    fun onScreenStarted(started: Boolean) {
        if (!started) return poll.pause()
        val content = _state.value as? ContactDetailUiState.Content
        poll.resume(content?.takeIf { it.pollFailure == null }?.contact)
    }

    /**
     * Retry after the dossier poll stopped on a failed read.
     *
     * ⚠️ A READ, NEVER THE ENRICH. It re-reads the contact and, if the crawl is still running,
     * restarts the poll; nothing billable is re-sent.
     *
     * ⚠️ ONLY WHILE A POLL FAILURE IS SHOWING, which also makes a double tap dispatched before the
     * card recomposes away one read rather than two.
     */
    fun checkDossierAgain() {
        val content = _state.value as? ContactDetailUiState.Content ?: return
        if (content.pollFailure == null) return

        _state.value = content.copy(pollFailure = null)
        viewModelScope.launch {
            val result = repository.detail(workspaceId, contactId)
            val current = _state.value as? ContactDetailUiState.Content ?: return@launch
            when (result) {
                // Like a poll tick, only the contact changes: a mutation may be in flight.
                is ApiResult.Success -> {
                    _state.value = current.copy(contact = result.value)
                    poll.sync(result.value)
                }
                is ApiResult.Failure -> _state.value = current.copy(pollFailure = result.toFailureText())
            }
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
                    // ⚠️ [refresh], not [load]: the rename landed, so a failed re-read belongs beside
                    // the contact rather than replacing it with a full-screen failure.
                    refresh()
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
        /**
         * ⚠️ The status the server stamps on the row BEFORE `contacts/enrich` answers (see
         * `EnrichResponse.status`), and one of [Contact.dgiInProgress]'s three.
         */
        private const val DGI_PENDING = "pending"

        fun factory(
            repository: ContactsRepository,
            workspaceId: String,
            contactId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContactDetailViewModel(repository, workspaceId, contactId, role) }
        }
    }
}

/**
 * Put a failed write beside the contact, ending its `saving`; dropped unless the contact is showing.
 *
 * ⚠️ A TOP-LEVEL EXTENSION rather than a private method, for the reason ThreadViewModel's
 * `withContent` is one: it reads nothing of [ContactDetailViewModel] beyond the flow it is called
 * on, and that class sits on detekt's function ceiling, which [ContactDetailViewModel.checkDossierAgain]
 * reached. Moving a pure helper out is the honest answer; raising the threshold would be the other.
 */
private fun MutableStateFlow<ContactDetailUiState>.reportMutationFailure(failure: ApiResult.Failure) {
    (value as? ContactDetailUiState.Content)?.let {
        value = it.copy(saving = false, mutationFailure = failure.toFailureText())
    }
}

/**
 * Apply one dossier poll read to the contact on screen.
 *
 * ⚠️ ONLY THE CONTACT (OR THE POLL'S OWN FAILURE) IS REPLACED. A poll landing while a rename is in
 * flight must not clear `saving` or a pending failure message: it is a background read, not the
 * outcome of anything the operator did. Dropped unless the contact is showing. A top-level
 * extension for the reason [reportMutationFailure] is one.
 */
private fun MutableStateFlow<ContactDetailUiState>.applyPollResult(result: ApiResult<Contact>) {
    val current = value as? ContactDetailUiState.Content ?: return
    value = when (result) {
        is ApiResult.Success -> current.copy(contact = result.value)
        // ⛔ THE FLOW ENDS ON A FAILURE, SO THIS IS THE LAST THING THE POLL SAYS. Dropping it froze
        // the badge on "building" for good, with nothing to tap; it is surfaced with a retry
        // instead. See [ContactDetailViewModel.checkDossierAgain].
        is ApiResult.Failure -> current.copy(pollFailure = result.toFailureText())
    }
}

/**
 * The dossier poll's lifetime: at most one collector, and only while the screen is started.
 *
 * ⚠️ ITS OWN CLASS because [ContactDetailViewModel] sits on detekt's function ceiling, and the
 * job, the started flag and the rules joining them belong together anyway: every start, stop,
 * pause and resume goes through here, so "at most one" is checked in one place.
 */
private class DossierPoll(
    private val scope: CoroutineScope,
    private val updates: () -> Flow<ApiResult<Contact>>,
    private val onResult: (ApiResult<Contact>) -> Unit,
) {
    /**
     * ⚠️ AT MOST ONE, EVER. Two concurrent polls would double the read rate on a route that fans
     * out to a regional database, for no extra information: both would be asking the same
     * question about the same row. ⚠️ Non-null means RUNNING: every way a poll ends (the flow
     * completing, or [stop]) clears the field, so there is no finished job left in it to ask about.
     */
    private var job: Job? = null

    /**
     * ⚠️ TRUE UNTIL THE SCREEN SAYS OTHERWISE. The ViewModel is built during the destination's
     * first composition, which happens on a started screen, and its first load can land before the
     * start effect has run; starting false would hold that first poll back for no reason.
     */
    private var started = true

    /**
     * Start or stop the poll to match [contact].
     *
     * ⛔ THE TERMINAL CHECK IS [Contact.dgiInProgress], WHICH COVERS ALL THREE IN-FLIGHT
     * STATUSES. The pipeline advances pending -> crawling -> synthesizing, so a poll that stopped
     * on anything-but-"pending" would quit the moment the crawler started and leave the screen
     * showing a stale dossier for a job still running.
     *
     * ⚠️ AND NULL IS NOT IN FLIGHT. After `clear-intel` the status is null with nothing queued;
     * treating that as pending would poll forever against a job that does not exist.
     */
    fun sync(contact: Contact) {
        if (!contact.dgiInProgress || !started) return stop()
        // Already watching this contact: a second collector would double the read rate and learn
        // nothing the first one does not.
        if (job != null) return
        job = scope.launch {
            updates().collect(onResult)
            // The flow completed, so the enrichment settled (or the read failed). Either way
            // nothing is watching any more.
            job = null
        }
    }

    /** The screen stopped: no read until it starts again. */
    fun pause() {
        started = false
        stop()
    }

    /** The screen started: watch [contact] again if it is still in flight, or nothing if null. */
    fun resume(contact: Contact?) {
        started = true
        contact?.let(::sync)
    }

    private fun stop() {
        job?.cancel()
        job = null
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
        /**
         * The dossier poll stopped on a failed read.
         *
         * ⚠️ SEPARATE FROM [mutationFailure] because it is not the outcome of anything the operator
         * did, and because it has a retry ([ContactDetailViewModel.checkDossierAgain]) where a failed
         * write deliberately has none.
         */
        val pollFailure: FailureText? = null,
    ) : ContactDetailUiState

    data class Failed(val failure: FailureText) : ContactDetailUiState
}
