package com.distronode.districtai.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Billing, from TWO routes with two different envelopes.
 *
 * ⛔ `GET /api/district/workspace/billing` ANSWERS THE ORDINARY `{success, billing}` DISTRICT
 * ENVELOPE AND REACHES NO VENDOR AT ALL. Every field of [WorkspaceBilling] is a column on our own
 * workspace row, written by the Stripe webhook handlers, so the plan, the status and the overage cap
 * still render during a Stripe outage. The route's header forbids adding a Stripe lookup to it, and
 * the contract test asserts none happens.
 *
 * ⛔ `GET /api/billing` ANSWERS A **BARE OBJECT WITH NO `success` KEY**, and that is the single most
 * important fact in this file. Every other response in this client carries `success` and the
 * repository layer rejects a 200 that does not affirm it (see `rejectedEnvelope`); applying that
 * rule here would reject every HEALTHY Stripe response as contract drift. See [StripeBilling].
 *
 * ⛔ AND THERE IS NO MUTATION ANYWHERE IN THIS FILE, BY DESIGN. `POST /api/billing` exists and
 * cancels subscriptions, changes plans and detaches cards; this client deliberately does not reach
 * it. Google Play's Payments policy is the reason a plan change cannot be offered in-app at all, and
 * a `cancel` here would be a policy violation rather than a missing feature. If a request DTO ever
 * appears in this file, that decision has been reversed and should have been discussed.
 */

/**
 * The plan/entitlement summary, from our own database.
 *
 * ⚠️ [billing] is nullable ONLY so a malformed `{}` body does not decode into a confident empty
 * plan. The server always sends it on a 200; the repository turns a missing one into a decode
 * failure rather than into "no plan", because those are different claims and only one of them is
 * something to tell a customer.
 */
@Serializable
data class WorkspaceBillingResponse(
    val success: Boolean = false,
    val billing: WorkspaceBilling? = null,
)

/**
 * One workspace's plan state.
 *
 * ⛔ [overagePolicy] AND [overageCapExceeded] MUST BE READ TOGETHER, AND THE COMBINATION CHANGES
 * WHAT IS TRUE OF THE PRODUCT. Under [OVERAGE_POLICY_AUTO_BILL] an exceeded cap is a billing note:
 * calls continue and the overage is charged. Under [OVERAGE_POLICY_HARD_CAP] an exceeded cap means
 * **calls are being refused right now**, which is an outage the operator is living through and has
 * no other way to learn about from this app. Rendering the flag without the policy states neither.
 *
 * ⛔ [subscriptionTier] IS NULLABLE AND NULL IS NOT "Free". The column is nullable and this route
 * passes it through untouched — `GET /api/settings` substitutes a capitalised "Free" and this one
 * deliberately does not. Inventing the word here would put a plan name on screen that no row
 * contains.
 *
 * ⚠️ [subscriptionTier] and [plan] are the SAME FACT IN TWO CASINGS, not two facts. They are written
 * together (`subscriptionTier: tier`, `plan: tierLower`) and the tier keeps the catalogue's mixed
 * case — "VoicePro", not "voicepro". A case-insensitive comparison is the only safe one.
 *
 * @param usage ⛔ NULL MEANS "NOTHING METERED THIS MONTH YET", AND IT IS NOT ZERO. Reused from the
 *   analytics package rather than redeclared: this route and `workspace/usage` both call `getUsage`,
 *   and the contract test asserts the two payloads are byte-identical so one DTO decodes both. See
 *   [UsageData] for why every metric inside it is nullable.
 */
@Serializable
data class WorkspaceBilling(
    val subscriptionTier: String? = null,
    val subscriptionStatus: String = "",
    val plan: String = "",
    val overagePolicy: String = "",
    val overageCapExceeded: Boolean = false,
    val usage: UsageData? = null,
)

/**
 * The subscription statuses this screen distinguishes.
 *
 * ⚠️ PLAIN STRINGS RATHER THAN AN ENUM, for the reason [WorkspaceRole] documents: the column is
 * free text server-side with no Prisma enum behind it, so a fourth value is a schema-level
 * possibility. An enum would throw on decode and take the whole response with it; an unrecognised
 * string here simply falls through to the neutral styling.
 */
const val SUBSCRIPTION_STATUS_ACTIVE: String = "active"
const val SUBSCRIPTION_STATUS_PAST_DUE: String = "past_due"
const val SUBSCRIPTION_STATUS_CANCELED: String = "canceled"

/** ⛔ See [WorkspaceBilling]: with an exceeded cap, this one BLOCKS CALLS and the other bills. */
const val OVERAGE_POLICY_HARD_CAP: String = "hard_cap"
const val OVERAGE_POLICY_AUTO_BILL: String = "auto_bill"

