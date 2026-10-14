package com.distronode.districtai.ui.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.data.InboxRepository
import com.distronode.districtai.core.data.MessageSearchRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Starting a conversation with somebody the Inbox has no thread for yet.
 *
 * ⛔ WITHOUT THIS THE ONLY WAY INTO A THREAD IS A ROW THAT ALREADY EXISTS, which makes the Inbox a
 * reader rather than a client: an operator who wants to text a customer first would have to open the
 * web console. iOS's `ComposeModel` is the reference and this matches it.
 *
 * ⛔ THE THREAD KEY IS RESOLVED BY THE SERVER AND NEVER COMPUTED HERE. It is `contact:<id>` when the
 * counterpart resolves to a `Contact` row and `addr:<normalized>` when it does not, and for a new
 * address the send itself may create the row. So the flow is: send, then
 * `GET /api/district/messages/{id}` with the row id the send echoed, then open the thread. A guessed
 * key would open a second thread beside the one the message landed in.
 *
 * ⛔ TWO REQUESTS, AND ONLY THE FIRST SPENDS MONEY. A failed LOOKUP is still a sent message (see
 * [ComposeSendState.Sent]), and NOTHING IS RETRIED on either half: a send that timed out may well
 * have landed, which is the rule on `messages/send` in core-network.
 */
class ComposeViewModel(
    private val contacts: ContactsRepository,
    private val inbox: InboxRepository,
    private val threads: MessageSearchRepository,
    val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    /**
     * ⛔ `messages/send` and the message resolver both exclude `viewer` server-side, so the form is
     * not offered at all rather than offered and refused. ⚠️ Errs low: a null role is "could not be
     * established", never "assume client".
     */
    val canSend: Boolean = role.allowsMutation()

    private val _state = MutableStateFlow(ComposeUiState())
    val state: StateFlow<ComposeUiState> = _state.asStateFlow()

    /** The form's controls as one value, built once so a redraw never rebuilds them. */
    val actions: ComposeActions = ComposeActions(
        open = ::open,
        dismiss = ::dismiss,
        onQueryChange = ::onQueryChange,
        choose = ::choose,
        useTypedAddress = ::useTypedAddress,
        clearRecipient = ::clearRecipient,
        selectChannel = ::selectChannel,
        onBodyChange = ::onBodyChange,
        onSubjectChange = ::onSubjectChange,
        send = ::send,
    )

    /** The contact window read, cancelled on close so a late answer cannot land in the next form. */
    private var contactsJob: Job? = null

    /**
     * Open a clean form and read the newest window of contacts.
     *
     * ⚠️ RE-READ ON EVERY OPEN RATHER THAN CACHED: a contact added since the last open (by this
     * operator, on another device, or by the voice agent from an inbound call) has to be in the list.
     * ⚠️ FAIL-SOFT: a failure is a caption, because typing an address needs no CRM read.
     */
    fun open() {
        if (!canSend) return
        contactsJob?.cancel()
        _state.value = ComposeUiState(open = true)
        contactsJob = viewModelScope.launch {
            val loaded = when (val result = contacts.recent(workspaceId, CONTACT_WINDOW)) {
                is ApiResult.Success -> ComposeContacts.Ready(result.value)
                is ApiResult.Failure -> ComposeContacts.Failed(result.toFailureText())
            }
            _state.update { it.copy(contacts = loaded) }
        }
    }

    /**
     * Close the form and drop the draft.
     *
     * ⛔ REFUSED WHILE A SEND IS IN FLIGHT. Closing then would lose whether the message went out, and
     * an operator who cannot tell will send it again.
     */
    fun dismiss() {
        if (_state.value.sending) return
        contactsJob?.cancel()
        _state.value = ComposeUiState()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
    }

    /** ⚠️ Resets the channel to the first choice: the old index may point past this contact's list. */
    fun choose(contact: Contact) {
        _state.update { it.copy(recipient = ComposeRecipient.Picked(contact), selectedChannel = 0).withoutFailure() }
    }

    /**
     * Arm whatever is in the box as a raw address.
     *
     * ⚠️ REFUSES A BLANK STRING AND VALIDATES NOTHING ELSE. The server owns address validation and
     * its refusals are specific; a client-side pattern would refuse international formats it had not
     * been taught and tell the operator their customer's number is wrong.
     */
    fun useTypedAddress() {
        val typed = _state.value.query.trim()
        if (typed.isEmpty()) return
        _state.update { it.copy(recipient = ComposeRecipient.Typed(typed), selectedChannel = 0).withoutFailure() }
    }

    fun clearRecipient() {
        _state.update { it.copy(recipient = null, selectedChannel = 0).withoutFailure() }
    }

    /** ⚠️ Bounds-checked against the ARMED recipient's choices, which change when the recipient does. */
    fun selectChannel(index: Int) {
        _state.update { if (index in it.channelChoices.indices) it.copy(selectedChannel = index) else it }
    }

    fun onBodyChange(body: String) {
        _state.update { it.copy(body = body) }
    }

    fun onSubjectChange(subject: String) {
        _state.update { it.copy(subject = subject) }
    }

    /**
     * Send the first message, then find the thread it landed in.
     *
     * ⛔ GUARDED BY [ComposeUiState.submittable], WHICH IS FALSE WHILE IN FLIGHT: a second tap must not
     * become a second charge and a message the customer receives twice.
     *
     * ⚠️ THE SUBJECT IS SENT ONLY ON EMAIL, and never as `""`: the route treats an empty subject and
     * an absent one the same, so a blank string would look like a fix and be none.
     */
    fun send() {
        val current = _state.value
        val target = current.target
        if (!canSend || target == null || !current.submittable) return
        _state.value = current.copy(send = ComposeSendState.Sending)

        viewModelScope.launch {
            val result = inbox.send(
                workspaceId = workspaceId,
                to = target.to,
                body = current.body,
                channel = target.channel,
                subject = current.subject.takeIf { current.requiresSubject },
            )
            val next = when (result) {
                // ⛔ THE SERVER'S PAIR FROM HERE ON, NOT THE ONE THIS FORM DERIVED: the thread exists
                // now, so the resolver's counterpart and channel are what the thread replies on. A
                // failed lookup (or a blank echoed id) is `Sent(null)`, never a failure.
                is ApiResult.Success -> ComposeSendState.Sent(
                    result.value.takeIf { it.isNotBlank() }?.let { messageId ->
                        (threads.threadFor(workspaceId, messageId) as? ApiResult.Success)?.value
                    },
                )
                is ApiResult.Failure -> ComposeSendState.Failed(result.toFailureText())
            }
            _state.update { it.copy(send = next) }
        }
    }

    class Factory(
        private val contacts: ContactsRepository,
        private val inbox: InboxRepository,
        private val threads: MessageSearchRepository,
        private val workspaceId: String,
        private val role: WorkspaceRole?,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return ComposeViewModel(contacts, inbox, threads, workspaceId, role) as T
        }
    }

    companion object {
        /**
         * ⚠️ ONE WINDOW OF 50, as iOS reads. The route has no search parameter, so the field filters
         * this window and the free-text path covers everyone outside it (the server resolves a typed
         * number to the existing contact either way, so the thread is the same).
         */
        const val CONTACT_WINDOW: Int = 50
    }
}
