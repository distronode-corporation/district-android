package com.distronode.districtai.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.distronode.districtai.core.data.ContactsRepository
import com.distronode.districtai.core.model.Contact
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The paged CRM for one workspace, plus creating a contact.
 *
 * ⛔ `cachedIn(viewModelScope)` IS NOT OPTIONAL. Without it the Pager's flow is cold and restarts on
 * every collection, so a configuration change — or simply opening a contact and coming back —
 * refetches from page one and loses the scroll position.
 */
class ContactsViewModel(
    private val repository: ContactsRepository,
    val workspaceId: String,
    /**
     * The caller's role in this workspace, for deciding which controls to OFFER.
     *
     * ⚠️ THIS IS THE MEMBERSHIP ROLE FROM THE WORKSPACE LIST, WHICH CAN UNDERSTATE ACCESS. Support
     * access resolves to "agency" for any workspace, so a caller with it may be permitted more than
     * this says. Erring low is the correct direction: the gate is an affordance, and hiding a
     * button a user could have used is far better than offering one that 403s. The server remains the
     * authority either way.
     */
    role: WorkspaceRole?,
) : ViewModel() {

    /** Whether to offer create/edit/delete. All three exclude `viewer` server-side. */
    val canMutate: Boolean = role.allowsMutation()

    val contacts: Flow<PagingData<Contact>> =
        repository.contactList(workspaceId).cachedIn(viewModelScope)

    private val _createState = MutableStateFlow<CreateContactUiState>(CreateContactUiState.Idle)
    val createState: StateFlow<CreateContactUiState> = _createState.asStateFlow()

    /**
     * Create a contact.
     *
     * ⚠️ A contact needs a phone number **OR** an email address — contacts became email-first, and the
     * database allows any number of phone-less rows. Requiring both would refuse legitimate input; the
     * caller-side check below only refuses when BOTH are absent, which the server would reject anyway.
     *
     * ⚠️ Refuses locally when the role does not permit it, so the app never fires a request it knows
     * will 403. That is an affordance, NOT a security control.
     */
    fun create(name: String, phoneNumber: String, email: String) {
        if (!canMutate || _createState.value is CreateContactUiState.Saving) return

        val trimmedName = name.trim()
        val trimmedPhone = phoneNumber.trim()
        val trimmedEmail = email.trim()
        if (trimmedName.isBlank() || (trimmedPhone.isBlank() && trimmedEmail.isBlank())) return

        _createState.value = CreateContactUiState.Saving
        viewModelScope.launch {
            _createState.value = when (
                val result = repository.create(
                    workspaceId = workspaceId,
                    name = trimmedName,
                    phoneNumber = trimmedPhone,
                    email = trimmedEmail,
                )
            ) {
                is ApiResult.Success -> CreateContactUiState.Created(result.value)
                // ⚠️ A 409 means a contact with that phone or email already exists — the database
                // enforces one per phone and one per lowercased email per workspace. That is a
                // duplicate, not a server fault, and the message says so.
                is ApiResult.Failure -> CreateContactUiState.Failed(result.toFailureText())
            }
        }
    }

    /** Return to Idle, e.g. after the list has been refreshed or the sheet dismissed. */
    fun clearCreateState() {
        _createState.value = CreateContactUiState.Idle
    }

    companion object {
        fun factory(
            repository: ContactsRepository,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ContactsViewModel(repository, workspaceId, role) }
        }
    }
}

sealed interface CreateContactUiState {
    data object Idle : CreateContactUiState
    data object Saving : CreateContactUiState

    /** Carries the new id so the caller can refresh the list and optionally open the contact. */
    data class Created(val contactId: String) : CreateContactUiState
    data class Failed(val failure: FailureText) : CreateContactUiState
}
