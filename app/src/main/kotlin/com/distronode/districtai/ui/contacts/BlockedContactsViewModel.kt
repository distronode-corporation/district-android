package com.distronode.districtai.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.model.BlockedContact
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The blocked-callers list. */
sealed interface BlockedContactsUiState {
    data object Loading : BlockedContactsUiState

    /** ⛔ The list could not be read: never drawn as "no one is blocked". */
    data class Failed(val failure: FailureText) : BlockedContactsUiState

    data class Content(
        val contacts: List<BlockedContact>,
        /** The caller being unblocked, whose row is disabled until the server answers. */
        val unblocking: String? = null,
        val failure: FailureText? = null,
    ) : BlockedContactsUiState
}

/**
 * Who this workspace has blocked, with an Unblock on each (Windows `BlockedPage`).
 *
 * ⚠️ THE ROWS ARE THE SHARED STORE'S, so an unblock here takes the badge off the contact at once.
 */
class BlockedContactsViewModel(
    private val store: BlockedContactsStore,
    private val workspaceId: String,
    role: WorkspaceRole?,
) : ViewModel() {

    /** ⚠️ `viewer` may read the list and is offered no Unblock: the route excludes it. */
    val canMutate: Boolean = role.allowsMutation()

    private val read = MutableStateFlow<ReadState>(ReadState.Loading)
    private val unblocking = MutableStateFlow<String?>(null)
    private val failure = MutableStateFlow<FailureText?>(null)

    private sealed interface ReadState {
        data object Loading : ReadState
        data object Loaded : ReadState
        data class Failed(val failure: FailureText) : ReadState
    }

    val state: StateFlow<BlockedContactsUiState> =
        combine(read, store.state, unblocking, failure) { read, snapshot, unblocking, failure ->
            when (read) {
                ReadState.Loading -> BlockedContactsUiState.Loading
                is ReadState.Failed -> BlockedContactsUiState.Failed(read.failure)
                ReadState.Loaded -> BlockedContactsUiState.Content(
                    contacts = if (snapshot.workspaceId == workspaceId) snapshot.contacts else emptyList(),
                    unblocking = unblocking,
                    failure = failure,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, BlockedContactsUiState.Loading)

    init {
        load()
    }

    fun load() {
        read.value = ReadState.Loading
        viewModelScope.launch {
            read.value = when (val result = store.refresh(workspaceId)) {
                is ApiResult.Success -> ReadState.Loaded
                is ApiResult.Failure -> ReadState.Failed(result.toFailureText())
            }
        }
    }

    /** Unblock one caller. ⚠️ The confirmation lives in the screen; one write at a time. */
    fun unblock(contactId: String) {
        if (!canMutate || unblocking.value != null) return
        unblocking.value = contactId
        failure.value = null
        viewModelScope.launch {
            val result = store.setBlocked(workspaceId, BlockSubject.Contact(contactId), blocked = false)
            if (result is ApiResult.Failure) failure.value = result.toFailureText()
            unblocking.value = null
        }
    }

    fun clearFailure() {
        failure.value = null
    }

    companion object {
        fun factory(
            store: BlockedContactsStore,
            workspaceId: String,
            role: WorkspaceRole?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BlockedContactsViewModel(store, workspaceId, role) }
        }
    }
}
