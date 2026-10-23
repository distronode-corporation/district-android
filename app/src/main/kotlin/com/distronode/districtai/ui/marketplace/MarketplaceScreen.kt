package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.distronode.districtai.R
import com.distronode.districtai.core.model.ListedNumber
import com.distronode.districtai.core.designsystem.ButtonSize
import com.distronode.districtai.core.designsystem.ButtonVariant
import com.distronode.districtai.core.designsystem.ContentContainer
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictScaffold
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.DistrictTopBar
import com.distronode.districtai.core.designsystem.SkeletonBlock
import com.distronode.districtai.core.model.WorkspaceRole
import com.distronode.districtai.core.model.allowsMutation
import com.distronode.districtai.ui.InlineFailure

/**
 * The phone-number marketplace: what the workspace has, what a carrier has for sale, the regulatory
 * filings, and the carrier account (iOS `MarketplaceView`).
 *
 * ⛔ EVERYTHING BUT BUYING IS HERE SINCE 3.0: a number's routing and release (an owned row opens its
 * actions), the filings, SIP, one-time passcodes, A2P, toll-free and the billed lookup. The writes
 * are [NumberProvisioningViewModel]'s, gated on the role and confirmed where billed, filed or
 * irreversible. ⛔ BUYING STAYS ON THE WEB, and the hand-off's caption says so.
 *
 * ⛔ THE ONE AFFORDANCE THAT LEAVES IS A LINK, NOT A PURCHASE. Buying stays off the phone by an
 * owner decision recorded on [marketplaceWebUrl] — Play's Payments policy — so [PurchaseHandOff]
 * opens the web marketplace in a Custom Tab and this screen keeps no purchase state at all.
 *
 * ⛔ BOTH ROUTES BEHIND THIS SCREEN ADMIT `viewer`, so no READ is role-gated. The role decides the
 * CAPTIONS — a viewer is told who can change a number (telling them to "use the web dashboard"
 * points them at a second refusal), anyone else is told under the hand-off where numbers are
 * bought — and it decides whether the hand-off is offered at all, for exactly the same reason.
 *
 * ⚠️ THE CARDS LIVE IN `MarketplaceCards.kt`. This file owns the shell: the scaffold, the two
 * segments, and the dispatch into each tab's states — the same split AnalyticsScreen.kt uses, and
 * for the same detekt file ceiling.
 */