/**
 * The Stripe-backed detail, and the one response in this client with NO `success` key.
 *
 * ⛔ THIS ROUTE HAS **THREE** SHAPES AND TWO OF THEM ARE NEARLY IDENTICAL. Pinned by three separate
 * fixtures because the difference between them is a single absent key:
 *
 *   - **Healthy** — twelve top-level keys, no `billingUnavailable`.
 *   - **Degraded** (`district-billing-unavailable.json`) — six keys, `billingUnavailable: true`.
 *     Stripe could not be reached. The screen must say so.
 *   - **No customer** (`district-billing-no-customer.json`) — the SAME six minus the flag. This
 *     account genuinely has no billing set up, which is a legitimate state.
 *
 * A client that branched on "are the arrays empty" would call a Stripe outage a free account:
 * the "we could not look" / "there is nothing" conflation that can route a paying customer to a
 * checkout page, except here it would be doing it about their PLAN.
 * Branch on [billingUnavailable].
 *
 * ⛔ EVERY FIELD HAS A DEFAULT AND THE DEGRADED SHAPES ARE WHY. Seven of the twelve keys are ABSENT
 * from the degraded body — not null, absent — so a required field on any of them would fail to
 * decode precisely when billing was already broken, turning a legible "temporarily unavailable"
 * into an illegible "the app does not understand this response".
 *
 * ⚠️ THE SUB-OBJECTS ARE OPAQUE [JsonElement]s ON PURPOSE, AND THAT IS A DELIBERATE NARROWING
 * RATHER THAN LAZINESS. [paymentMethod], [paymentMethods], [businessProfile], [taxIds] and
 * [billingAddress] are card details, a legal business name, tax registration numbers and a postal
 * address — and this screen renders NONE of them, because it offers no way to change any of them
 * (see the ⛔ on the file). They are modelled only because the strict contract decoder runs with
 * `ignoreUnknownKeys = false`, so an unmodelled key would fail the gate. Typing them out would
 * invite a future screen to display them and would make a Stripe-side shape change to a card
 * object break a screen that never showed one. If one of these is ever needed, model THAT one.
 *
 * ⚠️ [overagePolicy] AND [overageCapExceeded] APPEAR ON **BOTH** ROUTES and are read from our own
 * database on both. [WorkspaceBilling] is the copy to trust: this one is best-effort here (the
 * route swallows a failed read into null/false) and absent entirely from the degraded shapes.
 *
 * ⛔ [overageSpendCapCents] AND [overageSpendCapExceeded] ARE ON **THIS ROUTE ONLY**, and that is
 * the asymmetry to remember: the spending cap has no Stripe-independent copy, so unlike the
 * overage PAIR above there is nothing to fall back on when this route degrades.
 */
@Serializable
data class StripeBilling(
    /** ⛔ True means "we could not ask Stripe", never "there is no plan". See the class header. */
    val billingUnavailable: Boolean = false,
    val subscriptions: List<BillingSubscription> = emptyList(),
    val invoices: List<BillingInvoice> = emptyList(),
    /** ⚠️ The server caps the list at 10. True means the history shown is not all of it. */
    val invoicesHasMore: Boolean = false,
    val paymentMethod: JsonElement? = null,
    val paymentMethods: JsonElement? = null,
    val businessProfile: JsonElement? = null,
    val taxIds: JsonElement? = null,
    val billingAddress: JsonElement? = null,
    val customerId: String? = null,
    /**
     * The workspace whose subscription this is — the server's ACTIVE workspace, index 0 of its own
     * listing.
     *
     * ⚠️ IT CAN DISAGREE WITH THE WORKSPACE THE SCREEN NAMES, and the server logs a warning when it
     * does: if the active workspace carries no Stripe linkage, the route falls through to another
     * workspace of the same user. This screen reads both routes, so the mismatch is detectable —
     * `/api/district/workspace/billing` is always about the workspace that was asked for.
     */
    val usageWorkspaceId: String? = null,
    val overagePolicy: String? = null,
    val overageCapExceeded: Boolean = false,
    /**
     * The workspace's monthly overage SPENDING cap, in **CENTS**, or null for no cap.
     *
     * ⛔ NULL AND 0 ARE DIFFERENT STATES. Null is "no ceiling set"; 0 would be a $0.00 ceiling,
     * i.e. block everything. Defaulting a missing value to 0 would tell a customer with no cap
     * that they are capped at nothing — hence `Int?`, not `Int = 0`.
     *
     * ⚠️ CENTS, like every money field on this route. 5000 is $50.00.
     *
     * ⚠️ ABSENT ON THE DEGRADED SHAPES, where the default null then means "we could not look"
     * rather than "no cap is set". Do not render "no spending cap" off a null while
     * [billingUnavailable] is true — that states a fact about the customer's configuration that
     * was never read.
     */
    val overageSpendCapCents: Int? = null,
    /**
     * True when this month's overage spend has REACHED [overageSpendCapCents] and calls are being
     * refused for that reason.
     *
     * ⛔ THE SECOND WAY A WORKSPACE LOSES ITS PHONE LINE, and it is not the same as
     * [overageCapExceeded]. That one bites under `hard_cap` and is fixed by switching policy or
     * upgrading; this one bites under `auto_bill` and is fixed by RAISING OR REMOVING THE CAP.
     * Offering the wrong remedy sends the customer to a control that cannot unblock them.
     *
     * ⚠️ NOT PUBLISHED BY `/api/district/workspace/billing`, unlike [overageCapExceeded]. There is
     * no Stripe-independent copy of this field to fall back on during a Stripe outage.
     */
    val overageSpendCapExceeded: Boolean = false,
)

