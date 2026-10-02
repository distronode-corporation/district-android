package com.distronode.districtai.ui.settings.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.MemberMutationOutcome
import com.distronode.districtai.core.data.MembersRepository
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.toWire
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The members screen's state machine.
 *
 * ⛔ THE TWO ROLE GATES ARE COMPUTED ONCE, HERE, FROM THE ROLE THE ROUTE CARRIED — and they are
 * DIFFERENT gates. Membership writes are agency-only, the narrowest allow-list in the API, because
 * these rows are what `getWorkspaceRole` answers from; `workspace/rename` admits agency and client.
 * ⚠️ Neither is a security boundary: the server re-checks on every request and can legitimately
 * answer 403 when a role changed between the roster being read and a button being pressed. Hiding
 * a control that would 403 is a better experience, and that is its entire job.
 *
 * ⛔ EVERY WRITE RE-READS THE ROSTER, INCLUDING A FAILED ONE. The list is ordered `createdAt asc`
 * server-side and a removal echoes no row at all, so there is nothing to patch locally that would
 * be reliably right. Re-reading after a FAILURE matters too: a `last_agency_member` refusal
 * usually means the operator's copy of who holds `agency` is out of date, and leaving the stale
 * list on screen would invite the same refusal again.
 *
 * ⛔ AND THE TWO 409s GET THEIR OWN WORDING RATHER THAN THE SERVER'S SENTENCE. The repository has
 * already turned the machine-readable codes into [MemberMutationOutcome.Duplicate] and
 * [MemberMutationOutcome.LastAgency]; this maps them to resources, so the copy is translatable and
 * nothing here compares English strings. Neither is retryable — pressing the button again produces
 * the identical refusal.
 *
 * ⚠️ THE RENAME FIELD IS NEVER PREFILLED. Nothing this client can call returns the workspace's
 * current name, so a seeded field could only be blank — which is the shape that saves a blank over
 * a real value. See the ⛔ on [MembersUiState].
 */
