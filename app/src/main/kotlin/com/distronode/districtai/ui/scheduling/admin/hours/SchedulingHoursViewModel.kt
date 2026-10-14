package com.distronode.districtai.ui.scheduling.admin.hours

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.availabilityOverrides
import com.distronode.districtai.core.data.availabilityRules
import com.distronode.districtai.core.data.map
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.UTC
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.displayTimezone
import com.distronode.districtai.ui.scheduling.admin.schedulingZone
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @property rules every rule the read found; the screen draws the member's own (see
 *   [schedulingWeekFromRules]).
 * @property overrides the overrides not yet over, ranges folded.
 * @property today the member's today, in their zone: the override filter and the date picker's start.
 * @property canManage whether the editors are drawn (the status read's answer).
 */
data class SchedulingHoursUiState(
    val rules: SectionState<List<SchedulingAvailabilityRule>> = SectionState.Loading,
    val overrides: SectionState<List<SchedulingOverrideRow>> = SectionState.Loading,
    val timezone: String = UTC,
    val today: LocalDate,
    val canManage: Boolean = false,
)

/**
 * The weekly hours the member is bookable and the dates they are not, ported from district-ios's
 * `SchedulingHoursModel`.
 *
 * ⚠️ THE PROFILE IS READ FIRST, BECAUSE THE OVERRIDE FILTER NEEDS TODAY IN THE MEMBER'S ZONE: asking
 * in the device's zone drops a day off that has not started yet for anybody east of it. ⛔ A failed
 * profile read is silent and reads as UTC, as in district-ios and on the web: the zone only decides
 * which day is today and how the times are captioned.
 */
class SchedulingHoursViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingHoursUiState(today = todayIn(UTC)))
    val state: StateFlow<SchedulingHoursUiState> = _state.asStateFlow()

    /** The week editor; a save re-reads this screen. */
    val rulesEditor = SchedulingHoursRulesEditor(viewModelScope, admin, workspaceId, onChanged = ::load)

    /** Add and delete overrides; each one that works re-reads this screen. */
    val overrideWrites = SchedulingHoursOverrideWrites(viewModelScope, admin, workspaceId, onChanged = ::load)

    private var reading: Job? = null

    init {
        load()
    }

    fun load() {
        reading?.cancel()
        _state.value = _state.value.copy(rules = SectionState.Loading, overrides = SectionState.Loading)
        reading = viewModelScope.launch {
            val access = async { scheduling.access(workspaceId) }
            val timezone = admin.me(workspaceId).map { it.displayTimezone }.valueOrNull() ?: UTC
            val today = todayIn(timezone)
            val rules = async { admin.availabilityRules(workspaceId) }
            val overrides = async { admin.availabilityOverrides(workspaceId) }
            _state.value = SchedulingHoursUiState(
                rules = rules.await().toSectionState(),
                overrides = overrides.await()
                    .map { schedulingUpcomingOverrides(it, today.toString()) }
                    .toSectionState(),
                timezone = timezone,
                today = today,
                canManage = access.await().canManage,
            )
        }
    }

    private fun todayIn(timezone: String): LocalDate = now().atZone(schedulingZone(timezone)).toLocalDate()

    companion object {
        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingHoursViewModel(admin, scheduling, workspaceId) }
        }
    }
}
