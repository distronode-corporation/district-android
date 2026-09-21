package com.distronode.districtai.ui.marketplace

import com.distronode.districtai.core.model.AvailableNumber
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.ui.FailureText

/**
 * The phone-number marketplace's state.
 *
 * ⛔ TWO INDEPENDENT READS BEHIND ONE SCREEN, AND THEY NEVER SHARE A FAILURE. "What can I buy"
 * and "what do I have" hit different routes with different data layers — the first asks a
 * carrier's inventory API, the second merges that carrier's account with the platform's hub
 * records — so a single failure state would blank an answer the client already holds. Each half
 * carries its own.
 *
 * ⛔ AND THIS SCREEN HAS NO WRITE STATE AT ALL, BY DESIGN. There is no purchasing, releasing or
 * reconfiguring here: a number is a recurring carrier charge and a live line, and a released one
 * cannot be reclaimed. The app shows; the web dashboard changes. The web hand-off does not change
 * that: it opens a browser and holds no state of its own. If a `purchasing` flag ever appears in
 * this file, the decision recorded on [marketplaceWebUrl] has been reversed and
 * should have been discussed.
 */
data class MarketplaceUiState(
    val tab: MarketplaceTab = MarketplaceTab.OWNED,
    val form: NumberSearchForm = NumberSearchForm(),
    val search: SearchState = SearchState.Idle,
    val owned: OwnedState = OwnedState.Loading,
)

/**
 * ⚠️ "My numbers" IS THE LANDING TAB, not search. The common reason to open this screen is to
 * look up a line the workspace already runs; searching is the rarer, more deliberate act — and
 * opening on an empty search form would make the screen look like it had nothing in it.
 */
enum class MarketplaceTab { OWNED, SEARCH }

/**
 * The search form's fields.
 *
 * ⚠️ NO PAGE SIZE AND NO CAPABILITY FILTER, because the server hardcodes both (limit 10,
 * sms+voice). A control for either would be a control the route ignores, which is worse than its
 * absence: the operator would believe they had narrowed something.
 *
 * ⚠️ [country] DEFAULTS TO "US" TO MATCH THE SERVER'S OWN DEFAULT, so an untouched form sends
 * exactly what an omitted parameter would.
 */
data class NumberSearchForm(
    val areaCode: String = "",
    val country: String = DEFAULT_COUNTRY,
    val type: String = NUMBER_TYPE_LOCAL,
)

/** The three values the route accepts. Anything else is silently ignored server-side. */
const val NUMBER_TYPE_LOCAL: String = "local"
const val NUMBER_TYPE_TOLL_FREE: String = "tollFree"
const val NUMBER_TYPE_MOBILE: String = "mobile"
const val DEFAULT_COUNTRY: String = "US"

sealed interface SearchState {

    /** ⚠️ Nothing has been searched yet. NOT the same as "no results", and it must not read as it. */
    data object Idle : SearchState

    data object Loading : SearchState

    data class Ready(val provider: String?, val numbers: List<AvailableNumber>) : SearchState

    /**
     * ⛔ THE WORKSPACE HAS NO CARRIER CONNECTED, WHICH IS AN ACCOUNT STATE RATHER THAN A FAULT.
     * The server answers 400 with its own sentence; this is rendered as an explanatory empty
     * state with no retry, because retrying cannot help and a red error would tell an operator
     * their app is broken when the truth is they have not finished setting up.
     *
     * @param message the server's own wording, which also covers the narrower case of naming a
     *   provider the workspace has not connected.
     */
    data class NotConfigured(val message: String) : SearchState

    data class Failed(val failure: FailureText) : SearchState
}

sealed interface OwnedState {

    data object Loading : OwnedState

    /**
     * @param partial ⛔ TRUE MEANS THIS LIST IS SHORT AND THE SCREEN MUST SAY SO. One carrier
     *   answered and another did not, on a 200 that decodes perfectly — the most dangerous shape
     *   this route produces, because it draws exactly like a complete answer. The rows still
     *   render: a banner that replaced them would discard inventory already in hand.
     * @param failedProviders which carrier was unreachable, so the banner can name it.
     */
    data class Ready(
        val numbers: List<ListedNumber>,
        val partial: Boolean = false,
        val failedProviders: List<String> = emptyList(),
    ) : OwnedState

    data class Failed(val failure: FailureText) : OwnedState
}

/**
 * A monthly price, ready to show.
 *
 * ⛔ NULL IN, NULL OUT. A missing price is not zero and must not be rendered as one — the carrier's
 * pricing lookup fails independently of the search that returned the number, and the key is then
 * absent from the wire entirely. A rendered "0" would quote a price nobody was given.
 *
 * ⚠️ AND NO SYMBOL WITHOUT A CURRENCY. `monthlyPrice` and `currency` are separate optional fields,
 * so a price whose currency the server did not send is shown bare rather than guessed at.
 *
 * ⚠️ Lives beside the state rather than in `MarketplaceCards.kt` because that file sits at detekt's
 * 11-function ceiling — and because this is pure model formatting with no composition in it, which
 * makes it testable without a Robolectric environment.
 */
internal fun priceLabel(monthlyPrice: Double?, currency: String?): String? {
    if (monthlyPrice == null) return null
    val amount = monthlyPrice.toString()
    return if (currency.isNullOrBlank()) amount else "$amount $currency"
}

/**
 * The web marketplace, on the SAME ORIGIN this app already talks to.
 *
 * ⛔ BUYING A NUMBER STAYS OFF THE PHONE, AND THIS IS A POLICY DECISION RATHER THAN AN UNFINISHED
 * FEATURE. DO NOT "FINISH" THIS INTO AN IN-APP PURCHASE. Google Play's Payments
 * policy governs what an app may charge for in its own UI; a phone number is a recurring carrier
 * charge on a Stripe subscription that also covers the web product, and moving that flow into the
 * app is the kind of change that gets a listing removed rather than rejected at review. The
 * hand-off is the whole feature: the app searches, the browser sells.
 *
 * ⛔ AND IT IS A CUSTOM TAB, NOT A WEBVIEW, for the reason sign-in and account deletion are: the
 * purchase page is authenticated, so it needs the user's real browser session — which this app
 * cannot supply and must not be trusted to intercept.
 *
 * ⚠️ DERIVED FROM THE API BASE URL RATHER THAN HARDCODED. `ApiEnvironment.baseUrl` is the single
 * origin this client talks to, so a build pointed at a staging host hands off to that host's
 * dashboard instead of sending the operator to production to buy a number. ⚠️ A trailing slash on
 * the base is trimmed: the two concatenated would give `//dashboard`, which is a protocol-relative
 * path in some parsers and simply a 404 in others.
 */
internal fun marketplaceWebUrl(apiBaseUrl: String): String =
    apiBaseUrl.trimEnd('/') + MARKETPLACE_WEB_PATH

/** ⚠️ The web dashboard's own route. A rename there is a broken link here and nothing catches it. */
internal const val MARKETPLACE_WEB_PATH: String = "/dashboard/district/marketplace"
