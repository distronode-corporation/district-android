package com.distronode.districtai.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.OverviewRepository
import com.distronode.districtai.core.data.SetupRepository
import com.distronode.districtai.core.data.WorkspaceRepository
import com.distronode.districtai.core.data.WorkspaceState
import com.distronode.districtai.R
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.UiText
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the overview screen: resolve the active workspace, then load its overview.
 *
 * ⛔ TWO REQUESTS, IN ORDER, AND THE ORDER IS LOAD-BEARING. The overview MUST be asked for a
 * specific `workspaceId`, and only the workspace list can say which ids are legal. Firing them in
 * parallel would mean either guessing an id or omitting it — and omitting it makes the SERVER
 * choose, falling back to index 0 of its own membership listing, which cannot know what the user
 * selected in this app because that selection is local state rather than a cookie. The result
 * would be a screen labelled with one workspace's name showing another workspace's numbers, with
 * no error anywhere.
 */
class OverviewViewModel(
    private val workspaceRepository: WorkspaceRepository,
    private val overviewRepository: OverviewRepository,
    /**
     * ⚠️ OPTIONAL, AND NULL MEANS "NEVER SHOW THE SETUP CARD". The card is an extra read that must
     * not be able to affect the rest of the screen, so a screen built without it is still whole.
     */
    private val setupRepository: SetupRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow<OverviewUiState>(OverviewUiState.Loading)
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * @param refreshing keep existing content visible while re-reading, so a pull-to-refresh does
     *   not flash the screen back to a spinner.
     */
    fun load(refreshing: Boolean = false) {
        val existing = _state.value
        _state.value = if (refreshing && existing is OverviewUiState.Content) {
            existing.copy(refreshing = true)
        } else {
            OverviewUiState.Loading
        }

        viewModelScope.launch {
            when (val workspaces = workspaceRepository.load()) {
                is ApiResult.Success -> loadOverview(workspaces.value)
                is ApiResult.Failure -> _state.value = workspaces.toUiState()
            }
        }
    }

    /** Switch workspace, persist the choice, and re-read. */
    fun selectWorkspace(workspaceId: String) {
        workspaceRepository.select(workspaceId)
        load()
    }

    private suspend fun loadOverview(workspaces: WorkspaceState) {
        // The three "nothing to show" states, kept apart. See OverviewUiState.
        val active = workspaces.active
        if (active == null) {
            _state.value = when {
                workspaces.isBillingBlocked -> OverviewUiState.BillingBlocked(workspaces.inactiveCount)
                // ⛔ Checked BEFORE NoWorkspaces. An incomplete answer must never be reported as
                // an empty account.
                workspaces.isPartial -> OverviewUiState.Unavailable(
                    message = MESSAGE_REGIONS_DEGRADED,
                    degradedRegions = workspaces.degradedRegions,
                )
                else -> OverviewUiState.NoWorkspaces
            }
            return
        }

        // ⚠️ A refresh of the SAME workspace keeps the card it already knew about, so a
        // pull-to-refresh does not blink it out while the setup read is in flight.
        val previous = _state.value as? OverviewUiState.Content
        val keepSetupCard = previous?.active?.id == active.id && previous.showFinishSetup

        _state.value = when (val overview = overviewRepository.load(active.id)) {
            is ApiResult.Success -> OverviewUiState.Content(
                overview = overview.value,
                workspaces = workspaces.workspaces,
                active = active,
                degradedRegions = workspaces.degradedRegions,
                showFinishSetup = keepSetupCard,
            )
            is ApiResult.Failure -> overview.toUiState()
        }

        if (_state.value is OverviewUiState.Content) refreshSetupCard(active.id)
    }

    /**
     * ⛔ AFTER THE OVERVIEW HAS RENDERED, NEVER BEFORE IT AND NEVER IN ITS WAY. The setup read is
     * owner-only, so for most users it is a 403, and it must neither delay the screen nor turn it
     * into an error; [SetupRepository.needsWebSetup] answers false for every failure.
     *
     * ⚠️ APPLIED ONLY IF THE SAME WORKSPACE IS STILL SHOWING. A switch while this was in flight
     * would otherwise put one workspace's setup card on another workspace's overview.
     */
    private fun refreshSetupCard(workspaceId: String) {
        val repository = setupRepository ?: return
        viewModelScope.launch {
            val show = repository.needsWebSetup(workspaceId)
            val current = _state.value
            if (current is OverviewUiState.Content && current.active.id == workspaceId) {
                _state.value = current.copy(showFinishSetup = show)
            }
        }
    }

    /**
     * Map a transport/API failure to a screen state.
     *
     * ⚠️ The WORDING and the sign-out reasoning live in [toFailureText], shared with every other
     * screen so the "never render a failure as an absence of data" rule has one home. What is
     * decided HERE is only what this particular screen does with it — notably that a 404 means
     * "this account has no workspace", which is specific to a workspace-scoped read.
     */
    private fun ApiResult.Failure.toUiState(): OverviewUiState {
        // ⚠️ Handled before the shared mapping: the auth guard answers 404 (not 403) for an account
        // with no workspace at all, and that is a legitimate state with its own screen rather than
        // a failure to report.
        if (this is ApiResult.NotFound) return OverviewUiState.NoWorkspaces

        val text = toFailureText()
        return text.signedOutCause
            ?.let { OverviewUiState.SignedOut(it) }
            ?: OverviewUiState.Unavailable(text.message, text.degradedRegions)
    }

    companion object {
        /**
         * ⚠️ A resource reference, not a literal. A ViewModel has no `Context`, which is exactly why
         * this sentence used to be the app's only untranslatable copy on its most important failure
         * path. See [UiText].
         */
        private val MESSAGE_REGIONS_DEGRADED = UiText.Resource(R.string.overview_workspaces_degraded)

        /**
         * ⚠️ A factory rather than constructor injection because ViewModelProvider must own the
         * instantiation — that is what ties the instance to the ViewModelStore and lets it survive
         * a configuration change. Constructing one directly and holding it in the activity would
         * rebuild it on every rotation, restarting both requests.
         */
        fun factory(
            workspaceRepository: WorkspaceRepository,
            overviewRepository: OverviewRepository,
            // ⚠️ No default: the activity, the one caller, always hands its container's repository.
            setupRepository: SetupRepository?,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OverviewViewModel(workspaceRepository, overviewRepository, setupRepository) as T
        }
    }
}
