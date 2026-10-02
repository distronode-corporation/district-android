package com.distronode.districtai.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.distronode.districtai.core.designsystem.DistrictBadge
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictCard
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.EmptyState
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.Tone
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.core.model.AvailableNumber
import com.distronode.districtai.core.model.ListedNumber

/**
 * The marketplace's cards.
 *
 * ⚠️ Split out of MarketplaceScreen.kt for detekt's 11-function file ceiling, exactly as
 * AnalyticsCards.kt was. That file owns the shell and the states; this one owns what a row of
 * either list looks like.
 */

/**
 * The search filters.
 *
 * ⛔ THREE FIELDS, AND THE TWO THAT ARE MISSING ARE MISSING ON PURPOSE. The route hardcodes its
 * result limit (10) and its capability filter (sms+voice), so a page-size control or a
 * "SMS only" toggle would be a control the server ignores — worse than an absence, because the
 * operator would believe they had narrowed something.
 */
@Composable
internal fun SearchFormCard(
    form: NumberSearchForm,
    searching: Boolean,
    onUpdateForm: (NumberSearchForm) -> Unit,
    onSearch: () -> Unit,
) {
    DistrictCard(modifier = Modifier.padding(horizontal = DistrictTheme.spacing.gutter)) {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            OutlinedTextField(
                value = form.areaCode,
                onValueChange = { onUpdateForm(form.copy(areaCode = it)) },
                label = { Text(stringResource(R.string.marketplace_area_code)) },
                singleLine = true,
                enabled = !searching,
                colors = districtFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = MARKETPLACE_AREA_CODE_DESCRIPTION },
            )
            OutlinedTextField(
                value = form.country,
                onValueChange = { onUpdateForm(form.copy(country = it)) },
                label = { Text(stringResource(R.string.marketplace_country)) },
                singleLine = true,
                enabled = !searching,
                colors = districtFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = MARKETPLACE_COUNTRY_DESCRIPTION },
            )
            Row(
                modifier = Modifier.padding(top = DistrictTheme.spacing.tight),
                horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
            ) {
                TypeChip(NUMBER_TYPE_LOCAL, R.string.marketplace_type_local, form, onUpdateForm)
                TypeChip(NUMBER_TYPE_TOLL_FREE, R.string.marketplace_type_toll_free, form, onUpdateForm)
                TypeChip(NUMBER_TYPE_MOBILE, R.string.marketplace_type_mobile, form, onUpdateForm)
            }
            DistrictButton(
                text = stringResource(R.string.marketplace_search_action),
                onClick = onSearch,
                enabled = !searching,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DistrictTheme.spacing.tight)
                    .semantics { contentDescription = MARKETPLACE_SEARCH_ACTION_DESCRIPTION },
            )
        }
    }
}

/** ⚠️ The three values the route accepts; anything else is silently ignored server-side. */
@Composable
private fun TypeChip(
    type: String,
    labelRes: Int,
    form: NumberSearchForm,
    onUpdateForm: (NumberSearchForm) -> Unit,
) {
    DistrictButton(
        text = stringResource(labelRes),
        onClick = { onUpdateForm(form.copy(type = type)) },
        variant = if (form.type == type) ButtonVariant.Primary else ButtonVariant.Secondary,
        size = ButtonSize.Sm,
        modifier = Modifier.semantics { contentDescription = typeDescription(type) },
    )
}

