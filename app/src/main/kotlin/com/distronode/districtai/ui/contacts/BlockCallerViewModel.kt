package com.distronode.districtai.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.BlockSubject
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The block control of one caller, on whichever screen shows it (a contact, a thread).
 *
 * ⛔ THE STATE SHOWN IS THE STORE'S, read again on every change, never remembered here: a block made
 * from a thread must flip the contact's control the next time it is looked at.
 *
 * ⚠️ A SECOND WRITE WHILE ONE IS IN FLIGHT DOES NOTHING. The route is idempotent, so a lost second
 * tap costs nothing, while two in flight would leave the list written by whichever answered last.
 *
 * ⚠️ `viewer` IS NEVER OFFERED THE CONTROL (the route excludes it server-side); the screens gate it.
 */
class BlockCallerViewModel(
    private val store: BlockedContactsStore,
    private val workspaceId: String,
    private val subject: BlockSubject,
) : ViewModel() {

    /** Whether the caller is blocked now. ⚠️ Always false for a caller known only by number. */
    val blocked: StateFlow<Boolean> = store.state
        .map { it.isBlocked(workspaceId, (subject as? BlockSubject.Contact)?.contactId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The last write's failure, shown beside the screen's content, never instead of it. */
    private val _failure = MutableStateFlow<FailureText?>(null)
    val failure: StateFlow<FailureText?> = _failure.asStateFlow()

    /** Read the blocked list, silently: a failure keeps what is known. */
    fun refresh() {
        viewModelScope.launch { store.refresh(workspaceId) }
    }

    /**
     * Leave the caller [blocked]. [onBlocked] runs only after the server confirmed a BLOCK, never an
     * unblock: the thread screen closes on one and must stay open on the other.
     *
     * ⚠️ THE CONFIRMATION LIVES IN THE SCREEN; this assumes it happened.
     */
    fun setBlocked(blocked: Boolean, onBlocked: () -> Unit = {}) {
        if (_busy.value) return
        _busy.value = true
        _failure.value = null
        viewModelScope.launch {
            when (val result = store.setBlocked(workspaceId, subject, blocked)) {
                is ApiResult.Success -> if (blocked) onBlocked()
                is ApiResult.Failure -> _failure.value = result.toFailureText()
            }
            _busy.value = false
        }
    }

    fun clearFailure() {
        _failure.value = null
    }

    companion object {
        fun factory(
            store: BlockedContactsStore,
            workspaceId: String,
            subject: BlockSubject,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BlockCallerViewModel(store, workspaceId, subject) }
        }
    }
}
