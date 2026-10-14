package com.distronode.districtai.ui.scheduling.admin.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.R
import com.distronode.districtai.core.data.SchedulingAdminRepository
import com.distronode.districtai.core.data.SchedulingRepository
import com.distronode.districtai.core.data.calendarStatus
import com.distronode.districtai.core.data.connectionCalendars
import com.distronode.districtai.core.data.deleteCalendarConnection
import com.distronode.districtai.core.data.valueOrNull
import com.distronode.districtai.core.data.zoomStatus
import com.distronode.districtai.core.model.SchedulingCalendarConnection
import com.distronode.districtai.core.model.SchedulingCalendarSelection
import com.distronode.districtai.core.model.SchedulingCalendarStatus
import com.distronode.districtai.core.model.SchedulingZoomStatus
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.scheduling.admin.SchedulingRemoval
import com.distronode.districtai.ui.scheduling.admin.SectionState
import com.distronode.districtai.ui.scheduling.admin.toSectionState
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * @property zoom the member's Zoom status, or null when it could not be read (see [load]).
 * @property calendars each connection's calendars, by connection id. ⛔ A MISSING ENTRY IS NOT AN
 *   EMPTY ONE: absent means the per-connection read failed and the row falls back to the
 *   connection's own flags; an empty list means it was read and nothing is selected.
 * @property connecting a Connect hand-off is being minted.
 * @property connectFailure why the last Connect press opened nothing.
 */
data class SchedulingCalendarUiState(
    val status: SectionState<SchedulingCalendarStatus> = SectionState.Loading,
    val zoom: SchedulingZoomStatus? = null,
    val calendars: Map<String, List<SchedulingCalendarSelection>> = emptyMap(),
    val connecting: Boolean = false,
    val connectFailure: FailureText? = null,
)

/** Everything the calendar screen can ask for. */
interface SchedulingCalendarActions {
    fun back()
    fun refresh()
    fun connect(provider: String)
    fun dismissConnectFailure()
    fun caldav()
    fun choose(connection: SchedulingCalendarConnection)
    fun disconnect(connection: SchedulingCalendarConnection)
    fun dismissDisconnectFailure()
}

/**
 * The member's own calendar accounts, ported from district-ios's `SchedulingCalendarModel`.
 *
 * ⛔ EVERY WRITE HERE IS `viewer`-LEVEL, AND NONE IS GATED ON [SchedulingAccess.canManage]. The
 * `calendar.*` ops touch only the CALLER's own calendars: somebody who cannot connect their own
 * calendar cannot be booked at all, so gating them would break the product for exactly the role the
 * narrow rule exists to protect.
 *
 * ⛔ THE OAuth LEG IS A HAND-OFF, NOT A NATIVE FLOW. Connect opens the provider's consent screen in
 * a browser and the app is never told what happened there, so the status is read again when the
 * member comes back ([onResume]) and the status is what says which accounts are connected.
 */
