package com.distronode.districtai.ui.calls

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.distronode.districtai.core.data.CallsRepository
import com.distronode.districtai.core.model.CallSummary
import kotlinx.coroutines.flow.Flow

/**
 * Holds the paged call log for one workspace.
 *
 * ⛔ `cachedIn(viewModelScope)` IS NOT OPTIONAL HERE. Without it the Pager's flow is cold and
 * restarts on every collection, so a configuration change — or simply navigating to a call and
 * back — refetches from page one and loses the scroll position. It also makes the flow safe to
 * collect more than once, which a recomposing screen does.
 *
 * ⚠️ The workspace is fixed for the lifetime of this ViewModel. Switching workspace must build a
 * new one rather than mutate this: the paging source's offsets and its deduplication set are only
 * meaningful within one tenant, and reusing them across a switch would mix two workspaces' rows.
 */
class CallLogViewModel(
    repository: CallsRepository,
    val workspaceId: String,
) : ViewModel() {

    val calls: Flow<PagingData<CallSummary>> =
        repository.callLog(workspaceId).cachedIn(viewModelScope)

    companion object {
        /**
         * ⚠️ Keyed on the workspace by the caller, not here — see the class note. The factory takes
         * the id so the ViewModelStore holds one instance per workspace rather than one that
         * silently changes tenant underneath its cached pages.
         */
        fun factory(
            repository: CallsRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CallLogViewModel(repository, workspaceId) }
        }
    }
}
