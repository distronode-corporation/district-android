package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.MessagingRepository
import com.distronode.districtai.core.data.MessagingTestOutcome
import com.distronode.districtai.core.data.MessagingWriteOutcome
import com.distronode.districtai.core.model.MessagingAccountRequest
import com.distronode.districtai.core.model.MessagingTestRequest
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The messaging screen's state machine.
 *
 * ⛔ [load] IS THE ONLY THING A SESSION CHANGE MAY REPLAY, and it is the only thing
 * `OnSessionChanged` is wired to. A replayed create would mint a second carrier account;
 * a replayed delete would remove whichever account had since taken the id's place in the operator's
 * head. Neither is reachable, because no write is invoked from anything but a press.
 *
 * ⛔ EVERY WRITE RE-CHECKS THE ROLE AT THE CALL SITE rather than trusting that the UI hid the
 * control. [MessagingUiState.canEdit] is a UX gate; the server's `requireWorkspaceRole` is the
 * boundary. A viewer reaches this screen, so "the button was not drawn" is the
 * kind of assumption that survives until someone adds a keyboard shortcut.
 *
 * ⛔ EVERY WRITE RE-READS, AND A FAILED RE-READ DOES NOT OVERWRITE THE WRITE'S OWN NOTICE. The two
 * are separate fields precisely so "the change landed" and "we could not re-read the list" can both
 * be true and both be said. Telling an operator their change failed when only the read did invites
 * them to make it again — and on this surface "again" can mean a second carrier account.
 *
 * ⚠️ THE UPSERT'S RESPONSE IS NEVER USED TO PATCH THE LIST. It echoes ids only; the route trims the
 * label, may substitute a generated one, and filters the numbers. Appending a row built from the
 * request would show what was typed rather than what was stored.
 *
 * ⚠️ `TooManyFunctions` IS SUPPRESSED, DELIBERATELY AND NARROWLY, exactly as `DeskViewModel`
 * records for the same rule. detekt caps a class at eleven and this one sits at eleven because the
 * ROUTE it drives is a five-way switch plus a sibling probe — the count is the server's shape, not a
 * second responsibility creeping in. Two reductions were made first rather than reaching for the
 * suppression: `startCreate`/`startEdit` became one nullable-id entry point, and `dismissDraft`
 * became `editDraft(null)`. The alternative considered and rejected was a second ViewModel owning
 * the writes — it would need its own copy of [load] and its own [MessagingUiState.canEdit], and a
 * write that re-read into one of them would leave the other drawing a stale account list.
 */
