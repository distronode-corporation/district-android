package com.distronode.districtai.ui.scheduling.admin.bookings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingAdminResult
import com.distronode.districtai.core.data.SchedulingBookingFilter
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.bookings
import com.distronode.districtai.core.data.eventTypes
import com.distronode.districtai.core.data.me
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.model.SchedulingBooking
import com.distronode.districtai.core.model.SchedulingEventType
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.UTC
import com.distronode.districtai.ui.scheduling.admin.access
import com.distronode.districtai.ui.scheduling.admin.displayTimezone
import com.distronode.districtai.ui.scheduling.admin.schedulingInstantOrNull
import com.distronode.districtai.ui.scheduling.admin.schedulingZone
import com.distronode.districtai.ui.scheduling.admin.toFailureText
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The four ways the bookings list is filtered, after district-core-swift's `SchedulingBookingView`.
 *
 * ⚠️ UPCOMING AND PAST ARE CONFIRMED BOOKINGS ONLY, and upcoming reads soonest first while every
 * other view reads newest first.
 */
enum class SchedulingBookingView(
    val pathValue: String,
    @get:StringRes val label: Int,
    @get:StringRes val empty: Int,
) {
    UPCOMING(
        "upcoming",
        R.string.scheduling_admin_bookings_view_upcoming,
        R.string.scheduling_admin_bookings_empty_upcoming,
    ),
    PAST("past", R.string.scheduling_admin_bookings_view_past, R.string.scheduling_admin_bookings_empty_past),
    CANCELLED(
        "cancelled",
        R.string.scheduling_admin_bookings_view_cancelled,
        R.string.scheduling_admin_bookings_empty_cancelled,
    ),
    ALL("all", R.string.scheduling_admin_bookings_view_all, R.string.scheduling_admin_bookings_empty_upcoming),
    ;

    /** The `bookings.list` filter for one page of this view. */
    fun filter(limit: Int, offset: Int, allHosts: Boolean): SchedulingBookingFilter = SchedulingBookingFilter(
        status = when (this) {
            UPCOMING, PAST -> "confirmed"
            CANCELLED -> "cancelled"
            ALL -> null
        },
        whenFilter = when (this) {
            UPCOMING -> "upcoming"
            PAST -> "past"
            CANCELLED, ALL -> null
        },
        limit = limit,
        offset = offset,
        allHosts = allHosts,
        order = if (this == UPCOMING) "asc" else "desc",
    )
}

/**
 * @property list the first page: loading, failed, or in hand ([rows]).
 * @property appendFailure the last Load more that failed, shown above the button that retries it.
 * @property canManage whether the write controls are drawn (the status read's answer).
 * @property schedulerIsAdmin whether Reassign is offered: the scheduler's own admin bit.
 */
data class SchedulingBookingsUiState(
    val view: SchedulingBookingView = SchedulingBookingView.UPCOMING,
    val list: SectionState<Unit> = SectionState.Loading,
    val rows: List<SchedulingBooking> = emptyList(),
    val loadingMore: Boolean = false,
    val isEnd: Boolean = false,
    val appendFailure: FailureText? = null,
    val timezone: String = UTC,
    val canManage: Boolean = false,
    val schedulerIsAdmin: Boolean = false,
    val eventTypes: List<SchedulingEventType> = emptyList(),
)

/**
 * Every booking in the tenancy, a page at a time, ported from district-ios's
 * `SchedulingBookingsModel`.
 *
 * ⚠️ A MEMBER WHO MAY MANAGE SEES THE WHOLE TENANCY (`scope: all`); anyone else sees their own.
 */
class SchedulingBookingsViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingBookingsUiState())
    val state: StateFlow<SchedulingBookingsUiState> = _state.asStateFlow()

    val writes = SchedulingBookingWrites(
        scope = viewModelScope,
        admin = admin,
        workspaceId = workspaceId,
        timezone = { _state.value.timezone },
        today = { todayIn(_state.value.timezone, now()) },
        onChanged = ::load,
    )

    private var page: Job? = null

    init {
        load()
    }

    /** Read the context (status, profile, event types), then the first page of the current view. */
    fun load() {
        page?.cancel()
        _state.value = _state.value.copy(
            list = SectionState.Loading,
            rows = emptyList(),
            isEnd = false,
            appendFailure = null,
        )
        page = viewModelScope.launch {
            val profile = async { admin.me(workspaceId) }
            val types = async { admin.eventTypes(workspaceId) }
            val access = scheduling.access(workspaceId)
            val me = profile.await().valueOrNull()
            _state.value = _state.value.copy(
                canManage = access.canManage,
                timezone = me?.displayTimezone ?: UTC,
                schedulerIsAdmin = me?.isAdmin == true,
                eventTypes = types.await().valueOrNull().orEmpty(),
            )
            fetchPage()
        }
    }

    /** Switch views. ⚠️ The context stays: only the list is read again. */
    fun selectView(view: SchedulingBookingView) {
        if (view == _state.value.view) return
        page?.cancel()
        _state.value = _state.value.copy(
            view = view,
            list = SectionState.Loading,
            rows = emptyList(),
            isEnd = false,
            appendFailure = null,
        )
        page = viewModelScope.launch { fetchPage() }
    }

    /** The next page. Ignored while one is on its way or after the last. */
    fun loadMore() {
        val current = _state.value
        if (current.loadingMore || current.isEnd || current.list !is SectionState.Ready) return
        _state.value = current.copy(loadingMore = true, appendFailure = null)
        page = viewModelScope.launch { fetchPage() }
    }

    /**
     * ⚠️ THE END IS A SHORT PAGE OR THE TOTAL REACHED, as district-ios's `OffsetPager` decides it: the
     * server may or may not send `total`, and a full page with no total means "ask again".
     */
    private suspend fun fetchPage() {
        val current = _state.value
        val offset = current.rows.size
        val filter = current.view.filter(PAGE_SIZE, offset, allHosts = current.canManage)
        when (val result = admin.bookings(workspaceId, filter)) {
            is SchedulingAdminResult.Ok -> {
                val rows = current.rows + result.value.items
                val total = result.value.total
                _state.value = _state.value.copy(
                    list = SectionState.Ready(Unit),
                    rows = rows,
                    loadingMore = false,
                    isEnd = result.value.items.size < PAGE_SIZE || (total != null && rows.size >= total),
                )
            }
            is SchedulingAdminResult.Failed -> _state.value = if (current.rows.isEmpty()) {
                _state.value.copy(list = SectionState.Failed(result.error.toFailureText()), loadingMore = false)
            } else {
                _state.value.copy(appendFailure = result.error.toFailureText(), loadingMore = false)
            }
        }
    }

    /**
     * Whether the write controls apply to [booking]: confirmed and not yet over. ⚠️ A booking whose
     * end cannot be read is not actionable; there is no honest default.
     */
    fun isActionable(booking: SchedulingBooking): Boolean {
        if (booking.status != "confirmed") return false
        val end = schedulingInstantOrNull(booking.endAt) ?: return false
        return end.isAfter(now())
    }

    companion object {
        const val PAGE_SIZE: Int = 25

        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingBookingsViewModel(admin, scheduling, workspaceId) }
        }
    }
}

/** Today in [timezone]: the first day a booking may move to. */
internal fun todayIn(timezone: String, now: Instant): LocalDate = now.atZone(schedulingZone(timezone)).toLocalDate()
