package com.distronode.districtai.core.model

import kotlinx.serialization.Serializable

/**
 * One phone number the carrier has for sale.
 *
 * ⛔ EVERY PRICE FIELD IS OPTIONAL BECAUSE THE KEY IS GENUINELY ABSENT, NOT NULL. The Twilio
 * implementation wraps its pricing lookup in a bare `catch {}` and leaves `monthlyPrice`
 * undefined when the account has no Pricing API access — and `JSON.stringify` DROPS an undefined
 * value rather than writing null, so the field vanishes from the wire. Typing it as required
 * would throw on the first search from such an account, in production, with a decode failure that
 * reads like contract drift rather than a missing price. `district-numbers-search.json` carries
 * one row with pricing and one without, precisely so this stays covered.
 *
 * ⚠️ [monthlyPrice] IS A NUMBER, NOT A FORMATTED STRING, and carries no currency of its own —
 * [currency] is a separate, equally optional field. A price with no currency beside it must not
 * be rendered with a symbol the server never sent.
 */
@Serializable
data class AvailableNumber(
    val phoneNumber: String,
    val locality: String? = null,
    val region: String? = null,
    val capabilities: List<String> = emptyList(),
    /** "local" or "tollFree" in practice; free text on the wire. */
    val type: String = "",
    val monthlyPrice: Double? = null,
    val setupPrice: Double? = null,
    val currency: String? = null,
)

/**
 * `GET /api/district/workspace/numbers/search`
 *
 * ⚠️ [provider] IS THE SERVER'S CHOICE, ECHOED BACK. A client may name a provider, but the
 * resolved credentials decide which carrier actually answered — so this reports what happened
 * rather than repeating the request.
 *
 * ⛔ THE SERVER HARDCODES `limit: 10` AND `capabilities: ["sms","voice"]`. Neither is a client
 * parameter, so a UI offering a page size or a capability filter would be offering a control the
 * route ignores.
 *
 * ⛔ AN UNCONFIGURED WORKSPACE ANSWERS **400**, NOT AN EMPTY LIST — `{success:false, error:
 * "Messaging provider not configured for workspace"}`. That is a legitimate account state (no
 * carrier connected yet), not a fault, and it reaches the client as
 * [com.distronode.districtai.core.network.ApiResult.HttpFailure] with status 400. Rendering it as
 * a crash would tell an operator their app is broken when the truth is that they have not
 * connected a carrier.
 */
@Serializable
data class NumberSearchResponse(
    val success: Boolean = false,
    val provider: String? = null,
    val numbers: List<AvailableNumber> = emptyList(),
)

/**
 * One line the workspace already has, whoever supplies it.
 *
 * ⛔ [managed] IS THE ONLY FIELD THAT IS NOT THE CARRIER'S OWN, AND IT DECIDES WHAT MAY BE
 * OFFERED. `true` means the line is held on DISTRONODE's carrier account rather than the
 * tenant's, so the tenant cannot release or reconfigure it — the row is theirs to USE, not to
 * administer. The two halves of this list also come from different databases: `managed:false`
 * rows are what the tenant's carrier account reports, `managed:true` rows are hub records the
 * carrier fetch deliberately never sees. Before the hub read existed, a workspace with live
 * managed DIDs got an empty list, which is indistinguishable from owning none.
 *
 * ⚠️ [provider] IS WIDENED TO A PLAIN STRING on purpose: the hub's `PhoneNumberIndex.provider` is
 * a free-text column, and a managed row with a blank one falls back to whatever
 * `messagingConfig.managed` names — or to the literal "unknown".
 */
@Serializable
data class ListedNumber(
    val phoneNumber: String,
    val friendlyName: String? = null,
    val capabilities: List<String> = emptyList(),
    val type: String = "",
    val status: String = "",
    val smsUrl: String? = null,
    val voiceUrl: String? = null,
    val provider: String = "",
    /** ⚠️ Absent, not null, on a managed row with no recorded price. See [AvailableNumber]. */
    val monthlyPrice: Double? = null,
    val managed: Boolean = false,
)

/**
 * `GET /api/district/workspace/provider/numbers`
 *
 * ⛔ [partial] IS THE DANGEROUS SHAPE OF THIS ROUTE AND IT MUST NEVER BE DROPPED. It arrives on a
 * **200** carrying a real but SHORT list: one carrier answered, another did not, and the response
 * decodes perfectly while describing less inventory than the workspace owns. The route reserves
 * its 502 for "a carrier failed AND nothing resolved at all", because a failed lookup rendered as
 * an empty list reads as "you own no numbers". A client must therefore render the rows AND say
 * the list is short. A banner that replaced the list would throw away an answer already in hand,
 * the same conflation that can send a paying customer to a checkout page.
 *
 * ⚠️ BOTH FLAGS ARE ABSENT — not false, not empty — ON A CLEAN LIST. That is why both carry
 * defaults, and why `district-provider-numbers-partial.json` exists beside the clean fixture:
 * with only one of them, the pair would be indistinguishable to a decoder.
 */
@Serializable
data class OwnedNumbersResponse(
    val success: Boolean = false,
    val numbers: List<ListedNumber> = emptyList(),
    val partial: Boolean = false,
    /** ⚠️ Non-empty exactly when [partial] is true. Named so the banner can say WHICH carrier. */
    val failedProviders: List<String> = emptyList(),
)
