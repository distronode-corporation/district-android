package com.distronode.districtai.ui.marketplace

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.distronode.districtai.R
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

/**
 * The phone-number marketplace: what the workspace has, and what a carrier has for sale.
 *
 * ⛔ READ ONLY, AND THE SCREEN SAYS SO OUT LOUD. There is no buy, release or configure control
 * anywhere in this file or its siblings — a number is a recurring carrier charge and a live line,
 * and a released one cannot be reclaimed, so those changes happen on the web dashboard where
 * there is room to explain them. The caption is not decoration: without it, a list of purchasable
 * numbers with no button reads as a broken screen.
 *
 * ⛔ THE ONE AFFORDANCE THAT LEAVES IS A LINK, NOT A PURCHASE. Buying stays off the phone by an
 * owner decision recorded on [marketplaceWebUrl] — Play's Payments policy — so [PurchaseHandOff]
 * opens the web marketplace in a Custom Tab and this screen keeps no purchase state at all.
 *
 * ⛔ BOTH ROUTES BEHIND THIS SCREEN ADMIT `viewer`, so no READ is role-gated. The role decides the
 * CAPTION's wording — telling a viewer to "use the web dashboard" points them at a second refusal,
 * so they are told who can instead — and it decides whether the hand-off is offered at all, for
 * exactly the same reason.
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
) {
    DistrictScaffold(
        modifier = Modifier.semantics { contentDescription = MARKETPLACE_ROOT_DESCRIPTION },
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
                MarketplaceTab.OWNED -> OwnedTab(state.owned, onRetryOwned)
                MarketplaceTab.SEARCH -> SearchTab(state, onUpdateForm, onSearch)
            }

            ContentContainer { ReadOnlyCaption(role) }

            ContentContainer { PurchaseHandOff(role, onOpenWeb) }

            // Bottom breathing room; a modifier padding would be clipped by the scroll container.
            Column(modifier = Modifier.height(DistrictTheme.spacing.header)) {}
        }
    }
}

/**
 * ⚠️ TWO BUTTONS RATHER THAN A TabRow, for the same reason the analytics window selector is
 * buttons rather than chips: this design system has no tab component, and inventing one for a
 * single screen is how a second, drifting set of primitives starts. Primary-versus-Secondary is
 * already the system's "this one is active".
 */
@Composable
private fun Segments(selected: MarketplaceTab, onSelectTab: (MarketplaceTab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DistrictTheme.spacing.gutter),
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
    ) {
        DistrictButton(
            text = stringResource(R.string.marketplace_tab_owned),
            onClick = { onSelectTab(MarketplaceTab.OWNED) },
            variant = if (selected == MarketplaceTab.OWNED) {
                ButtonVariant.Primary
            } else {
                ButtonVariant.Secondary
            },
            size = ButtonSize.Sm,
            modifier = Modifier.semantics { contentDescription = MARKETPLACE_TAB_OWNED_DESCRIPTION },
        )
        DistrictButton(
            text = stringResource(R.string.marketplace_tab_search),
            onClick = { onSelectTab(MarketplaceTab.SEARCH) },
            variant = if (selected == MarketplaceTab.SEARCH) {
                ButtonVariant.Primary
            } else {
                ButtonVariant.Secondary
            },
            size = ButtonSize.Sm,
            modifier = Modifier.semantics { contentDescription = MARKETPLACE_TAB_SEARCH_DESCRIPTION },
        )
    }
}

@Composable
private fun OwnedTab(state: OwnedState, onRetry: () -> Unit) {
    when (state) {
        OwnedState.Loading -> ContentContainer {
            Column(
                modifier = Modifier
                    .padding(DistrictTheme.spacing.gutter)
                    .semantics { contentDescription = MARKETPLACE_LOADING_DESCRIPTION },
                verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.row),
            ) {
                repeat(SKELETON_ROWS) { SkeletonBlock(height = DistrictTheme.spacing.header) }
            }
        }
        is OwnedState.Ready -> OwnedList(state)
        is OwnedState.Failed -> ContentContainer {
            MarketplaceFailure(
                title = stringResource(R.string.marketplace_owned_failed),
                failure = state.failure,
                description = MARKETPLACE_OWNED_FAILURE_DESCRIPTION,
                onRetry = onRetry,
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
                    .semantics { contentDescription = MARKETPLACE_SEARCHING_DESCRIPTION },
            ) {
                SkeletonBlock(height = DistrictTheme.spacing.header)
            }
        }
        is SearchState.Ready -> SearchResults(search)
        is SearchState.NotConfigured -> ContentContainer { NotConfiguredState(search.message) }
        is SearchState.Failed -> ContentContainer {
            MarketplaceFailure(
                title = stringResource(R.string.marketplace_search_failed),
                failure = search.failure,
                description = MARKETPLACE_SEARCH_FAILURE_DESCRIPTION,
                onRetry = onSearch,
            )
        }
    }
}

/**
 * ⛔ THE CAPTION THAT MAKES THE ABSENCE OF BUTTONS LEGIBLE. A list of purchasable numbers with
 * nothing to tap on reads as a half-built screen; saying where the change happens turns it into
 * a deliberate boundary. Worded per role, because "use the web dashboard" is unhelpful advice to
 * someone the web dashboard will also refuse.
 */
@Composable
private fun ReadOnlyCaption(role: WorkspaceRole?) {
    Text(
        text = if (role.allowsMutation()) {
            stringResource(R.string.marketplace_read_only)
        } else {
            stringResource(R.string.marketplace_read_only_viewer)
        },
        style = MaterialTheme.typography.bodySmall,
        color = DistrictTheme.colors.mutedForeground,
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = MARKETPLACE_READ_ONLY_DESCRIPTION },
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
 * second refusal — the same reason [ReadOnlyCaption] tells a viewer who to ask instead of where to
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
                .semantics { contentDescription = MARKETPLACE_BUY_WEB_DESCRIPTION },
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

/** Stable handles for tests; a literal duplicated in a test drifts silently. */
const val MARKETPLACE_ROOT_DESCRIPTION: String = "district-marketplace-root"
const val MARKETPLACE_TAB_OWNED_DESCRIPTION: String = "district-marketplace-tab-owned"
const val MARKETPLACE_TAB_SEARCH_DESCRIPTION: String = "district-marketplace-tab-search"
const val MARKETPLACE_LOADING_DESCRIPTION: String = "district-marketplace-loading"
const val MARKETPLACE_SEARCHING_DESCRIPTION: String = "district-marketplace-searching"
const val MARKETPLACE_READ_ONLY_DESCRIPTION: String = "district-marketplace-read-only"
const val MARKETPLACE_OWNED_FAILURE_DESCRIPTION: String = "district-marketplace-owned-failure"
const val MARKETPLACE_SEARCH_FAILURE_DESCRIPTION: String = "district-marketplace-search-failure"
const val MARKETPLACE_BUY_WEB_DESCRIPTION: String = "district-marketplace-buy-web"
