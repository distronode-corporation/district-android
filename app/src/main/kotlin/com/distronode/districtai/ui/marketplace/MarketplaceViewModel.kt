package com.distronode.districtai.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.NumbersRepository
import com.distronode.districtai.core.model.NumberSearchResponse
import com.distronode.districtai.core.model.OwnedNumbersResponse
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The marketplace's state machine.
 *
 * ⛔ THE OWNED LIST LOADS ON ENTRY AND THE SEARCH DOES NOT. Listing what the workspace has is why
 * the screen exists and costs one request; a search is a carrier inventory query against filters
 * only the operator can supply, so firing one on open would spend a request to answer a question
 * nobody asked — and would fill the tab with US local numbers regardless of where the workspace
 * operates.
 *
 * ⛔ NOTHING HERE WRITES. There is no purchase, release or configure path in this ViewModel
 * because there is none in the client at all — see [MarketplaceUiState].
 */
class MarketplaceViewModel(
    private val repository: NumbersRepository,
    private val workspaceId: String,
    region: String? = null,
) : ViewModel() {

    // ⚠️ The region decides only the country the search form OPENS on. See
    // [defaultSearchCountryFor]; null (a workspace list not loaded yet) opens on the US.
    private val _state = MutableStateFlow(
        MarketplaceUiState(form = NumberSearchForm(country = defaultSearchCountryFor(region))),
    )
    val state: StateFlow<MarketplaceUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Read the workspace's own numbers.
     *
     * ⚠️ DELIBERATELY DOES NOT TOUCH THE SEARCH HALF. A session change or a retry re-reads the
     * owned list; re-running a search the operator typed minutes ago would replace results they
     * are reading with a fresh, possibly different set under no visible trigger.
     */
    fun load() {
        _state.value = _state.value.copy(owned = OwnedState.Loading)
        viewModelScope.launch {
            // ⛔ Read the state AFTER the request lands. One `copy` around the call reads the
            // receiver first and holds it across the suspension, so a tab switch, a typed filter
            // or a search result that arrived meanwhile would be written back to what it was.
            val owned = ownedState(repository.owned(workspaceId))
            _state.value = _state.value.copy(owned = owned)
        }
    }

    fun selectTab(tab: MarketplaceTab) {
        if (tab == _state.value.tab) return
        _state.value = _state.value.copy(tab = tab)
    }

    /** ⚠️ Held here rather than in composition so the form survives a tab switch and a rotation. */
    fun updateForm(form: NumberSearchForm) {
        _state.value = _state.value.copy(form = form)
    }

    /**
     * Run the search currently in the form.
     *
     * ⚠️ NOT GUARDED AGAINST A CONCURRENT CALL. This is an idempotent GET that spends nothing on
     * this side; the realistic double-trigger is an impatient second tap, and its cost is one
     * duplicated read resolving to the same state. (Contrast the contact dossier's enrich, where
     * the same double tap buys a second LLM run and IS guarded.)
     */
    fun search() {
        val form = _state.value.form
        _state.value = _state.value.copy(search = SearchState.Loading)
        viewModelScope.launch {
            val result = repository.search(
                workspaceId = workspaceId,
                areaCode = form.areaCode,
                country = form.country,
                type = form.type,
            )
            _state.value = _state.value.copy(search = searchState(result))
        }
    }

    /**
     * ⛔ A 400 IS AN ACCOUNT STATE, NOT A FAULT, AND IT IS THE ONLY STATUS TREATED SPECIALLY HERE.
     * The route answers 400 both for "no messaging provider configured for this workspace" and
     * for "the provider you named is not connected", and neither is something a retry fixes —
     * they are both "finish connecting a carrier". Rendering them as a red failure with a retry
     * button would tell an operator their app is broken while their account is merely new.
     *
     * ⚠️ The server's own sentence is carried through rather than replaced: it distinguishes
     * those two cases, and this layer cannot.
     */
    private fun searchState(result: ApiResult<NumberSearchResponse>): SearchState = when {
        result is ApiResult.Success ->
            SearchState.Ready(result.value.provider, result.value.numbers)
        result is ApiResult.HttpFailure && result.status == NOT_CONFIGURED_STATUS ->
            SearchState.NotConfigured(result.message)
        else -> SearchState.Failed((result as ApiResult.Failure).toFailureText())
    }

    /**
     * ⛔ `partial` IS CARRIED INTO THE READY STATE RATHER THAN PROMOTED TO A FAILURE. It arrives
     * on a 200 with real rows: one carrier answered, another did not. Promoting it would hide
     * inventory the operator owns; ignoring it would draw an incomplete list as a complete one.
     * Both are wrong, which is why it is a flag on the content.
     */
    private fun ownedState(result: ApiResult<OwnedNumbersResponse>): OwnedState = when (result) {
        is ApiResult.Success -> OwnedState.Ready(
            numbers = result.value.numbers,
            partial = result.value.partial,
            failedProviders = result.value.failedProviders,
        )
        is ApiResult.Failure -> OwnedState.Failed(result.toFailureText())
    }

    companion object {
        /** The route's "no carrier connected yet" answer. See [searchState]. */
        private const val NOT_CONFIGURED_STATUS = 400

        fun factory(
            repository: NumbersRepository,
            workspaceId: String,
            region: String?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MarketplaceViewModel(repository, workspaceId, region) }
        }
    }
}
