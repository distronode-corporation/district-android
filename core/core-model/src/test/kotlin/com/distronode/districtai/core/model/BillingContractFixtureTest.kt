package com.distronode.districtai.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billing half of the contract gate.
 *
 * ⛔ A SEPARATE CLASS FROM `ContractFixtureTest` RATHER THAN MORE METHODS ON IT — the same reason
 * `DeviceContractFixtureTest` is one. That class reached detekt's LargeClass ceiling as endpoints
 * accumulated, and the healthy answer to a class that has grown too large is another class. The
 * strict decoder and the fixture loader are shared through [ContractFixtures] precisely so the
 * split cannot make one of them lenient.
 *
 * ⛔ FIVE FIXTURES FOR TWO ROUTES, AND EVERY ONE OF THEM EARNS ITS PLACE:
 *
 *   - `district-workspace-billing.json` / `-null-usage.json` — the same District route, carrying
 *     the OPPOSITE value of every binary it has: tier present/null, active/past_due,
 *     auto_bill/hard_cap, cap not-exceeded/exceeded, usage populated/null.
 *   - `district-billing.json` — the healthy Stripe answer, fourteen top-level keys.
 *   - `district-billing-unavailable.json` — six keys plus `billingUnavailable: true`. Stripe is
 *     down.
 *   - `district-billing-no-customer.json` — **the same six keys MINUS the flag**, meaning the
 *     account simply has no billing. Two bodies, one absent key apart, opposite meanings.
 *
 * ⛔ AND `/api/billing` HAS NO `success` KEY. Every other response this module decodes carries one
 * and the data layer rejects a 200 that does not affirm it. That guard must NOT be applied here or
 * every healthy response is rejected as contract drift — asserted below rather than left to a
 * comment, because it is a property of the fixture rather than of anyone's memory.
 */
class BillingContractFixtureTest {

    private val json: Json = ContractFixtures.json

    private fun fixture(name: String): String = ContractFixtures.read(name)

    @Test
    fun `the workspace plan decodes with its usage and its overage pair`() {
        val response = json.decodeFromString<WorkspaceBillingResponse>(
            fixture("district-workspace-billing.json"),
        )

        assertEquals(true, response.success)
        assertNotNull("the fixture must carry a billing object", response.billing)
        val billing = response.billing!!

        // ⚠️ MIXED CASE, MATCHING THE STORED COLUMN. `plan` is its lowercase twin, written by the
        // same provisioning call. A client comparing either case-sensitively against the other
        // would silently never match.
        assertEquals("VoicePro", billing.subscriptionTier)
        assertEquals("voicepro", billing.plan)
        assertEquals(SUBSCRIPTION_STATUS_ACTIVE, billing.subscriptionStatus)
        assertEquals(OVERAGE_POLICY_AUTO_BILL, billing.overagePolicy)
        assertFalse(billing.overageCapExceeded)

        // ⛔ THE USAGE OBJECT IS THE ANALYTICS PACKAGE'S OWN `UsageData`, REUSED RATHER THAN
        // REDECLARED. The server calls the same `getUsage` for both routes and the website's
        // contract test asserts the two payloads are byte-identical, so one DTO has to decode both
        // — a second type here would drift the moment either surface was regenerated alone.
        assertNotNull("the populated fixture must carry usage", billing.usage)
        val usage = billing.usage!!
        assertEquals("2026-08", usage.month)
        // ⛔ FRACTIONAL. `amount` is summed as a float server-side, and this is the screen that
        // meters these minutes against an included allowance — an Int would truncate a bill.
        assertEquals(1204.25, usage.callMinutesInbound!!, 0.0)
        assertEquals(318.5, usage.callMinutesOutbound!!, 0.0)
        // An unused metric has NO KEY rather than a zero; a metric measured at zero IS present.
        assertNull("whatsappInbound is absent, not zero", usage.whatsappInbound)
        assertEquals(0.0, usage.whatsappOutbound!!, 0.0)
    }

    @Test
    fun `a capped, unmetered workspace decodes with the OPPOSITE of every branch`() {
        val response = json.decodeFromString<WorkspaceBillingResponse>(
            fixture("district-workspace-billing-null-usage.json"),
        )

        assertNotNull(response.billing)
        val billing = response.billing!!

        // ⛔ NULL IS NOT "Free". `GET /api/settings` substitutes a capitalised "Free" for its own
        // callers and this route deliberately does not, so a client that defaulted the field to a
        // plan name would put a word on screen that no row contains.
        assertNull("a null tier must survive as null", billing.subscriptionTier)
        assertEquals(SUBSCRIPTION_STATUS_PAST_DUE, billing.subscriptionStatus)

        // ⛔ THE COMBINATION THAT MEANS CALLS ARE BLOCKED. Under auto_bill an exceeded cap bills;
        // under hard_cap it REFUSES CALLS, and the operator has no other way to learn that here.
        // A fixture set where the two never varied independently could not pin this.
        assertEquals(OVERAGE_POLICY_HARD_CAP, billing.overagePolicy)
        assertTrue(billing.overageCapExceeded)

        // ⛔ `usage: null` MEANS NOTHING WAS METERED, AND IT IS NOT ZERO. A column of zeros beside
        // a cap that says calls are being refused states two contradictory things, only one of
        // which was measured.
        assertNull("usage must decode as null, not as an empty object", billing.usage)
    }

