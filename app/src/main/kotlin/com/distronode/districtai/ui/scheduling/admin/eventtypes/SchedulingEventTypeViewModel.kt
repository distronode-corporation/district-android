package com.distronode.districtai.ui.scheduling.admin.eventtypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.eventType
import com.distronode.districtai.core.data.eventTypeHosts
import com.distronode.districtai.core.data.eventTypeQuestions
import com.distronode.districtai.core.data.map
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.core.model.SchedulingHost
import com.distronode.districtai.core.model.SchedulingQuestion
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One event type, read three ways.
 *
 * @property canManage whether the write controls are drawn (the status read's answer).
 * @property deleted true once the event type was deleted from here: the screen goes back.
 */
data class SchedulingEventTypeUiState(
    val eventType: SectionState<SchedulingEventType> = SectionState.Loading,
    val hosts: SectionState<List<SchedulingHost>> = SectionState.Loading,
    val questions: SectionState<List<SchedulingQuestion>> = SectionState.Loading,
    val canManage: Boolean = false,
    val deleted: Boolean = false,
)

/** The four writes one event type carries, each opened from the loaded row. */
data class SchedulingEventTypeBar(
    val onEdit: (SchedulingEventType) -> Unit,
    val onManage: (SchedulingEventType) -> Unit,
    val onHosts: (SchedulingEventType) -> Unit,
    val onQuestions: (SchedulingEventType) -> Unit,
)

/**
 * Ported from district-ios's `SchedulingEventTypeModel`.
 *
 * ⚠️ THREE INDEPENDENT READS, THREE INDEPENDENT FAILURES: the hosts and questions are separate ops,
 * and one of them failing must not hide the row that did load.
 *
 * ⚠️ A DELETE IS NOT A REFRESH. The route addresses this screen by slug, so once the row is gone
 * there is nothing to re-read; [SchedulingEventTypeUiState.deleted] sends the screen back instead.
 */
class SchedulingEventTypeViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
    val slug: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingEventTypeUiState())
    val state: StateFlow<SchedulingEventTypeUiState> = _state.asStateFlow()

    val writes = SchedulingEventTypeWrites(
        scope = viewModelScope,
        admin = admin,
        workspaceId = workspaceId,
        onChanged = ::load,
        onDeleted = { _state.value = _state.value.copy(deleted = true) },
    )

    private var reading: Job? = null

    init {
        load()
    }

    fun load() {
        reading?.cancel()
        _state.value = _state.value.copy(
            eventType = SectionState.Loading,
            hosts = SectionState.Loading,
            questions = SectionState.Loading,
        )
        reading = viewModelScope.launch {
            val row = async { admin.eventType(workspaceId, slug) }
            val hosts = async { admin.eventTypeHosts(workspaceId, slug) }
            val questions = async { admin.eventTypeQuestions(workspaceId, slug) }
            val access = scheduling.access(workspaceId)
            _state.value = _state.value.copy(
                eventType = row.await().toSectionState(),
                hosts = hosts.await().toSectionState(),
                // ⚠️ In `position` order, the order the booker sees; the op promises none.
                questions = questions.await().map { it.sortedBy(SchedulingQuestion::position) }.toSectionState(),
                canManage = access.canManage,
            )
        }
    }

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
            slug: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingEventTypeViewModel(admin, scheduling, workspaceId, slug) }
        }
    }
}