@Composable
fun MarketplaceScreen(
    state: MarketplaceUiState,
    role: WorkspaceRole?,
    onSelectTab: (MarketplaceTab) -> Unit,
    onUpdateForm: (NumberSearchForm) -> Unit,
    onSearch: () -> Unit,
    onRetryOwned: () -> Unit,
    /**
     * ⛔ OPENS THE WEB MARKETPLACE IN A BROWSER. It is not a purchase and must never become one —
     * see [marketplaceWebUrl] for the Play Payments reasoning. The screen holds no state for it.
     */
    onOpenWeb: () -> Unit,
    onBack: () -> Unit,
    /** Number management; null where it is not wired (a test about the reads). */
    provisioning: ProvisioningUi? = null,
) {
    DistrictScaffold(
        modifier = Modifier.testTag(MARKETPLACE_ROOT_DESCRIPTION),
        topBar = {
            DistrictTopBar(title = stringResource(R.string.marketplace_title), onBack = onBack)
        },
    ) { inset ->
        Column(
            modifier = inset
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
        ) {
            ContentContainer { Segments(state.tab, onSelectTab) }

            when (state.tab) {
                MarketplaceTab.OWNED -> OwnedTab(state.owned, onRetryOwned, provisioning?.onOpenNumber)
                MarketplaceTab.SEARCH -> SearchTab(state, onUpdateForm, onSearch)
                MarketplaceTab.REGISTRATIONS -> provisioning?.let {
                    RegistrationsTab(it.state, it.canProvision, it.handlers)
                }
                MarketplaceTab.CARRIER -> provisioning?.let { CarrierTab(it.state, it.canProvision, it.handlers) }
            }

            if (!role.allowsMutation() && state.tab in VIEWER_CAPTION_TABS) {
                ContentContainer { ViewerCaption() }
            }

            ContentContainer { PurchaseHandOff(role, onOpenWeb) }

            // Bottom breathing room; a modifier padding would be clipped by the scroll container.
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
    provisioning?.let { ProvisioningDialogs(it) }
}

/**
 * ⚠️ TWO BUTTONS RATHER THAN A TabRow, for the same reason the analytics window selector is
 * buttons rather than chips: this design system has no tab component, and inventing one for a
 * single screen is how a second, drifting set of primitives starts. Primary-versus-Secondary is
 * already the system's "this one is active".
 */
@Composable
private fun Segments(selected: MarketplaceTab, onSelectTab: (MarketplaceTab) -> Unit) {
    val tabs = listOf(
        Triple(MarketplaceTab.OWNED, R.string.marketplace_tab_owned, MARKETPLACE_TAB_OWNED_DESCRIPTION),
        Triple(MarketplaceTab.SEARCH, R.string.marketplace_tab_search, MARKETPLACE_TAB_SEARCH_DESCRIPTION),
        Triple(
            MarketplaceTab.REGISTRATIONS,
            R.string.numbers_tab_registrations,
            MARKETPLACE_TAB_REGISTRATIONS_DESCRIPTION
        ),
        Triple(MarketplaceTab.CARRIER, R.string.numbers_tab_carrier, MARKETPLACE_TAB_CARRIER_DESCRIPTION),
    )
    // ⚠️ SCROLLS SIDEWAYS: four segments do not fit a phone's width in every language.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        tabs.forEach { (tab, label, tag) ->
            DistrictButton(
                text = stringResource(label),
                onClick = { onSelectTab(tab) },
                variant = if (selected == tab) ButtonVariant.Primary else ButtonVariant.Secondary,
                size = ButtonSize.Sm,
                modifier = Modifier.testTag(tag),
            )
        }
    }
}

@Composable
private fun OwnedTab(state: OwnedState, onRetry: () -> Unit, onOpenNumber: ((ListedNumber) -> Unit)?) {
    when (state) {
        OwnedState.Loading -> ContentContainer {
            Column(
                modifier = Modifier
                    .padding(DistrictTheme.spacing.gutter)
                    .testTag(MARKETPLACE_LOADING_DESCRIPTION),
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
            }
        }
        is OwnedState.Ready -> OwnedList(state, onOpenNumber)
        is OwnedState.Failed -> ContentContainer {
            InlineFailure(
                title = stringResource(R.string.marketplace_owned_failed),
                failure = state.failure,
                onRetry = onRetry,
                description = MARKETPLACE_OWNED_FAILURE_DESCRIPTION,
                modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
            )
        }
    }
}

@Composable
private fun SearchTab(
    state: MarketplaceUiState,
    onUpdateForm: (NumberSearchForm) -> Unit,
    onSearch: () -> Unit,
) {
    ContentContainer {
        SearchFormCard(
            form = state.form,
            searching = state.search is SearchState.Loading,
            onUpdateForm = onUpdateForm,
            onSearch = onSearch,
        )
    }
    when (val search = state.search) {
        // ⚠️ Nothing has been searched yet, which is NOT "no numbers available". The form above
        // is the whole content of this state; adding an empty-results message here would answer
        // a question the operator has not asked.
        SearchState.Idle -> Unit
        SearchState.Loading -> ContentContainer {
            Column(
                modifier = Modifier
                    .padding(DistrictTheme.spacing.gutter)
                    .testTag(MARKETPLACE_SEARCHING_DESCRIPTION),
            ) {
                SkeletonBlock(height = DistrictTheme.spacing.header)
            }
        }
        is SearchState.Ready -> SearchResults(search)
        is SearchState.NotConfigured -> ContentContainer { NotConfiguredState(search.message, search.code) }
        is SearchState.Failed -> ContentContainer {
            InlineFailure(
                title = stringResource(R.string.marketplace_search_failed),
                failure = search.failure,
                onRetry = onSearch,
                description = MARKETPLACE_SEARCH_FAILURE_DESCRIPTION,
                modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter),
            )
        }
    }
}