    @Test
    fun `the Stripe route carries NO success key, so the envelope guard must not be applied`() {
        // ⛔ THE PROPERTY THE REPOSITORY IS WRITTEN AGAINST, PINNED AS A FACT ABOUT THE BYTES. Every
        // District route answers `{success, ...}` and `rejectedEnvelope` rejects a 200 that does not
        // affirm it. Applying that rule to this route would reject every HEALTHY response as
        // contract drift and show "this version of the app does not understand the response" to
        // every customer with a working subscription.
        val raw = fixture("district-billing.json")
        assertFalse(
            "`/api/billing` must not carry a `success` key — the repository decodes it bare",
            raw.contains("\"success\""),
        )
        assertFalse(
            "the healthy shape must NOT carry billingUnavailable; its ABSENCE is the signal",
            raw.contains("billingUnavailable"),
        )
    }

    @Test
    fun `the healthy Stripe answer covers the present AND absent branch of every optional field`() {
        val detail = json.decodeFromString<StripeBilling>(fixture("district-billing.json"))

        assertFalse(detail.billingUnavailable)
        assertEquals("cus_contract_1", detail.customerId)
        assertEquals("ws-contract-test", detail.usageWorkspaceId)
        // ⚠️ Truncated history, flagged. The server caps at 10; a client that ignored this would
        // present a partial list as the whole history.
        assertTrue(detail.invoicesHasMore)

        assertEquals(2, detail.subscriptions.size)
        val (pro, legacy) = detail.subscriptions

        // ⛔ CENTS. 24900 is $249.00 — the field where rendering the integer verbatim overstates a
        // price by a factor of a hundred.
        assertEquals(24900L, pro.amount)
        assertEquals(1500, pro.includedMinutes)
        assertEquals(0.16, pro.overageRate!!, 0.0)
        assertFalse(pro.cancelAtPeriodEnd)
        assertNotNull("one subscription must carry a discount", pro.discount)
        val discount = pro.discount!!
        assertEquals("Founding customer", discount.couponName)
        assertEquals(20.0, discount.percentOff!!, 0.0)
        // ⚠️ EXACTLY ONE OF percentOff/amountOff arrives — the other key is not on the wire.
        assertNull("amountOff must be absent when percentOff is present", discount.amountOff)

        // ⛔ THE ROW WHERE FOUR OPTIONAL KEYS ARE ABSENT RATHER THAN NULL. A fully-populated
        // fixture would let a client type any of these as required and throw on a legacy price.
        assertNull("a price outside the tier catalogue has no allowance", legacy.includedMinutes)
        assertNull(legacy.overageRate)
        assertNull(legacy.discount)
        // ⛔ AND THE FLAG THAT FLIPS THE DATE'S MEANING. Same timestamp, "ends" rather than
        // "renews": drawing it under a "renews" heading tells someone who cancelled that they are
        // about to be billed again.
        assertTrue("the fixture must cover cancel_at_period_end = true", legacy.cancelAtPeriodEnd)
        assertNotNull(legacy.currentPeriodEnd)

        assertEquals(2, detail.invoices.size)
        val (paid, open) = detail.invoices
        assertEquals(24900L, paid.amountPaid)
        assertEquals(3237L, paid.tax)
        assertEquals(1_754_231_400L, paid.created)
        assertNotNull("a finalised invoice has a hosted page", paid.hostedInvoiceUrl)

        // ⛔ THE UNPAID ROW: a zero `amount_paid` beside a non-zero total, no links at all, and a
        // SUMMED zero tax. Stripe omits both URLs until an invoice is finalised, which makes this
        // the most ordinary row there is — this month's, before it is paid.
        assertEquals(0L, open.amountPaid)
        assertEquals(1500L, open.total)
        assertEquals(0L, open.tax)
        assertNull("an unfinalised invoice has no hosted page", open.hostedInvoiceUrl)
        assertNull(open.invoicePdf)

        // ⚠️ Opaque by design: the screen renders none of these, so they are modelled only so the
        // strict decoder does not reject the keys. See `StripeBilling`.
        assertNotNull(detail.paymentMethod)
        assertNotNull(detail.businessProfile)
        assertNotNull(detail.billingAddress)

        // ⛔ THE MONTHLY OVERAGE SPENDING CAP — CENTS, AND NULLABLE. 5000 is $50.00. The fixture
        // carries a cap that is SET but NOT reached, so the pair varies independently: a client
        // that conflated "a cap exists" with "the cap is hit" would show an outage banner to every
        // customer who ever set one.
        assertEquals(5000, detail.overageSpendCapCents)
        assertFalse(detail.overageSpendCapExceeded)
    }