@Suppress("TooManyFunctions")
class MessagingViewModel(
    private val repository: MessagingRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    private val _state = MutableStateFlow(MessagingUiState(canEdit = role.allowsMutation()))
    val state: StateFlow<MessagingUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * ⚠️ AN IDEMPOTENT GET, WHICH IS WHAT MAKES REPLAYING IT ON A SESSION CHANGE SAFE. It also
     * DISCARDS any open draft, and that is the safe direction: a session change means the client's
     * belief about which accounts exist is no longer something it can vouch for, and an edit form
     * still pointing at an account id from before is how a save lands on the wrong row.
     */
    fun load() {
        _state.value = _state.value.copy(load = MessagingLoadState.Loading, draft = null)
        viewModelScope.launch { read() }
    }

    /**
     * Open the form, for a new account or for one that exists.
     *
     * ⛔ ONE ENTRY POINT TAKING A NULLABLE ID RATHER THAN TWO FUNCTIONS, because the two differ by
     * one field and this class is at detekt's eleven-function ceiling. `null` is a create; an id is
     * an edit.
     *
     * ⚠️ SILENTLY DOES NOTHING FOR AN ID THE READ DOES NOT HOLD, rather than falling back to a
     * create. A stale row is the ordinary cause, and turning "edit the account that is gone" into
     * "add a new one" is how a duplicate carrier account gets made.
     */
    fun startEditing(accountId: String?) {
        val current = _state.value
        if (!current.canEditNow) return
        val draft = if (accountId == null) {
            MessagingDraft()
        } else {
            current.accountFor(accountId)?.let(MessagingDraft::of) ?: return
        }
        _state.value = current.copy(draft = draft, test = MessagingTestState.Idle)
    }

    /**
     * Replace the open draft, or close the form with `null`.
     *
     * ⛔ ONE EDITOR RATHER THAN ONE PER FIELD, and it is not laziness: the form has a label, a
     * provider, a credential source, a numbers box, a make-default toggle and up to five credential
     * boxes. Eleven setters on this class would push it past detekt's function ceiling and, worse,
     * would be eleven places for the [MessagingTestState] reset below to be forgotten.
     *
     * ⛔ ANY EDIT RETIRES A PREVIOUS PROBE RESULT. A green "credentials verified" sitting under a
     * key that has since been retyped is a claim about a value nobody tested.
     *
     * ⚠️ A NULL DRAFT DISCARDS THE FORM OUTRIGHT. Nothing was sent, so there is nothing to undo —
     * and a "keep editing?" prompt would be friction on a form whose ordinary content is a label.
     */
    fun editDraft(draft: MessagingDraft?) {
        if (draft != null && _state.value.draft == null) return
        _state.value = _state.value.copy(draft = draft, test = MessagingTestState.Idle)
    }

    /**
     * Create or edit the account in the open draft.
     *
     * ⛔ THE ONLY NON-IDEMPOTENT CALL ON THIS SCREEN, when the draft has no `accountId`. It is
     * guarded by [MessagingUiState.busy] (which disables the button for the whole round trip) and by
     * the draft being cleared on success, so a second press cannot re-send the same create.
     *
     * ⚠️ THE DRAFT SURVIVES A FAILURE. Losing a typed credential because the save was refused would
     * be two losses for one fault — and on this form the refusal is often something the operator
     * can act on (a number another workspace holds, a managed plan they do not have).
     */
    fun saveAccount() {
        val current = _state.value
        val draft = current.draft ?: return
        if (!current.canEditNow || !draft.canSave) return

        _state.value = current.copy(accountSave = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.saveAccount(
                MessagingAccountRequest(
                    workspaceId = workspaceId,
                    activeProvider = draft.provider,
                    credentialSource = draft.credentialSource,
                    providerConfig = draft.toProviderConfig(),
                    accountId = draft.accountId,
                    label = draft.label.trim().takeIf { it.isNotEmpty() },
                    makeDefault = draft.makeDefault.takeIf { it },
                ),
            )
            val saved = outcome is MessagingWriteOutcome.Saved
            _state.value = _state.value.copy(
                accountSave = outcome.toSaveState(),
                draft = if (saved) null else _state.value.draft,
                test = if (saved) MessagingTestState.Idle else _state.value.test,
            )
            read()
        }
    }

    /** ⚠️ Idempotent and reversible, which is why it is a row tap rather than a confirmed action. */
    fun setDefault(accountId: String) {
        val current = _state.value
        if (!current.canEditNow) return

        _state.value = current.copy(defaultSave = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.setDefaultAccount(workspaceId, accountId)
            _state.value = _state.value.copy(defaultSave = result.toSaveState())
            read()
        }
    }

    /** ⚠️ Merges one key server-side. There is no way to CLEAR an override from this client. */
    fun setChannelDefault(channel: String, accountId: String) {
        val current = _state.value
        if (!current.canEditNow) return

        _state.value = current.copy(channelSave = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.setChannelDefault(workspaceId, channel, accountId)
            _state.value = _state.value.copy(channelSave = result.toSaveState())
            read()
        }
    }

    /**
     * Remove one account.
     *
     * ⛔ THE CONFIRMATION IS THE SCREEN'S AND HAS ALREADY HAPPENED, and it is not a formality: this
     * releases the hub's claim on every phone number only this account held, so another tenant can
     * then claim one. Re-adding the account does not take them back — it re-proves ownership at the
     * carrier, which only works if nobody else got there first.
     */
    fun deleteAccount(accountId: String) {
        val current = _state.value
        if (!current.canEditNow) return

        _state.value = current.copy(deleteSave = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.deleteAccount(workspaceId, accountId)
            _state.value = _state.value.copy(deleteSave = result.toSaveState())
            read()
        }
    }

    fun editCreatorCell(value: String) {
        _state.value = _state.value.copy(creatorCellDraft = value)
    }

    /**
     * Write the creator cell number.
     *
     * ⚠️ NO RE-READ. The stored value is not on the messaging GET, so re-reading could not confirm
     * it — and letting a failed list read replace a successful save notice would report a fault
     * that did not happen. Same call as the workspace rename.
     *
     * ⚠️ THE DRAFT IS CLEARED ONLY ON SUCCESS, so a failure leaves the typed number on screen.
     */
    fun saveCreatorCell() {
        val current = _state.value
        if (!current.canSaveCreatorCell) return

        _state.value = current.copy(metaSave = SaveState.Saving)
        viewModelScope.launch {
            val result = repository.saveCreatorCell(workspaceId, current.creatorCellDraft.trim())
            _state.value = _state.value.copy(
                metaSave = result.toSaveState(),
                creatorCellDraft = if (result is ApiResult.Success) "" else _state.value.creatorCellDraft,
            )
        }
    }

    /**
     * Ask the carrier whether the typed credentials authenticate.
     *
     * ⛔ ONLY WHEN THE DRAFT ACTUALLY HOLDS THEM. The route reads PLAINTEXT, UNSAVED credentials out
     * of the request body, so on an ordinary edit — where the secret boxes are deliberately blank —
     * there is nothing to test with, and sending blanks would report the carrier's 401 as though
     * the STORED credentials were broken. [MessagingDraft.canTest] is that gate.
     *
     * ⛔ ONE PRESS, ONE AUTHENTICATED THIRD-PARTY CALL FROM OUR ORIGIN IPS, capped at 10/min per
     * workspace. Nothing here retries and nothing may call this from a recomposition.
     */
    fun testCredentials() {
        val current = _state.value
        val draft = current.draft ?: return
        if (!current.canEditNow || !draft.canTest) return

        _state.value = current.copy(test = MessagingTestState.Running)
        viewModelScope.launch {
            val outcome = repository.testCredentials(
                MessagingTestRequest(
                    workspaceId = workspaceId,
                    providerConfig = draft.toTestProviderConfig(),
                ),
            )
            _state.value = _state.value.copy(
                test = when (outcome) {
                    is MessagingTestOutcome.Passed -> MessagingTestState.Passed(outcome.detail)
                    is MessagingTestOutcome.Rejected -> MessagingTestState.Rejected(outcome.message)
                    is MessagingTestOutcome.Unreachable ->
                        MessagingTestState.Unreachable(outcome.failure.toFailureText())
                },
            )
        }
    }

    /**
     * Read the account list into [MessagingUiState.load].
     *
     * ⛔ DOES NOT TOUCH ANY [SaveState]. See the ⛔ on the class: a failed re-read must not
     * overwrite a successful write's notice.
     */
    private suspend fun read() {
        _state.value = when (val result = repository.messaging(workspaceId)) {
            is ApiResult.Success -> _state.value.copy(load = MessagingLoadState.Ready(result.value))
            is ApiResult.Failure ->
                _state.value.copy(load = MessagingLoadState.Failed(result.toFailureText()))
        }
    }

    companion object {
        fun factory(
            repository: MessagingRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagingViewModel(repository, workspaceId, role) as T
        }
    }
}

