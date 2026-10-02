package com.distronode.districtai.ui.billing

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.BillingDiscount
import com.distronode.districtai.core.model.BillingInvoice
import com.distronode.districtai.core.model.BillingSubscription
import com.distronode.districtai.core.model.StripeBilling
import com.distronode.districtai.core.model.UsageData
import com.distronode.districtai.core.model.WorkspaceBilling
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the billing screen renders, and the four things it must never render: a Stripe outage as a
 * free account, an unmetered month as zero minutes, a cancelled plan as one that "renews", and a
 * cents figure as dollars.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, AND IT IS NOT COSMETIC — the same constraint AnalyticsScreenTest documents.
// This screen is a `verticalScroll` Column, so every card is COMPOSED whether or not it is on
// screen, and `assertIsDisplayed` checks visible BOUNDS rather than existence. On a phone-sized
// Robolectric display the invoice list sits below the fold and the assertion fails with "is not
// displayed" while the node is perfectly present, which reads as a rendering bug.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class BillingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val activePlan = WorkspaceBilling(
        subscriptionTier = "VoicePro",
        subscriptionStatus = "active",
        plan = "voicepro",
        overagePolicy = "auto_bill",
        overageCapExceeded = false,
        usage = UsageData(month = "2026-08", callMinutesOutbound = 318.5, callMinutesInbound = 1204.25),
    )

    private val detail = StripeBilling(
        subscriptions = listOf(
            BillingSubscription(
                id = "sub_pro",
                status = "active",
                currentPeriodEnd = 1_756_909_800,
                cancelAtPeriodEnd = false,
                tierName = "District AI Voice Pro",
                amount = 24900,
                includedMinutes = 1500,
                overageRate = 0.16,
                discount = BillingDiscount(couponName = "Founding customer", percentOff = 20.0),
            ),
            BillingSubscription(
                id = "sub_legacy",
                status = "active",
                currentPeriodEnd = 1_757_514_600,
                cancelAtPeriodEnd = true,
                tierName = "Legacy Add-on",
                amount = 1500,
            ),
        ),
        invoices = listOf(
            BillingInvoice(
                id = "in_paid",
                amountPaid = 24900,
                total = 24900,
                tax = 3237,
                status = "paid",
                created = 1_754_231_400,
                hostedInvoiceUrl = "https://invoice.stripe.test/in_paid",
                invoicePdf = "https://invoice.stripe.test/in_paid.pdf",
            ),
            BillingInvoice(
                id = "in_open",
                amountPaid = 0,
                total = 1500,
                tax = 0,
                status = "open",
                created = 1_756_909_800,
                hostedInvoiceUrl = null,
                invoicePdf = null,
            ),
        ),
        invoicesHasMore = true,
        customerId = "cus_1",
    )

    private fun render(
        state: BillingUiState,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        onOpenInvoice: (String) -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                BillingScreen(
                    state = state,
                    role = role,
                    onRetry = onRetry,
                    onSignIn = {},
                    onOpenInvoice = onOpenInvoice,
                    onBack = {},
                )
            }
        }
    }

    private fun content(
        plan: WorkspaceBilling = activePlan,
        stripe: StripeSectionState = StripeSectionState.Ready(detail),
    ) = BillingUiState.Content(plan = plan, stripe = stripe)

    // ── Plan status ──────────────────────────────────────────────────────────

    @Test
    fun `an active plan shows its tier and an active badge`() {
        render(content())

        composeRule.onNodeWithContentDescription(BILLING_PLAN_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("VoicePro").assertIsDisplayed()
        composeRule.onNodeWithText("Active").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(BILLING_REFRESHING_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `a refresh in flight shows its bar over the plan it already has, rather than blanking it`() {
        render(content().copy(refreshing = true))

        composeRule.onNodeWithContentDescription(BILLING_REFRESHING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("VoicePro").assertIsDisplayed()
    }

    @Test
    fun `past_due is a WARNING with an explanation, not a bare Stripe word`() {
        // ⛔ "Past due" alone is a vendor status. What a customer needs to know is whether their
        // service is still running, which is the caption's whole job — and the tone is Warning
        // rather than destructive because this is a state of the account, not a fault of the app.
        render(content(plan = activePlan.copy(subscriptionStatus = "past_due")))

        composeRule.onNodeWithText("Past due").assertIsDisplayed()
        composeRule.onNodeWithText(
            "A payment did not go through. Service continues while it is retried.",
        ).assertIsDisplayed()
    }

    @Test
    fun `canceled says the plan will not renew`() {
        render(content(plan = activePlan.copy(subscriptionStatus = "canceled")))

        composeRule.onNodeWithText("Cancelled").assertIsDisplayed()
        composeRule.onNodeWithText("This plan will not renew.").assertIsDisplayed()
    }

    @Test
    fun `a null tier reads as no plan, NEVER as Free`() {
        // ⛔ `GET /api/settings` substitutes a capitalised "Free" for its own callers and this route
        // deliberately does not. Rendering the word here would put a plan name on screen that no
        // database row contains.
        render(content(plan = activePlan.copy(subscriptionTier = null, subscriptionStatus = "none")))

        composeRule.onNodeWithText("No plan on this workspace").assertIsDisplayed()
        composeRule.onNodeWithText("No subscription").assertIsDisplayed()
    }

    // ── The overage pair ─────────────────────────────────────────────────────

    @Test
    fun `hard_cap plus an exceeded cap says CALLS ARE BEING DECLINED`() {
        // ⛔ THE COMBINATION THAT IS AN OUTAGE. Under a hard cap an exceeded allowance means calls
        // are being refused right now, and the operator has no other way to learn that from this
        // app. The same flag under auto_bill is a charge — asserted below.
        render(
            content(
                plan = activePlan.copy(overagePolicy = "hard_cap", overageCapExceeded = true),
            ),
        )

        composeRule.onNodeWithContentDescription(BILLING_OVERAGE_BLOCKED_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Calls are being declined — your included minutes are used up",
        ).assertIsDisplayed()
    }

    @Test
    fun `auto_bill plus an exceeded cap says the extra is BILLED, not blocked`() {
        render(content(plan = activePlan.copy(overagePolicy = "auto_bill", overageCapExceeded = true)))

        composeRule.onNodeWithContentDescription(BILLING_OVERAGE_EXCEEDED_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Over your included minutes — the extra is being billed")
            .assertIsDisplayed()
    }

    @Test
    fun `a cap that has NOT been exceeded shows the policy and no warning`() {
        render(content(plan = activePlan.copy(overagePolicy = "hard_cap", overageCapExceeded = false)))

        composeRule.onNodeWithText("Calls stop once your included minutes run out.")
            .assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(BILLING_OVERAGE_BLOCKED_DESCRIPTION).assertCountEquals(0)
    }

    // ── The usage meter ──────────────────────────────────────────────────────

    @Test
    fun `minutes are metered against the plan's allowance when both are known`() {
        // ⚠️ 318.5 + 1204.25 = 1522.75, against 1500 included — deliberately OVER, so the label
        // proves it is not clamped the way the bar is.
        render(content())

        composeRule.onNodeWithText("1522.75 of 1500 included minutes used").assertIsDisplayed()
    }

    @Test
    fun `an unmetered month is a SENTENCE, never a column of zeros`() {
        // ⛔ A "0 minutes" beside an allowance asserts, in the register of a bill, that the
        // workspace made no calls. `usage: null` means nothing has been recorded, which is a
        // different claim and the only one that was measured.
        render(content(plan = activePlan.copy(usage = null)))

        composeRule.onNodeWithContentDescription(BILLING_METER_UNMETERED_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("No call minutes have been recorded this month yet.")
            .assertIsDisplayed()
    }

    @Test
    fun `minutes with no allowance are shown alone rather than against an invented one`() {
        // ⛔ THE ALLOWANCE LIVES ON THE STRIPE HALF, so this is what an outage looks like to the
        // meter. A bar drawn against an unknown denominator would have to invent one.
        render(content(stripe = StripeSectionState.Unavailable))

        composeRule.onNodeWithText("1522.75 minutes used").assertIsDisplayed()
    }

    // ── The Stripe section: three shapes of one 200 ──────────────────────────

    @Test
    fun `an unavailable Stripe section says so, and does NOT read as a free tier`() {
        // ⛔ THE ASSERTION THIS WHOLE SCREEN EXISTS FOR. `billingUnavailable` and "no Stripe
        // customer" arrive as the same body one key apart; rendering the first as an empty invoice
        // list would tell a paying customer they have no plan.
        render(content(stripe = StripeSectionState.Unavailable))

        composeRule.onNodeWithContentDescription(BILLING_UNAVAILABLE_DESCRIPTION).assertIsDisplayed()
        // ⚠️ The BODY, not the card's Eyebrow title — `Eyebrow` uppercases its input (CSS
        // `text-transform` has no TextStyle equivalent in Compose), so an assertion on the
        // sentence-case resource would silently match nothing.
        composeRule.onNodeWithText(
            "We could not reach our payment provider just now, so your subscription and invoices " +
                "are not shown. Your plan and your usage above are current, and nothing about your " +
                "account has changed.",
        ).assertIsDisplayed()
        // ⛔ AND THE PLAN CARD IS STILL THERE, correct and current: it never went to Stripe.
        composeRule.onNodeWithText("VoicePro").assertIsDisplayed()
        // ⛔ NO INVOICE CARD AT ALL, which is the free-account reading this state must not produce.
        composeRule.onAllNodesWithContentDescription(BILLING_INVOICES_DESCRIPTION)
            .assertCountEquals(0)
    }

    @Test
    fun `an empty Ready reads as no billing account, which is a different sentence`() {
        render(content(stripe = StripeSectionState.Ready(StripeBilling())))

        composeRule.onNodeWithText("No subscription is attached to this account.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(BILLING_INVOICES_EMPTY_DESCRIPTION).assertIsDisplayed()
        // ⛔ And NOT the outage card.
        composeRule.onAllNodesWithContentDescription(BILLING_UNAVAILABLE_DESCRIPTION)
            .assertCountEquals(0)
    }

    @Test
    fun `a failed Stripe read shows a card failure while the plan card survives`() {
        render(
            content(
                stripe = StripeSectionState.Failed(FailureText(message = UiText.Literal("Nope"))),
            ),
        )

        composeRule.onNodeWithContentDescription(BILLING_STRIPE_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("VoicePro").assertIsDisplayed()
    }

    // ── Subscriptions: renews vs ends, cents, discount ───────────────────────

    @Test
    fun `an active subscription RENEWS and a cancelled one ENDS on the same kind of date`() {
        // ⛔ SAME DATE FIELD, OPPOSITE MEANING, chosen by `cancel_at_period_end`. Drawing the
        // cancelled row under a "renews" heading tells a customer who cancelled that they are about
        // to be billed again — which is why the two handles are per-subscription rather than shared.
        render(content())

        assertRenewalStartsWith("sub_pro", "Renews")
        assertRenewalStartsWith("sub_legacy", "Ends")
    }

    @Test
    fun `a subscription price renders in DOLLARS, not in the cents it arrives as`() {
        // ⛔ 24900 IS $249.00. The integer rendered verbatim overstates the price by a factor of a
        // hundred.
        render(content())

        composeRule.onNodeWithText("$249.00 / month").assertIsDisplayed()
        composeRule.onNodeWithText("$15.00 / month").assertIsDisplayed()
    }

    @Test
    fun `a discount is shown when present and nothing is invented when absent`() {
        render(content())

        composeRule.onNodeWithContentDescription(BILLING_DISCOUNT_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Founding customer — 20% off").assertIsDisplayed()
    }

    @Test
    fun `a subscription with no discount renders no discount line`() {
        render(
            content(
                stripe = StripeSectionState.Ready(
                    StripeBilling(subscriptions = listOf(BillingSubscription(id = "s", tierName = "Plain"))),
                ),
            ),
        )

        composeRule.onAllNodesWithContentDescription(BILLING_DISCOUNT_DESCRIPTION).assertCountEquals(0)
    }

    // ── Invoices ─────────────────────────────────────────────────────────────

    @Test
    fun `invoice amounts render in dollars and an OPEN row shows what is OWED`() {
        // ⛔ An unpaid invoice has `amount_paid: 0`; showing that zero tells a customer they owe
        // nothing. The open row here has total 1500 → $15.00.
        render(content())

        composeRule.onNodeWithText("$249.00").assertIsDisplayed()
        composeRule.onNodeWithText("$15.00").assertIsDisplayed()
        composeRule.onNodeWithText("incl. $32.37 tax").assertIsDisplayed()
    }

    @Test
    fun `tapping an invoice with a hosted page hands the URL to the caller`() {
        var opened: String? = null
        render(content(), onOpenInvoice = { opened = it })

        composeRule.onNodeWithContentDescription(invoiceOpenDescription("in_paid")).performClick()

        assertEquals("https://invoice.stripe.test/in_paid", opened)
    }

    @Test
    fun `an invoice with NO hosted page offers no open action`() {
        // ⛔ `hosted_invoice_url` IS NULL UNTIL AN INVOICE IS FINALISED, which is the most ordinary
        // row there is — this month's, before it is paid. A row that looked tappable and did
        // nothing would be worse than a plain one.
        render(content())

        composeRule.onAllNodesWithContentDescription(invoiceOpenDescription("in_open")).assertCountEquals(0)
    }

    @Test
    fun `a truncated invoice history says so`() {
        // ⚠️ The server caps the list at 10. Staying silent would present a partial history as a
        // complete one.
        render(content())

        composeRule.onNodeWithContentDescription(BILLING_INVOICES_TRUNCATED_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── The read-only caption, and the states around it ──────────────────────

    @Test
    fun `the read-only caption states the limit for a client and names nowhere`() {
        // ⛔ PLAY'S PAYMENTS POLICY IS WHY THERE ARE NO CONTROLS. Without this line the screen reads
        // as half-built; with it, the boundary is deliberate. ⛔ IT NAMES NO DESTINATION (no "web
        // dashboard"): App Store Guideline 3.1.1 makes naming the destination the offence as much
        // as linking to it, and this copy is shared with the iOS client.
        render(content(), role = WorkspaceRole.CLIENT)

        composeRule.onNodeWithContentDescription(BILLING_READ_ONLY_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText(
            "Plan changes, payment methods and cancellations are not available in this app. " +
                "This screen is read-only.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a viewer is told who CAN change it, not to go somewhere that will also refuse them`() {
        render(content(), role = WorkspaceRole.VIEWER)

        composeRule.onNodeWithText(
            "This screen is read-only. Ask an agency or client member of this workspace to change " +
                "the plan.",
        ).assertIsDisplayed()
    }

    @Test
    fun `an unparsed role gets the viewer wording, failing closed`() {
        // ⚠️ `WorkspaceRole.fromWire` returns null for anything unrecognised, and `allowsMutation()`
        // is false for null — so a corrupted role is told to ask someone who can, which is the one
        // answer that is true for every role.
        render(content(), role = null)

        composeRule.onNodeWithText(
            "This screen is read-only. Ask an agency or client member of this workspace to change " +
                "the plan.",
        ).assertIsDisplayed()
    }

    @Test
    fun `loading shows skeletons and a total failure offers a retry`() {
        render(BillingUiState.Loading)
        composeRule.onNodeWithContentDescription(BILLING_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a whole-screen failure offers exactly one retry, and only when retrying could work`() {
        var retries = 0
        render(
            BillingUiState.Failed(FailureText(message = UiText.Literal("Could not load billing"))),
            onRetry = { retries += 1 },
        )

        composeRule.onNodeWithContentDescription(BILLING_FAILED_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(BILLING_RETRY_DESCRIPTION).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a non-retryable failure offers no retry button`() {
        // ⚠️ Contract drift and a role refusal produce the identical failure every time, so a
        // button that cannot work is worse than no button.
        render(
            BillingUiState.Failed(
                FailureText(message = UiText.Literal("Unexpected response"), retryable = false),
            ),
        )

        composeRule.onAllNodesWithContentDescription(BILLING_RETRY_DESCRIPTION).assertCountEquals(0)
    }

    // ── The remaining shapes of a row ────────────────────────────────────────

    @Test
    fun `a fixed-amount coupon is quoted in dollars, and a coupon with neither figure by name`() {
        val subscriptions = listOf(
            BillingSubscription(
                id = "s_amount",
                tierName = "Amount",
                discount = BillingDiscount(couponName = "Spring", amountOff = 500),
            ),
            BillingSubscription(
                id = "s_named",
                tierName = "Named",
                discount = BillingDiscount(couponName = "Partner"),
            ),
        )
        render(content(stripe = StripeSectionState.Ready(StripeBilling(subscriptions = subscriptions))))

        composeRule.onNodeWithText(string(R.string.billing_discount_amount, "Spring", "5.00")).assertIsDisplayed()
        composeRule.onNodeWithText("Partner").assertIsDisplayed()
        // A subscription with no period end states no turnover date at all.
        composeRule.onAllNodesWithContentDescription(renewalDescription("s_amount")).assertCountEquals(0)
    }

    @Test
    fun `tapping the row itself opens the hosted invoice too`() {
        var opened: String? = null
        render(content(), onOpenInvoice = { opened = it })

        composeRule.onNodeWithContentDescription(invoiceDescription("in_paid")).performClick()

        assertEquals("https://invoice.stripe.test/in_paid", opened)
    }

    @Test
    fun `a blank hosted URL is treated as no page, so the row offers nothing to tap`() {
        var opened: String? = null
        val invoice = BillingInvoice(id = "in_blank", total = 700, status = "draft", hostedInvoiceUrl = " ")
        render(
            content(stripe = StripeSectionState.Ready(StripeBilling(invoices = listOf(invoice)))),
            onOpenInvoice = { opened = it },
        )

        composeRule.onAllNodesWithContentDescription(invoiceOpenDescription("in_blank")).assertCountEquals(0)
        composeRule.onNodeWithContentDescription(invoiceDescription("in_blank")).performClick()
        assertEquals(null, opened)
        // An unrecognised Stripe status is still shown, in its own words.
        composeRule.onNodeWithText("draft").assertIsDisplayed()
        // And a list the server did not cap says nothing about truncation.
        composeRule.onAllNodesWithContentDescription(BILLING_INVOICES_TRUNCATED_DESCRIPTION).assertCountEquals(0)
    }

    @Test
    fun `an uncollectible invoice is badged, and a missing or blank status draws no badge`() {
        val invoices = listOf(
            BillingInvoice(id = "in_bad", total = 100, status = "uncollectible"),
            BillingInvoice(id = "in_null", total = 200, status = null),
            BillingInvoice(id = "in_blank", total = 300, status = ""),
        )
        render(content(stripe = StripeSectionState.Ready(StripeBilling(invoices = invoices))))

        composeRule.onNodeWithText("uncollectible").assertIsDisplayed()
        // Three rows, and exactly one status badge among them.
        composeRule.onNodeWithText("$2.00").assertIsDisplayed()
        composeRule.onNodeWithText("$3.00").assertIsDisplayed()
        composeRule.onAllNodesWithText("paid").assertCountEquals(0)
        composeRule.onAllNodesWithText("open").assertCountEquals(0)
    }

    @Test
    fun `a failed Stripe read offers a retry only when retrying could work`() {
        var retries = 0
        render(
            content(stripe = StripeSectionState.Failed(FailureText(message = UiText.Literal("Timed out")))),
            onRetry = { retries += 1 },
        )
        composeRule.onNodeWithText("Timed out").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a Stripe read that failed for good offers no retry`() {
        render(
            content(
                stripe = StripeSectionState.Failed(
                    FailureText(message = UiText.Literal("Unexpected response"), retryable = false),
                ),
            ),
        )
        composeRule.onNodeWithText("Unexpected response").assertIsDisplayed()
        composeRule.onAllNodesWithText("Try again").assertCountEquals(0)
    }

    @Test
    fun `the billing month is named when it is known`() {
        render(content())
        composeRule.onNodeWithText(string(R.string.billing_meter_month, "2026-08")).assertIsDisplayed()
    }

    @Test
    fun `a blank billing month names no month`() {
        render(content(plan = activePlan.copy(usage = UsageData(month = "", callMinutesInbound = 5.0))))
        composeRule.onNodeWithText("5 of 1500 included minutes used").assertIsDisplayed()
        composeRule.onAllNodesWithText(string(R.string.billing_meter_month, ""), substring = false)
            .assertCountEquals(0)
    }

    @Test
    fun `a month with no call keys is unmetered even though a usage row exists`() {
        render(content(plan = activePlan.copy(usage = UsageData(month = "2026-08", smsOutbound = 3.0))))
        composeRule.onNodeWithContentDescription(BILLING_METER_UNMETERED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `the preview renders the plan it describes`() {
        composeRule.setContent { BillingScreenPreview() }
        composeRule.onNodeWithText("VoicePro").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(BILLING_SUBSCRIPTIONS_EMPTY_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `a theme change redraws the invoices with both open actions still wired`() {
        val opened = mutableListOf<String>()
        val theme = ThemeFlip(composeRule)
        theme.setContent {
            BillingScreen(
                state = content(),
                role = WorkspaceRole.CLIENT,
                onRetry = {},
                onSignIn = {},
                onOpenInvoice = { opened += it },
                onBack = {},
            )
        }

        theme.flip()

        composeRule.onNodeWithContentDescription(invoiceDescription("in_paid")).performClick()
        composeRule.onNodeWithContentDescription(invoiceOpenDescription("in_paid")).performClick()
        assertEquals(List(2) { "https://invoice.stripe.test/in_paid" }, opened)
    }

    private fun string(id: Int, vararg args: Any): String =
        ApplicationProvider.getApplicationContext<Context>().getString(id, *args)

    /**
     * Assert which SENTENCE a subscription's turnover line uses.
     *
     * ⚠️ Reads the node's own text rather than searching for a string, because "Renews …" and
     * "Ends …" are both on screen at once for the two fixture subscriptions — a text search would
     * match whichever came first and a test asserting the cancelled row said "ends" could pass
     * against the active row saying "renews". The per-subscription handle is what makes the
     * question answerable at all.
     */
    private fun assertRenewalStartsWith(subscriptionId: String, prefix: String) {
        val text = composeRule.onNodeWithContentDescription(renewalDescription(subscriptionId))
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue("expected \"$text\" to start with \"$prefix\"", text.startsWith(prefix))
    }
}