    @Test
    fun `the degraded and no-customer shapes differ by ONE key and mean opposite things`() {
        // ⛔ THE MOST IMPORTANT ASSERTION IN THIS FILE. Both bodies decode cleanly, both have empty
        // arrays, both have a null customerId — and one means "Stripe is down, your plan is fine"
        // while the other means "this account has no billing". A client branching on "are the
        // arrays empty" would call a vendor outage a free account, the same conflation that can
        // route a paying customer to a checkout page on the web.
        val unavailable = json.decodeFromString<StripeBilling>(
            fixture("district-billing-unavailable.json"),
        )
        val noCustomer = json.decodeFromString<StripeBilling>(
            fixture("district-billing-no-customer.json"),
        )

        assertTrue("the degraded shape must set the flag", unavailable.billingUnavailable)
        assertFalse(
            "the no-customer shape must NOT set the flag — the key is absent entirely",
            noCustomer.billingUnavailable,
        )
        assertFalse(
            "the raw no-customer body must not mention billingUnavailable at all",
            fixture("district-billing-no-customer.json").contains("billingUnavailable"),
        )

        // Everything else about them is identical, which is exactly why the flag is load-bearing.
        assertEquals(unavailable.copy(billingUnavailable = false), noCustomer)

        // ⛔ SEVEN KEYS ARE ABSENT FROM BOTH — not null, ABSENT — so every one of them needs a DTO
        // default. A required field on any would fail to decode precisely when billing was already
        // broken, turning a legible "temporarily unavailable" into "the app does not understand
        // this response".
        listOf(unavailable, noCustomer).forEach { degraded ->
            assertTrue(degraded.subscriptions.isEmpty())
            assertTrue(degraded.invoices.isEmpty())
            assertFalse(degraded.invoicesHasMore)
            assertNull(degraded.paymentMethods)
            assertNull(degraded.businessProfile)
            assertNull(degraded.taxIds)
            assertNull(degraded.usageWorkspaceId)
            assertNull(degraded.overagePolicy)
            assertFalse(degraded.overageCapExceeded)
            assertNull(degraded.customerId)
        }
    }

    @Test
    fun `billing fixtures survive a round trip in both encodings`() {
        // ⛔ THE DEGRADED SHAPES ESPECIALLY. Seven of their keys are ABSENT, which the terse
        // encoding also writes as absent — so a default that swallowed one (an empty object for
        // `paymentMethods`, say) would decode and re-encode to something different. That is what
        // this catches and what no decode assertion above can.
        val verbose = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val terse = Json {
            encodeDefaults = false
            explicitNulls = false
        }

        fun <T> roundTrip(serializer: KSerializer<T>, fixtureName: String) {
            val decoded = json.decodeFromString(serializer, fixture(fixtureName))
            assertEquals(
                "$fixtureName must survive a round trip through an explicit-nulls encoding",
                decoded,
                json.decodeFromString(serializer, verbose.encodeToString(serializer, decoded)),
            )
            assertEquals(
                "$fixtureName must survive a round trip in the server's own omit-defaults shape",
                decoded,
                json.decodeFromString(serializer, terse.encodeToString(serializer, decoded)),
            )
        }

        roundTrip(WorkspaceBillingResponse.serializer(), "district-workspace-billing.json")
        roundTrip(WorkspaceBillingResponse.serializer(), "district-workspace-billing-null-usage.json")
        roundTrip(StripeBilling.serializer(), "district-billing.json")
        roundTrip(StripeBilling.serializer(), "district-billing-unavailable.json")
        roundTrip(StripeBilling.serializer(), "district-billing-no-customer.json")
    }

    @Test
    fun `an unmodelled field on either billing route is rejected rather than ignored`() {
        // ⛔ PROVES THE GUARD GUARDS, for this file's decoder too. `ContractFixtureTest` and
        // `DeviceContractFixtureTest` carry the same assertion, and the duplication is deliberate:
        // all three share one `Json` instance, so if this ever passes, the shared decoder has been
        // relaxed and EVERY contract test has quietly stopped protecting anything.
        //
        // ⚠️ Asserted on the STRIPE route specifically as well as the district one, because that is
        // the shape whose sub-objects are opaque `JsonElement`s — an unknown key nested inside one
        // of those is legitimately ignored, and this pins that the TOP level is still strict.
        val districtExtra = """{"success":true,"billing":{"plan":"voicepro","brandNewField":1}}"""
        val stripeExtra = """{"subscriptions":[],"invoices":[],"brandNewServerField":"boom"}"""

        assertTrue(
            "an unknown key on the district billing envelope MUST fail to decode",
            runCatching { json.decodeFromString<WorkspaceBillingResponse>(districtExtra) }.isFailure,
        )
        assertTrue(
            "an unknown TOP-LEVEL key on /api/billing MUST fail to decode",
            runCatching { json.decodeFromString<StripeBilling>(stripeExtra) }.isFailure,
        )
    }
}