/**
 * What a viewer is told: who can change a number. ⚠️ ONLY ON THE TABS THAT DO NOT SAY IT THEMSELVES.
 * Registrations and Carrier carry their own notice (`numbers_viewer_cannot_provision`), and this one
 * under it repeated the same sentence a second time.
 *
 * ⚠️ A ROLE THAT MAY CHANGE NUMBERS GETS NO CAPTION HERE. Where a number is bought is said once, by
 * [PurchaseHandOff]'s caption under its button; a caption above it ("New numbers are bought on the
 * web") said it twice in a row.
 */
@Composable
private fun ViewerCaption() {
    Text(
        text = stringResource(R.string.numbers_read_only_viewer),
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .testTag(MARKETPLACE_READ_ONLY_DESCRIPTION),
    )
}

/**
 * The hand-off to the web marketplace.
 *
 * ⛔ THIS IS A LINK, NOT A BUY BUTTON, AND THE COPY HAS TO SAY SO BEFORE IT IS TAPPED. Purchasing
 * stays on the web by an owner decision recorded on [marketplaceWebUrl] — Google Play's Payments
 * policy governs what an app may charge for in its own UI, and a phone number is a recurring
 * carrier charge on the same subscription as the web product. A button labelled "Buy" that opened
 * a browser would be a worse version of the same boundary: it would read as an in-app purchase
 * that failed.
 *
 * ⛔ NOT OFFERED TO A VIEWER. The web dashboard refuses them too, so the link would be a route to a
 * second refusal — the same reason [ViewerCaption] tells a viewer who to ask instead of where to
 * go. This is the one thing on this screen the role decides beyond wording.
 */
@Composable
private fun PurchaseHandOff(role: WorkspaceRole?, onOpenWeb: () -> Unit) {
    if (!role.allowsMutation()) return

    Column(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
        DistrictButton(
            text = stringResource(R.string.marketplace_buy_web),
            onClick = onOpenWeb,
            variant = ButtonVariant.Secondary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(MARKETPLACE_BUY_WEB_DESCRIPTION),
        )
        Text(
            text = stringResource(R.string.marketplace_buy_web_caption),
            style = MaterialTheme.typography.bodySmall,
            color = DistrictTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = DistrictTheme.spacing.hairline),
        )
    }
}

/**
 * A stable per-type test handle, derived in one place so the chip and its test cannot drift apart.
 *
 * ⚠️ Lives here rather than beside the chip itself only because `MarketplaceCards.kt` is at
 * detekt's 11-function file ceiling.
 */
internal fun typeDescription(type: String): String = "district-marketplace-type-$type"

private const val SKELETON_ROWS = 4

/** The tabs with no role notice of their own, where [ViewerCaption] is the only one. */
private val VIEWER_CAPTION_TABS = setOf(MarketplaceTab.OWNED, MarketplaceTab.SEARCH)

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MARKETPLACE_ROOT_DESCRIPTION: String = "district-marketplace-root"
const val MARKETPLACE_TAB_OWNED_DESCRIPTION: String = "district-marketplace-tab-owned"
const val MARKETPLACE_TAB_SEARCH_DESCRIPTION: String = "district-marketplace-tab-search"
const val MARKETPLACE_TAB_REGISTRATIONS_DESCRIPTION: String = "district-marketplace-tab-registrations"
const val MARKETPLACE_TAB_CARRIER_DESCRIPTION: String = "district-marketplace-tab-carrier"
const val MARKETPLACE_LOADING_DESCRIPTION: String = "district-marketplace-loading"
const val MARKETPLACE_SEARCHING_DESCRIPTION: String = "district-marketplace-searching"
const val MARKETPLACE_READ_ONLY_DESCRIPTION: String = "district-marketplace-read-only"
const val MARKETPLACE_OWNED_FAILURE_DESCRIPTION: String = "district-marketplace-owned-failure"
const val MARKETPLACE_SEARCH_FAILURE_DESCRIPTION: String = "district-marketplace-search-failure"
const val MARKETPLACE_BUY_WEB_DESCRIPTION: String = "district-marketplace-buy-web"