class SchedulingCalendarViewModel(
    private val admin: SchedulingAdminRepository,
    private val scheduling: SchedulingRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(SchedulingCalendarUiState())
    val state: StateFlow<SchedulingCalendarUiState> = _state.asStateFlow()

    /** CalDAV and the calendar picker. */
    val writes = SchedulingCalendarWrites(viewModelScope, admin, workspaceId, onChanged = ::load)

    /**
     * Unlink one account, behind a confirmation.
     *
     * ⚠️ `provider` AND THE ACCOUNT TRAVEL WITH THE ID: the fork recreates a connection id on every
     * token refresh, so identity is provider plus account.
     */
    val disconnect = SchedulingRemoval<SchedulingCalendarConnection>(
        scope = viewModelScope,
        remove = { admin.deleteCalendarConnection(workspaceId, it.id, it.provider, it.accountEmail) },
        onRemoved = { load() },
    )

    private var reading: Job? = null

    /** Set when a hand-off reached a browser; the next resume re-reads. */
    private var awaitingReturn = false

    init {
        load()
    }

    /**
     * Read the status and Zoom together, then every connection's calendars.
     *
     * ⚠️ THE ZOOM READ IS OPTIONAL AND ITS FAILURE IS SILENT: it reports a separate integration, and
     * a screen that failed over it would hide the calendar connections, which are the point.
     */
    fun load() {
        reading?.cancel()
        _state.value = _state.value.copy(status = SectionState.Loading, calendars = emptyMap())
        reading = viewModelScope.launch {
            val zoom = async { admin.zoomStatus(workspaceId).valueOrNull() }
            val status = admin.calendarStatus(workspaceId).toSectionState()
            _state.value = _state.value.copy(status = status, zoom = zoom.await())
            if (status is SectionState.Ready) {
                _state.value = _state.value.copy(calendars = calendarsOf(status.value.connections))
            }
        }
    }

    /**
     * ⚠️ ONE READ PER CONNECTION, CONCURRENTLY, EACH ALLOWED TO FAIL ALONE, and ⛔ ONLY A SUCCESSFUL
     * READ IS RECORDED: storing an empty list for a failed one would turn "could not read" into
     * "nothing selected".
     */
    private suspend fun calendarsOf(
        connections: List<SchedulingCalendarConnection>,
    ): Map<String, List<SchedulingCalendarSelection>> = coroutineScope {
        connections.map { connection ->
            async {
                val rows = admin.connectionCalendars(
                    workspaceId,
                    connection.id,
                    connection.provider,
                    connection.accountEmail,
                ).valueOrNull()
                rows?.let { connection.id to it }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    /**
     * Mint a hand-off onto [provider]'s consent screen and give it to [launch].
     *
     * ⛔ A NEW ONE PER PRESS, NEVER KEPT: it is single-use and lives about sixty seconds. ⛔ [launch]
     * must not capture an Activity: it is held across the mint, which a rotation can outlast.
     */
    fun connect(provider: String, launch: (String) -> Boolean) {
        if (_state.value.connecting) return
        _state.value = _state.value.copy(connecting = true, connectFailure = null)
        viewModelScope.launch {
            val failure = when (val result = scheduling.calendarConnectHandOff(workspaceId, provider)) {
                is ApiResult.Success -> if (launch(result.value)) {
                    awaitingReturn = true
                    null
                } else {
                    FailureText(UiText.Resource(R.string.settings_browser_missing), retryable = false)
                }
                is ApiResult.Failure -> handOffFailure(result)
            }
            _state.value = _state.value.copy(connecting = false, connectFailure = failure)
        }
    }

    /** The member is back on the screen: re-read if a hand-off sent them away. */
    fun onResume() {
        if (!awaitingReturn) return
        awaitingReturn = false
        load()
    }

    fun dismissConnectFailure() {
        _state.value = _state.value.copy(connectFailure = null)
    }

    companion object {
        /**
         * ⛔ THE TWO PROVIDERS A CONNECT EXISTS FOR, AND THE NAMES ARE THE FORK'S: its calendar
         * service keys on exactly `google` and `microsoft`. CalDAV has no redirect and has its own form.
         */
        val OAUTH_PROVIDERS: List<String> = listOf("google", "microsoft")

        /**
         * Which providers get a Connect button. ⛔ `configured` is the instance's answer and
         * `unconfiguredProviders` narrows it; ⚠️ that list is null, not empty, on a fully configured
         * instance, so null means "none missing".
         */
        fun offeredProviders(status: SchedulingCalendarStatus): List<String> {
            if (!status.configured) return emptyList()
            val unconfigured = status.unconfiguredProviders.orEmpty().toSet()
            return OAUTH_PROVIDERS.filter { it !in unconfigured }
        }

        /**
         * ⚠️ 409 IS NOT A FAULT: the route answers it while the tenancy is still being set up, and
         * the sentence says so rather than reporting something to fix. Everything else is the shared
         * mapping.
         */
        internal fun handOffFailure(failure: ApiResult.Failure): FailureText =
            if (failure is ApiResult.HttpFailure && failure.status == HTTP_CONFLICT) {
                FailureText(
                    UiText.Resource(R.string.scheduling_admin_calendar_connect_not_ready_yet),
                    retryable = false,
                )
            } else {
                failure.toFailureText()
            }

        private const val HTTP_CONFLICT = 409

        fun factory(
            admin: SchedulingAdminRepository,
            scheduling: SchedulingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { SchedulingCalendarViewModel(admin, scheduling, workspaceId) }
        }
    }
}
