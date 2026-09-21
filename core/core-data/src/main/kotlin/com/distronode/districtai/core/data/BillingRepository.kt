package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.network.ApiResult
import com.distronode.districtai.core.network.DistrictApi

/**
 * Billing: the plan we own, and the invoices Stripe owns.
 *
 * ⛔ THE TWO READS ARE DELIBERATELY NOT COMBINED, AND THE REASON IS AVAILABILITY RATHER THAN
 * LATENCY. `workspace/billing` reads columns in OUR OWN database and reaches no vendor;
 * `/api/billing` reaches Stripe and is therefore only as available as Stripe is. Folding them into
 * one `ApiResult` would mean a Stripe outage blanking the tier, the status and the overage cap —
 * facts we hold locally and that the customer most needs when something is wrong with their
 * billing. The ViewModel runs them in parallel and keeps two sub-states; this layer keeps each one
 * honest on its own.
 *
 * ⛔ AND THE TWO HALVES USE DIFFERENT ENVELOPE RULES, WHICH IS THE THING TO READ BEFORE EDITING
 * EITHER FUNCTION. Every District route answers `{success, ...}` and [rejectedEnvelope] is what
 * stops a `{}` body decoding into a confident empty answer. **`GET /api/billing` has no `success`
 * key at all** — it returns the object bare — so applying the guard there would reject every
 * healthy response as contract drift, and `district-billing.json` pins that absence so the
 * difference cannot be argued from memory. See [stripeBilling].
 *
 * ⛔ THERE ARE NO WRITES HERE, AND THERE MUST NOT BE. `POST /api/billing` cancels subscriptions,
 * changes plans and detaches cards. Offering any of that in-app breaches Google Play's Payments
 * policy — this is not the marketplace's read-only decision, which was ours to revisit.
 *
 * ⚠️ NO CACHING. Both answers are small, neither pages, and a stale plan or a stale cap is exactly
 * the kind of wrong number that becomes a support ticket.
 */
class BillingRepository(private val api: DistrictApi) {

    /**
     * The workspace's plan, status, overage state and this month's usage.
     *
     * ⚠️ Envelope first — see [rejectedEnvelope]. It matters here as much as anywhere in this
     * client: every field of `WorkspaceBilling` has a default, so a `{}` body would decode into a
     * complete-looking record reading no tier, no status and NO OVERAGE CAP. The last one is the
     * dangerous default: `overageCapExceeded = false` on an empty body says "you are within your
     * plan", which is the opposite of the fact this screen exists to surface.
     *
     * ⛔ A 200 THAT CARRIES NO `billing` OBJECT IS MALFORMED, NOT AN EMPTY PLAN, and is reported as
     * a decode failure. Absence of a plan is representable — the server sends
     * `subscriptionStatus: "none"` with a null tier — so a missing object can only mean drift.
     * The same distinction `ContactsRepository.detail` draws, and the opposite call from
     * `AnalyticsRepository.usage`, where the null genuinely IS the payload.
     */
    suspend fun workspaceBilling(workspaceId: String): ApiResult<WorkspaceBilling> =
        when (val result = api.workspaceBilling(workspaceId)) {
            is ApiResult.Success -> rejectedEnvelope(WORKSPACE_ENVELOPE, result.value.success)
                ?: result.value.billing?.let { ApiResult.Success(it) }
                ?: ApiResult.DecodeFailure(
                    IllegalStateException("$WORKSPACE_ENVELOPE affirmed success but carried no billing"),
                    "$WORKSPACE_ENVELOPE{billing=null}",
                )
            is ApiResult.Failure -> result
        }

    /**
     * Subscriptions and invoices, from Stripe.
     *
     * ⛔ NO ENVELOPE CHECK, AND ITS ABSENCE IS THE POINT. This route does not send `success` — not
     * `false`, ABSENT — so `rejectedEnvelope` would reject a perfectly good response every single
     * time, and the screen would show contract-drift copy ("this version of the app does not
     * understand the response") to every user with a working subscription. The strictness that
     * guard provides elsewhere is supplied here by the committed fixtures instead: three of them,
     * decoded with `ignoreUnknownKeys = false`.
     *
     * ⛔ AND `billingUnavailable` IS CARRIED THROUGH AS A **STATE**, NEVER CONVERTED INTO A
     * FAILURE. It arrives on a 200 with empty arrays, and the distinction it draws is the whole
     * reason it exists: the SAME body without the flag means the account has no Stripe customer,
     * which legitimately renders as "no billing set up". Promoting the flag to
     * [ApiResult.Failure] would collapse the two into one generic error and lose the only thing
     * that tells them apart — and demoting it to nothing would render a Stripe outage as a free
     * account, the conflation that can route a paying customer to a checkout page.
     */
    suspend fun stripeBilling(): ApiResult<StripeBilling> = api.stripeBilling()

    private companion object {
        const val WORKSPACE_ENVELOPE = "WorkspaceBillingResponse"
    }
}
