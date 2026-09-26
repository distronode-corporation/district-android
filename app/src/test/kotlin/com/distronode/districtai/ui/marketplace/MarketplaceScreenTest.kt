package com.distronode.districtai.ui.marketplace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.model.AvailableNumber
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.ui.FailureText
import com.distronode.districtai.ui.ROBOLECTRIC_SDK
import com.distronode.districtai.ui.ThemeFlip
import com.distronode.districtai.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the marketplace draws, and the three things it must never draw: a purchase control, an
 * unconfigured workspace as a red failure, and a partial list as a complete one.
 */
@RunWith(AndroidJUnit4::class)
// ⛔ A TALL VIEWPORT, for the same reason the analytics screen needs one: this is a
// `verticalScroll` Column, so every card is COMPOSED whether or not it is on screen while
// `assertIsDisplayed` checks visible BOUNDS. On a phone-sized Robolectric display the read-only
// caption sits below the fold and the assertion fails with "is not displayed" against a node that
// is perfectly present — which reads as a rendering bug rather than a short viewport.
@Config(sdk = [ROBOLECTRIC_SDK], qualifiers = "w1280dp-h3000dp")
class MarketplaceScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val available = AvailableNumber(
        phoneNumber = "+14165550111",
        locality = "Toronto",
        region = "ON",
        capabilities = listOf("sms", "voice"),
        type = "local",
        monthlyPrice = 1.15,
        currency = "USD",
    )

    private val ownNumber = ListedNumber(
        phoneNumber = "+14165550100",
        friendlyName = "Main line",
        capabilities = listOf("sms", "voice"),
        type = "local",
        status = "in-use",
        provider = "twilio",
    )

    private val managedNumber = ListedNumber(
        phoneNumber = "+14165550199",
        capabilities = listOf("voice"),
        type = "local",
        status = "active",
        provider = "telnyx",
        managed = true,
    )

    @Suppress("LongParameterList")
    private fun render(
        state: MarketplaceUiState,
        role: WorkspaceRole? = WorkspaceRole.CLIENT,
        onSelectTab: (MarketplaceTab) -> Unit = {},
        onUpdateForm: (NumberSearchForm) -> Unit = {},
        onSearch: () -> Unit = {},
        onRetryOwned: () -> Unit = {},
        onOpenWeb: () -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                MarketplaceScreen(
                    state = state,
                    role = role,
                    onSelectTab = onSelectTab,
                    onUpdateForm = onUpdateForm,
                    onSearch = onSearch,
                    onRetryOwned = onRetryOwned,
                    onOpenWeb = onOpenWeb,
                    onBack = {},
                )
            }
        }
    }

    // ── The read-only boundary ───────────────────────────────────────────────

    @Test
    fun `the read-only caption is always present`() {
        // ⛔ WITHOUT IT, A LIST OF PURCHASABLE NUMBERS WITH NOTHING TO TAP READS AS A BROKEN
        // SCREEN. The caption is what turns an absence of controls into a deliberate boundary.
        render(MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))))

        composeRule.onNodeWithContentDescription(MARKETPLACE_READ_ONLY_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun `a viewer is told who can make the change, and is sent nowhere`() {
        // ⚠️ Pointing a viewer anywhere else is useless advice (it will refuse them too), and it is
        // also App Store Guideline 3.1.1 steering, so the sentence names only the role that can act.
        render(
            MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))),
            role = WorkspaceRole.VIEWER,
        )

        composeRule.onNodeWithText(
            "This screen is read-only. Ask an agency or client member of this workspace to add " +
                "or release a number.",
        ).assertIsDisplayed()
    }

    // ── My numbers ───────────────────────────────────────────────────────────

    @Test
    fun `a managed line is badged, and a workspace-owned one is not`() {
        // ⛔ `managed` MEANS THE LINE SITS ON DISTRONODE'S CARRIER ACCOUNT. The tenant can use it
        // but cannot release it, which is the reason no release control could be offered for it
        // even if this screen had one.
        render(
            MarketplaceUiState(
                owned = OwnedState.Ready(listOf(ownNumber, managedNumber)),
            ),
        )

        composeRule.onNodeWithText("+14165550199").assertIsDisplayed()
        composeRule.onNodeWithText("+14165550100").assertIsDisplayed()
        // ⚠️ EXACTLY ONE BADGE FOR TWO ROWS. Asserting only that a badge EXISTS would also pass
        // for an implementation that badged every number, which is the direction that misleads —
        // it would tell an operator they cannot release a line that is entirely theirs.
        assertEquals(
            "exactly the managed row is badged",
            1,
            composeRule.onAllNodes(hasContentDescription(MARKETPLACE_MANAGED_DESCRIPTION))
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `a partial list shows the banner AND the rows`() {
        // ⛔ A BANNER THAT REPLACED THE LIST WOULD DISCARD INVENTORY ALREADY IN HAND. The list is
        // real, just short: one carrier answered and another did not.
        render(
            MarketplaceUiState(
                owned = OwnedState.Ready(
                    numbers = listOf(ownNumber, managedNumber),
                    partial = true,
                    failedProviders = listOf("telnyx"),
                ),
            ),
        )

        composeRule.onNodeWithContentDescription(MARKETPLACE_PARTIAL_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("+14165550100").assertIsDisplayed()
        composeRule.onNodeWithText("+14165550199").assertIsDisplayed()
    }

    @Test
    fun `a clean list shows no banner`() {
        render(MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))))

        composeRule.onNodeWithContentDescription(MARKETPLACE_PARTIAL_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `an empty owned list says so rather than showing nothing`() {
        render(MarketplaceUiState(owned = OwnedState.Ready(emptyList())))

        composeRule.onNodeWithText("This workspace has no active phone numbers.").assertIsDisplayed()
    }

    @Test
    fun `a failed owned read offers a retry`() {
        render(
            MarketplaceUiState(
                owned = OwnedState.Failed(FailureText(UiText.Literal("Could not reach telnyx."))),
            ),
        )

        composeRule.onNodeWithContentDescription(MARKETPLACE_OWNED_FAILURE_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Could not reach telnyx.").assertIsDisplayed()
    }

    // ── Search ───────────────────────────────────────────────────────────────

    @Test
    fun `the search tab shows a form and nothing else before a search`() {
        // ⚠️ Idle is NOT "no results". An empty-results message here would answer a question the
        // operator has not asked.
        render(MarketplaceUiState(tab = MarketplaceTab.SEARCH))

        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCH_ACTION_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MARKETPLACE_RESULTS_DESCRIPTION).assertDoesNotExist()
        composeRule.onNodeWithText("No matches").assertDoesNotExist()
    }

    @Test
    fun `search results render, with a price only where the carrier published one`() {
        // ⛔ AN ABSENT PRICE IS NOT ZERO. The carrier's pricing lookup fails independently of the
        // search, and the key is then missing from the wire entirely — a rendered "0" would quote
        // a price nobody was given.
        val priceless = available.copy(
            phoneNumber = "+18005550122",
            locality = null,
            region = null,
            type = "tollFree",
            monthlyPrice = null,
            currency = null,
        )
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.Ready("twilio", listOf(available, priceless)),
            ),
        )

        composeRule.onNodeWithText("+14165550111").assertIsDisplayed()
        composeRule.onNodeWithText("1.15 USD").assertIsDisplayed()
        composeRule.onNodeWithText("+18005550122").assertIsDisplayed()
        composeRule.onNodeWithText("0").assertDoesNotExist()
    }

    @Test
    fun `an unconfigured workspace is an explanatory empty state, not an error`() {
        // ⛔ NO RETRY, BECAUSE RETRYING CANNOT CONNECT A CARRIER. And the server's own sentence is
        // shown, because it distinguishes "none configured" from "that one is not connected".
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.NotConfigured("Messaging provider not configured for workspace"),
            ),
        )

        composeRule.onNodeWithContentDescription(MARKETPLACE_NOT_CONFIGURED_DESCRIPTION)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Messaging provider not configured for workspace")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCH_FAILURE_DESCRIPTION)
            .assertDoesNotExist()
    }

    @Test
    fun `a successful search with no matches says the carrier has nothing`() {
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.Ready("twilio", emptyList()),
            ),
        )

        composeRule.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test
    fun `the type chips report the value the route accepts`() {
        var form: NumberSearchForm? = null
        render(MarketplaceUiState(tab = MarketplaceTab.SEARCH), onUpdateForm = { form = it })

        composeRule.onNodeWithContentDescription(typeDescription(NUMBER_TYPE_TOLL_FREE)).performClick()

        // ⚠️ "tollFree", the server's own spelling. An unrecognised value is silently ignored
        // server-side, so a wrong one would serve LOCAL numbers under a "toll-free" heading with
        // nothing anywhere reporting a problem.
        assertEquals(NUMBER_TYPE_TOLL_FREE, form?.type)
    }

    @Test
    fun `the search button fires the search`() {
        var searches = 0
        render(MarketplaceUiState(tab = MarketplaceTab.SEARCH), onSearch = { searches += 1 })

        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCH_ACTION_DESCRIPTION).performClick()

        assertEquals(1, searches)
    }

    @Test
    fun `the segments switch tabs`() {
        var selected: MarketplaceTab? = null
        render(MarketplaceUiState(), onSelectTab = { selected = it })

        composeRule.onNodeWithContentDescription(MARKETPLACE_TAB_SEARCH_DESCRIPTION).performClick()

        assertEquals(MarketplaceTab.SEARCH, selected)
    }

    @Test
    fun `the owned list is the landing tab`() {
        render(MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))))

        composeRule.onNodeWithContentDescription(MARKETPLACE_OWNED_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `loading draws skeletons rather than an empty screen`() {
        render(MarketplaceUiState(owned = OwnedState.Loading))

        composeRule.onNodeWithContentDescription(MARKETPLACE_LOADING_DESCRIPTION).assertIsDisplayed()
    }

    // ── The web hand-off ─────────────────────────────────────────────────────

    @Test
    fun `the hand-off is a LINK to the web and its copy says purchasing happens there`() {
        // ⛔ NOT LABELLED "Buy", DELIBERATELY. Purchasing stays off the phone under Google Play's
        // Payments policy — see `marketplaceWebUrl` — and a "Buy" button that opened a browser
        // would read as an in-app purchase that failed rather than as a boundary.
        var opened = 0
        render(
            MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))),
            onOpenWeb = { opened++ },
        )

        composeRule.onNodeWithText("Open the number marketplace on the web").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Numbers are bought on the District AI website, not in the app. This opens it " +
                    "in your browser, signed in as you.",
            )
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(MARKETPLACE_BUY_WEB_DESCRIPTION).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the hand-off is present on the search tab too`() {
        // ⚠️ The search results are where "how do I get this number" is actually asked, so the
        // link has to be reachable from that tab and not only from the owned list.
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.Ready("twilio", listOf(available)),
            ),
        )

        composeRule
            .onNodeWithContentDescription(MARKETPLACE_BUY_WEB_DESCRIPTION)
            .assertIsDisplayed()
    }

    @Test
    fun `a viewer is not offered the hand-off at all`() {
        // ⛔ THE WEB DASHBOARD REFUSES THEM TOO, so the link would be a route to a second refusal.
        // The caption already tells them who to ask, which is the useful answer.
        render(
            MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))),
            role = WorkspaceRole.VIEWER,
        )

        composeRule
            .onNodeWithContentDescription(MARKETPLACE_BUY_WEB_DESCRIPTION)
            .assertDoesNotExist()
        composeRule.onNodeWithContentDescription(MARKETPLACE_READ_ONLY_DESCRIPTION)
            .assertIsDisplayed()
    }

    // ── Search in flight, a failed search, and rows the carrier left bare ────

    @Test
    fun `a search in flight holds the form and draws a placeholder for the results`() {
        render(MarketplaceUiState(tab = MarketplaceTab.SEARCH, search = SearchState.Loading))

        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCHING_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MARKETPLACE_AREA_CODE_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(MARKETPLACE_COUNTRY_DESCRIPTION).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCH_ACTION_DESCRIPTION).assertIsNotEnabled()
    }

    @Test
    fun `a failed search is a card with a retry that runs the search again`() {
        var searches = 0
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.Failed(FailureText(message = UiText.Literal("Carrier timed out"))),
            ),
            onSearch = { searches += 1 },
        )

        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCH_FAILURE_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithText("Carrier timed out").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, searches)
    }

    @Test
    fun `a search that failed for good offers no retry`() {
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.Failed(
                    FailureText(message = UiText.Literal("Unexpected response"), retryable = false),
                ),
            ),
        )

        composeRule.onNodeWithText("Unexpected response").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a number with no published capabilities falls back to its type, and no carrier is named`() {
        render(
            MarketplaceUiState(
                tab = MarketplaceTab.SEARCH,
                search = SearchState.Ready(
                    provider = null,
                    numbers = listOf(available.copy(capabilities = emptyList(), type = "mobile")),
                ),
            ),
        )

        composeRule.onNodeWithText("mobile").assertIsDisplayed()
        composeRule.onNodeWithText("sms · voice").assertDoesNotExist()
        composeRule.onNodeWithText("twilio", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a blank friendly name draws no name line`() {
        render(MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber.copy(friendlyName = " ")))))

        composeRule.onNodeWithText("+14165550100").assertIsDisplayed()
        composeRule.onNodeWithText("Main line").assertDoesNotExist()
    }

    @Test
    fun `switching tabs and back keeps each segment reporting its own tab`() {
        // ⚠️ The segments are redrawn on every switch; each must still report the tab it names.
        val selections = mutableListOf<MarketplaceTab>()
        var state by mutableStateOf(MarketplaceUiState(owned = OwnedState.Ready(listOf(ownNumber))))
        renderLive(
            { state },
            onSelectTab = {
                selections += it
                state = state.copy(tab = it)
            },
        )

        composeRule.onNodeWithContentDescription(MARKETPLACE_TAB_SEARCH_DESCRIPTION).performClick()
        composeRule.onNodeWithContentDescription(MARKETPLACE_SEARCH_ACTION_DESCRIPTION).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MARKETPLACE_TAB_OWNED_DESCRIPTION).performClick()
        composeRule.onNodeWithText("+14165550100").assertIsDisplayed()

        assertEquals(listOf(MarketplaceTab.SEARCH, MarketplaceTab.OWNED), selections)
    }

    @Test
    fun `a type chip picked after typing an area code keeps the area code`() {
        // ⚠️ The chip copies the CURRENT form; a chip holding the form it was first drawn with would
        // silently drop what the operator typed since.
        var state by mutableStateOf(MarketplaceUiState(tab = MarketplaceTab.SEARCH))
        renderLive({ state }, onUpdateForm = { state = state.copy(form = it) })

        composeRule.onNodeWithContentDescription(MARKETPLACE_AREA_CODE_DESCRIPTION).performTextInput("416")
        composeRule.onNodeWithContentDescription(typeDescription(NUMBER_TYPE_MOBILE)).performClick()

        assertEquals(NumberSearchForm(areaCode = "416", type = NUMBER_TYPE_MOBILE), state.form)
    }

    @Test
    fun `typing a country reaches the form`() {
        var state by mutableStateOf(MarketplaceUiState(tab = MarketplaceTab.SEARCH))
        renderLive({ state }, onUpdateForm = { state = state.copy(form = it) })

        composeRule.onNodeWithContentDescription(MARKETPLACE_COUNTRY_DESCRIPTION).performTextClearance()
        composeRule.onNodeWithContentDescription(MARKETPLACE_COUNTRY_DESCRIPTION).performTextInput("CA")

        assertEquals("CA", state.form.country)
    }

    @Test
    fun `a failure card follows a new failure and a new handle`() {
        var failure by mutableStateOf(FailureText(message = UiText.Literal("First")))
        var description by mutableStateOf("first-handle")
        composeRule.setContent {
            DistrictTheme {
                MarketplaceFailure(title = "Title", failure = failure, description = description, onRetry = {})
            }
        }

        failure = FailureText(message = UiText.Literal("Second"))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("first-handle").assertIsDisplayed()
        composeRule.onNodeWithText("Second").assertIsDisplayed()

        description = "second-handle"
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("second-handle").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("first-handle").assertDoesNotExist()
    }

    @Test
    fun `a theme change redraws the search form with its chips and tabs still reporting`() {
        val forms = mutableListOf<NumberSearchForm>()
        val tabs = mutableListOf<MarketplaceTab>()
        val theme = ThemeFlip(composeRule)
        theme.setContent {
            MarketplaceScreen(
                state = MarketplaceUiState(tab = MarketplaceTab.SEARCH),
                role = WorkspaceRole.CLIENT,
                onSelectTab = { tabs += it },
                onUpdateForm = { forms += it },
                onSearch = {},
                onRetryOwned = {},
                onOpenWeb = {},
                onBack = {},
            )
        }

        theme.flip()

        composeRule.onNodeWithContentDescription(typeDescription(NUMBER_TYPE_TOLL_FREE)).performClick()
        composeRule.onNodeWithContentDescription(MARKETPLACE_TAB_OWNED_DESCRIPTION).performClick()
        assertEquals(listOf(NUMBER_TYPE_TOLL_FREE), forms.map { it.type })
        assertEquals(listOf(MarketplaceTab.OWNED), tabs)
    }

    /** Like [render], but the state is read on every composition so a test can move it. */
    private fun renderLive(
        state: () -> MarketplaceUiState,
        onSelectTab: (MarketplaceTab) -> Unit = {},
        onUpdateForm: (NumberSearchForm) -> Unit = {},
    ) {
        composeRule.setContent {
            DistrictTheme {
                MarketplaceScreen(
                    state = state(),
                    role = WorkspaceRole.CLIENT,
                    onSelectTab = onSelectTab,
                    onUpdateForm = onUpdateForm,
                    onSearch = {},
                    onRetryOwned = {},
                    onOpenWeb = {},
                    onBack = {},
                )
            }
        }
    }

    // ── Price labels ─────────────────────────────────────────────────────────

    @Test
    fun `a price with no currency, or a blank one, is shown bare rather than guessed at`() {
        assertEquals("1.15", priceLabel(1.15, null))
        assertEquals("1.15", priceLabel(1.15, " "))
        assertEquals("1.15 CAD", priceLabel(1.15, "CAD"))
        assertEquals(null, priceLabel(null, "USD"))
    }

    // ── The hand-off URL ─────────────────────────────────────────────────────

    @Test
    fun `the web url is built from the api origin rather than hardcoded`() {
        // ⚠️ A build pointed at a staging host must hand off to THAT host's dashboard, not send
        // the operator to production to buy a number.
        assertEquals(
            "https://staging.distronode.test/dashboard/district/marketplace",
            marketplaceWebUrl("https://staging.distronode.test"),
        )
        assertEquals(
            "https://www.distronode.com/dashboard/district/marketplace",
            marketplaceWebUrl("https://www.distronode.com"),
        )
    }

    @Test
    fun `a trailing slash on the origin does not become a double slash`() {
        // ⚠️ `https://host//dashboard` is a protocol-relative path to some parsers and a plain 404
        // to others, and neither failure names its cause.
        assertEquals(
            "https://www.distronode.com/dashboard/district/marketplace",
            marketplaceWebUrl("https://www.distronode.com/"),
        )
    }
}