/**
 * One active subscription.
 *
 * ⛔ [amount] IS IN **CENTS**, like every money field on this route. 24900 is $249.00. Rendering the
 * integer verbatim overstates a price by a factor of a hundred, on the one screen where a wrong
 * number becomes a support ticket.
 *
 * ⛔ [cancelAtPeriodEnd] CHANGES WHAT [currentPeriodEnd] MEANS. False: the plan RENEWS on that date.
 * True: it ENDS on it. Drawing the same date under a "renews" heading tells a customer who has
 * already cancelled that they are about to be billed again.
 *
 * ⛔ [includedMinutes], [overageRate] AND [discount] ARE ABSENT KEYS, NOT NULLS, when they do not
 * apply — `JSON.stringify` drops an undefined value. The server derives the first two by matching
 * the price against its tier catalogue, so a legacy or custom price yields neither, and a usage
 * meter has nothing to measure against. Render the meter only when the allowance is known.
 *
 * ⚠️ [currentPeriodEnd] is UNIX SECONDS, not milliseconds — multiply by 1000 before it is a date.
 * Carried as a `Long` because a seconds timestamp already exceeds `Int` range in 2038 and the
 * multiplication overflows a `Long`-less arithmetic long before that.
 */
@Serializable
data class BillingSubscription(
    val id: String = "",
    /** Stripe's own status word. The plan status this screen headlines is [WorkspaceBilling]'s. */
    val status: String = "",
    @SerialName("current_period_end")
    val currentPeriodEnd: Long? = null,
    @SerialName("cancel_at_period_end")
    val cancelAtPeriodEnd: Boolean = false,
    val tierName: String = "",
    /** ⛔ CENTS. See the class header. */
    val amount: Long? = null,
    val includedMinutes: Int? = null,
    val overageRate: Double? = null,
    val discount: BillingDiscount? = null,
)

/**
 * A coupon applied to a subscription.
 *
 * ⚠️ EXACTLY ONE OF [percentOff] AND [amountOff] IS PRESENT, and the server sends neither key when
 * the corresponding Stripe field is null. [amountOff] is in CENTS like every other money field here.
 */
@Serializable
data class BillingDiscount(
    val couponName: String = "",
    val percentOff: Double? = null,
    val amountOff: Long? = null,
)

/**
 * One invoice.
 *
 * ⛔ [amountPaid], [total] AND [tax] ARE ALL IN **CENTS**. [tax] is summed server-side out of
 * Stripe's `total_taxes`, so a zero here is a MEASURED zero rather than an absence — the key is
 * always present.
 *
 * ⛔ [hostedInvoiceUrl] AND [invoicePdf] ARE NULL UNTIL AN INVOICE IS FINALISED, which makes the
 * most ordinary row there is — this month's, before it is paid — the one that would throw on a
 * client that typed them non-null. A row with no URL must render without an open action rather than
 * with a dead one.
 *
 * ⚠️ [created] is UNIX SECONDS. Treating it as milliseconds dates every invoice to January 1970.
 */
@Serializable
data class BillingInvoice(
    val id: String = "",
    @SerialName("amount_paid")
    val amountPaid: Long = 0,
    val total: Long = 0,
    val tax: Long = 0,
    /** Stripe's word: `paid`, `open`, `draft`, `void`, `uncollectible`. Shown verbatim. */
    val status: String? = null,
    val created: Long = 0,
    @SerialName("hosted_invoice_url")
    val hostedInvoiceUrl: String? = null,
    @SerialName("invoice_pdf")
    val invoicePdf: String? = null,
)