class MembersViewModel(
    private val repository: MembersRepository,
    private val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    private val _state = MutableStateFlow(
        MembersUiState(
            canManage = role.canManageMembers(),
            canRename = role.canRenameWorkspace(),
        ),
    )
    val state: StateFlow<MembersUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** ⚠️ An idempotent GET, so replaying it on a session change is unconditionally safe. */
    fun load() {
        _state.value = _state.value.copy(list = MembersListState.Loading)
        viewModelScope.launch { readRoster() }
    }

    fun editEmail(value: String) {
        _state.value = _state.value.copy(draftEmail = value, addRejected = false)
    }

    fun editRole(role: WorkspaceRole) {
        _state.value = _state.value.copy(draftRole = role)
    }

    fun editName(value: String) {
        _state.value = _state.value.copy(renameDraft = value)
    }

    /**
     * Add one member.
     *
     * ⚠️ NO INVITATION IS SENT AND NO ACCOUNT IS CREATED. Success means a row exists; if that
     * address has never signed up, the row simply waits for it. The screen says so, because an
     * operator who expects an email will otherwise wait for one that is not coming.
     *
     * ⚠️ THE DRAFT IS CLEARED ONLY ON SUCCESS — including on a duplicate, where keeping the address
     * on screen is what lets the operator see WHICH one was already there.
     */
    fun addMember() {
        val current = _state.value
        if (current.busy || !current.canManage) return
        if (!current.canAdd) {
            _state.value = current.copy(addRejected = true)
            return
        }

        _state.value = current.copy(addSave = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.addMember(
                workspaceId = workspaceId,
                // ⚠️ Normalised the same way the route does before it stores or compares.
                email = current.draftEmail.trim().lowercase(),
                role = current.draftRole.toWire(),
            )
            val saved = outcome is MemberMutationOutcome.Done
            _state.value = _state.value.copy(
                addSave = outcome.toSaveState(),
                draftEmail = if (saved) "" else _state.value.draftEmail,
            )
            readRoster()
        }
    }

    /**
     * Change one member's role.
     *
     * ⛔ MAY BE REFUSED WITH `last_agency_member`, which is not a mistake the operator made — see
     * [MemberMutationOutcome.LastAgency]. The confirmation for it lives on the screen only in the
     * sense that the message is shown there; nothing here retries.
     */
    fun changeRole(email: String, role: WorkspaceRole) {
        val current = _state.value
        if (current.busy || !current.canManage) return

        _state.value = current.copy(roleSave = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.changeRole(workspaceId, email, role.toWire())
            _state.value = _state.value.copy(roleSave = outcome.toSaveState())
            readRoster()
        }
    }

    /**
     * Remove one member.
     *
     * ⛔ THE CONFIRMATION IS THE SCREEN'S AND HAS ALREADY HAPPENED. Removing someone ends their
     * access to every call, contact and conversation in this workspace immediately — the next
     * request they make resolves no role — and re-adding them is a new row, not an undo.
     */
    fun removeMember(email: String) {
        val current = _state.value
        if (current.busy || !current.canManage) return

        _state.value = current.copy(removeSave = SaveState.Saving)
        viewModelScope.launch {
            val outcome = repository.removeMember(workspaceId, email)
            _state.value = _state.value.copy(removeSave = outcome.toSaveState())
            readRoster()
        }
    }

    /**
     * Rename the workspace.
     *
     * ⛔ THE SERVER'S ECHO IS ADOPTED, NEVER THE TYPED STRING. The route trims before it measures
     * and returns what it stored, so displaying the typed value would show a name nobody saved —
     * and would hide the trim from an operator who typed trailing spaces.
     *
     * ⚠️ NO ROSTER RE-READ. Renaming changes nothing about who belongs here, and re-reading would
     * only give a failed list read a chance to replace a successful rename notice.
     */
    fun rename() {
        val current = _state.value
        if (!current.canRenameNow) return

        _state.value = current.copy(renameSave = SaveState.Saving)
        viewModelScope.launch {
            _state.value = when (val result = repository.rename(workspaceId, current.renameDraft.trim())) {
                is ApiResult.Success -> _state.value.copy(
                    storedName = result.value,
                    renameDraft = "",
                    renameSave = SaveState.Saved,
                )
                is ApiResult.Failure -> _state.value.copy(
                    // ⚠️ THE DRAFT SURVIVES A FAILURE. Losing a typed name because the save failed
                    // would be two losses for one fault.
                    renameSave = SaveState.Failed(result.toFailureText()),
                )
            }
        }
    }

    /**
     * Read the roster into [MembersUiState.list].
     *
     * ⛔ A FAILED RE-READ DOES NOT OVERWRITE THE WRITE'S OWN NOTICE. The two are separate fields
     * precisely so "the change landed" and "we could not re-read the list" can both be true and
     * both be said — the mistake in the other direction is telling an operator their change failed
     * when only the read did, which invites them to make it again.
     */
    private suspend fun readRoster() {
        _state.value = when (val result = repository.members(workspaceId)) {
            is ApiResult.Success -> _state.value.copy(list = MembersListState.Ready(result.value))
            is ApiResult.Failure ->
                _state.value.copy(list = MembersListState.Failed(result.toFailureText()))
        }
    }

    companion object {
        fun factory(
            repository: MembersRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MembersViewModel(repository, workspaceId, role) }
        }
    }
}

/**
 * The one place a membership outcome becomes something the screen can show.
 *
 * ⛔ THE TWO 409s ARE OUR OWN COPY, NOT THE SERVER'S SENTENCE, AND NEITHER IS RETRYABLE. The
 * server's wording is written for an operator reading a log ("Cannot demote the last agency member
 * — the workspace would have no administrator"); the screen needs one plain line, translatable,
 * that says what to do instead. And a retry button on either would be dishonest: pressing again
 * produces the identical refusal, because nothing about the request was unlucky.
 *
 * ⚠️ EVERYTHING ELSE KEEPS THE SHARED MAPPING. `Failed` goes through [toFailureText] like every
 * other failure in the app, which is what keeps "offline", "signed out", "rate limited" and a 5xx
 * saying the same thing here as they do on every other screen — including the rule that a 5xx body
 * is never rendered verbatim.
 */
internal fun MemberMutationOutcome.toSaveState(): SaveState = when (this) {
    is MemberMutationOutcome.Done -> SaveState.Saved
    MemberMutationOutcome.Duplicate -> SaveState.Failed(
        FailureText(
            message = UiText.Resource(R.string.members_error_duplicate),
            retryable = false,
        ),
    )
    MemberMutationOutcome.LastAgency -> SaveState.Failed(
        FailureText(
            message = UiText.Resource(R.string.members_error_last_agency),
            retryable = false,
        ),
    )
    is MemberMutationOutcome.Failed -> SaveState.Failed(failure.toFailureText())
}
