package com.distronode.districtai.ui.scheduling.admin.overview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingBookingFilter
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.availabilityRules
import com.distronode.districtai.core.data.bookings
import com.distronode.districtai.core.data.calendarStatus
import com.distronode.districtai.core.data.eventTypes
import com.distronode.districtai.core.data.map
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.model.SchedulingAvailabilityRule
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.OnSessionChanged
import com.distronode.districtai.ui.scheduling.admin.SchedulingAdminDeps
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.UTC
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.displayTimezone
import com.distronode.districtai.ui.scheduling.admin.schedulingToday
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The booking desk: the five facts a member checks first, each read on its own so one slow or
 * failed read does not blank the others.
 *
 * @property timezone the member's own zone, from `me.get`; every time on the screen is in it.
 * @property publicHost the tenancy's booking host, for the booking links. Null until it is known.
 */
data class SchedulingOverviewUiState(
    val eventTypes: SectionState<List<SchedulingEventType>> = SectionState.Loading,
    val calendar: SectionState<SchedulingCalendarStatus> = SectionState.Loading,
    val rules: SectionState<List<SchedulingAvailabilityRule>> = SectionState.Loading,
    val bookings: SectionState<List<SchedulingBooking>> = SectionState.Loading,
    val timezone: String = UTC,
    val publicHost: String? = null,
)

/**
 * Reads the booking desk, ported from district-ios's `SchedulingOverviewModel`.
 *
 * ⚠️ THE BOOKINGS WAIT FOR THE PROFILE, because "upcoming from today" means today in the MEMBER's
 * zone, and the status read decides whether to ask for the whole tenancy's bookings (`canManage`)
 * or the member's own. The other three reads go out together.
 */
class SchedulingOverviewViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingOverviewUiState())
    val state: StateFlow<SchedulingOverviewUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = SchedulingOverviewUiState()
        viewModelScope.launch {
            val access = scheduling.access(workspaceId)
            val profile = async { admin.me(workspaceId) }
            val types = async { admin.eventTypes(workspaceId) }
            val calendar = async { admin.calendarStatus(workspaceId) }
            val rules = async { admin.availabilityRules(workspaceId) }
            val timezone = profile.await().map { it.displayTimezone }.valueOrNull() ?: UTC
            _state.value = _state.value.copy(
                timezone = timezone,
                publicHost = access.publicHost,
                eventTypes = types.await().toSectionState(),
                calendar = calendar.await().toSectionState(),
                rules = rules.await().toSectionState(),
            )
            val page = admin.bookings(
                workspaceId,
                SchedulingBookingFilter(
                    whenFilter = "upcoming",
                    from = schedulingToday(timezone, now()),
                    limit = COMING_UP_LIMIT,
                    allHosts = access.canManage,
                    order = "asc",
                ),
            )
            _state.value = _state.value.copy(bookings = page.map { it.items }.toSectionState())
        }
    }

    companion object {
        /** How many upcoming bookings the desk lists, the reference's ten. */
        const val COMING_UP_LIMIT: Int = 10

        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingOverviewViewModel(admin, scheduling, workspaceId) }
        }
    }
}

/** The overview section, wired to its ViewModel. */
@Composable
fun SchedulingOverviewRoute(deps: SchedulingAdminDeps) {
    val viewModel: SchedulingOverviewViewModel = viewModel(
        key = "scheduling-overview-${deps.workspaceId}",
        factory = SchedulingOverviewViewModel.factory(deps.admin, deps.scheduling, deps.workspaceId),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnSessionChanged(deps.sessionEpoch) { viewModel.load() }
    SchedulingOverviewScreen(state = state, onBack = deps.onBack, onRefresh = viewModel::load, now = Instant.now())
}