@Composable
internal fun SearchResults(state: SearchState.Ready) {
    ContentContainer {
        Column(
            modifier = Modifier
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .semantics { contentDescription = MARKETPLACE_RESULTS_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            // ⚠️ The carrier that ACTUALLY answered, which need not be the one the operator
            // expected: the resolved credentials decide, not the request.
            state.provider?.let { Eyebrow(stringResource(R.string.marketplace_provider, it)) }

            if (state.numbers.isEmpty()) {
                // ⚠️ Reachable only once a search SUCCEEDED, so it genuinely means the carrier
                // has nothing matching — never "we could not look".
                EmptyState(
                    title = stringResource(R.string.marketplace_search_empty_title),
                    body = stringResource(R.string.marketplace_search_empty),
                )
            } else {
                state.numbers.forEach { AvailableNumberRow(it) }
            }
        }
    }
}

@Composable
private fun AvailableNumberRow(number: AvailableNumber) {
    DistrictCard {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            Text(text = number.phoneNumber, style = MaterialTheme.typography.bodyLarge)
            val locality = listOfNotNull(number.locality, number.region).joinToString(", ")
            if (locality.isNotBlank()) {
                Text(text = locality, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = number.capabilities.joinToString(" · ").ifBlank { number.type },
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
            // ⛔ ABSENT PRICE MEANS "NOT PUBLISHED", NOT "FREE". The carrier's pricing lookup can
            // fail independently of the search, and the key is then missing from the wire
            // entirely — rendering a zero here would quote a price nobody was given.
            priceLabel(number.monthlyPrice, number.currency)?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun OwnedList(state: OwnedState.Ready) {
    ContentContainer {
        Column(
            modifier = Modifier
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .semantics { contentDescription = MARKETPLACE_OWNED_DESCRIPTION },
            verticalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        ) {
            // ⛔ THE BANNER SITS ABOVE THE ROWS AND DOES NOT REPLACE THEM. The list is real but
            // short: a carrier did not answer, so numbers the workspace owns may be missing. A
            // banner instead of the rows would discard an answer already in hand.
            if (state.partial) PartialBanner(state.failedProviders)

            if (state.numbers.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.marketplace_owned_empty_title),
                    body = stringResource(R.string.marketplace_owned_empty),
                )
            } else {
                state.numbers.forEach { ListedNumberRow(it) }
            }
        }
    }
}

@Composable
private fun ListedNumberRow(number: ListedNumber) {
    DistrictCard {
        Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
            Text(text = number.phoneNumber, style = MaterialTheme.typography.bodyLarge)
            number.friendlyName?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(
                    R.string.marketplace_number_meta,
                    number.provider,
                    number.status,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = DistrictTheme.colors.mutedForeground,
            )
            // ⛔ THE BADGE IS NOT COSMETIC. `managed` means the line is held on DISTRONODE's
            // carrier account rather than the tenant's, so it is theirs to USE and not to
            // administer — the reason no release control could be offered for it even if this
            // screen had one.
            if (number.managed) {
                DistrictBadge(
                    text = stringResource(R.string.marketplace_managed),
                    tone = Tone.Info,
                    modifier = Modifier
                        .padding(top = DistrictTheme.spacing.hairline)
                        .semantics { contentDescription = MARKETPLACE_MANAGED_DESCRIPTION },
                )
            }
        }
    }
}

/** ⛔ Names the carrier, so "the list may be short" is actionable rather than ominous. */
@Composable
private fun PartialBanner(failedProviders: List<String>) {
    DistrictBadge(
        text = stringResource(
            R.string.marketplace_partial,
            failedProviders.joinToString(", "),
        ),
        tone = Tone.Warning,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = MARKETPLACE_PARTIAL_DESCRIPTION },
    )
}

/**
 * ⛔ AN EMPTY STATE, NOT AN ERROR, AND NO RETRY. The workspace has not connected a carrier — a
 * legitimate state for a new account, and one no amount of retrying changes. A red failure here
 * would tell an operator their app is broken when the truth is that their setup is unfinished.
 *
 * ⚠️ The server's own sentence is shown, because it distinguishes "no provider configured" from
 * "the provider you asked for is not connected" and this layer cannot.
 */
@Composable
internal fun NotConfiguredState(message: String) {
    Column(
        modifier = Modifier
            .padding(horizontal = DistrictTheme.spacing.gutter)
            .semantics { contentDescription = MARKETPLACE_NOT_CONFIGURED_DESCRIPTION },
    ) {
        EmptyState(
            title = stringResource(R.string.marketplace_not_configured_title),
            body = message,
        )
    }
}

const val MARKETPLACE_AREA_CODE_DESCRIPTION: String = "district-marketplace-area-code"
const val MARKETPLACE_COUNTRY_DESCRIPTION: String = "district-marketplace-country"
const val MARKETPLACE_SEARCH_ACTION_DESCRIPTION: String = "district-marketplace-search-action"
const val MARKETPLACE_RESULTS_DESCRIPTION: String = "district-marketplace-results"
const val MARKETPLACE_OWNED_DESCRIPTION: String = "district-marketplace-owned"
const val MARKETPLACE_MANAGED_DESCRIPTION: String = "district-marketplace-managed"
const val MARKETPLACE_PARTIAL_DESCRIPTION: String = "district-marketplace-partial"
const val MARKETPLACE_NOT_CONFIGURED_DESCRIPTION: String = "district-marketplace-not-configured"
