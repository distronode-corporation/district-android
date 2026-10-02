package com.distronode.districtai.ui.billing

import com.distronode.districtai.core.data.BillingRepository
import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.BillingSubscription
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.model.WorkspaceBillingResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi
import org.junit.Rule
import com.distronode.districtai.core.network.testing.MainDispatcherRule

/**
 * The billing screen's state machine, and the three guarantees it exists to keep: the two reads are
 * genuinely concurrent, a Stripe outage never blanks the plan, and `billingUnavailable` becomes its
 * OWN state rather than an empty one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BillingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val mainDispatcher = MainDispatcherRule(dispatcher)

    private fun viewModel(api: FakeDistrictApi) =
        BillingViewModel(BillingRepository(api), workspaceId = "ws-1")

    private val plan = WorkspaceBilling(
        subscriptionTier = "VoicePro",
        subscriptionStatus = "active",
        plan = "voicepro",
        overagePolicy = "auto_bill",
        usage = UsageData(month = "2026-08", callMinutesOutbound = 318.5, callMinutesInbound = 1204.25),
    )

    private val detail = StripeBilling(
        subscriptions = listOf(
            BillingSubscription(id = "sub_1", tierName = "Voice Pro", amount = 24900, includedMinutes = 1500),
        ),
        invoices = listOf(BillingInvoice(id = "in_1", amountPaid = 24900, status = "paid")),
        customerId = "cus_1",
    )

    private fun healthyApi() = FakeDistrictApi().apply {
        workspaceBillingResult = ApiResult.Success(
            WorkspaceBillingResponse(success = true, billing = plan),
        )
        stripeBillingResult = ApiResult.Success(detail)
    }

    private fun content(vm: BillingViewModel) = vm.state.value as BillingUiState.Content

    // ── Loading ──────────────────────────────────────────────────────────────

    @Test
    fun `both halves land before anything is published`() = runTest(dispatcher) {
        // ⛔ ONE STATE CHANGE, BOTH ANSWERS. Emitting the first arrival would let a reader see an
        // empty invoice area beside a populated plan card and read the emptiness as "you have never
        // been invoiced" rather than as "still loading".
        val vm = viewModel(healthyApi())

        assertEquals(BillingUiState.Loading, vm.state.value)
        advanceUntilIdle()

        val state = content(vm)
        assertEquals(plan, state.plan)
        assertTrue(state.stripe is StripeSectionState.Ready)
        assertFalse(state.refreshing)
    }

    @Test
    fun `the two reads are issued CONCURRENTLY, not one after the other`() = runTest(dispatcher) {
        // ⛔ THE ONLY WAY TO OBSERVE THIS AT ALL — both calls complete instantly against a fake, so
        // a sequential implementation and a parallel one produce identical state. Parking the PLAN
        // read makes the ordering visible.
        //
        // ⚠️ AND IT MATTERS MORE HERE THAN ON ANALYTICS. The obvious sequential shape — await the
        // plan, then read Stripe — is the harmless direction; the reverse (await Stripe, then read
        // the plan) means a SLOW OR FAILING VENDOR delays or prevents the read that works when the
        // vendor does not. Asserting concurrency is what forecloses both orderings.
        val api = healthyApi().apply { workspaceBillingGate = CompletableDeferred() }
        val vm = viewModel(api)

        advanceUntilIdle()

        assertEquals("the plan read must have been issued", listOf("ws-1"), api.workspaceBillingRequests)
        assertEquals(
            "the Stripe read must ALREADY have happened while the plan read is still suspended",
            1,
            api.stripeBillingRequests.size,
        )
        // ⚠️ And nothing is published while one half is outstanding — see the test above.
        assertEquals(BillingUiState.Loading, vm.state.value)

        api.workspaceBillingGate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(plan, content(vm).plan)
    }

    // ── Independent failure ──────────────────────────────────────────────────

    @Test
    fun `a failed Stripe read leaves the plan card on screen with its own failure`() =
        runTest(dispatcher) {
            // ⛔ THE HALF THAT SURVIVES IS THE HALF THAT MATTERS. Stripe is down; the tier, the
            // status and the overage cap all came from our own database and are still correct. A
            // shared failure state would blank exactly the answer a customer opened this screen for.
            val api = healthyApi().apply {
                stripeBillingResult = ApiResult.HttpFailure(503, "Service Unavailable")
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            val state = content(vm)
            assertEquals(plan, state.plan)
            assertTrue(state.stripe is StripeSectionState.Failed)
        }

    @Test
    fun `a failed PLAN read is a whole-screen failure even when Stripe answered`() =
        runTest(dispatcher) {
            // ⛔ THE DELIBERATE ASYMMETRY FROM THE ANALYTICS SCREEN, where the top-level failure
            // needs BOTH halves to fail. Two reasons: an invoice list under no tier and no status
            // is a receipt drawer rather than a billing page, and — the load-bearing one — the
            // Stripe half CANNOT be relied on to report a failure at all, since its outage arrives
            // as a 200. An "only when both fail" rule would keep a screen alive with nothing true
            // on it.
            val api = healthyApi().apply {
                workspaceBillingResult = ApiResult.HttpFailure(503, "Service Unavailable")
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            assertTrue(vm.state.value is BillingUiState.Failed)
        }

    // ── The three shapes of one 200 ──────────────────────────────────────────

    @Test
    fun `billingUnavailable becomes its OWN state, never an empty Ready`() = runTest(dispatcher) {
        // ⛔ THE BRANCH THIS WHOLE PACKAGE EXISTS FOR. `billingUnavailable: true` arrives on a 200
        // with empty arrays; the SAME body without the flag means the account has no Stripe
        // customer. Mapping the first to `Ready(empty)` would tell a paying customer they have no
        // plan during a vendor outage: the "we could not look" / "there is nothing" conflation,
        // which on the web is enough to route a paying customer to a checkout page.
        val api = healthyApi().apply {
            stripeBillingResult = ApiResult.Success(StripeBilling(billingUnavailable = true))
        }
        val vm = viewModel(api)
        advanceUntilIdle()

        val state = content(vm)
        assertEquals(StripeSectionState.Unavailable, state.stripe)
        // And the plan is untouched: it never went to Stripe.
        assertEquals("VoicePro", state.plan.subscriptionTier)
    }

    @Test
    fun `an empty answer WITHOUT the flag is a legitimate Ready, not an outage`() =
        runTest(dispatcher) {
            // ⛔ THE OTHER SIDE OF THE SAME COIN. This account genuinely has no Stripe customer,
            // which a client may render as such. Mapping it to Unavailable would tell an unstarted
            // account that billing is broken.
            val api = healthyApi().apply {
                stripeBillingResult = ApiResult.Success(StripeBilling())
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            val stripe = content(vm).stripe
            assertTrue(stripe is StripeSectionState.Ready)
            assertTrue((stripe as StripeSectionState.Ready).detail.subscriptions.isEmpty())
        }

    // ── Reload ───────────────────────────────────────────────────────────────

    @Test
    fun `a reload keeps the content on screen and flags itself as refreshing`() =
        runTest(dispatcher) {
            // ⚠️ Blanking a plan card in order to re-fetch the same plan would flash the whole
            // screen for nothing.
            val api = healthyApi()
            val vm = viewModel(api)
            advanceUntilIdle()

            api.workspaceBillingGate = CompletableDeferred()
            vm.load()
            advanceUntilIdle()

            val state = content(vm)
            assertTrue("content must survive the reload", state.refreshing)
            assertEquals(plan, state.plan)

            api.workspaceBillingGate?.complete(Unit)
            advanceUntilIdle()
            assertFalse(content(vm).refreshing)
        }

    @Test
    fun `a viewer gets the same content — nothing on this screen is role-gated`() =
        runTest(dispatcher) {
            // ⛔ THE ROUTE ADMITS `viewer` AND `/api/billing` IS CALLER-SCOPED, so there is nothing
            // here to gate and the ViewModel takes no role at all. The role reaches the SCREEN only
            // to word the read-only caption — asserted in `BillingScreenTest`. A role parameter on
            // this class would imply a gate that does not exist.
            val vm = viewModel(healthyApi())
            advanceUntilIdle()

            val state = content(vm)
            assertEquals(plan, state.plan)
            assertTrue(state.stripe is StripeSectionState.Ready)
        }

    @Test
    fun `a 200 that does not affirm the district envelope fails the screen rather than emptying it`() =
        runTest(dispatcher) {
            // ⛔ An empty body would otherwise decode into a plan with no tier and
            // `overageCapExceeded = false` — "you are within your plan", asserted without anything
            // having been read. The repository's envelope check is what turns it into a failure;
            // this asserts the ViewModel does not soften it back into content.
            val api = healthyApi().apply {
                workspaceBillingResult = ApiResult.Success(WorkspaceBillingResponse())
            }
            val vm = viewModel(api)
            advanceUntilIdle()

            assertTrue(vm.state.value is BillingUiState.Failed)
            // Not retryable: retrying cannot fix a shape this build cannot parse.
            assertFalse((vm.state.value as BillingUiState.Failed).failure.retryable)
        }
}
