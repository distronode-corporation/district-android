package com.distronode.districtai.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.distronode.districtai.core.data.AnalyticsRepository
import com.distronode.districtai.core.model.AnalyticsRange
import com.distronode.districtai.core.model.AnalyticsResponse
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The analytics screen's state machine.
 *
 * ⛔ THE THREE READS RUN IN PARALLEL, AND THAT IS A CORRECTNESS-ADJACENT DECISION RATHER THAN A
 * PERFORMANCE ONE. Sequentially, the usage figures would be gated behind an aggregate that scans
 * every Call row in the window — so a slow analytics query would hold back a fast, unrelated read,
 * and a FAILING one would (in the obvious `?:` shaped implementation) prevent it from being issued
 * at all. Running them concurrently makes "any read can be late or absent without touching the
 * others" structural instead of something each branch has to remember.
 *
 * ⛔ AND ALL THREE MUST LAND BEFORE CONTENT IS PUBLISHED. Emitting the first arrival would make the
 * screen assemble itself in whatever order the network happened to answer, so the layout would
 * jump and — worse — a reader could see a usage card next to an empty analytics area and read the
 * emptiness as data. One state change, every answer.
 *
 * ⚠️ THE HISTORY IS RE-READ ON A RANGE SWITCH THAT CANNOT AFFECT IT, for the reason the current
 * month already is: the alternative is two load paths, and the one that skipped the unwindowed
 * reads would be the one a session-expiry retry needed. One redundant request per chip tap against
 * a screen with two of them already.
 */
class AnalyticsViewModel(
    private val repository: AnalyticsRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<AnalyticsUiState>(AnalyticsUiState.Loading)
    val state: StateFlow<AnalyticsUiState> = _state.asStateFlow()

    /**
     * The selected window.
     *
     * ⚠️ HELD HERE RATHER THAN READ BACK OUT OF THE STATE. [AnalyticsUiState.Failed] carries no
     * range, so a reload after a total failure would otherwise have to invent one — and it would
     * silently pick 7d, discarding the 90d window the operator had chosen.
     */
    private var range: AnalyticsRange = AnalyticsRange.SEVEN_DAYS

    init {
        load()
    }

    /**
     * Read both halves.
     *
     * ⚠️ NOT GUARDED AGAINST A CONCURRENT CALL, unlike the HQ console's prompt turn. Both requests
     * are idempotent GETs that spend nothing, and the guard there exists because a second prompt
     * is a second billable model run against customer data. The realistic double-trigger here is a
     * session change landing on top of a manual retry, and the cost of that is one wasted read
     * against one duplicated state write of the same value.
     */
    fun load() {
        beginLoad()
        viewModelScope.launch {
            // ⛔ `async` BEFORE ANY `await`. Awaiting the first Deferred before creating the second
            // is the shape that looks parallel and is not — it is the single most common way this
            // gets written back into a sequence.
            val analyticsRead = async { repository.analytics(workspaceId, range) }
            val usageRead = async { repository.usage(workspaceId) }
            // ⚠️ NO EXPLICIT `months`. The repository's default is the span the web console asks
            // for, and naming a number here would be a second copy of that decision — the kind
            // that drifts and makes the two surfaces show different spans under one heading.
            val historyRead = async { repository.usageHistory(workspaceId) }
            publish(analyticsRead.await(), usageRead.await(), historyRead.await())
        }
    }

    /**
     * Switch the window.
     *
     * ⚠️ A TAP ON THE ALREADY-SELECTED CHIP IS A NO-OP, not a refresh. The chips are a selector
     * rather than a set of buttons, and re-issuing the same window on a repeat tap would make an
     * impatient double-tap cost two full-window aggregates.
     */
    fun selectRange(next: AnalyticsRange) {
        if (next == range) return
        range = next
        load()
    }

    /**
     * ⚠️ KEEPS THE FIGURES ON SCREEN FOR A RELOAD and only shows [AnalyticsUiState.Loading] when
     * there is nothing to keep. A range switch that blanked the screen would flash it on every
     * tap; the chips move immediately (see [AnalyticsUiState.Content.range]) so the tap is still
     * acknowledged.
     */
    private fun beginLoad() {
        val current = _state.value
        _state.value = if (current is AnalyticsUiState.Content) {
            current.copy(range = range, refreshing = true)
        } else {
            AnalyticsUiState.Loading
        }
    }

    /**
     * ⛔ A WHOLE-SCREEN FAILURE ONLY WHEN EVERY READ FAILED. If any answered, its card is rendered
     * and the others carry their own failures — see [AnalyticsUiState]. Collapsing a partial
     * failure into the full-screen state throws away an answer already in hand, which is the same
     * mistake as rendering "we could not look" as "there is nothing".
     *
     * ⚠️ The analytics failure is the one surfaced in the all-failed case. They are almost always
     * the same underlying cause (a dead session, an unreachable region), and analytics is the
     * subject of the screen.
     */
    private fun publish(
        analytics: ApiResult<AnalyticsResponse>,
        usage: ApiResult<UsageData?>,
        history: ApiResult<List<UsageData>>,
    ) {
        val analyticsCard = when (analytics) {
            is ApiResult.Success -> AnalyticsCardState.Ready(analytics.value)
            is ApiResult.Failure -> AnalyticsCardState.Failed(analytics.toFailureText())
        }
        val usageCard = when (usage) {
            is ApiResult.Success -> UsageCardState.Ready(usage.value)
            is ApiResult.Failure -> UsageCardState.Failed(usage.toFailureText())
        }
        val historyCard = when (history) {
            is ApiResult.Success -> UsageHistoryCardState.Ready(history.value)
            is ApiResult.Failure -> UsageHistoryCardState.Failed(history.toFailureText())
        }

        _state.value = if (
            analyticsCard is AnalyticsCardState.Failed &&
            usageCard is UsageCardState.Failed &&
            historyCard is UsageHistoryCardState.Failed
        ) {
            AnalyticsUiState.Failed(analyticsCard.failure)
        } else {
            AnalyticsUiState.Content(
                range = range,
                analytics = analyticsCard,
                usage = usageCard,
                history = historyCard,
            )
        }
    }

    companion object {
        fun factory(
            repository: AnalyticsRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AnalyticsViewModel(repository, workspaceId) as T
        }
    }
}
