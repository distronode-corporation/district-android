package com.distronode.districtai.ui.dialer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.distronode.districtai.R
import com.distronode.districtai.core.designsystem.DistrictButton
import com.distronode.districtai.core.designsystem.DistrictTheme
import com.distronode.districtai.core.designsystem.districtFieldColors
import com.distronode.districtai.l10n.appLanguage

/*
 * The parts of the dialler's entry card that dialler design A added: the country button, the
 * number field, the lines under it and the Call button's refused tap. Their own file because
 * `DialerScreen.kt` crossed detekt's per-file function count with them in it.
 */

/** The flag and calling code in front of the number, or "Country" when none is chosen. */
@Composable
internal fun CountryButton(country: String?, enabled: Boolean, onClick: () -> Unit) {
    val code = country?.let(DialCountries::callingCode)
    val locale = DialCountryNames.localeFor(appLanguage())
    val spoken = if (country != null && code != null) {
        stringResource(R.string.dialer_country_button, DialCountryNames.name(country, locale), code)
    } else {
        stringResource(R.string.dialer_country_button_none)
    }
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = DistrictTheme.spacing.tight),
        modifier = Modifier
            .testTag(DIALER_COUNTRY_BUTTON_DESCRIPTION)
            .semantics { contentDescription = spoken },
    ) {
        Text(
            text = if (country != null && code != null) {
                DialCountries.flag(country) + " +" + code
            } else {
                stringResource(R.string.dialer_country)
            },
            color = DistrictTheme.colors.foreground,
            maxLines = 1,
        )
    }
}

/**
 * The number, in the local form of the country on the button.
 *
 * ⚠️ THE CARET GOES TO THE END WHEN THE TEXT WAS REWRITTEN, and stays where the operator put it when
 * it was not. The ViewModel formats after every edit, so `(416) 5` arriving for a typed `4165`
 * would otherwise leave the caret inside the brackets.
 *
 * ⛔ A WRONG LENGTH IS CHECKED WHEN THE OPERATOR STOPS: the field losing focus, or Done.
 */
@Composable
internal fun NumberField(state: DialerUiState, handlers: DialerHandlers, modifier: Modifier) {
    var field by remember { mutableStateOf(TextFieldValue(state.entry, TextRange(state.entry.length))) }
    if (field.text != state.entry) field = TextFieldValue(state.entry, TextRange(state.entry.length))
    var hadFocus by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val example = DialCountryNames.nationalExample(state.country)
    OutlinedTextField(
        value = field,
        onValueChange = { typed ->
            field = typed
            handlers.onEntryChange(typed.text)
        },
        enabled = state.canDial,
        singleLine = true,
        placeholder = if (example.isEmpty()) null else ({ Text(example) }),
        // ⚠️ `Phone`, so `+`, `*` and `#` are reachable. `Number` offers digits only, which makes an
        // international number untypeable without switching keyboards.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                handlers.onCheckLength()
                focus.clearFocus()
            },
        ),
        colors = districtFieldColors(),
        modifier = modifier
            .onFocusChanged { focusState ->
                if (hadFocus && !focusState.isFocused) handlers.onCheckLength()
                hadFocus = focusState.isFocused
            }
            .testTag(DIALER_FIELD_DESCRIPTION),
    )
}

/**
 * The lines under the field: the hint while it is empty, where the number rings once it is whole,
 * and why Call is off when the operator can do something about it.
 */
@Composable
internal fun EntryLines(state: DialerUiState) {
    val reading = state.reading
    val locale = DialCountryNames.localeFor(appLanguage())
    if (reading.status == DialPlan.Status.EMPTY) {
        val hint = state.country?.let { country ->
            stringResource(R.string.dialer_entry_hint_local, DialCountryNames.name(country, locale))
        } ?: stringResource(R.string.dialer_entry_hint_none)
        EntryLine(hint, DistrictTheme.colors.mutedForeground, DIALER_HINT_DESCRIPTION)
    }
    destinationLine(reading)?.let { (iso, number) ->
        val text = when {
            iso == null -> stringResource(R.string.dialer_destination_unrecognised)
            belongsToNoCountry(reading) -> stringResource(R.string.dialer_rings_no_country, number)
            else -> stringResource(R.string.dialer_rings, DialCountryNames.name(iso, locale), number)
        }
        EntryLine(text, DistrictTheme.colors.foreground, DIALER_DESTINATION_DESCRIPTION)
    }
    state.entryProblem?.let { problem ->
        EntryLine(stringResource(problem), DistrictTheme.colors.destructive, DIALER_PROBLEM_DESCRIPTION)
    }
}

