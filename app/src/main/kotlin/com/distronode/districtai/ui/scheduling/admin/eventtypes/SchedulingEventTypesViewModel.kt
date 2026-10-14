package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.eventTypes
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.scheduling.admin.SchedulingAccess
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import com.distronode.districtai.ui.scheduling.admin.valueOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @property list every event type, in the order the scheduler serves them.
 * @property access whether Create is drawn, and the public host the booking page addresses use.
 */
data class SchedulingEventTypesUiState(
    val list: SectionState<List<SchedulingEventType>> = SectionState.Loading,
    val access: SchedulingAccess = SchedulingAccess(),
)

/**
 * Every event type in the tenancy, ported from district-ios's `SchedulingEventTypesModel`.
 *
 * ⚠️ UNPAGED, BECAUSE THE OP IS: `eventTypes.list` takes no window. ⚠️ NOTHING IS READ IN `init`:
 * the screen reads on every appearance, as the reference's `.task` does, so a row renamed or deleted
 * on the event type's own screen is gone when the member comes back.
 */
class SchedulingEventTypesViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingEventTypesUiState())
    val state: StateFlow<SchedulingEventTypesUiState> = _state.asStateFlow()

    val writes = SchedulingEventTypeWrites(viewModelScope, admin, workspaceId, onChanged = ::load, onDeleted = ::load)

    private var reading: Job? = null

    /** ⚠️ The status and the list are sent together; the status's host is applied with the rows. */
    fun load() {
        reading?.cancel()
        _state.value = _state.value.copy(list = SectionState.Loading)
        reading = viewModelScope.launch {
            val list = async { admin.eventTypes(workspaceId) }
            val access = scheduling.access(workspaceId)
            _state.value = SchedulingEventTypesUiState(list = list.await().toSectionState(), access = access)
        }
    }

    /**
     * Open the create dialog. ⛔ ONLY OVER A LIST IN HAND: the new slug is made unique against the
     * rows on screen, and checking it against a failed read's empty set would let the server refuse it
     * after the form was filled in.
     */
    fun create() {
        val rows = _state.value.list.valueOrNull ?: return
        writes.editor.openCreate(rows.map { it.slug })
    }

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingEventTypesViewModel(admin, scheduling, workspaceId) }
        }
    }
}
