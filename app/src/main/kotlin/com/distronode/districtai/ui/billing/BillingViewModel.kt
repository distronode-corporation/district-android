package com.distronode.districtai.ui.billing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.distronode.districtai.core.data.BillingRepository
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.ui.toFailureText
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The billing screen's state machine.
 *
 * ⛔ THE TWO READS RUN IN PARALLEL, AND HERE THAT IS AVAILABILITY RATHER THAN LATENCY. The Stripe
 * read can be slow or can fail outright; sequentially — in the obvious `?:`-shaped implementation —
 * a failing Stripe call would prevent the plan read from being ISSUED at all, and the plan read is
 * the one that works when Stripe does not. Running them concurrently makes "either half can be late
 * or absent without touching the other" structural instead of something each branch has to
 * remember.
 *
 * ⛔ AND BOTH MUST LAND BEFORE CONTENT IS PUBLISHED, for the reason the analytics screen documents:
 * emitting the first arrival would let a reader see an empty invoice area beside a populated plan
 * card and read the emptiness as "no invoices" rather than as "still loading".
 *
 * ⛔ THIS ViewModel HAS NO MUTATION AND MUST NOT GROW ONE. No cancel, no plan change, no payment
 * method. See `BillingRepository` — it is Google Play's Payments policy, not a product preference.
 */
class BillingViewModel(
    private val repository: BillingRepository,
    private val workspaceId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<BillingUiState>(BillingUiState.Loading)
    val state: StateFlow<BillingUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * Read both halves.
     *
     * ⚠️ NOT GUARDED AGAINST A CONCURRENT CALL. Both are idempotent GETs that spend nothing — the
     * Stripe half is a read of the caller's own customer, not a write — so the worst a double
     * trigger costs is one wasted round trip and a duplicated state write of the same value.
     */
    fun load() {
        beginLoad()
        viewModelScope.launch {
            // ⛔ `async` BEFORE ANY `await`. Awaiting the first Deferred before creating the second
            // is the shape that looks parallel and is not — and here it would additionally mean a
            // Stripe failure could stop the plan read being issued.
            val planRead = async { repository.workspaceBilling(workspaceId) }
            val stripeRead = async { repository.stripeBilling() }
            publish(planRead.await(), stripeRead.await())
        }
    }

    /**
     * ⚠️ KEEPS THE PLAN ON SCREEN FOR A RELOAD and only shows [BillingUiState.Loading] when there
     * is nothing to keep.
     */
    private fun beginLoad() {
        val current = _state.value
        _state.value = if (current is BillingUiState.Content) {
            current.copy(refreshing = true)
        } else {
            BillingUiState.Loading
        }
    }

    /**
     * ⛔ THE WHOLE-SCREEN FAILURE IS DRIVEN BY THE **PLAN** READ ALONE, which is a deliberate
     * asymmetry from the analytics screen's both-must-fail rule. Two reasons, and the second is the
     * load-bearing one:
     *
     *   1. Without a plan there is no headline for this screen — an invoice list under no tier and
     *      no status is a receipt drawer, not a billing page.
     *   2. The Stripe read **cannot be relied on to report a failure at all.** Its outage shape is
     *      a 200 carrying `billingUnavailable`, so an "only fail when both fail" rule would keep a
     *      screen alive on a plan read that genuinely failed, next to a Stripe section that was
     *      merely unavailable — and the screen would have nothing true on it.
     *
     * ⛔ `billingUnavailable` IS MAPPED TO ITS OWN STATE HERE RATHER THAN LEFT TO THE SCREEN. It is
     * the one branch that must never be rendered as "free tier", and putting the decision in the
     * state machine means a screen cannot forget to make it.
     */
    private fun publish(
        plan: ApiResult<WorkspaceBilling>,
        stripe: ApiResult<StripeBilling>,
    ) {
        val stripeSection = when (stripe) {
            is ApiResult.Success ->
                if (stripe.value.billingUnavailable) {
                    StripeSectionState.Unavailable
                } else {
                    StripeSectionState.Ready(stripe.value)
                }
            is ApiResult.Failure -> StripeSectionState.Failed(stripe.toFailureText())
        }

        _state.value = when (plan) {
            is ApiResult.Success -> BillingUiState.Content(
                plan = plan.value,
                stripe = stripeSection,
            )
            is ApiResult.Failure -> BillingUiState.Failed(plan.toFailureText())
        }
    }

    companion object {
        fun factory(
            repository: BillingRepository,
            workspaceId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BillingViewModel(repository, workspaceId) }
        }
    }
}