@Composable
private fun EntryLine(text: String, color: Color, tag: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier
            .padding(top = DistrictTheme.spacing.hairline)
            .testTag(tag),
    )
}

/**
 * Call, and what a tap on it does while it is off.
 *
 * ⚠️ A DISABLED BUTTON NEVER RUNS ITS ACTION, so a tap on it could not say why it is off (Seer on
 * district-ios #96). A transparent layer over it takes that tap while Call is off and is gone once
 * Call is on, so it can never place a call. It carries no semantics: TalkBack reads the button.
 */
@Composable
internal fun CallButton(state: DialerUiState, handlers: DialerHandlers) {
    // ⚠️ `pointerInput(Unit)` keeps its first block, so the tap reads the handler the host holds now.
    val onRefusedTap by rememberUpdatedState(handlers.onDial)
    Box(modifier = Modifier.padding(top = DistrictTheme.spacing.row)) {
        DistrictButton(
            text = stringResource(R.string.dialer_call),
            onClick = handlers.onDial,
            enabled = state.canPlaceCall,
            modifier = Modifier.testTag(DIALER_CALL_DESCRIPTION),
        )
        if (!state.canPlaceCall) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    // ⚠️ Only a test tag, which TalkBack does not read; the button itself is announced.
                    .clearAndSetSemantics { testTag = DIALER_CALL_REFUSED_DESCRIPTION }
                    .pointerInput(Unit) { detectTapGestures { onRefusedTap() } },
            )
        }
    }
}

/**
 * What the line under the field names: the country (null for a code no listed country uses) and the
 * international number, or null when there is nothing to send yet.
 *
 * ⚠️ AN UNKNOWN CODE SAYS SO PLAINLY RATHER THAN GUESSING: a wrong country name is read as
 * confirmation. It stays callable, as it always was.
 */
internal fun destinationLine(reading: DialPlan.Reading): Pair<String?, String>? {
    noCountryNumber(reading)?.let { return reading.iso to it }
    if (reading.status == DialPlan.Status.READY) return reading.iso to reading.international
    // ⚠️ A `+` and three digits that name no country is already worth saying.
    val unknown = reading.status == DialPlan.Status.TYPING && reading.namedItsCountry &&
        reading.iso == null && reading.national.length > UNKNOWN_CODE_SHOWN_AFTER
    return if (unknown) null to reading.international else null
}

/**
 * Whether the line under the field is `dialer_rings_no_country` rather than "Rings Canada".
 *
 * ⛔ A +1 TOLL-FREE NUMBER (800, 833 … 888) RINGS NO ONE COUNTRY, AND THE LINE SAYS SO, as the web
 * dialler does (`ringsNoCountry`; Sean, 2026-11-04, GitLab #2). [DialPlan] keeps the +1 country on
 * the button, because the flag is where the next digits are read, but naming it under a 1-800
 * number would claim a country the number does not have. So do the web's other no-country codes, 500,
 * 600, 700, 710 and 900.
 */
internal fun belongsToNoCountry(reading: DialPlan.Reading): Boolean = noCountryNumber(reading) != null

/**
 * The number a +1 no-country line shows (toll-free, or the web's 500, 600, 700, 710 and 900), or
 * null when the reading is not one.
 *
 * ⚠️ THE WEB'S TIMING, NOT THE REST OF THIS LINE'S: said from 8 digits with the calling code
 * ([DialPlan.MINIMUM_DIGITS]) up to 15, where the web's `resolveDialEntry` reads a number as `ready`,
 * so a toll-free number still short of its ten digits already says it has no country, beside Call
 * being off. Grouped only once it is whole; before that the bare `+1800…`, the web's rule that a
 * half-grouped number reads as a real one.
 */
internal fun noCountryNumber(reading: DialPlan.Reading): String? {
    if (reading.callingCode != "1" || !DialCountries.isNanpNoCountry(reading.nationalNumber)) return null
    val total = reading.callingCode.length + reading.nationalNumber.length
    if (total < DialPlan.MINIMUM_DIGITS || total > DialPlan.MAXIMUM_DIGITS) return null
    return if (reading.status == DialPlan.Status.READY) reading.international else "+1" + reading.nationalNumber
}

/** `+` and three digits: a calling code is at most three, so by then it names a country or none. */
private const val UNKNOWN_CODE_SHOWN_AFTER = 3
