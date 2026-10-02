package com.distronode.districtai.core.data

import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.BillingSubscription
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.model.WorkspaceBillingResponse
import com.distronode.districtai.core.network.ApiResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.distronode.districtai.core.network.testing.FakeDistrictApi

/**
 * The two billing reads, and the one thing this layer exists to get right: **the two routes do not
 * share an envelope rule.**
 *
 * ⛔ THE DISTRICT ROUTE IS ENVELOPE-CHECKED AND `/api/billing` IS NOT. Applying the guard to the
 * second would reject every healthy response as contract drift, because it genuinely sends no
 * `success` key — `district-billing.json` pins that absence. Applying no guard to the first would
 * let a `{}` body decode into a plan with no tier, no status and NO OVERAGE CAP, which is the
 * dangerous default: `overageCapExceeded = false` says "you are within your plan" without anything
 * having been read.
 */
class BillingRepositoryTest {

    private fun repository(api: FakeDistrictApi) = BillingRepository(api)

    // ── The workspace plan: envelope-checked ─────────────────────────────────

    @Test
    fun `a populated plan is unwrapped from its envelope`() = runTest {
        val billing = WorkspaceBilling(
            subscriptionTier = "VoicePro",
            subscriptionStatus = "active",
            plan = "voicepro",
            overagePolicy = "auto_bill",
            usage = UsageData(month = "2026-08", callMinutesInbound = 1204.25),
        )
        val api = FakeDistrictApi().apply {
            workspaceBillingResult = ApiResult.Success(
                WorkspaceBillingResponse(success = true, billing = billing),
            )
        }

        val result = repository(api).workspaceBilling("ws-1")

        assertEquals(billing, (result as ApiResult.Success).value)
        assertEquals(listOf("ws-1"), api.requestedWorkspaceIds)
    }

    @Test
    fun `a null usage is carried through inside a SUCCESS, not turned into a failure`() = runTest {
        // ⛔ "NOTHING WAS METERED THIS MONTH" IS AN ANSWER, NOT A FAILURE TO GET ONE. The screen
        // says "no call minutes recorded yet" for the first and offers a retry for the second, and
        // collapsing them in either direction is the mistake this assertion exists to prevent — a
        // zeroed meter would assert a billing fact that was never measured.
        val api = FakeDistrictApi().apply {
            workspaceBillingResult = ApiResult.Success(
                WorkspaceBillingResponse(
                    success = true,
                    billing = WorkspaceBilling(subscriptionStatus = "past_due", usage = null),
                ),
            )
        }

        val result = repository(api).workspaceBilling("ws-1")

        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).value.usage)
        assertEquals("past_due", result.value.subscriptionStatus)
    }

    @Test
    fun `a 200 that does not affirm success is contract drift, not an unbilled workspace`() = runTest {
        // ⛔ EVERY FIELD OF WorkspaceBilling HAS A DEFAULT, so an empty body decodes into a
        // complete-looking record: no tier, no status, and `overageCapExceeded = false`. That last
        // default is the dangerous one — it reads as "you are within your plan" on a screen whose
        // job is to say when you are not.
        val api = FakeDistrictApi().apply {
            workspaceBillingResult = ApiResult.Success(WorkspaceBillingResponse())
        }

        val result = repository(api).workspaceBilling("ws-1")

        assertTrue("must be reported as drift", result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a success carrying no billing object is drift, not an absent plan`() = runTest {
        // ⛔ ABSENCE OF A PLAN IS REPRESENTABLE — the server sends `subscriptionStatus: "none"` with
        // a null tier — so a missing object can only mean the shape changed. The opposite call from
        // `AnalyticsRepository.usage`, where the null genuinely IS the payload.
        val api = FakeDistrictApi().apply {
            workspaceBillingResult = ApiResult.Success(
                WorkspaceBillingResponse(success = true, billing = null),
            )
        }

        val result = repository(api).workspaceBilling("ws-1")

        assertTrue(result is ApiResult.DecodeFailure)
    }

    @Test
    fun `a transport failure is passed through untouched`() = runTest {
        val api = FakeDistrictApi().apply {
            workspaceBillingResult = ApiResult.Unauthorized(reason = null)
        }

        assertTrue(repository(api).workspaceBilling("ws-1") is ApiResult.Unauthorized)
    }

    // ── The Stripe detail: deliberately NOT envelope-checked ─────────────────

    @Test
    fun `a bare Stripe object with NO success key is a success, not drift`() = runTest {
        // ⛔ THE ASSERTION THE WHOLE REPOSITORY IS SHAPED AROUND. `StripeBilling()` is what an
        // account with no subscription decodes to, and it affirms nothing — there is no `success`
        // field on this DTO because the route sends none. If `rejectedEnvelope` were ever applied
        // here, THIS is the test that fails, and it fails for every customer with a working
        // subscription rather than only for the empty case.
        val detail = StripeBilling(
            subscriptions = listOf(BillingSubscription(id = "sub_1", tierName = "Voice Pro")),
            invoices = listOf(BillingInvoice(id = "in_1", amountPaid = 24900)),
            customerId = "cus_1",
        )
        val api = FakeDistrictApi().apply { stripeBillingResult = ApiResult.Success(detail) }

        val result = repository(api).stripeBilling()

        assertEquals(detail, (result as ApiResult.Success).value)
    }

    @Test
    fun `an EMPTY Stripe answer is still a success — it means no billing account`() = runTest {
        val api = FakeDistrictApi().apply {
            stripeBillingResult = ApiResult.Success(StripeBilling())
        }

        val result = repository(api).stripeBilling()

        assertTrue("an empty account is an answer, not drift", result is ApiResult.Success)
        assertFalse((result as ApiResult.Success).value.billingUnavailable)
        assertTrue(result.value.subscriptions.isEmpty())
    }

    @Test
    fun `billingUnavailable stays a STATE inside a success and is never promoted to a failure`() =
        runTest {
            // ⛔ IT ARRIVES ON A 200 AND MUST STAY ONE. Promoting it to `ApiResult.Failure` would
            // collapse it into the generic error path and lose the only thing that distinguishes a
            // Stripe outage from an account with no billing — the two bodies are identical apart
            // from this flag. The screen has to be able to say "temporarily unavailable" rather
            // than either "no plan" or "something went wrong".
            val api = FakeDistrictApi().apply {
                stripeBillingResult = ApiResult.Success(StripeBilling(billingUnavailable = true))
            }

            val result = repository(api).stripeBilling()

            assertTrue(result is ApiResult.Success)
            assertTrue((result as ApiResult.Success).value.billingUnavailable)
        }

    @Test
    fun `a real transport failure on the Stripe read is still a failure`() = runTest {
        // ⚠️ The distinction from the test above: a 200 carrying the flag is a vendor outage the
        // server reported; this is the request never getting an answer at all.
        val api = FakeDistrictApi().apply {
            stripeBillingResult = ApiResult.HttpFailure(503, "Service Unavailable")
        }

        assertTrue(repository(api).stripeBilling() is ApiResult.HttpFailure)
    }
}