/**
 * ⛔ THE 502 GETS ITS OWN, RETRYABLE WORDING AND IS THE ONE 5xx IN THIS APP THAT IS SHOWN AT ALL.
 * `FailureText` refuses to render a 5xx body because several routes return raw exception messages
 * there — but `handleUpsert`'s 502 is an authored sentence naming the number and the carrier, and
 * it means "we could not ASK", not "that number is not yours". The repository has already separated
 * it; this only decides how it reads. ⚠️ Retryable, deliberately: the number stays unclaimed, so
 * pressing save again in a minute is exactly the right thing to do.
 */
internal fun MessagingWriteOutcome.toSaveState(): SaveState = when (this) {
    is MessagingWriteOutcome.Saved -> SaveState.Saved
    is MessagingWriteOutcome.Unverifiable -> SaveState.Failed(
        FailureText(message = UiText.Literal(message), retryable = true),
    )
    is MessagingWriteOutcome.NotSaved -> SaveState.Failed(failure.toFailureText())
}

/**
 * ⚠️ THE SHARED MAPPING FOR THE FOUR ORDINARY WRITES. Their refusals are 4xx — a 403 when the role
 * changed under the operator, a 404 when the list on screen is stale — and `FailureText` already
 * renders a 4xx with the server's own sentence, which is more specific than anything invented here.
 */
internal fun ApiResult<*>.toSaveState(): SaveState = when (this) {
    is ApiResult.Success -> SaveState.Saved
    is ApiResult.Failure -> SaveState.Failed(toFailureText())
}
