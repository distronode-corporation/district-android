package com.distronode.districtai.ui.dialer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictRowDivider
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.Eyebrow
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.l10n.appLanguage

/**
 * The searchable country list behind the flag button.
 *
 * ⚠️ TWO GROUPS WHILE THE SEARCH IS EMPTY: Suggested (the workspace's home country, the one on the
 * button, then Canada and the United States) and every country, alphabetical in the app's language.
 * A search drops the groups and ranks: exact code, name prefix, calling code, then name contains.
 *
 * ⚠️ DONE ON THE KEYBOARD PICKS THE FIRST ROW, which is why the ranking puts `us` on the United
 * States rather than on Australia (see [DialCountryNames.search]).
 */
@Composable
internal fun DialerCountryPicker(
    home: String?,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val locale = DialCountryNames.localeFor(appLanguage())
    val all = remember(locale) { DialCountryNames.sorted(locale) }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = remember(query, all) { DialCountryNames.search(query, locale, all) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = DistrictTheme.colors.background,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(PICKER_HEIGHT)
                .padding(horizontal = DistrictTheme.spacing.gutter)
                .testTag(DIALER_COUNTRY_PICKER_DESCRIPTION),
        ) {
            Column(modifier = Modifier.padding(DistrictTheme.spacing.gutter)) {
                Text(
                    text = stringResource(R.string.dialer_country),
                    style = MaterialTheme.typography.titleMedium,
                    color = DistrictTheme.colors.foreground,
                    modifier = Modifier.semantics { heading() },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.dialer_country_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { matches.firstOrNull()?.let(onPick) }),
                    colors = districtFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = DistrictTheme.spacing.tight)
                        .testTag(DIALER_COUNTRY_SEARCH_DESCRIPTION),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (query.isBlank()) {
                        val suggested = DialCountryNames.suggested(home, current)
                        group(R.string.dialer_countries_suggested, suggested, "s", current, onPick)
                        group(R.string.dialer_countries_all, all, "a", current, onPick)
                    } else if (matches.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.dialer_countries_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = DistrictTheme.colors.mutedForeground,
                                modifier = Modifier
                                    .testTag(DIALER_COUNTRY_NO_MATCH_DESCRIPTION)
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    } else {
                        group(null, matches, "m", current, onPick)
                    }
                }
            }
        }
    }
}

/** ⚠️ [keyPrefix] because a suggested country is also in the full list, and keys must be unique. */
private fun LazyListScope.group(
    title: Int?,
    countries: List<String>,
    keyPrefix: String,
    current: String?,
    onPick: (String) -> Unit,
) {
    title?.let {
        item(key = "$keyPrefix-title") { Eyebrow(stringResource(it), Modifier.semantics { heading() }) }
    }
    items(countries, key = { "$keyPrefix-$it" }) { iso -> CountryRow(iso, iso == current, onPick) }
}

/**
 * ⛔ AT LEAST 48dp TALL: the bare text with its padding was about 40dp, under the touch target.
 * ⚠️ TalkBack reads the name and the code, not the flag ("flag: Canada, Canada"), and the country on
 * the button reads "selected".
 */
@Composable
private fun CountryRow(iso: String, selected: Boolean, onPick: (String) -> Unit) {
    val locale = DialCountryNames.localeFor(appLanguage())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DistrictTheme.spacing.tight),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_ROW_HEIGHT)
            .clickable { onPick(iso) }
            .semantics { this.selected = selected }
            .padding(vertical = DistrictTheme.spacing.tight)
            .testTag(countryRowDescription(iso)),
    ) {
        Text(
            text = DialCountries.flag(iso),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Text(
            text = DialCountryNames.name(iso, locale),
            style = MaterialTheme.typography.bodyLarge,
            color = DistrictTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "+" + DialCountries.callingCode(iso).orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = DistrictTheme.colors.mutedForeground,
        )
    }
    DistrictRowDivider()
}

/** The touch target's floor. */
private val MIN_ROW_HEIGHT = 48.dp

/** The share of the screen's height the list takes. */
private const val PICKER_HEIGHT = 0.85f

internal fun countryRowDescription(iso: String): String = "district-dialer-country-$iso"

const val DIALER_COUNTRY_PICKER_DESCRIPTION: String = "district-dialer-country-picker"
const val DIALER_COUNTRY_SEARCH_DESCRIPTION: String = "district-dialer-country-search"
const val DIALER_COUNTRY_NO_MATCH_DESCRIPTION: String = "district-dialer-country-no-match"
